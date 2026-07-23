package forge.ai.ability;


import forge.ai.*;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.TextUtil;

import java.util.ArrayList;
import java.util.List;

public class RollPlanarDiceAi extends SpellAbilityAi {
    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    // Differential-evaluation tunables. GameStateEvaluator scores run in the hundreds+ (life x2,
    // cards x5, creatures ~100 each), so a plane's swing on this AI is on the order of a few dozen
    // points. KEEP_THRESHOLD is how strongly the current plane must favor the AI before it declines
    // even a free roll. ROLL_MARGIN_PER_COST is how large a measured drag must be, per unit of the
    // escalating {X} reroll cost, before the AI pays mana to leave. These are heuristics -- tune here.
    private static final int KEEP_THRESHOLD = 20;
    private static final int ROLL_MARGIN_PER_COST = 40;

    @Override
    protected AiAbilityDecision canPlay(Player ai, SpellAbility sa) {
        final Game game = ai.getGame();
        if (game.getActivePlanes() == null || game.getActivePlanes().isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // Battlebox planechase: dynamically assess whether the current plane is a net drag on this
        // AI (and whether the escalating reroll cost is worth paying), rather than obeying the
        // blanket Mode$ Always hint every stock plane ships with. That hint exists only to make the
        // AI engage at all; here we want it to actually keep planes that favor it.
        if (game.isBattleboxGame()) {
            if (shouldRollViaDifferentialEval(ai)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        // Non-Battlebox Planechase: honor the authored per-plane scripted heuristic (unchanged).
        for (Card c : game.getActivePlanes()) {
            if (willRollOnPlane(ai, c)) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    /**
     * Dynamic roll decision for planes without an authored AI hint. Rolls only when the current
     * plane leaves this AI measurably worse off than not being on it, gated by turn/phase, the
     * per-turn roll cap, and affordability of the escalating {X} cost. Re-assessment for each
     * additional roll is automatic: the special action is re-offered after every roll with a fresh
     * current plane and a higher {X}, and this method re-runs against that new state.
     */
    private static final boolean DEBUG_ROLL = "true".equalsIgnoreCase(System.getenv("FORGE_DEBUG_PLANAR_ROLL"));

    private boolean shouldRollViaDifferentialEval(Player ai) {
        final Game game = ai.getGame();
        final PhaseHandler ph = game.getPhaseHandler();

        final int rollsThisTurn = ph.getPlanarDiceSpecialActionThisTurn();
        final int maxRolls = AiProfileUtil.getIntProperty(ai, AiProps.DEFAULT_MAX_PLANAR_DIE_ROLLS_PER_TURN);
        if (rollsThisTurn >= maxRolls) {
            debugRoll(ai, ph, -1, "SKIP(maxRolls " + rollsThisTurn + ">=" + maxRolls + ")");
            return false;
        }
        final int minTurn = AiProfileUtil.getIntProperty(ai, AiProps.DEFAULT_MIN_TURN_TO_ROLL_PLANAR_DIE);
        if (ph.getTurn() < minTurn) {
            debugRoll(ai, ph, -1, "SKIP(minTurn " + ph.getTurn() + "<" + minTurn + ")");
            return false;
        }
        // Sorcery-speed action; spend in main2 so a roll never robs the AI's own main1 tempo.
        if (ph.getPhase().isBefore(PhaseType.MAIN2)) {
            debugRoll(ai, ph, -1, "SKIP(phase " + ph.getPhase() + ")");
            return false;
        }

        // Cost of this roll is {X} where X = rolls already taken this turn (first roll is free).
        final int cost = rollsThisTurn;
        if (cost > 0 && ComputerUtilMana.getAvailableManaEstimate(ai) < cost) {
            debugRoll(ai, ph, -1, "SKIP(cost " + cost + " unaffordable)");
            return false;
        }

        // planeValueToAi > 0 => the plane helps me (keep it); < 0 => it hurts me (worth leaving).
        final int planeValueToAi = evaluatePlaneValueToAi(ai);
        final boolean decision;
        if (cost == 0) {
            // The first roll of the turn is free: gamble off any plane that isn't clearly helping
            // this AI. Because a static evaluation cannot see purely-triggered plane effects (they
            // read as ~neutral), "roll unless the plane is clearly good for me" keeps genuinely
            // good planes while still cycling away from neutral/unknown ones at no cost -- which is
            // also what produces the roughly per-turn roll cadence a shared planar deck expects.
            decision = planeValueToAi < KEEP_THRESHOLD;
        } else {
            // Paid reroll: only spend escalating mana to escape a plane that measurably drags this AI
            // down, with the bar rising as each additional roll costs more.
            decision = planeValueToAi < -(ROLL_MARGIN_PER_COST * cost);
        }
        debugRoll(ai, ph, planeValueToAi, (decision ? "ROLL" : "KEEP") + "(cost=" + cost + ")");
        return decision;
    }

    private void debugRoll(Player ai, PhaseHandler ph, int planeValue, String verdict) {
        if (DEBUG_ROLL) {
            System.out.println("[PLANAR-ROLL] turn=" + ph.getTurn() + " " + ai + " plane="
                    + ai.getGame().getActivePlanes() + " planeValue=" + planeValue + " -> " + verdict);
        }
    }

    /**
     * Differential evaluation of the current active plane's worth to {@code ai}: the AI's
     * {@link GameStateEvaluator} score with the plane in play, minus its score in a copy where the
     * active plane(s) have been removed (so their continuous effects stop). A positive result means
     * the plane is helping this AI; a negative result means it is a drag. Fails safe to 0 (neutral,
     * i.e. "don't roll") on any copy/eval error. Captures continuous-effect planes; purely
     * triggered-effect planes read as ~neutral, since a static snapshot cannot see them.
     */
    private int evaluatePlaneValueToAi(Player ai) {
        final Game origGame = ai.getGame();
        final List<Card> activePlanes = origGame.getActivePlanes();
        if (activePlanes == null || activePlanes.isEmpty()) {
            return 0;
        }
        try {
            final GameStateEvaluator eval = new GameStateEvaluator();
            final int scoreWith = eval.getScoreForGameState(origGame, ai).value;

            final GameCopier copier = new GameCopier(origGame);
            final Game copy = copier.makeCopy();
            final Player aiCopy = (Player) copier.find(ai);
            if (aiCopy == null) {
                return 0;
            }
            final List<Card> copyPlanes = copy.getActivePlanes();
            if (copyPlanes != null) {
                for (final Card plane : new ArrayList<>(copyPlanes)) {
                    copy.getAction().moveTo(ZoneType.PlanarDeck, plane, null, AbilityKey.newMap());
                }
            }
            copy.setActivePlanes(null);
            copy.getAction().checkStateEffects(true);

            final int scoreWithout = eval.getScoreForGameState(copy, aiCopy).value;
            return scoreWith - scoreWithout;
        } catch (RuntimeException | StackOverflowError ex) {
            return 0;
        }
    }

    private boolean willRollOnPlane(Player ai, Card plane) {
        boolean decideToRoll = false;
        boolean rollInMain1 = false;
        String modeName = "never";
        int maxActivations = AiProfileUtil.getIntProperty(ai, AiProps.DEFAULT_MAX_PLANAR_DIE_ROLLS_PER_TURN);
        int chance = AiProfileUtil.getIntProperty(ai, AiProps.DEFAULT_PLANAR_DIE_ROLL_CHANCE);
        int hesitationChance = AiProfileUtil.getIntProperty(ai, AiProps.PLANAR_DIE_ROLL_HESITATION_CHANCE);
        int minTurnToRoll = AiProfileUtil.getIntProperty(ai, AiProps.DEFAULT_MIN_TURN_TO_ROLL_PLANAR_DIE);
        
        if (plane.hasSVar("AIRollPlanarDieParams")) {
            String[] params = plane.getSVar("AIRollPlanarDieParams").toLowerCase().trim().split("\\|");
            for (String param : params) {
                String[] paramData = param.split("\\$");
                String paramName = paramData[0].trim();
                String paramValue = paramData[1].trim();

                switch (paramName) {
                    case "mode":
                        modeName = paramValue;
                        break;
                    case "chance":
                        chance = Integer.parseInt(paramValue);
                        break;
                    case "minturn":
                        minTurnToRoll = Integer.parseInt(paramValue);
                        break;
                    case "maxrollsperturn":
                        maxActivations = Integer.parseInt(paramValue);
                        break;
                    case "rollinmain1":
                        if (paramValue.equals("true")) {
                            rollInMain1 = true;
                        }
                        break;
                    case "lowpriority":
                        // this is handled in AiController.saComparator at the moment
                        break;
                    case "cardsinhandle": // num of cards in hand less than or equal to N
                        if (ai.getCardsIn(ZoneType.Hand).size() > Integer.parseInt(paramValue)) {
                            return false;
                        }
                        break;
                    case "cardsinhandge": // num of cards in hand greater than or equal to N
                        if (ai.getCardsIn(ZoneType.Hand).size() < Integer.parseInt(paramValue)) {
                            return false;
                        }
                        break;
                    case "cardsingraveyardle":
                        if (ai.getCardsIn(ZoneType.Graveyard).size() > Integer.parseInt(paramValue)) {
                            return false;
                        }
                        break;
                    case "cardsingraveyardge":
                        if (ai.getCardsIn(ZoneType.Graveyard).size() < Integer.parseInt(paramValue)) {
                            return false;
                        }
                        break;
                    case "hascreatureinplay": // TODO: All abilities below only test the presence of the option. The value (true/false) is not yet tested.
                        if (!detectCreatureInZone(ai, ZoneType.Battlefield)) {
                            return false;
                        }
                        break;
                    case "opphascreatureinplay":
                        boolean oppHasCreature = false;
                        for (Player op : ai.getOpponents()) {
                            oppHasCreature |= detectCreatureInZone(op, ZoneType.Battlefield);
                        }
                        if (!oppHasCreature) {
                            return false;
                        }
                        break;
                    case "hascolorcreatureinplay":
                        if (!detectColorInZone(ai, paramValue, ZoneType.Battlefield, true)) {
                            return false;
                        }
                        break;
                    case "hascolorinplay":
                        if (!detectColorInZone(ai, paramValue, ZoneType.Battlefield, false)) {
                            return false;
                        }
                        break;
                    case "hascoloringraveyard":
                        if (!detectColorInZone(ai, paramValue, ZoneType.Graveyard, false)) {
                            return false;
                        }
                        break;
                    default:
                        System.out.println(TextUtil.concatNoSpace("Unexpected AI hint parameter in card ", plane.getName(), " in RollPlanarDiceAi: ", paramName, "."));
                        break;
                }
            }
            
            switch (modeName) {
                case "always":
                    decideToRoll = true;
                    break;
                case "random":
                    if (MyRandom.getRandom().nextInt(100) < chance) {
                        decideToRoll = true;
                    }
                    break;
                case "never":
                    return false;
                default:
                    return false;
            }

            if (ai.getGame().getPhaseHandler().getTurn() < minTurnToRoll) {
                decideToRoll = false;
            } else if (!rollInMain1 && ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)) {
                decideToRoll = false;
            }

            if (ai.getGame().getPhaseHandler().getPlanarDiceSpecialActionThisTurn() >= maxActivations) {
                decideToRoll = false;
            }
        
            // check if the AI hesitates
            if (MyRandom.getRandom().nextInt(100) < hesitationChance) {
                decideToRoll = false; // hesitate
            }
        }

        return decideToRoll;
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#chkAIDrawback(java.util.Map, forge.card.spellability.SpellAbility, forge.game.player.Player)
     */
    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        // for potential implementation of drawback checks?
        return canPlay(aiPlayer, sa);
    }

    private boolean detectColorInZone(Player p, String paramValue, ZoneType zone, boolean creaturesOnly) {
        boolean hasColorInPlay = false;
        for (Card c : p.getCardsIn(zone)) {
            if (!creaturesOnly || c.isCreature()) {
                if (paramValue.contains("u") && c.isBlue()) {
                    hasColorInPlay = true;
                    break;
                }
                if (paramValue.contains("g") && c.isGreen()) {
                    hasColorInPlay = true;
                    break;
                }
                if (paramValue.contains("r") && c.isRed()) {
                    hasColorInPlay = true;
                    break;
                }
                if (paramValue.contains("w") && c.isWhite()) {
                    hasColorInPlay = true;
                    break;
                }
                if (paramValue.contains("b") && c.isBlack()) {
                    hasColorInPlay = true;
                    break;
                }
            }
        }
        return hasColorInPlay;
    }

    private boolean detectCreatureInZone(Player p, ZoneType zone) {
        boolean hasCreatureInPlay = false;
        for (Card c : p.getCardsIn(zone)) {
            if (c.isCreature()) {
                hasCreatureInPlay = true;
                break;
            }
        }
        return hasCreatureInPlay;
    }
}
