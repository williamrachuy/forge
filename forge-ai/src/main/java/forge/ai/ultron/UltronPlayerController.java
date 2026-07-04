package forge.ai.ultron;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.ai.AiAttackController;
import forge.ai.PlayerControllerAi;
import forge.ai.llm.runtime.UltronTableThreatSummary;
import forge.ai.llm.runtime.UltronThreatModel;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameStateEvaluator;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.*;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;

import java.util.*;
import java.util.function.Predicate;

/**
 * Ultron v3's own {@link PlayerController} subclass — see FORGE_TRACKER TICKET-V3-101.
 *
 * <p><b>Why this class exists:</b> Ultron v2 bolted its decision overrides onto the shared
 * {@code AiController}/{@code PlayerControllerAi} used by every AI profile, gated behind
 * environment-variable flag checks scattered through {@code AiController} (main-phase spell
 * choice, attacker declaration, stack response — roughly 3 of {@code PlayerControllerAi}'s
 * ~121 decision methods). Every other AI profile paid the cost of those branches without ever
 * using them, and Ultron itself silently fell back to default behavior for the other ~97% of
 * decisions with no way to measure how often that happened.
 *
 * <p>Phase 1 gives Ultron its own controller so it is the sole owner of its decision surface.
 * {@code AiController} has been stripped back to profile-agnostic stock behavior (no more
 * {@code isUltronRuntime}/{@code isUltronRuntimeProfile} branches) — see the P1.1 diff in
 * {@code AiController.java}'s {@code declareAttackers}, {@code chooseSpellAbilityToPlayFromList},
 * and the stack-response veto path formerly in {@code getSpellAbilityToPlay}.
 *
 * <p><b>Phase 1 scope (pure plumbing, not a behavior change):</b> every override below times a
 * call to {@code super} (stock {@code PlayerControllerAi}/{@code AiController} logic) and records
 * it via {@link UltronDecisionTelemetry} as "inherited". No Ultron-specific decision logic exists
 * yet — that is Phase 2/3's job. The v2 heuristic runtime classes under
 * {@code forge.ai.llm.runtime} ({@code UltronRuntimeController}, {@code UltronActionScorer},
 * {@code UltronCombatPolicy}, {@code UltronFastPriorityPolicy}, {@code UltronWeights},
 * {@code UltronCardStats}, …) are intentionally NOT referenced anywhere in this class — they are
 * orphaned per plan section 9, retired in stages behind future gates. This is also what fixes
 * TICKET-V3-006/TICKET-V3-104: those classes' static initializers eagerly load
 * {@code ~/.forge/ultron-learning/weights.json} and {@code ultron_card_stats.json} the moment the
 * class is first touched (regardless of the {@code adaptiveWeights} config flag) — by never
 * referencing them here, an Ultron v3 game's decision path never triggers that load, so v3 runs
 * start clean of v2 learned state. See {@code UltronPlayerControllerContaminationGuardTest} for a
 * verification that this class's compiled bytecode contains no reference to those classes.
 *
 * <p>Coverage as of Phase 1: 0% Ultron-authored / 100% inherited, across all
 * {@value #DECISION_METHOD_COUNT} overridden methods — the correct baseline for Phase 2 to
 * improve against (plan §11 secondary success criterion: coverage rising from ~20% today under
 * v2's measurement to a Phase-3-and-beyond target of 80%+).
 *
 * <p>Phase 2 P2.4 (TICKET-V3-203) was the first method to move off that 0% baseline:
 * {@link #chooseSpellAbilityToPlay()} answers via the simulation-based
 * {@code SpellAbilityPicker}/{@code Plan} machinery instead of delegating straight to
 * {@code super}. P2.5 (TICKET-V3-204) adds {@link #declareAttackers}: a pruned-candidate
 * simulation search over attacker subsets, scored with {@code GameStateEvaluator} after
 * simulating through {@code COMBAT_DAMAGE}. Both are recorded as {@code answeredBy=ultron} in
 * {@link UltronDecisionTelemetry} — coverage is now 2/114. {@code declareBlockers} remains pure
 * inherited plumbing pending a future session (see FORGE_TRACKER TICKET-V3-204's "not attempted
 * this session" note); all other 112 methods remain pure inherited plumbing pending future
 * phases/sessions.
 */
public class UltronPlayerController extends PlayerControllerAi {

    /** Count of {@code PlayerController} decision methods this class overrides (kept in sync with the generator). */
    public static final int DECISION_METHOD_COUNT = 114;

    private final UltronDecisionTelemetry telemetry = new UltronDecisionTelemetry();

    public UltronPlayerController(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
    }

    /** Telemetry accessor for {@code SimulateStats.java}'s per-game JSONL coverage summary (P1.2). */
    public UltronDecisionTelemetry getTelemetry() {
        return telemetry;
    }

    /**
     * P1.3 — threat model as a read-only feature/analysis provider, NOT a decision authority.
     *
     * <p>Computes (or recomputes) the current table threat summary for this Ultron player. Phase 1
     * does not consume the result anywhere in a decision path — it exists purely so Phase 2/3's
     * search/value-function work has a proven-callable entry point for threat-model features
     * (leader/most-dangerous/monarch tracking, per plan §5 architecture and §7 P1.3). Deliberately
     * does not filter, veto, or choose anything.
     */
    public UltronTableThreatSummary refreshThreatSummary() {
        return UltronThreatModel.analyze(getGame(), getPlayer());
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        final long __start = System.nanoTime();
        SpellAbility __result = super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        telemetry.record("getAbilityToPlay", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean isAI() {
        final long __start = System.nanoTime();
        boolean __result = super.isAI();
        telemetry.record("isAI", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        final long __start = System.nanoTime();
        List<PaperCard> __result = super.sideboard(deck, gameType, message);
        telemetry.record("sideboard", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        final long __start = System.nanoTime();
        Map<Card, Integer> __result = super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        telemetry.record("assignCombatDamage", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        final long __start = System.nanoTime();
        Map<GameEntity, Integer> __result = super.divideShield(effectSource, affected, shieldAmount);
        telemetry.record("divideShield", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        final long __start = System.nanoTime();
        Map<Byte, Integer> __result = super.specifyManaCombo(sa, colorSet, manaAmount, different);
        telemetry.record("specifyManaCombo", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        final long __start = System.nanoTime();
        Integer __result = super.announceRequirements(ability, min, max, announce);
        telemetry.record("announceRequirements", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        telemetry.record("choosePermanentsToSacrifice", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
        telemetry.record("choosePermanentsToDestroy", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        telemetry.record("chooseCardsForEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        final long __start = System.nanoTime();
        List<Card> __result = super.chooseContraptionsToCrank(contraptions);
        telemetry.record("chooseContraptionsToCrank", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        final long __start = System.nanoTime();
        boolean __result = super.helpPayForAssistSpell(cost, sa, max, requested);
        telemetry.record("helpPayForAssistSpell", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        final long __start = System.nanoTime();
        Player __result = super.choosePlayerToAssistPayment(optionList, sa, title, max);
        telemetry.record("choosePlayerToAssistPayment", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        final long __start = System.nanoTime();
        T __result = super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        telemetry.record("chooseSingleEntityForEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        final long __start = System.nanoTime();
        List<T> __result = super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        telemetry.record("chooseEntitiesForEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        final long __start = System.nanoTime();
        List<SpellAbility> __result = super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        telemetry.record("chooseSpellAbilitiesForEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        final long __start = System.nanoTime();
        SpellAbility __result = super.chooseSingleSpellForEffect(spells, sa, title, params);
        telemetry.record("chooseSingleSpellForEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        final long __start = System.nanoTime();
        boolean __result = super.confirmAction(sa, mode, message, options, cardToShow, params);
        telemetry.record("confirmAction", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String string, int bid, Player winner) {
        final long __start = System.nanoTime();
        boolean __result = super.confirmBidAction(sa, mode, string, bid, winner);
        telemetry.record("confirmBidAction", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        final long __start = System.nanoTime();
        boolean __result = super.confirmStaticApplication(hostCard, mode, message, logic);
        telemetry.record("confirmStaticApplication", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        final long __start = System.nanoTime();
        boolean __result = super.confirmTrigger(wrapper);
        telemetry.record("confirmTrigger", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) {
        final long __start = System.nanoTime();
        boolean __result = super.confirmPayment(costPart, prompt, sa);
        telemetry.record("confirmPayment", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        final long __start = System.nanoTime();
        boolean __result = super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
        telemetry.record("confirmReplacementEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        final long __start = System.nanoTime();
        List<Card> __result = super.exertAttackers(attackers);
        telemetry.record("exertAttackers", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        final long __start = System.nanoTime();
        List<Card> __result = super.enlistAttackers(attackers);
        telemetry.record("enlistAttackers", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        final long __start = System.nanoTime();
        CardCollection __result = super.orderBlockers(attacker, blockers);
        telemetry.record("orderBlockers", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        final long __start = System.nanoTime();
        CardCollection __result = super.orderBlocker(attacker, blocker, oldBlockers);
        telemetry.record("orderBlocker", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        final long __start = System.nanoTime();
        CardCollection __result = super.orderAttackers(blocker, attackers);
        telemetry.record("orderAttackers", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
        final long __start = System.nanoTime();
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        telemetry.record("reveal", System.nanoTime() - __start);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addSuffix) {
        final long __start = System.nanoTime();
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        telemetry.record("reveal", System.nanoTime() - __start);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        final long __start = System.nanoTime();
        ImmutablePair<CardCollection, CardCollection> __result = super.arrangeForScry(topN);
        telemetry.record("arrangeForScry", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        final long __start = System.nanoTime();
        ImmutablePair<CardCollection, CardCollection> __result = super.arrangeForSurveil(topN);
        telemetry.record("arrangeForSurveil", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        final long __start = System.nanoTime();
        boolean __result = super.willPutCardOnTop(c);
        telemetry.record("willPutCardOnTop", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.orderMoveToZoneList(cards, destinationZone, source);
        telemetry.record("orderMoveToZoneList", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        final long __start = System.nanoTime();
        CardCollection __result = super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
        telemetry.record("chooseCardsToDiscardFrom", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        final long __start = System.nanoTime();
        super.playSpellAbilityNoStack(effectSA, canSetupTargets);
        telemetry.record("playSpellAbilityNoStack", System.nanoTime() - __start);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.chooseCardsToDelve(genericAmount, grave);
        telemetry.record("chooseCardsToDelve", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand, String[] uTypes, SpellAbility sa) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.chooseCardsToDiscardUnlessType(num, hand, uTypes, sa);
        telemetry.record("chooseCardsToDiscardUnlessType", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        final long __start = System.nanoTime();
        Mana __result = super.chooseManaFromPool(manaChoices);
        telemetry.record("chooseManaFromPool", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        final long __start = System.nanoTime();
        String __result = super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
        telemetry.record("chooseSomeType", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        final long __start = System.nanoTime();
        Object __result = super.vote(sa, prompt, options, votes, forPlayer, optional);
        telemetry.record("vote", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        final long __start = System.nanoTime();
        String __result = super.chooseSector(assignee, ai, sectors);
        telemetry.record("chooseSector", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        final long __start = System.nanoTime();
        int __result = super.chooseSprocket(assignee, sprockets);
        telemetry.record("chooseSprocket", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        final long __start = System.nanoTime();
        PlanarDice __result = super.choosePDRollToIgnore(rolls);
        telemetry.record("choosePDRollToIgnore", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        final long __start = System.nanoTime();
        Integer __result = super.chooseRollToIgnore(rolls);
        telemetry.record("chooseRollToIgnore", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        final long __start = System.nanoTime();
        List<Integer> __result = super.chooseDiceToReroll(rolls);
        telemetry.record("chooseDiceToReroll", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        final long __start = System.nanoTime();
        Integer __result = super.chooseRollToModify(rolls);
        telemetry.record("chooseRollToModify", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        final long __start = System.nanoTime();
        RollDiceEffect.DieRollResult __result = super.chooseRollToSwap(rolls);
        telemetry.record("chooseRollToSwap", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        final long __start = System.nanoTime();
        String __result = super.chooseRollSwapValue(swapChoices, currentResult, power, toughness);
        telemetry.record("chooseRollSwapValue", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        final long __start = System.nanoTime();
        boolean __result = super.mulliganKeepHand(firstPlayer, cardsToReturn);
        telemetry.record("mulliganKeepHand", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.tuckCardsViaMulligan(hand, cardsToReturn);
        telemetry.record("tuckCardsViaMulligan", System.nanoTime() - __start);
        return __result;
    }

    /**
     * P2.5 (FORGE_TRACKER TICKET-V3-204) -- attack declaration now runs a pruned-candidate
     * simulation search instead of delegating to {@code AiAttackController} via {@code super}.
     *
     * <p><b>Candidate generation (per plan §7 P2.5 "singleton ± all-in ± threat-model-suggested
     * sets", kept to a handful of subsets, not full 2^N enumeration):</b>
     * <ol>
     *   <li>Attack with nothing (always a candidate -- the honest baseline).</li>
     *   <li>All legal attackers vs. {@link AiAttackController#choosePreferredDefenderPlayer}
     *       (the default AI's own opponent-selection heuristic, reused rather than
     *       reinvented -- P2.5 is about *which creatures* attack, not rebuilding opponent
     *       targeting).</li>
     *   <li>"Survivors only" vs. the same preferred defender: attackers that are either
     *       unblockable in practice by anything the defender controls, or whose toughness beats
     *       every creature that could actually block them (a rough evasion/toughness heuristic,
     *       per the plan's own wording -- not full combat-trick-aware combat math).</li>
     *   <li>All legal attackers vs. the single weakest-life alive opponent, if that differs from
     *       the preferred defender (a cheap "threat-model-suggested" variant: redirect the whole
     *       attack at whoever is lowest on life instead of the default AI's board-position-based
     *       pick).</li>
     * </ol>
     * Duplicate candidates (identical attacker-set + defender) are only scored once.
     *
     * <p><b>Simulation mechanism:</b> for each candidate, copies the game with {@link GameCopier}
     * at the current (still-empty) {@code combat} state -- {@code GameCopier} already copies a
     * non-null {@code PhaseHandler} combat via its {@code Combat(Combat, IEntityMap)} copy
     * constructor -- adds the candidate's attacker/defender pairs directly to the copy's
     * {@code Combat} object (no controller re-entry), then scores with
     * {@link GameStateEvaluator#getScoreForGameState}, which internally drives the copy through
     * {@code DECLARE_BLOCKERS} (each defending player's *own*, correctly-multiplayer-safe copied
     * controller decides its own blocks -- see the class-level P2.5 note below) and
     * {@code COMBAT_DAMAGE} before scoring the resulting state. The candidate with the highest
     * {@code Score.value} wins; its assignments are applied to the real {@code combat} argument.
     *
     * <p><b>Multiplayer combat finding (task-mandated check for the P2.4-discovered
     * single-weakest-opponent landmine leaking into combat):</b> {@code declareAttackersTurnBasedAction}
     * / {@code declareBlockersTurnBasedAction} in {@code PhaseHandler} already call each
     * attacking/defending player's *own* controller (see the {@code do { p = getNextPlayerAfter(p);
     * ... whoDeclaresBlockers.getController().declareBlockers(p, combat); }} loop) -- so which
     * creatures attack whom, and who blocks with what, is NOT affected by the single-weakest-opponent
     * assumption; that part of multiplayer combat already works correctly today. The landmine DOES
     * still apply one layer down: {@code GameStateEvaluator.simulateUpcomingCombatThisTurn} advances
     * the copy via {@code GameSimulator.resolveStack(gameCopy, aiPlayer.getWeakestOpponent())}, so
     * any *triggered-ability choice* that needs to be made by a player while combat-phase triggers
     * resolve (not the block/attack declarations themselves) is answered using only the weakest
     * opponent's controller context -- the identical shape of gap TICKET-V3-203 found and correctly
     * deferred for main-phase spell simulation. Not fixed here for the same reason: properly modeling
     * "which of N opponents would actually respond" is the belief-state/determinization work Phase 4
     * already plans (not a small, contained fix), so this is left as a known, documented input to that
     * future work rather than patched. Practical impact: no crashes/illegal states (verified by this
     * ticket's tests below); a possible optimism gap in a candidate's score whenever a triggered
     * ability mid-combat needed a non-weakest opponent's decision -- narrow in practice since most
     * combat-relevant decisions are the block/attack declarations themselves, which are unaffected.
     *
     * <p>Fails safe like {@link #chooseSpellAbilityToPlay()}: any {@code RuntimeException} anywhere
     * in the simulation path falls back to {@code super.declareAttackers} and is recorded as
     * {@code answeredBy=inherited}.
     */
    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        final long __start = System.nanoTime();
        boolean __answeredByUltron;
        int __candidateCount = 0;
        Integer __chosenScore = null;
        try {
            AttackPlan __chosen = chooseAttackPlanViaSimulation(attacker, combat);
            __candidateCount = __chosen.candidatesEvaluated;
            __chosenScore = __chosen.chosenScore;
            for (Pair<Card, GameEntity> assignment : __chosen.assignments) {
                combat.addAttacker(assignment.getLeft(), assignment.getRight());
            }
            __answeredByUltron = true;
        } catch (RuntimeException __ex) {
            Logger.warn("[Ultron] simulation-based declareAttackers() threw " + __ex
                    + "; falling back to inherited behavior (see FORGE_TRACKER TICKET-V3-204)");
            super.declareAttackers(attacker, combat);
            __answeredByUltron = false;
        }
        Map<String, Object> __detail = new LinkedHashMap<>();
        __detail.put("candidateCount", __candidateCount);
        __detail.put("chosenScore", __chosenScore);
        telemetry.recordDetail("declareAttackers", __detail);
        telemetry.record("declareAttackers", __answeredByUltron, System.nanoTime() - __start);
    }

    /** Simple holder: the winning candidate's attacker/defender assignments plus search stats. */
    private static final class AttackPlan {
        final List<Pair<Card, GameEntity>> assignments;
        final int candidatesEvaluated;
        final int chosenScore;

        AttackPlan(List<Pair<Card, GameEntity>> assignments, int candidatesEvaluated, int chosenScore) {
            this.assignments = assignments;
            this.candidatesEvaluated = candidatesEvaluated;
            this.chosenScore = chosenScore;
        }
    }

    /**
     * Builds the pruned candidate set described in {@link #declareAttackers}'s javadoc, simulates
     * each one through combat damage, and returns the highest-scoring plan. Never returns null --
     * "attack with nothing" is always at least one of the evaluated candidates, so a legitimate
     * "don't attack" decision is a normal winning result, not a fallback.
     */
    private AttackPlan chooseAttackPlanViaSimulation(Player attacker, Combat combat) {
        Game game = attacker.getGame();
        List<Player> aliveOpponents = Lists.newArrayList();
        for (Player p : attacker.getOpponents()) {
            if (!p.hasLost()) {
                aliveOpponents.add(p);
            }
        }

        List<List<Pair<Card, GameEntity>>> candidates = Lists.newArrayList();
        Set<String> seenSignatures = Sets.newHashSet();

        // Candidate 1: attack with nothing -- always evaluated as the honest baseline.
        addCandidateIfNew(candidates, seenSignatures, Collections.emptyList());

        if (!aliveOpponents.isEmpty()) {
            Player preferredDefender = AiAttackController.choosePreferredDefenderPlayer(attacker);

            List<Card> allInVsPreferred = legalAttackersAgainst(attacker, preferredDefender);
            addCandidateIfNew(candidates, seenSignatures, toAssignments(allInVsPreferred, preferredDefender));

            List<Card> survivorsVsPreferred = filterLikelySurvivors(allInVsPreferred, preferredDefender);
            addCandidateIfNew(candidates, seenSignatures, toAssignments(survivorsVsPreferred, preferredDefender));

            Player weakestOpponent = attacker.getWeakestOpponent();
            if (weakestOpponent != null && !weakestOpponent.equals(preferredDefender) && !weakestOpponent.hasLost()) {
                List<Card> allInVsWeakest = legalAttackersAgainst(attacker, weakestOpponent);
                addCandidateIfNew(candidates, seenSignatures, toAssignments(allInVsWeakest, weakestOpponent));
            }
        }

        List<Pair<Card, GameEntity>> bestAssignments = Collections.emptyList();
        int bestScore = Integer.MIN_VALUE;
        for (List<Pair<Card, GameEntity>> candidate : candidates) {
            int score = scoreAttackCandidate(game, attacker, combat, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestAssignments = candidate;
            }
        }
        return new AttackPlan(bestAssignments, candidates.size(), bestScore);
    }

    private static void addCandidateIfNew(List<List<Pair<Card, GameEntity>>> candidates, Set<String> seenSignatures,
            List<Pair<Card, GameEntity>> candidate) {
        String signature = candidateSignature(candidate);
        if (seenSignatures.add(signature)) {
            candidates.add(candidate);
        }
    }

    private static String candidateSignature(List<Pair<Card, GameEntity>> candidate) {
        List<String> parts = Lists.newArrayList();
        for (Pair<Card, GameEntity> p : candidate) {
            parts.add(p.getLeft().getId() + "->" + p.getRight().getId());
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private static List<Card> legalAttackersAgainst(Player attacker, GameEntity defender) {
        List<Card> result = Lists.newArrayList();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, defender)) {
                result.add(c);
            }
        }
        return result;
    }

    private static List<Pair<Card, GameEntity>> toAssignments(List<Card> attackers, GameEntity defender) {
        List<Pair<Card, GameEntity>> result = Lists.newArrayList();
        for (Card c : attackers) {
            result.add(ImmutablePair.of(c, (GameEntity) defender));
        }
        return result;
    }

    /**
     * Rough evasion/toughness heuristic (deliberately not full combat math, per plan §7 P2.5's own
     * wording): a candidate attacker "likely survives" if either no creature the defender controls
     * could legally block it, or every creature that could block it has less power than the
     * attacker's toughness (so a straight-up block wouldn't kill it). Ignores combat tricks,
     * first strike/deathtouch nuance, and multi-blocks by design -- it's a pruning heuristic to keep
     * candidate count small, not a combat outcome predictor; the actual simulation is what scores
     * the real outcome.
     */
    private static List<Card> filterLikelySurvivors(List<Card> candidates, Player defender) {
        List<Card> potentialBlockers = defender.getCreaturesInPlay();
        List<Card> survivors = Lists.newArrayList();
        for (Card attackerCard : candidates) {
            boolean anyBlockerThreatens = false;
            for (Card blocker : potentialBlockers) {
                if (!CombatUtil.canBlock(attackerCard, blocker)) {
                    continue;
                }
                if (blocker.getNetPower() >= attackerCard.getNetToughness()) {
                    anyBlockerThreatens = true;
                    break;
                }
            }
            if (!anyBlockerThreatens) {
                survivors.add(attackerCard);
            }
        }
        return survivors;
    }

    /**
     * Copies the game, applies one candidate attacker/defender assignment set directly to the
     * copy's {@code Combat} object (bypassing any controller re-entry), and scores the resulting
     * post-combat-damage state via {@link GameStateEvaluator}. Returns {@code Integer.MIN_VALUE}
     * on any simulation failure for this specific candidate so one bad candidate doesn't abort the
     * whole search -- {@link #chooseAttackPlanViaSimulation} still has the "attack with nothing"
     * candidate as a safety net if every other candidate fails.
     */
    private int scoreAttackCandidate(Game game, Player attacker, Combat combat, List<Pair<Card, GameEntity>> candidate) {
        try {
            GameCopier copier = new GameCopier(game);
            Game gameCopy = copier.makeCopy(null, attacker);
            Player attackerCopy = (Player) copier.find(attacker);
            Combat combatCopy = gameCopy.getPhaseHandler().getCombat();
            if (combatCopy == null) {
                // Defensive: should always be non-null since `combat` is the PhaseHandler's live
                // combat object at COMBAT_DECLARE_ATTACKERS and GameCopier copies it verbatim.
                return Integer.MIN_VALUE;
            }
            for (Pair<Card, GameEntity> assignment : candidate) {
                Card cardCopy = (Card) copier.find(assignment.getLeft());
                GameEntity defenderCopy = (GameEntity) copier.find(assignment.getRight());
                combatCopy.addAttacker(cardCopy, defenderCopy);
            }
            GameStateEvaluator.Score score = new GameStateEvaluator().getScoreForGameState(gameCopy, attackerCopy);
            return score.value;
        } catch (RuntimeException ex) {
            Logger.warn("[Ultron] declareAttackers candidate simulation threw " + ex + "; skipping this candidate");
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        final long __start = System.nanoTime();
        super.declareBlockers(defender, combat);
        telemetry.record("declareBlockers", System.nanoTime() - __start);
    }

    /**
     * P2.4 (FORGE_TRACKER TICKET-V3-203) -- main-phase spell/land selection now routes through the
     * existing (previously dormant, opt-in-only-via-{@code AIOption.USE_SIMULATION}) simulation
     * machinery: {@link forge.ai.simulation.SpellAbilityPicker} + {@link forge.ai.simulation.Plan}.
     *
     * <p>Reuses {@code getAi().getSimulationPicker()} rather than constructing a new
     * {@code SpellAbilityPicker} -- {@code AiController} always builds one in its constructor
     * regardless of the {@code useSimulation} flag (see {@code AiController#simPicker}), so this
     * is the exact same object/instance state (its in-progress {@code Plan}, if any) that the
     * legacy 2-player-oriented lobby flag would have used; no duplicate picker, no divergent state.
     *
     * <p>This machinery was verified by prior Phase 2 sessions to be Battlebox/4-player-safe at the
     * layers it touches directly: {@code GameCopier} now copies shared zones correctly
     * (TICKET-V3-201), {@code GameStateEvaluator} scores multiplayer state correctly
     * (TICKET-V3-202), and neither {@code SpellAbilityPicker} nor {@code Plan} itself calls the
     * single-opponent {@code Player.getOpponent()} anywhere -- {@code GameCopier}/{@code
     * GameSimulator} already use {@code getWeakestOpponent()} where an opponent reference is
     * needed. One real 4-player landmine remains, deeper than a small contained fix, and is left
     * for a future session: {@code GameSimulator.simulateSpellAbility}'s stack-resolution step
     * (used when scoring "if I play this spell" candidates) resolves responses as if only the
     * single weakest opponent could respond (see the {@code // TODO: Support multiple opponents.}
     * comment at {@code GameSimulator.java:228}) -- a stronger opponent's actual interaction (e.g.
     * removal/counterspells) is never modeled during that lookahead. This under-estimates risk in
     * 4-player Battlebox but does not produce illegal or crashing output; it is a fidelity gap in
     * the score, not a correctness bug in the decision path itself.
     *
     * <p>Fails safe: any {@code RuntimeException} from the simulation path falls back to inherited
     * ({@code super}) behavior and is recorded as {@code answeredBy=inherited} so telemetry/coverage
     * never lies about an exception-driven fallback.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final long __start = System.nanoTime();
        List<SpellAbility> __result;
        boolean __answeredByUltron;
        try {
            SpellAbility __chosen = getAi().getSimulationPicker().chooseSpellAbilityToPlay(null);
            __result = __chosen == null ? null : Lists.newArrayList(__chosen);
            __answeredByUltron = true;
        } catch (RuntimeException __ex) {
            Logger.warn("[Ultron] simulation-based chooseSpellAbilityToPlay() threw " + __ex
                    + "; falling back to inherited behavior (see FORGE_TRACKER TICKET-V3-203)");
            __result = super.chooseSpellAbilityToPlay();
            __answeredByUltron = false;
        }
        telemetry.record("chooseSpellAbilityToPlay", __answeredByUltron, System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        final long __start = System.nanoTime();
        boolean __result = super.playChosenSpellAbility(sa);
        telemetry.record("playChosenSpellAbility", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        final long __start = System.nanoTime();
        CardCollection __result = super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        telemetry.record("chooseCardsToDiscardToMaximumHandSize", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.chooseCardsToRevealFromHand(min, max, valid);
        telemetry.record("chooseCardsToRevealFromHand", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        final long __start = System.nanoTime();
        Player __result = super.chooseStartingPlayer(isFirstGame);
        telemetry.record("chooseStartingPlayer", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        final long __start = System.nanoTime();
        PlayerZone __result = super.chooseStartingHand(zones);
        telemetry.record("chooseStartingHand", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        final long __start = System.nanoTime();
        List<SpellAbility> __result = super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
        telemetry.record("chooseSaToActivateFromOpeningHand", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        final long __start = System.nanoTime();
        int __result = super.chooseNumber(sa, title, min, max);
        telemetry.record("chooseNumber", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String string, int min, int max, Map<String, Object> params) {
        final long __start = System.nanoTime();
        int __result = super.chooseNumber(sa, string, min, max, params);
        telemetry.record("chooseNumber", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        final long __start = System.nanoTime();
        int __result = super.chooseNumber(sa, title, options, relatedPlayer);
        telemetry.record("chooseNumber", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        final long __start = System.nanoTime();
        boolean __result = super.chooseFlipResult(sa, flipper, call);
        telemetry.record("chooseFlipResult", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility saSrc, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        final long __start = System.nanoTime();
        Pair<SpellAbilityStackInstance, GameObject> __result = super.chooseTarget(saSrc, allTargets);
        telemetry.record("chooseTarget", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        final long __start = System.nanoTime();
        super.notifyOfValue(saSource, realtedTarget, value);
        telemetry.record("notifyOfValue", System.nanoTime() - __start);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        final long __start = System.nanoTime();
        boolean __result = super.chooseBinary(sa, question, kindOfChoice, defaultVal);
        telemetry.record("chooseBinary", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Map<String, Object> params) {
        final long __start = System.nanoTime();
        boolean __result = super.chooseBinary(sa, question, kindOfChoice, params);
        telemetry.record("chooseBinary", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        final long __start = System.nanoTime();
        List<AbilitySub> __result = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        telemetry.record("chooseModeForAbility", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, ColorSet colors) {
        final long __start = System.nanoTime();
        byte __result = super.chooseColorAllowColorless(message, card, colors);
        telemetry.record("chooseColorAllowColorless", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        final long __start = System.nanoTime();
        byte __result = super.chooseColor(message, sa, colors);
        telemetry.record("chooseColor", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        final long __start = System.nanoTime();
        ColorSet __result = super.chooseColors(message, sa, min, max, options);
        telemetry.record("chooseColors", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        final long __start = System.nanoTime();
        CounterType __result = super.chooseCounterType(options, sa, prompt, params);
        telemetry.record("chooseCounterType", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseKeywordForPump(final List<String> options, final SpellAbility sa, final String prompt, final Card tgtCard) {
        final long __start = System.nanoTime();
        String __result = super.chooseKeywordForPump(options, sa, prompt, tgtCard);
        telemetry.record("chooseKeywordForPump", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        final long __start = System.nanoTime();
        ReplacementEffect __result = super.chooseSingleReplacementEffect(possibleReplacers);
        telemetry.record("chooseSingleReplacementEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleStatics) {
        final long __start = System.nanoTime();
        StaticAbility __result = super.chooseSingleStaticAbility(possibleStatics);
        telemetry.record("chooseSingleStaticAbility", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        final long __start = System.nanoTime();
        String __result = super.chooseProtectionType(sa, choices);
        telemetry.record("chooseProtectionType", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt /* ai needs hints as well */, ManaConversionMatrix matrix, boolean effect) {
        final long __start = System.nanoTime();
        boolean __result = super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        telemetry.record("payManaCost", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean payCombatCost(Card c, Cost cost, SpellAbility sa, String prompt) {
        final long __start = System.nanoTime();
        boolean __result = super.payCombatCost(c, cost, sa, prompt);
        telemetry.record("payCombatCost", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
        telemetry.record("chooseCardsForCost", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        final long __start = System.nanoTime();
        boolean __result = super.applyManaToCost(toPay, ability, prompt, matrix, effect);
        telemetry.record("applyManaToCost", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        final long __start = System.nanoTime();
        CostDecisionMakerBase __result = super.getCostDecisionMaker(player, ability, effect, prompt);
        telemetry.record("getCostDecisionMaker", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        final long __start = System.nanoTime();
        boolean __result = super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        telemetry.record("payCostToPreventEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        final long __start = System.nanoTime();
        List<SpellAbility> __result = super.orderSimultaneousSa(activePlayerSAs);
        telemetry.record("orderSimultaneousSa", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        final long __start = System.nanoTime();
        super.orderAndPlaySimultaneousSa(activePlayerSAs);
        telemetry.record("orderAndPlaySimultaneousSa", System.nanoTime() - __start);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        final long __start = System.nanoTime();
        boolean __result = super.playTrigger(host, wrapperAbility, isMandatory);
        telemetry.record("playTrigger", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        final long __start = System.nanoTime();
        boolean __result = super.playSaFromPlayEffect(tgtSA);
        telemetry.record("playSaFromPlayEffect", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        final long __start = System.nanoTime();
        boolean __result = super.chooseTargetsFor(currentAbility);
        telemetry.record("chooseTargetsFor", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        final long __start = System.nanoTime();
        TargetChoices __result = super.chooseNewTargetsFor(ability, filter, optional);
        telemetry.record("chooseNewTargetsFor", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        final long __start = System.nanoTime();
        boolean __result = super.chooseCardsPile(sa, pile1, pile2, faceUp);
        telemetry.record("chooseCardsPile", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
        final long __start = System.nanoTime();
        super.revealAnte(message, removedAnteCards);
        telemetry.record("revealAnte", System.nanoTime() - __start);
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        final long __start = System.nanoTime();
        super.revealAISkipCards(message, deckCards);
        telemetry.record("revealAISkipCards", System.nanoTime() - __start);
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        final long __start = System.nanoTime();
        super.revealUnsupported(unsupported);
        telemetry.record("revealUnsupported", System.nanoTime() - __start);
    }

    @Override
    public Map<DeckSection, List<? extends PaperCard>> complainCardsCantPlayWell(Deck myDeck) {
        final long __start = System.nanoTime();
        Map<DeckSection, List<? extends PaperCard>> __result = super.complainCardsCantPlayWell(myDeck);
        telemetry.record("complainCardsCantPlayWell", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView list) {
        final long __start = System.nanoTime();
        CardCollectionView __result = super.cheatShuffle(list);
        telemetry.record("cheatShuffle", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        final long __start = System.nanoTime();
        List<PaperCard> __result = super.chooseCardsYouWonToAddToDeck(losses);
        telemetry.record("chooseCardsYouWonToAddToDeck", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        final long __start = System.nanoTime();
        Map<Card, ManaCostShard> __result = super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
        telemetry.record("chooseCardsForConvokeOrImprovise", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        final long __start = System.nanoTime();
        String __result = super.chooseCardName(sa, faces, message);
        telemetry.record("chooseCardName", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        final long __start = System.nanoTime();
        String __result = super.chooseCardName(sa, cpp, valid, message);
        telemetry.record("chooseCardName", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        final long __start = System.nanoTime();
        Card __result = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        telemetry.record("chooseSingleCardForZoneChange", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        final long __start = System.nanoTime();
        List<Card> __result = super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
        telemetry.record("chooseCardsForZoneChange", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public void resetAtEndOfTurn() {
        final long __start = System.nanoTime();
        super.resetAtEndOfTurn();
        telemetry.record("resetAtEndOfTurn", System.nanoTime() - __start);
    }

    @Override
    public void autoPassCancel() {
        final long __start = System.nanoTime();
        super.autoPassCancel();
        telemetry.record("autoPassCancel", System.nanoTime() - __start);
    }

    @Override
    public void awaitNextInput() {
        final long __start = System.nanoTime();
        super.awaitNextInput();
        telemetry.record("awaitNextInput", System.nanoTime() - __start);
    }

    @Override
    public void cancelAwaitNextInput() {
        final long __start = System.nanoTime();
        super.cancelAwaitNextInput();
        telemetry.record("cancelAwaitNextInput", System.nanoTime() - __start);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        final long __start = System.nanoTime();
        ICardFace __result = super.chooseSingleCardFace(sa, faces, message);
        telemetry.record("chooseSingleCardFace", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        final long __start = System.nanoTime();
        ICardFace __result = super.chooseSingleCardFace(sa, message, cpp, name);
        telemetry.record("chooseSingleCardFace", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        final long __start = System.nanoTime();
        CardState __result = super.chooseSingleCardState(sa, states, message, params);
        telemetry.record("chooseSingleCardState", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        final long __start = System.nanoTime();
        List<Card> __result = super.chooseCardsForSplice(sa, cards);
        telemetry.record("chooseCardsForSplice", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        final long __start = System.nanoTime();
        List<OptionalCostValue> __result = super.chooseOptionalCosts(chosen, optionalCostValues);
        telemetry.record("chooseOptionalCosts", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        final long __start = System.nanoTime();
        int __result = super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
        telemetry.record("chooseNumberForKeywordCost", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) {
        final long __start = System.nanoTime();
        int __result = super.chooseNumberForCostReduction(sa, min, max);
        telemetry.record("chooseNumberForCostReduction", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        final long __start = System.nanoTime();
        List<CostPart> __result = super.orderCosts(costs);
        telemetry.record("orderCosts", System.nanoTime() - __start);
        return __result;
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        final long __start = System.nanoTime();
        CardCollection __result = super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
        telemetry.record("chooseCardsForEffectMultiple", System.nanoTime() - __start);
        return __result;
    }}
