package forge.ai.ultron;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.ai.AiAttackController;
import forge.ai.PlayerControllerAi;
import forge.ai.llm.UltronConfig;
import forge.ai.llm.runtime.UltronTableThreatSummary;
import forge.ai.llm.runtime.UltronThreatModel;
import forge.ai.nn.NeuralStateEvaluator;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.GameStateEvaluator;
import forge.ai.simulation.SpellAbilityPicker;
import forge.ai.simulation.StateEvaluator;
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
import forge.game.phase.PhaseType;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
     *
     * <p><b>TICKET-V3-207 (session 4) recursion guard -- the actual OOM root cause.</b> Unlike
     * {@link #declareBlockers}, this method originally had NO {@link #SIMULATION_IN_PROGRESS}
     * guard at all. Instrumented call counts ({@code GameCopier.getMakeCopyCallCount()}, see
     * {@code UltronGameCopierCallCountTest}) proved this was the dominant multiplier, not just a
     * theoretical risk: {@link GameStateEvaluator#getScoreForGameState} -- called for EVERY
     * candidate scored anywhere (main-phase candidates in {@code SpellAbilityPicker}, attack
     * candidates here, block candidates in {@link #declareBlockers}) -- internally runs
     * {@code simulateUpcomingCombatThisTurn}, which copies the game and drives it through
     * {@code COMBAT_DAMAGE} via real turn-based actions. Whenever the copy's active player is
     * Ultron-controlled (true for the copy's own attacker in the common single-Ultron-seat case,
     * not just the multi-Ultron-seat case the original TICKET-V3-205 guard was written for), that
     * turn-based action calls THIS method again on a freshly-constructed {@code
     * UltronPlayerController} -- which, unguarded, ran its own full {@code
     * chooseAttackPlanViaSimulation} (its own {@code GameCopier} + nested {@code
     * GameStateEvaluator.getScoreForGameState} call per candidate, which can recurse into combat
     * simulation yet again). The result: every single candidate scored anywhere in the decision
     * tree paid for a full nested attack-candidate search of its own -- a multiplicative blowup on
     * top of {@code SpellAbilityPicker}'s already-recursive (depth-3) main-phase planning, and the
     * true explanation for why {@code -Xmx8g} OOM'd worse than {@code -Xmx3g} (more heap just let
     * the same runaway multiplication run longer before exhausting it). Guarded exactly like
     * {@link #declareBlockers}: if a nested call lands here while {@link #SIMULATION_IN_PROGRESS}
     * is already true (this thread is already inside some Ultron simulation search, at any of the
     * three guarded entry points -- this method, {@link #declareBlockers}, or
     * {@link #chooseSpellAbilityToPlay()}), skip the search and fall back to cheap {@code super},
     * recorded as {@code answeredBy=inherited}. This bounds the decision tree to exactly the
     * pruned candidate counts ({@code chooseAttackPlanViaSimulation}'s own ~4, {@code
     * chooseBlockPlanViaSimulation}'s own ~4, {@code SpellAbilityPicker}'s depth-3 recursive
     * search) at the single real top-level decision, without ever neutering the top-level decision
     * itself -- a nested nested-inside-scoring nested-inside-scoring search added no measurable
     * decision quality (it was evaluating a candidate's OWN best-response combat, several plies
     * deeper than the plan's stated design), only runaway cost.
     */
    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        final long __start = System.nanoTime();
        if (Boolean.TRUE.equals(SIMULATION_IN_PROGRESS.get())) {
            // Nested call inside another Ultron controller's in-progress simulation search (see
            // javadoc above) -- fall back to cheap inherited behavior rather than recursing into
            // our own full search. Recorded as inherited, not ultron: telemetry must not lie.
            super.declareAttackers(attacker, combat);
            telemetry.record("declareAttackers", false, System.nanoTime() - __start);
            return;
        }

        boolean __answeredByUltron;
        int __candidateCount = 0;
        Integer __chosenScore = null;
        try {
            // TICKET-V3-207 (session 6): bounded to UltronConfig.maxSimDecisionTimeoutSeconds() --
            // SIMULATION_IN_PROGRESS is set/cleared INSIDE the worker thread's own work, not here on
            // the calling thread, since nested recursion (see javadoc above) happens synchronously
            // on whichever thread actually runs the search. See runWithDecisionTimeout's javadoc.
            AttackPlan __chosen = runWithDecisionTimeout("declareAttackers", () -> {
                SIMULATION_IN_PROGRESS.set(Boolean.TRUE);
                // TICKET-V4-014 (Version A, change 2): see chooseSpellAbilityToPlay's matching
                // comment -- activates/clears this decision's hard per-decision copy budget.
                UltronConfig.resetSimCopyBudget();
                try {
                    return chooseAttackPlanViaSimulation(attacker, combat);
                } finally {
                    UltronConfig.clearSimCopyBudget();
                    SIMULATION_IN_PROGRESS.set(Boolean.FALSE);
                }
            });
            __candidateCount = __chosen.candidatesEvaluated;
            __chosenScore = __chosen.chosenScore;
            for (Pair<Card, GameEntity> assignment : __chosen.assignments) {
                combat.addAttacker(assignment.getLeft(), assignment.getRight());
            }
            __answeredByUltron = true;
        } catch (RuntimeException __ex) {
            Logger.warn("[Ultron] simulation-based declareAttackers() threw " + __ex
                    + "; falling back to inherited behavior (see FORGE_TRACKER TICKET-V3-204/207)");
            super.declareAttackers(attacker, combat);
            __answeredByUltron = false;
        }
        Map<String, Object> __detail = new LinkedHashMap<>();
        __detail.put("candidateCount", __candidateCount);
        __detail.put("chosenScore", __chosenScore);
        telemetry.recordDetail("declareAttackers", __detail);
        telemetry.record("declareAttackers", __answeredByUltron, System.nanoTime() - __start);
    }

    /**
     * TICKET-V3-207 (session 6): simple holder returned from {@link #chooseSpellAbilityToPlay()}'s
     * {@code runWithDecisionTimeout}-wrapped work, so the picker's result (chosen ability, candidate
     * count, and score) can cross back out of the worker thread as a single value.
     */
    private static final class SpellPlanResult {
        final SpellAbility chosen;
        final int candidateCount;
        final Integer chosenScore;

        SpellPlanResult(SpellAbility chosen, int candidateCount, Integer chosenScore) {
            this.chosen = chosen;
            this.candidateCount = candidateCount;
            this.chosenScore = chosenScore;
        }
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
     * TICKET-V4-013: resolves the {@link StateEvaluator} combat candidate scoring uses -- identical
     * selection to {@code SpellAbilityPicker.selectEvaluator}: the learned {@link
     * NeuralStateEvaluator} when {@code ULTRON_NN_EVAL=true} AND {@code player} is Ultron-profiled
     * AND a model loaded successfully, else the hand-tuned {@link GameStateEvaluator}. Resolved once
     * per top-level combat decision (by {@link #chooseAttackPlanViaSimulation}/{@link
     * #chooseBlockPlanViaSimulation}), not once per candidate -- constructing either evaluator is
     * cheap (no model reload; {@link NeuralStateEvaluator} caches its loaded net statically) but
     * there is no reason to re-resolve it on every one of the ~2-4 candidates in a single decision.
     */
    private static StateEvaluator resolveCombatEvaluator(Player player) {
        if (UltronConfig.nnEvalEnabled() && UltronConfig.isUltronPlayer(player) && NeuralStateEvaluator.isAvailable()) {
            return new NeuralStateEvaluator();
        }
        return new GameStateEvaluator();
    }

    /**
     * TICKET-V4-013: advances {@code gameCopy} through combat damage and on to {@link
     * PhaseType#COMBAT_END} in place, so the caller can hand the resulting state directly to either
     * evaluator and get correct post-combat-damage scoring semantics from both:
     * <ul>
     *   <li>{@link NeuralStateEvaluator} never advances combat itself (by design -- see its class
     *       javadoc) -- it just encodes whatever state it is handed. Without this advance it would
     *       score the PRE-damage state, which is wrong for these two candidate-scoring call sites.</li>
     *   <li>{@link GameStateEvaluator#getScoreForGameState} DOES advance combat itself, via
     *       {@code simulateUpcomingCombatThisTurn} -- but only when the state it is handed is at or
     *       before {@code COMBAT_DAMAGE}; that method's own guard
     *       ({@code phase.isAfter(PhaseType.COMBAT_DAMAGE)}) short-circuits to a no-op (no second
     *       {@code GameCopier}, no second combat advance) once the state is already past
     *       {@code COMBAT_DAMAGE}. Landing this advance on {@code COMBAT_END} rather than stopping
     *       exactly at {@code COMBAT_DAMAGE} matters: stopping at {@code COMBAT_DAMAGE} would leave
     *       the two phases merely <em>equal</em>, which does not satisfy {@code isAfter} (strict),
     *       so the heuristic would still pay for one wasted extra {@code GameCopier.makeCopy} (its
     *       own while-loop would immediately find nothing left {@code isBefore} its target and do no
     *       further advancing -- harmless to correctness, but not free). Landing one phase further,
     *       on {@code COMBAT_END}, makes {@code isAfter} strictly true and eliminates that copy
     *       entirely -- this is the actual TICKET-V4-013 fix: each combat candidate now pays for
     *       exactly ONE {@code GameCopier} copy total, no matter which evaluator scores it.</li>
     * </ul>
     * {@code COMBAT_END}'s own turn-based action (per-card {@code onEndOfCombat} triggers) is a
     * normal, harmless part of a real turn's flow that happens in actual play regardless of who is
     * scoring the state, so advancing one phase past {@code COMBAT_DAMAGE} does not change what is
     * being measured -- it only stabilizes on a state both evaluators treat as "final" for the
     * turn's combat.
     */
    private static void advanceCopyThroughCombatDamage(Game gameCopy, Player aiPlayerCopy) {
        if (gameCopy.isGameOver()) {
            return;
        }
        PhaseType phase = gameCopy.getPhaseHandler().getPhase();
        if (phase.isAfter(PhaseType.COMBAT_DAMAGE)) {
            // Already past combat damage -- nothing to advance (defensive; not expected at either
            // call site today, both of which start at COMBAT_DECLARE_ATTACKERS/_BLOCKERS).
            return;
        }
        gameCopy.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_END,
                () -> GameSimulator.resolveStack(gameCopy, aiPlayerCopy.getWeakestOpponent()));
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

        // TICKET-V4-013 part 1: resolve the scoring evaluator ONCE for this decision (neural when
        // Ultron+ULTRON_NN_EVAL+model, else the heuristic -- see resolveCombatEvaluator's javadoc),
        // not once per candidate.
        StateEvaluator evaluator = resolveCombatEvaluator(attacker);

        // TICKET-V4-013 part 3: cheap insurance mirroring chooseSpellAbilityToPlay's V4-011 deadline
        // -- combat candidate counts are already small (~2-4, see class javadoc), so this should
        // essentially never fire once part 1 makes each candidate cheap, but it bounds even a
        // pathological huge-board candidate from blowing the decision budget. The first candidate
        // ("attack with nothing") is always scored regardless, so bestAssignments/bestScore are
        // always populated from at least one real evaluation.
        long deadlineMillis = System.currentTimeMillis() + (long) (UltronConfig.maxSimDecisionTimeoutSeconds() * 1000L * 0.8);

        List<Pair<Card, GameEntity>> bestAssignments = Collections.emptyList();
        int bestScore = Integer.MIN_VALUE;
        int evaluatedCount = 0;
        for (List<Pair<Card, GameEntity>> candidate : candidates) {
            if (evaluatedCount > 0 && System.currentTimeMillis() > deadlineMillis) {
                Logger.warn("[Ultron] declareAttackers candidate search hit its deadline after "
                        + evaluatedCount + "/" + candidates.size() + " candidates; returning best-so-far");
                break;
            }
            // TICKET-V4-014 (Version A, change 2): hard copy-budget checkpoint, mirroring the
            // deadline checkpoint above -- see UltronConfig.simCopyBudgetExceeded()'s javadoc. The
            // first candidate ("attack with nothing") is always scored regardless (evaluatedCount ==
            // 0 skips this check), matching the deadline checkpoint's own contract.
            if (evaluatedCount > 0 && UltronConfig.simCopyBudgetExceeded()) {
                Logger.warn("[Ultron] declareAttackers candidate search hit its copy budget ("
                        + UltronConfig.maxSimCopiesPerDecision() + ") after " + evaluatedCount + "/"
                        + candidates.size() + " candidates; returning best-so-far");
                break;
            }
            int score = scoreAttackCandidate(game, attacker, combat, candidate, evaluator);
            evaluatedCount++;
            if (score > bestScore) {
                bestScore = score;
                bestAssignments = candidate;
            }
        }
        return new AttackPlan(bestAssignments, evaluatedCount, bestScore);
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
     * copy's {@code Combat} object (bypassing any controller re-entry), advances that same copy
     * through combat damage (see {@link #advanceCopyThroughCombatDamage}), and scores the resulting
     * post-combat-damage state with {@code evaluator} (TICKET-V4-013: neural or heuristic, resolved
     * once per decision by the caller -- see {@link #resolveCombatEvaluator}). Returns
     * {@code Integer.MIN_VALUE} on any simulation failure for this specific candidate so one bad
     * candidate doesn't abort the whole search -- {@link #chooseAttackPlanViaSimulation} still has
     * the "attack with nothing" candidate as a safety net if every other candidate fails.
     */
    private int scoreAttackCandidate(Game game, Player attacker, Combat combat, List<Pair<Card, GameEntity>> candidate,
            StateEvaluator evaluator) {
        try {
            // TICKET-V4-014 (Version A, change 2): the HARD copy-budget check, immediately before
            // the actual GameCopier.makeCopy() this candidate pays -- see SpellAbilityPicker's
            // matching evaluateSa checkpoint for the full "checked before allocating = true ceiling"
            // rationale. Inert (never blocks) whenever no budget is active on this thread.
            if (!UltronConfig.tryConsumeSimCopyBudget()) {
                Logger.warn("[Ultron] declareAttackers candidate simulation skipped; per-decision "
                        + "copy budget (" + UltronConfig.maxSimCopiesPerDecision() + ") exhausted");
                return Integer.MIN_VALUE;
            }
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
            advanceCopyThroughCombatDamage(gameCopy, attackerCopy);
            GameStateEvaluator.Score score = evaluator.getScoreForGameState(gameCopy, attackerCopy);
            return score.value;
        } catch (RuntimeException ex) {
            Logger.warn("[Ultron] declareAttackers candidate simulation threw " + ex + "; skipping this candidate");
            return Integer.MIN_VALUE;
        }
    }

    /**
     * TICKET-V3-207 (Ultron v3, session 6): JVM-wide gate ensuring at most one Ultron
     * simulation-decision worker thread (see {@link #runWithDecisionTimeout}) is ever running at a
     * time. See that method's javadoc for exactly why this is needed (shared mutable state that a
     * timed-out, abandoned worker could otherwise race with a subsequent decision's worker).
     */
    private static final AtomicBoolean SIM_WORKER_BUSY = new AtomicBoolean(false);

    /**
     * Thrown when a simulation-based decision is abandoned after exceeding its per-decision
     * wall-clock budget ({@link UltronConfig#maxSimDecisionTimeoutSeconds()}). Deliberately a plain
     * {@code RuntimeException} subclass: every one of the three guarded methods
     * ({@link #chooseSpellAbilityToPlay()}, {@link #declareAttackers}, {@link #declareBlockers})
     * already has a {@code catch (RuntimeException)} block that falls back to {@code super} and
     * records {@code answeredBy=inherited} -- reusing that exact path means a timeout and a thrown
     * exception share one honest, already-tested fallback/telemetry mechanism instead of needing a
     * second parallel one.
     */
    private static final class UltronDecisionTimeoutException extends RuntimeException {
        UltronDecisionTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * TICKET-V3-207 (Ultron v3, session 6): per-decision timeout backstop, mirroring
     * {@code AiController}'s existing {@code FutureTask}-based per-decision timeout (see
     * {@code AiController#chooseSpellAbilityToPlayFromList}, its {@code aiDecisionTimeoutSeconds}/
     * {@code timeoutReached} volatile-flag mechanism, and the historical BUG-004 note on why
     * {@code timeoutReached} had to become {@code volatile}). Session 5's live jstack evidence showed
     * a single Ultron simulation-based decision genuinely progressing through expensive, varied work
     * (GameCopier deep copies, stack resolution, nested combat prediction) for 90+ seconds without
     * ever returning, with NO backstop of its own short of the whole-game {@code timeoutSeconds}
     * budget (1200s in production). This runs {@code work} on a dedicated worker thread and bounds
     * the wait to {@link UltronConfig#maxSimDecisionTimeoutSeconds()} seconds; on timeout, the caller
     * gives up and this throws {@link UltronDecisionTimeoutException}, which the caller's existing
     * {@code catch (RuntimeException)} turns into the same inherited-behavior fallback already used
     * for a thrown exception.
     *
     * <p><b>Thread-safety -- mirroring AiController's own honestly-documented limitation
     * ("Thread.stop() removed in Java 20+ ... zombie threads accumulate"):</b> a CPU-bound simulation
     * search has no cooperative checkpoint deep inside {@code GameCopier}/{@code GameSimulator}/
     * {@code SpellAbilityPicker}'s recursive candidate search to poll a volatile flag the way
     * {@code AiController}'s simple per-candidate loop does, so a timed-out worker thread cannot be
     * forcibly, promptly stopped -- {@code future.cancel(true)} is best-effort (interrupts, which most
     * of the simulation call chain never checks) and the thread may keep running in the background
     * until its own work naturally completes. Two real hazards follow directly from that, both
     * mitigated here:
     * <ol>
     *   <li>{@code GameSimulator.debugPrint}/{@code debugLines} are JVM-global {@code static} fields,
     *       and {@code getAi().getSimulationPicker()} returns a single shared, NOT thread-safe
     *       {@code SpellAbilityPicker} instance per player -- if a second Ultron decision started its
     *       own worker thread while an earlier timed-out worker was still draining, the two could
     *       race on that shared mutable state (corrupted debug output at best, silently wrong
     *       simulation results at worst). {@link #SIM_WORKER_BUSY} is a single JVM-wide
     *       compare-and-set gate: at most one Ultron simulation worker thread may run at a time,
     *       across every player and every one of the three guarded methods. If a prior timed-out
     *       worker is still draining when the next decision arrives, the new decision skips spawning
     *       a second worker entirely and throws {@link UltronDecisionTimeoutException} immediately --
     *       a safe degrade (Ultron behaves like a plain AI profile until the backlog clears), never a
     *       crash or a second concurrent writer.</li>
     *   <li>An abandoned worker never touches the REAL {@code Game}/{@code Combat} objects directly
     *       -- {@code chooseAttackPlanViaSimulation}/{@code chooseBlockPlanViaSimulation}/the
     *       {@code SpellAbilityPicker} search only ever mutate {@code GameCopier}-produced copies; the
     *       real objects are only mutated by the caller using the worker's result, and only if the
     *       worker actually returned within the timeout. So an abandoned worker can burn CPU/heap
     *       uselessly, but cannot corrupt the real game state.</li>
     * </ol>
     * {@link #SIMULATION_IN_PROGRESS} is deliberately set/cleared INSIDE {@code work} (i.e. on the
     * worker thread), not by this method or by the caller on the calling thread -- nested simulation
     * recursion (declareAttackers/declareBlockers reentered via combat-lookahead scoring, see those
     * methods' javadoc) happens as ordinary synchronous Java calls on whichever thread is running
     * {@code work}, so the thread-local must be visible on THAT thread for the existing nested-call
     * guard at the top of each method to keep working correctly.
     */
    private static <T> T runWithDecisionTimeout(String methodName, Callable<T> work) {
        if (!SIM_WORKER_BUSY.compareAndSet(false, true)) {
            throw new UltronDecisionTimeoutException("[Ultron] " + methodName + ": a prior timed-out "
                    + "simulation worker is still draining in the background; skipping simulation for "
                    + "this decision to avoid a concurrent-access race on shared simulation state "
                    + "(see FORGE_TRACKER TICKET-V3-207 session 6)");
        }
        FutureTask<T> future = new FutureTask<>(() -> {
            try {
                return work.call();
            } finally {
                SIM_WORKER_BUSY.set(false);
            }
        });
        Thread worker = new Thread(future, "Ultron-Sim-" + methodName);
        worker.setDaemon(true);
        worker.start();
        int timeoutSeconds = UltronConfig.maxSimDecisionTimeoutSeconds();
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Logger.warn("[Ultron] " + methodName + " exceeded its " + timeoutSeconds + "s per-decision "
                    + "timeout; abandoning this decision and falling back to inherited behavior. The "
                    + "worker thread may keep running in the background until it finishes naturally -- "
                    + "no new Ultron simulation worker will start until then (see FORGE_TRACKER "
                    + "TICKET-V3-207 session 6).");
            future.cancel(true);
            throw new UltronDecisionTimeoutException(methodName + " exceeded " + timeoutSeconds + "s timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UltronDecisionTimeoutException(methodName + " interrupted while waiting for simulation result");
        }
    }

    /**
     * Guards against nested simulation recursion (see FORGE_TRACKER TICKET-V3-205 and the
     * recursion-safety note left by TICKET-V3-204). {@code ThreadLocal}, not an instance field:
     * a nested call during simulation lands on a <em>different</em> {@code UltronPlayerController}
     * instance (a fresh one constructed for the opponent inside the copied game -- see
     * {@link #declareBlockers}'s javadoc for why), so an instance flag on {@code this} would never
     * be seen by that other instance. A thread-local is exactly the right shape: it is shared by
     * every {@code UltronPlayerController} touched on this call stack/thread (parallel sim workers
     * each run on their own thread, so this never leaks across games), and it is naturally reset
     * because the try/finally around the outer simulation call always clears it back to false, even
     * on exception.
     */
    private static final ThreadLocal<Boolean> SIMULATION_IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * P2.5 continuation (FORGE_TRACKER TICKET-V3-205) -- block declaration now runs the same
     * pruned-candidate simulation search as {@link #declareAttackers}, instead of delegating to
     * {@code AiBlockController} via {@code super}.
     *
     * <p><b>Candidate generation (pruned, per plan §7 P2.5's "(a) no blocks, (b) block to prevent
     * lethal damage only, (c) block the biggest threat(s) for value, (d) block everything blockable
     * if that's clearly correct", roughly 3-5 candidates):</b>
     * <ol>
     *   <li>No blocks -- always evaluated as the honest baseline.</li>
     *   <li>Lethal-prevention chumps -- if the unblocked incoming damage from this defender's
     *       attackers would be lethal, greedily chump/trade the biggest attackers with the
     *       <em>cheapest</em> available legal blocker (by combined power+toughness) until incoming
     *       damage drops below the defender's life. A no-op (identical to "no blocks") when damage
     *       isn't lethal -- naturally deduplicated by {@link #addCandidateIfNew}, no special-casing
     *       needed.</li>
     *   <li>Value blocks on the biggest threat(s) -- for up to the two highest-power attackers,
     *       assign a blocker only if it is a <em>clean kill</em> (blocker's power &gt;= attacker's
     *       toughness, and the blocker itself survives the attacker's power). Chumps and even trades
     *       are deliberately excluded here -- this candidate is specifically "kill it for free."</li>
     *   <li>Block everything with a favorable outcome -- greedily assign a legal blocker to every
     *       attacker that has one, preferring clean kills first, falling back to even trades (both
     *       die) second, leaving attackers with no non-bad block unblocked. This is the "all trades
     *       favorable" case from the plan's own wording.</li>
     * </ol>
     * Deliberately not full combat math (no first strike/deathtouch/multi-block/trick-awareness --
     * same spirit as {@link #filterLikelySurvivors}); the simulation scores the real outcome, these
     * are just pruning heuristics to keep candidate count small.
     *
     * <p><b>Simulation mechanism:</b> identical in shape to {@link #declareAttackers} -- for each
     * candidate, {@link GameCopier} copies the game at the current (blocks-not-yet-declared) combat
     * state, the candidate's blocker/attacker pairs are added directly to the <em>copy's</em>
     * {@code Combat} via {@code Combat.addBlocker} (no controller re-entry), and the result is
     * scored with {@link GameStateEvaluator#getScoreForGameState}, which internally drives the copy
     * through {@code COMBAT_DAMAGE} before scoring. Highest-scoring candidate's assignments are
     * applied to the real {@code combat} argument.
     *
     * <p><b>Recursion-safety (task-mandated; see FORGE_TRACKER TICKET-V3-204's note and TICKET-V3-205
     * below for the full answer): confirmed real, and guarded.</b> Reading {@code GameCopier.
     * clonePlayer} directly (not assuming): for a player whose {@code LobbyPlayer} is already a
     * {@code LobbyPlayerAi} (true for every AI-controlled seat, including Ultron's), the clone
     * reuses the exact same {@code LobbyPlayerAi} instance -- it is only replaced with a fresh
     * {@code LobbyPlayerAi} for non-AI lobby players. Since {@code LobbyPlayerAi.createControllerFor}
     * decides {@code UltronPlayerController} vs {@code PlayerControllerAi} purely from that instance's
     * {@code aiProfile} field, and the field is untouched by cloning, every copied game constructs a
     * <em>fresh</em> {@code UltronPlayerController} for any player whose real seat uses the Ultron
     * profile. Tracing an actual call path confirms this recurses: when this method's own candidate
     * scoring calls {@code GameStateEvaluator.getScoreForGameState}, it internally re-copies the
     * (already-blocks-assigned) game and calls {@code PhaseHandler.devAdvanceToPhase(COMBAT_DAMAGE,
     * ...)}; because the inner copy's phase field was set via {@code devModeSet} (which does not run
     * phase-begin side effects) at whatever phase the outer copy was already sitting at, the
     * <em>next</em> phase's turn-based action is the first one actually executed -- for a
     * {@code declareAttackers} candidate at {@code COMBAT_DECLARE_ATTACKERS}, that is
     * {@code COMBAT_DECLARE_BLOCKERS}, whose {@code declareBlockersTurnBasedAction} unconditionally
     * calls {@code whoDeclaresBlockers.getController().declareBlockers(p, combat)} for every
     * defending player -- including one running a freshly-constructed {@code UltronPlayerController}
     * if that seat's profile is Ultron. (The same phase-skip means a controller can never recurse
     * into *itself* one level down -- confirmed for both {@link #declareAttackers} and this method --
     * but a *different* Ultron-controlled seat reached mid-combat is a different controller instance
     * and is not protected by that.) Concretely: in a 4-player Battlebox self-play game where two or
     * more seats run Ultron, evaluating an attack candidate against an Ultron-controlled defender (or
     * evaluating a block candidate where the surrounding combat also attacks a *different*
     * Ultron-controlled defender) would, without a guard, trigger that defender's full pruned-search
     * {@code declareBlockers} nested one level inside the outer search -- bounded (not exponential;
     * the phase-skip prevents any *further* nesting inside that nested call) but real, and not free
     * (each nested candidate is its own {@code GameCopier} + {@code GameStateEvaluator} pass).
     *
     * <p>The guard: {@link #SIMULATION_IN_PROGRESS} is set true for the duration of this method's
     * own simulation search (the try/finally in the body below) and checked at the very top of this
     * override. If a nested call lands here while the thread-local is already true -- i.e. this
     * {@code UltronPlayerController} instance was constructed inside another Ultron controller's
     * in-progress simulation, on the same thread -- it skips the simulation search entirely and
     * falls straight through to {@code super.declareBlockers}, recorded as {@code answeredBy=
     * inherited} (a deliberate, cheap, correctness-preserving fallback: a nested opponent-in-a-
     * simulation only needs *a* legal, sane block decision to keep the outer search's damage/score
     * math right, not its own full search). This bounds recursion to exactly one level no matter how
     * many Ultron seats a self-play game has, and keeps nested-simulation cost from compounding
     * across every candidate the outer search evaluates.
     *
     * <p>Fails safe like {@link #declareAttackers}: any {@code RuntimeException} anywhere in the
     * simulation path falls back to {@code super.declareBlockers} and is recorded as
     * {@code answeredBy=inherited}.
     */
    @Override
    public void declareBlockers(Player defender, Combat combat) {
        final long __start = System.nanoTime();
        if (Boolean.TRUE.equals(SIMULATION_IN_PROGRESS.get())) {
            // Nested call inside another Ultron controller's in-progress simulation (see javadoc
            // above) -- fall back to cheap inherited behavior rather than recursing into our own
            // full search. Recorded as inherited, not ultron: telemetry must not lie about this.
            super.declareBlockers(defender, combat);
            telemetry.record("declareBlockers", false, System.nanoTime() - __start);
            return;
        }

        boolean __answeredByUltron;
        int __candidateCount = 0;
        Integer __chosenScore = null;
        try {
            // TICKET-V3-207 (session 6): bounded to UltronConfig.maxSimDecisionTimeoutSeconds() --
            // see runWithDecisionTimeout's javadoc for why SIMULATION_IN_PROGRESS is set/cleared
            // inside the worker's own work rather than here on the calling thread.
            BlockPlan __chosen = runWithDecisionTimeout("declareBlockers", () -> {
                SIMULATION_IN_PROGRESS.set(Boolean.TRUE);
                // TICKET-V4-014 (Version A, change 2): see chooseSpellAbilityToPlay's matching
                // comment -- activates/clears this decision's hard per-decision copy budget.
                UltronConfig.resetSimCopyBudget();
                try {
                    return chooseBlockPlanViaSimulation(defender, combat);
                } finally {
                    UltronConfig.clearSimCopyBudget();
                    SIMULATION_IN_PROGRESS.set(Boolean.FALSE);
                }
            });
            __candidateCount = __chosen.candidatesEvaluated;
            __chosenScore = __chosen.chosenScore;
            for (Pair<Card, Card> assignment : __chosen.assignments) {
                combat.addBlocker(assignment.getLeft(), assignment.getRight());
            }
            __answeredByUltron = true;
        } catch (RuntimeException __ex) {
            Logger.warn("[Ultron] simulation-based declareBlockers() threw " + __ex
                    + "; falling back to inherited behavior (see FORGE_TRACKER TICKET-V3-205/207)");
            super.declareBlockers(defender, combat);
            __answeredByUltron = false;
        }
        Map<String, Object> __detail = new LinkedHashMap<>();
        __detail.put("candidateCount", __candidateCount);
        __detail.put("chosenScore", __chosenScore);
        telemetry.recordDetail("declareBlockers", __detail);
        telemetry.record("declareBlockers", __answeredByUltron, System.nanoTime() - __start);
    }

    /** Simple holder: the winning candidate's blocker/attacker assignments plus search stats. */
    private static final class BlockPlan {
        final List<Pair<Card, Card>> assignments;
        final int candidatesEvaluated;
        final int chosenScore;

        BlockPlan(List<Pair<Card, Card>> assignments, int candidatesEvaluated, int chosenScore) {
            this.assignments = assignments;
            this.candidatesEvaluated = candidatesEvaluated;
            this.chosenScore = chosenScore;
        }
    }

    /**
     * Builds the pruned candidate set described in {@link #declareBlockers}'s javadoc, simulates
     * each one through combat damage, and returns the highest-scoring plan. Never returns null --
     * "no blocks" is always at least one of the evaluated candidates.
     */
    private BlockPlan chooseBlockPlanViaSimulation(Player defender, Combat combat) {
        Game game = defender.getGame();
        List<Card> attackersOfDefender = combat.getAttackersOf(defender);

        List<List<Pair<Card, Card>>> candidates = Lists.newArrayList();
        Set<String> seenSignatures = Sets.newHashSet();

        // Candidate 1: no blocks -- always evaluated as the honest baseline.
        addBlockCandidateIfNew(candidates, seenSignatures, Collections.emptyList());

        if (!attackersOfDefender.isEmpty()) {
            List<Card> potentialBlockers = defender.getCreaturesInPlay();

            addBlockCandidateIfNew(candidates, seenSignatures,
                    buildLethalPreventionBlocks(attackersOfDefender, potentialBlockers, defender.getLife()));
            addBlockCandidateIfNew(candidates, seenSignatures,
                    buildValueBlocksOnBiggestThreats(attackersOfDefender, potentialBlockers));
            addBlockCandidateIfNew(candidates, seenSignatures,
                    buildAllFavorableBlocks(attackersOfDefender, potentialBlockers));
        }

        // TICKET-V4-013 part 1: resolve the scoring evaluator ONCE for this decision -- see
        // resolveCombatEvaluator's javadoc.
        StateEvaluator evaluator = resolveCombatEvaluator(defender);

        // TICKET-V4-013 part 3: cheap insurance mirroring chooseSpellAbilityToPlay's V4-011
        // deadline -- see chooseAttackPlanViaSimulation's matching comment for the full rationale.
        long deadlineMillis = System.currentTimeMillis() + (long) (UltronConfig.maxSimDecisionTimeoutSeconds() * 1000L * 0.8);

        List<Pair<Card, Card>> bestAssignments = Collections.emptyList();
        int bestScore = Integer.MIN_VALUE;
        int evaluatedCount = 0;
        for (List<Pair<Card, Card>> candidate : candidates) {
            if (evaluatedCount > 0 && System.currentTimeMillis() > deadlineMillis) {
                Logger.warn("[Ultron] declareBlockers candidate search hit its deadline after "
                        + evaluatedCount + "/" + candidates.size() + " candidates; returning best-so-far");
                break;
            }
            // TICKET-V4-014 (Version A, change 2): hard copy-budget checkpoint, mirroring the
            // deadline checkpoint above and declareAttackers' matching check.
            if (evaluatedCount > 0 && UltronConfig.simCopyBudgetExceeded()) {
                Logger.warn("[Ultron] declareBlockers candidate search hit its copy budget ("
                        + UltronConfig.maxSimCopiesPerDecision() + ") after " + evaluatedCount + "/"
                        + candidates.size() + " candidates; returning best-so-far");
                break;
            }
            int score = scoreBlockCandidate(game, defender, candidate, evaluator);
            evaluatedCount++;
            if (score > bestScore) {
                bestScore = score;
                bestAssignments = candidate;
            }
        }
        return new BlockPlan(bestAssignments, evaluatedCount, bestScore);
    }

    private static void addBlockCandidateIfNew(List<List<Pair<Card, Card>>> candidates, Set<String> seenSignatures,
            List<Pair<Card, Card>> candidate) {
        String signature = blockCandidateSignature(candidate);
        if (seenSignatures.add(signature)) {
            candidates.add(candidate);
        }
    }

    private static String blockCandidateSignature(List<Pair<Card, Card>> candidate) {
        List<String> parts = Lists.newArrayList();
        for (Pair<Card, Card> p : candidate) {
            parts.add(p.getLeft().getId() + "<-" + p.getRight().getId());
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    /** "Cost" of sacrificing a blocker for chump purposes -- lower is a cheaper chump. */
    private static int chumpCost(Card blocker) {
        return blocker.getNetPower() + blocker.getNetToughness();
    }

    /**
     * Candidate (b): if the unblocked incoming damage from {@code attackers} would be lethal for
     * this defender, greedily chump/trade the biggest attackers with the cheapest available legal
     * blocker until incoming damage drops below the defender's life total. A no-op (empty list,
     * naturally deduplicated against "no blocks") when the incoming damage isn't lethal.
     */
    private static List<Pair<Card, Card>> buildLethalPreventionBlocks(List<Card> attackers, List<Card> potentialBlockers, int defenderLife) {
        List<Card> byPowerDesc = Lists.newArrayList(attackers);
        byPowerDesc.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));

        int incoming = 0;
        for (Card a : byPowerDesc) {
            incoming += a.getNetPower();
        }
        if (incoming < defenderLife) {
            return Collections.emptyList();
        }

        List<Pair<Card, Card>> result = Lists.newArrayList();
        Set<Card> usedBlockers = Sets.newHashSet();
        for (Card attackerCard : byPowerDesc) {
            if (incoming < defenderLife) {
                break;
            }
            Card cheapest = null;
            for (Card blocker : potentialBlockers) {
                if (usedBlockers.contains(blocker) || !CombatUtil.canBlock(attackerCard, blocker)) {
                    continue;
                }
                if (cheapest == null || chumpCost(blocker) < chumpCost(cheapest)) {
                    cheapest = blocker;
                }
            }
            if (cheapest != null) {
                result.add(ImmutablePair.of(attackerCard, cheapest));
                usedBlockers.add(cheapest);
                incoming -= attackerCard.getNetPower();
            }
        }
        return result;
    }

    /**
     * Candidate (c): for up to the two highest-power attackers, block only if there is a clean
     * kill available -- a legal blocker whose power kills the attacker while itself surviving.
     * Chumps and even trades are intentionally excluded; this candidate is "kill it for free," not
     * "trade with it."
     */
    private static List<Pair<Card, Card>> buildValueBlocksOnBiggestThreats(List<Card> attackers, List<Card> potentialBlockers) {
        List<Card> byPowerDesc = Lists.newArrayList(attackers);
        byPowerDesc.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));
        int limit = Math.min(2, byPowerDesc.size());

        List<Pair<Card, Card>> result = Lists.newArrayList();
        Set<Card> usedBlockers = Sets.newHashSet();
        for (int i = 0; i < limit; i++) {
            Card attackerCard = byPowerDesc.get(i);
            Card cleanKiller = null;
            for (Card blocker : potentialBlockers) {
                if (usedBlockers.contains(blocker) || !CombatUtil.canBlock(attackerCard, blocker)) {
                    continue;
                }
                boolean killsAttacker = blocker.getNetPower() >= attackerCard.getNetToughness();
                boolean blockerSurvives = attackerCard.getNetPower() < blocker.getNetToughness();
                if (killsAttacker && blockerSurvives) {
                    if (cleanKiller == null || chumpCost(blocker) < chumpCost(cleanKiller)) {
                        cleanKiller = blocker;
                    }
                }
            }
            if (cleanKiller != null) {
                result.add(ImmutablePair.of(attackerCard, cleanKiller));
                usedBlockers.add(cleanKiller);
            }
        }
        return result;
    }

    /**
     * Candidate (d): greedily assign a legal blocker to every attacker that has a non-bad one
     * available -- clean kills first, then even trades (both die) -- leaving attackers with no
     * favorable/neutral block unblocked. Represents "block everything blockable if that's clearly
     * correct" from the plan's own wording.
     */
    private static List<Pair<Card, Card>> buildAllFavorableBlocks(List<Card> attackers, List<Card> potentialBlockers) {
        List<Card> byPowerDesc = Lists.newArrayList(attackers);
        byPowerDesc.sort((a, b) -> Integer.compare(b.getNetPower(), a.getNetPower()));

        List<Pair<Card, Card>> result = Lists.newArrayList();
        Set<Card> usedBlockers = Sets.newHashSet();

        // Pass 1: clean kills (blocker survives).
        for (Card attackerCard : byPowerDesc) {
            Card best = null;
            for (Card blocker : potentialBlockers) {
                if (usedBlockers.contains(blocker) || !CombatUtil.canBlock(attackerCard, blocker)) {
                    continue;
                }
                boolean killsAttacker = blocker.getNetPower() >= attackerCard.getNetToughness();
                boolean blockerSurvives = attackerCard.getNetPower() < blocker.getNetToughness();
                if (killsAttacker && blockerSurvives
                        && (best == null || chumpCost(blocker) < chumpCost(best))) {
                    best = blocker;
                }
            }
            if (best != null) {
                result.add(ImmutablePair.of(attackerCard, best));
                usedBlockers.add(best);
            }
        }

        // Pass 2: even trades (both die) for attackers still unblocked.
        for (Card attackerCard : byPowerDesc) {
            boolean alreadyBlocked = result.stream().anyMatch(p -> p.getLeft().equals(attackerCard));
            if (alreadyBlocked) {
                continue;
            }
            Card best = null;
            for (Card blocker : potentialBlockers) {
                if (usedBlockers.contains(blocker) || !CombatUtil.canBlock(attackerCard, blocker)) {
                    continue;
                }
                boolean killsAttacker = blocker.getNetPower() >= attackerCard.getNetToughness();
                boolean blockerDies = attackerCard.getNetPower() >= blocker.getNetToughness();
                if (killsAttacker && blockerDies
                        && (best == null || chumpCost(blocker) < chumpCost(best))) {
                    best = blocker;
                }
            }
            if (best != null) {
                result.add(ImmutablePair.of(attackerCard, best));
                usedBlockers.add(best);
            }
        }

        return result;
    }

    /**
     * Copies the game, applies one candidate blocker/attacker assignment set directly to the
     * copy's {@code Combat} object (bypassing any controller re-entry), advances that same copy
     * through combat damage (see {@link #advanceCopyThroughCombatDamage}), and scores the resulting
     * post-combat-damage state with {@code evaluator} (TICKET-V4-013: neural or heuristic, resolved
     * once per decision by the caller -- see {@link #resolveCombatEvaluator}). Returns
     * {@code Integer.MIN_VALUE} on any simulation failure for this specific candidate so one bad
     * candidate doesn't abort the whole search -- {@link #chooseBlockPlanViaSimulation} still has
     * "no blocks" as a safety net.
     */
    private int scoreBlockCandidate(Game game, Player defender, List<Pair<Card, Card>> candidate, StateEvaluator evaluator) {
        try {
            // TICKET-V4-014 (Version A, change 2): the HARD copy-budget check, immediately before
            // the actual GameCopier.makeCopy() this candidate pays -- see scoreAttackCandidate's
            // matching checkpoint.
            if (!UltronConfig.tryConsumeSimCopyBudget()) {
                Logger.warn("[Ultron] declareBlockers candidate simulation skipped; per-decision "
                        + "copy budget (" + UltronConfig.maxSimCopiesPerDecision() + ") exhausted");
                return Integer.MIN_VALUE;
            }
            GameCopier copier = new GameCopier(game);
            Game gameCopy = copier.makeCopy(null, defender);
            Player defenderCopy = (Player) copier.find(defender);
            Combat combatCopy = gameCopy.getPhaseHandler().getCombat();
            if (combatCopy == null) {
                return Integer.MIN_VALUE;
            }
            for (Pair<Card, Card> assignment : candidate) {
                Card attackerCopy = (Card) copier.find(assignment.getLeft());
                Card blockerCopy = (Card) copier.find(assignment.getRight());
                combatCopy.addBlocker(attackerCopy, blockerCopy);
            }
            // Normally set by PhaseHandler.declareBlockersTurnBasedAction's own post-loop call to
            // Combat.fireTriggersForUnblockedAttackers once every defending player has declared --
            // but (by design, same as declareAttackers' candidate scoring) this candidate's blocks
            // are applied directly to the copy's Combat, bypassing that turn-based action entirely,
            // so nothing else sets the per-band "blocked" flag. Leaving it null crashes downstream
            // combat-damage assignment (AttackingBand.isBlocked() unboxed without a null check).
            // Deliberately not calling fireTriggersForUnblockedAttackers itself here: it also fires
            // AttackerUnblocked triggers, which would be premature/duplicated once the outer
            // simulation's own devAdvanceToPhase runs (or, for other already-declared defenders'
            // bands whose flag was copied as non-null, would be flat-out wrong to refire).
            for (Card attackerCard : combatCopy.getAttackers()) {
                combatCopy.setBlocked(attackerCard, !combatCopy.getBlockers(attackerCard).isEmpty());
            }
            advanceCopyThroughCombatDamage(gameCopy, defenderCopy);
            GameStateEvaluator.Score score = evaluator.getScoreForGameState(gameCopy, defenderCopy);
            return score.value;
        } catch (RuntimeException ex) {
            Logger.warn("[Ultron] declareBlockers candidate simulation threw " + ex + "; skipping this candidate");
            return Integer.MIN_VALUE;
        }
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
     *
     * <p><b>P2.6 (FORGE_TRACKER TICKET-V3-206) finding -- stack response is this same method, not a
     * separate decision point.</b> {@code PhaseHandler.mainLoopStep} (the game's only priority-pass
     * entrypoint, {@code PhaseHandler.java:1078}) calls
     * {@code pPlayerPriority.getController().chooseSpellAbilityToPlay()} identically whenever a
     * player has priority -- during their own main phase <em>and</em> during any other player's turn
     * with a non-empty stack. Forge has no separate "should I respond to the current stack object"
     * override point; "stack response" is simply this method invoked while
     * {@code game.getStack()} happens to be non-empty. {@code SpellAbilityPicker} (see its own
     * class) already handles that case correctly without any main-phase-vs-response special-casing:
     * it explicitly declines to act on its own spell ({@code "Pass if top of stack is owned by
     * me"}), its candidate list ({@link SpellAbilityPicker#getCandidateSpellsAndAbilities()}) is
     * naturally restricted to instant-speed abilities whenever the stack is non-empty (sorcery-speed
     * candidates fail {@code SpellAbility#isLegalAfterStack()}/timing checks in
     * {@code canPlayAndPayForSim}), and "pass" is always an implicit candidate -- the search only
     * ever returns something other than {@code null} if simulating it scores strictly better than
     * doing nothing. This is exactly the "respond vs pass" search P2.6 asked for; no new
     * candidate-enumeration or decision-routing code was needed. See
     * {@code UltronStackResponseSimulationTest} for the verification (countering a lethal-relevant
     * threat, declining to waste removal/counters on a low-value target, and killing an attacker in
     * response before blockers to survive lethal combat -- all routed through this one method).
     *
     * <p><b>No new recursion surface.</b> Unlike {@link #declareAttackers}/{@link #declareBlockers}
     * (which get re-entered mid-simulation because {@code GameStateEvaluator.
     * simulateUpcomingCombatThisTurn}'s {@code devAdvanceToPhase} runs real combat turn-based-actions
     * that call {@code getController().declareBlockers(...)} on a copy's players -- see
     * TICKET-V3-205), this method is never invoked on a copied/simulated game's players at all.
     * {@code devAdvanceToPhase} only runs phase-transition turn-based actions (never the interactive
     * priority loop in {@code mainLoopStep}), so a copy's {@code chooseSpellAbilityToPlay()} is never
     * called that way. {@code GameSimulator}'s own internal recursion for "what would I play after
     * this" ({@code SpellAbilityPicker sim = new SpellAbilityPicker(simGame, aiPlayer); sim.
     * chooseSpellAbilityToPlay(controller)}) calls the picker object directly, never
     * {@code aiPlayer.getController()} -- so it can never construct or invoke a fresh
     * {@code UltronPlayerController}. And {@code GameSimulator.resolveStack} (used to resolve
     * responses during scoring, the same {@code // TODO: Support multiple opponents.} weakest-
     * opponent gap TICKET-V3-203/204/205 already documented) explicitly constructs a plain
     * {@code new PlayerControllerAi(...)} for the responding opponent rather than looking up that
     * seat's real profile -- so even that path can never reach {@code UltronPlayerController}.
     * {@link #SIMULATION_IN_PROGRESS} is therefore correctly left unused by this method: there is no
     * cross-controller nesting for it to guard against here, only for the combat overrides.
     *
     * <p><b>The weakest-opponent gap is now directly load-bearing for correctness, not just an
     * optimism margin (still correctly deferred to Phase 4 per this session's instructions).</b> For
     * P2.4's main-phase case, the gap only affected the score of "what happens after I resolve my
     * own spell" lookahead. For stack response, the exact same {@code resolveStack(simGame,
     * aiPlayer.getWeakestOpponent())} call is what resolves the game state *immediately after*
     * Ultron's own simulated response -- so if a third, non-weakest opponent also held interaction
     * relevant to the outcome (e.g. a second counterspell, or a trick that changes whether Ultron's
     * removal actually saves it), the simulated score won't reflect it. This is a real, direct input
     * to the "should I respond" decision now (not fixed here -- Phase 4's belief-state/determinization
     * work is still the right place, per the plan and prior tickets' precedent).
     *
     * <p><b>A second, previously-undocumented gap found while building this session's verification
     * tests (distinct from the weakest-opponent one above): responses that legally target something
     * on the stack itself -- chiefly true countermagic -- can never be chosen today.</b>
     * {@code GameCopier.makeCopy} only preserves the actual {@code SpellAbilityStackInstance} queue
     * ({@code game.getStack()}) when the static {@code GameSimulator.COPY_STACK} flag is true, which
     * it is not by default (see {@code GameSimulator.java:20}, {@code GameCopier.java:177-178}) --
     * that flag only gets flipped on transiently, inside {@code GameSimulator}'s own constructor, to
     * resolve the *original* game's stack once for a comparable baseline score, never for the copies
     * actually used to simulate a candidate. So when scoring "what if I cast Counterspell now," the
     * copy's card-zone view of {@code Stack} still shows the opposing spell's card (that part of
     * {@code GameCopier} is unconditional), but there is no ability-stack entry for
     * {@code MultiTargetSelector} to offer as a legal {@code TargetType$ Spell} target --
     * {@code hasPossibleTargets()} is false, no target is ever chosen, {@code SpellAbility.
     * isTargetNumberValid()} fails, and {@code ComputerUtil.handlePlayingSpellAbility} returns
     * {@code false} -- {@code GameSimulator.simulateSpellAbility} then unconditionally scores that
     * candidate as {@code Integer.MIN_VALUE}, regardless of how severe the countered threat actually
     * is. Confirmed by direct instrumentation this session (not just code-reading): a Counterspell
     * candidate against an unanswered Serra Angel evaluates to {@code MIN_VALUE} every time, so
     * {@code chooseSpellAbilityToPlay()} always returns {@code null} rather than countering it -- see
     * {@code UltronStackResponseSimulationTest#testCounterspellCandidateCannotBeEvaluatedDueToUncopiedStack()}.
     * This is a harder failure than the weakest-opponent gap (a permanent, not probabilistic,
     * inability to ever counter anything) but is <b>not fixed here</b>: this session's constraints
     * explicitly forbid touching {@code GameCopier.java}, and a real fix means
     * {@code GameCopier} unconditionally preserving the ability-stack queue (not just the zone's
     * cards), which is squarely that file. Recommended as an early Phase 3/4 prerequisite --
     * countermagic is common enough in Battlebox that this caps Ultron's ceiling independent of the
     * belief-state/hidden-information work Phase 4 already plans. Anything targeting the
     * battlefield/players instead of the stack (removal, combat tricks, burn) is unaffected and works
     * correctly through this same path today.
     *
     * <p><b>TICKET-V3-207 (session 4) recursion guard, added alongside {@link #declareAttackers}'s
     * new one.</b> This method's own javadoc claims (correctly, verified above) that it is never
     * re-entered on a copied/simulated game's players -- {@code devAdvanceToPhase} never runs the
     * interactive priority loop. But it is very much a SOURCE of nested Ultron recursion for
     * {@link #declareAttackers}/{@link #declareBlockers}: every candidate this method's own
     * {@code SpellAbilityPicker} search scores calls {@code GameStateEvaluator.
     * getScoreForGameState}, whose combat lookahead can invoke this player's OWN {@code
     * declareAttackers}/{@code declareBlockers} on a copy. Setting {@link #SIMULATION_IN_PROGRESS}
     * for this method's own duration (not just {@code declareAttackers}/{@code declareBlockers}'s)
     * ensures that ANY nested combat decision triggered while scoring a main-phase candidate falls
     * back to the cheap inherited path rather than recursing into Ultron's full pruned-candidate
     * combat search -- see {@link #declareAttackers}'s javadoc for the full multiplier analysis
     * this eliminates. Safe to add here: unlike {@code declareAttackers}/{@code declareBlockers},
     * this method can never itself be a NESTED call (per the paragraph above), so it will never see
     * the flag already {@code true} on entry in practice -- the check below is defensive symmetry,
     * not a load-bearing case for this method specifically.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final long __start = System.nanoTime();
        if (Boolean.TRUE.equals(SIMULATION_IN_PROGRESS.get())) {
            List<SpellAbility> __fallback = super.chooseSpellAbilityToPlay();
            telemetry.record("chooseSpellAbilityToPlay", false, System.nanoTime() - __start);
            return __fallback;
        }
        List<SpellAbility> __result;
        boolean __answeredByUltron;
        boolean __stackNonEmpty = !getGame().getStack().isEmpty();
        int __candidateCount = 0;
        Integer __chosenScore = null;
        try {
            // TICKET-V3-207 (session 6): bounded to UltronConfig.maxSimDecisionTimeoutSeconds() --
            // SIMULATION_IN_PROGRESS is set/cleared INSIDE the worker's own work (not here on the
            // calling thread) since a nested combat-lookahead call reentering declareAttackers/
            // declareBlockers during scoring happens synchronously on whichever thread runs the
            // search. See runWithDecisionTimeout's javadoc for the full thread-safety analysis.
            SpellPlanResult __planResult = runWithDecisionTimeout("chooseSpellAbilityToPlay", () -> {
                SIMULATION_IN_PROGRESS.set(Boolean.TRUE);
                // TICKET-V4-014 (Version A, change 2): activate this decision's hard per-decision
                // copy budget (UltronConfig.maxSimCopiesPerDecision()) for the duration of the whole
                // decision tree on this worker thread, including any recursive lookahead the picker's
                // own search triggers -- see UltronConfig.SIM_COPY_BUDGET's javadoc for why a
                // ThreadLocal is correct here. Cleared in the matching finally below so it can never
                // leak into a later decision or a non-Ultron caller sharing the thread pool.
                UltronConfig.resetSimCopyBudget();
                try {
                    SpellAbilityPicker __picker = getAi().getSimulationPicker();
                    // TICKET-V4-014 (Version A, change 1): flat afterstate scoring, no recursive
                    // lookahead -- UltronConfig.maxSimRecursionDepth() defaults to 0. Verified by code
                    // read (GameSimulator.simulateSpellAbility's eval.getScoreForGameState call is
                    // unconditional, before the controller.shouldRecurse() gate that depth bounds) that
                    // depth 0 still scores every top-level candidate by its own immediate afterstate --
                    // it just removes cost source #1 of the three diagnosed in FORGE_TRACKER
                    // TICKET-V4-014 (nested MAX_LOOKAHEAD_CANDIDATES-wide recursive search per
                    // candidate), which V4-010/011/013's soft deadlines could not bound because
                    // recursion multiplies candidate count exponentially with depth. Superseded
                    // TICKET-V3-207 (Ultron v3, session 4)'s depth-1 bound -- see SimulationController's
                    // and SpellAbilityPicker's matching javadoc for that session's original rationale;
                    // depth 0 is strictly shallower and is now the default (configurable via
                    // ULTRON_SIM_MAX_RECURSION_DEPTH for tuning). Scoped to this picker instance only
                    // (does not touch the shared SimulationController default any other AI profile/test
                    // relies on).
                    __picker.setMaxRecursionDepth(UltronConfig.maxSimRecursionDepth());
                    // TICKET-V4-011 lever 1 (root-cause fix for the abandoned-worker OOM leak --
                    // FORGE_TRACKER TICKET-V4-011, diagnosed in TICKET-V4-003): give the picker's own
                    // top-level candidate search a cooperative deadline set to 80% of
                    // maxSimDecisionTimeoutSeconds(), so the search itself stops and returns
                    // best-so-far comfortably BEFORE runWithDecisionTimeout's FutureTask would
                    // otherwise abandon this worker thread mid-allocation -- that abandonment (the
                    // thread keeps running and keeps allocating GameCopier trees with no way to be
                    // stopped) is the actual OOM mechanism TICKET-V4-003 identified. The 20% margin
                    // leaves headroom for this method's own setup/teardown and the final candidate's
                    // in-flight GameSimulator work outside the picker's own per-candidate checkpoints.
                    // Only this call site ever calls setDeadlineMillis -- every other caller of
                    // SpellAbilityPicker (Default AI's own USE_SIMULATION path, every existing test)
                    // never sets it, so deadlineExceeded() is always false there: unchanged behavior.
                    long __budgetMillis = (long) (UltronConfig.maxSimDecisionTimeoutSeconds() * 1000L * 0.8);
                    __picker.setDeadlineMillis(System.currentTimeMillis() + __budgetMillis);
                    // TICKET-V4-011 lever 2: Ultron-only cap on top-level candidate breadth, so a
                    // pathological hand size doesn't need the deadline to even fire. Generous default
                    // (UltronConfig.maxSimTopLevelCandidates()) so it rarely bites in normal play.
                    __picker.setMaxTopLevelCandidates(UltronConfig.maxSimTopLevelCandidates());
                    try {
                        int candidateCount = __picker.getCandidateSpellsAndAbilities().size();
                        SpellAbility chosen = __picker.chooseSpellAbilityToPlay(null);
                        GameStateEvaluator.Score score = __picker.getScoreForChosenAbility();
                        return new SpellPlanResult(chosen, candidateCount, score == null ? null : score.value);
                    } finally {
                        // Clear back to the "unset" state: getSimulationPicker() returns a single
                        // instance reused across every future decision for this player, and a stale
                        // deadline/cap must not leak into an unrelated later decision.
                        __picker.setDeadlineMillis(0);
                        __picker.setMaxTopLevelCandidates(null);
                    }
                } finally {
                    UltronConfig.clearSimCopyBudget();
                    SIMULATION_IN_PROGRESS.set(Boolean.FALSE);
                }
            });
            __candidateCount = __planResult.candidateCount;
            __chosenScore = __planResult.chosenScore;
            __result = __planResult.chosen == null ? null : Lists.newArrayList(__planResult.chosen);
            __answeredByUltron = true;
        } catch (RuntimeException __ex) {
            Logger.warn("[Ultron] simulation-based chooseSpellAbilityToPlay() threw " + __ex
                    + "; falling back to inherited behavior (see FORGE_TRACKER TICKET-V3-203/207)");
            __result = super.chooseSpellAbilityToPlay();
            __answeredByUltron = false;
        }
        Map<String, Object> __detail = new LinkedHashMap<>();
        __detail.put("stackNonEmpty", __stackNonEmpty);
        __detail.put("candidateCount", __candidateCount);
        __detail.put("chosenScore", __chosenScore);
        telemetry.recordDetail("chooseSpellAbilityToPlay", __detail);
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
