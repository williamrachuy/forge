package forge.ai.llm.runtime;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full four-player table threat summary built from per-opponent profiles.
 * The single source of truth for multiplayer situational awareness during a
 * runtime decision.
 */
public final class UltronTableThreatSummary {

    public final Player ultron;
    public final List<UltronOpponentProfile> opponents;
    public final UltronOpponentProfile leader;               // highest boardValue opponent; null if Ultron leads
    public final UltronOpponentProfile weakest;              // lowest life among opponents
    public final UltronOpponentProfile mostDangerousToUltron;
    public final UltronOpponentProfile mostVulnerable;       // highest vulnerability score

    public final int ultronBoardValue;
    public final int ultronLife;
    public final int ultronHandSize;
    public final int ultronOpenManaEstimate;
    public final boolean ultronIsAhead;
    public final boolean ultronIsBehind;
    public final boolean ultronInDanger;
    public final boolean ultronHasCounterspell;
    public final boolean ultronHasMonarch;
    public final Player monarchHolder;          // null if no monarch in play yet

    private UltronTableThreatSummary(Builder b) {
        ultron = b.ultron;
        opponents = Collections.unmodifiableList(b.opponents);
        leader = b.leader;
        weakest = b.weakest;
        mostDangerousToUltron = b.mostDangerousToUltron;
        mostVulnerable = b.mostVulnerable;
        ultronBoardValue = b.ultronBoardValue;
        ultronLife = b.ultronLife;
        ultronHandSize = b.ultronHandSize;
        ultronOpenManaEstimate = b.ultronOpenManaEstimate;
        ultronIsAhead = b.ultronIsAhead;
        ultronIsBehind = b.ultronIsBehind;
        ultronInDanger = b.ultronInDanger;
        ultronHasCounterspell = b.ultronHasCounterspell;
        ultronHasMonarch = b.ultronHasMonarch;
        monarchHolder = b.monarchHolder;
    }

    /** Build the summary for Ultron from live game state. */
    public static UltronTableThreatSummary analyze(Game game, Player ultron) {
        Builder b = new Builder();
        b.ultron = ultron;
        b.ultronLife = ultron.getLife();
        b.ultronHandSize = ultron.getCardsIn(ZoneType.Hand).size();

        // Check whether Ultron holds a counterspell (gates reserveCounterspellMana in intent)
        outer:
        for (Card c : ultron.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() == ApiType.Counter) { b.ultronHasCounterspell = true; break outer; }
            }
        }

        // Ultron's own board metrics
        CardCollectionView ultronBf = ultron.getCardsIn(ZoneType.Battlefield);
        int ultronPower = 0;
        int untappedLands = 0;
        for (Card c : ultronBf) {
            if (c.isCreature()) ultronPower += Math.max(0, c.getNetPower());
            if (c.isLand() && !c.isTapped()) untappedLands++;
        }
        b.ultronOpenManaEstimate = untappedLands;
        b.ultronBoardValue = ultronPower * 3 + b.ultronHandSize * 2 + untappedLands
                + (int) ultronBf.stream().filter(c -> !c.isLand() && !c.isCreature()).count() * 2;

        // Opponent profiles
        List<UltronOpponentProfile> profiles = new ArrayList<>();
        for (Player opp : ultron.getOpponents()) {
            profiles.add(UltronOpponentProfile.analyze(opp, ultron));
        }

        // Identify leader (highest boardValue among opponents)
        UltronOpponentProfile bestOpp = null;
        for (UltronOpponentProfile p : profiles) {
            if (bestOpp == null || p.boardValue > bestOpp.boardValue) bestOpp = p;
        }
        boolean ultronIsLeader = bestOpp == null || b.ultronBoardValue > bestOpp.boardValue * 1.1;
        UltronOpponentProfile leader = null;
        if (!ultronIsLeader && bestOpp != null) {
            int idx = profiles.indexOf(bestOpp);
            profiles.set(idx, bestOpp.withLeader(true));
            leader = profiles.get(idx);
        }
        b.leader = leader;

        // Most dangerous to Ultron
        UltronOpponentProfile dangerous = null;
        for (UltronOpponentProfile p : profiles) {
            if (dangerous == null || p.combatThreatToUltron > dangerous.combatThreatToUltron) {
                dangerous = p;
            }
        }
        if (dangerous != null) {
            int idx = profiles.indexOf(dangerous);
            profiles.set(idx, dangerous.withMostDangerous(true));
            dangerous = profiles.get(idx);
        }
        b.mostDangerousToUltron = dangerous;

        // Weakest life
        UltronOpponentProfile weakest = null;
        for (UltronOpponentProfile p : profiles) {
            if (weakest == null || p.life < weakest.life) weakest = p;
        }
        b.weakest = weakest;

        // Most vulnerable
        UltronOpponentProfile mostVulnerable = null;
        for (UltronOpponentProfile p : profiles) {
            if (mostVulnerable == null || p.vulnerability > mostVulnerable.vulnerability) {
                mostVulnerable = p;
            }
        }
        b.mostVulnerable = mostVulnerable;

        b.opponents = profiles;

        // Situational flags (heuristic baseline)
        // Monarch tracking
        b.ultronHasMonarch = ultron.isMonarch();
        b.monarchHolder = game.getMonarch();

        b.ultronInDanger = profiles.stream().anyMatch(p -> p.canLikelyKillUltronSoon);
        int avgOppBV = profiles.isEmpty() ? 0
                : profiles.stream().mapToInt(p -> p.boardValue).sum() / profiles.size();
        b.ultronIsAhead  = b.ultronBoardValue > avgOppBV * 125 / 100;
        b.ultronIsBehind = b.ultronBoardValue < avgOppBV * 75  / 100;

        // Optional: refine ahead/behind with the simulation evaluator when budget allows
        if (game != null && forge.ai.llm.UltronConfig.useSimulationEval()) {
            int simScore = UltronGameStateEvaluator.evaluateWithSimulation(game, ultron);
            if (simScore > 200) b.ultronIsAhead = true;
            else if (simScore < -100) { b.ultronIsBehind = true; b.ultronIsAhead = false; }
        }

        return new UltronTableThreatSummary(b);
    }

    /** Look up the profile for a specific player. Returns null if not found. */
    public UltronOpponentProfile profileFor(Player p) {
        for (UltronOpponentProfile op : opponents) {
            if (op.player.equals(p)) return op;
        }
        return null;
    }

    private static final class Builder {
        Player ultron;
        List<UltronOpponentProfile> opponents;
        UltronOpponentProfile leader, weakest, mostDangerousToUltron, mostVulnerable;
        int ultronBoardValue, ultronLife, ultronHandSize, ultronOpenManaEstimate;
        boolean ultronIsAhead, ultronIsBehind, ultronInDanger, ultronHasCounterspell;
        boolean ultronHasMonarch;
        Player monarchHolder;
    }
}
