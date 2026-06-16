package forge.ai.llm.runtime;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
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
    }

    /** Build the summary for Ultron from live game state. */
    public static UltronTableThreatSummary analyze(Player ultron) {
        Builder b = new Builder();
        b.ultron = ultron;
        b.ultronLife = ultron.getLife();
        b.ultronHandSize = ultron.getCardsIn(ZoneType.Hand).size();

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
            profiles.add(UltronOpponentProfile.analyze(opp, b.ultronLife));
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

        // Situational flags
        b.ultronInDanger = profiles.stream().anyMatch(p -> p.canLikelyKillUltronSoon);
        int avgOppBV = profiles.isEmpty() ? 0
                : profiles.stream().mapToInt(p -> p.boardValue).sum() / profiles.size();
        b.ultronIsAhead  = b.ultronBoardValue > avgOppBV * 125 / 100;
        b.ultronIsBehind = b.ultronBoardValue < avgOppBV * 75  / 100;

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
        boolean ultronIsAhead, ultronIsBehind, ultronInDanger;
    }
}
