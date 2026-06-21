package forge.ai.llm.runtime;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Snapshot of a single opponent's visible board state.
 * Constructed once per UltronThreatModel build; never mutated after construction.
 */
public final class UltronOpponentProfile {

    public final Player player;
    public final int life;
    public final int poison;
    public final int cardsInHand;
    public final int battlefieldCount;
    public final int graveyardCount;
    public final int openManaEstimate;   // rough estimate based on untapped lands
    public final int creatureCount;
    public final int totalPower;
    public final int untappedPower;     // power available to block or attack with vigilance
    public final int evasivePower;      // power with flying/unblockable/shadow/etc.
    public final int boardValue;        // composite strength score
    public final int engineValue;       // repeatable-effect / draw-engine indicator
    public final int commanderValue;    // commander damage dealt / relevant commander on field
    public final int comboThreat;       // likelihood of assembling a combo soon (0-100)
    public final int combatThreatToUltron;  // estimated damage if this player attacks Ultron
    public final int lethalThreatToUltron;  // 0 = not lethal, 100 = very likely lethal soon
    public final int vulnerability;         // how easy this player is to eliminate (0-100)
    public final boolean isLeader;              // highest boardValue at the table
    public final boolean isMostDangerousToUltron;
    public final boolean canLikelyKillUltronSoon;

    private UltronOpponentProfile(Builder b) {
        player = b.player;
        life = b.life;
        poison = b.poison;
        cardsInHand = b.cardsInHand;
        battlefieldCount = b.battlefieldCount;
        graveyardCount = b.graveyardCount;
        openManaEstimate = b.openManaEstimate;
        creatureCount = b.creatureCount;
        totalPower = b.totalPower;
        untappedPower = b.untappedPower;
        evasivePower = b.evasivePower;
        boardValue = b.boardValue;
        engineValue = b.engineValue;
        commanderValue = b.commanderValue;
        comboThreat = b.comboThreat;
        combatThreatToUltron = b.combatThreatToUltron;
        lethalThreatToUltron = b.lethalThreatToUltron;
        vulnerability = b.vulnerability;
        isLeader = b.isLeader;
        isMostDangerousToUltron = b.isMostDangerousToUltron;
        canLikelyKillUltronSoon = b.canLikelyKillUltronSoon;
    }

    /**
     * Analyze an opponent relative to Ultron's full player state.
     * Accounts for commander damage dealt to Ultron and commanders on the battlefield.
     */
    public static UltronOpponentProfile analyze(Player opponent, Player ultron) {
        Builder b = new Builder();
        b.player = opponent;
        int ultronLife = ultron.getLife();
        b.life = opponent.getLife();
        b.poison = opponent.getPoisonCounters();
        b.cardsInHand = opponent.getCardsIn(ZoneType.Hand).size();
        b.graveyardCount = opponent.getCardsIn(ZoneType.Graveyard).size();

        CardCollectionView bf = opponent.getCardsIn(ZoneType.Battlefield);
        b.battlefieldCount = bf.size();

        int untappedLands = 0;
        int artifacts = 0;
        int enchantments = 0;
        int engineKeywords = 0;
        int graveyardCreatures = 0;
        int commanderPower = 0;

        for (Card c : bf) {
            if (c.isLand() && !c.isTapped()) untappedLands++;
            if (c.isArtifact()) artifacts++;
            if (c.isEnchantment()) enchantments++;

            if (c.isCreature()) {
                b.creatureCount++;
                int power = Math.max(0, c.getNetPower());
                b.totalPower += power;
                if (!c.isTapped()) b.untappedPower += power;

                boolean evasive = c.hasKeyword(Keyword.FLYING)
                        || c.hasKeyword(Keyword.SHADOW)
                        || c.hasKeyword(Keyword.HORSEMANSHIP)
                        || c.hasKeyword(Keyword.FEAR)
                        || c.hasKeyword(Keyword.INTIMIDATE)
                        || c.hasKeyword(Keyword.MENACE)
                        || c.getOracleText().toLowerCase().contains("can't be blocked");
                if (evasive) b.evasivePower += power;
                if (c.isCommander()) commanderPower += power;
            }

            // Engine heuristics from oracle text / keywords
            String rules = c.getOracleText().toLowerCase();
            if (rules.contains("whenever") || rules.contains("at the beginning")
                    || rules.contains("draw") || rules.contains("create")
                    || rules.contains("token")) {
                engineKeywords++;
            }
        }

        for (Card c : opponent.getCardsIn(ZoneType.Graveyard)) {
            if (c.isCreature()) graveyardCreatures++;
        }

        b.openManaEstimate = untappedLands;

        // Commander damage already dealt to Ultron by this opponent's commanders
        int cmdDamageDealt = 0;
        for (java.util.Map.Entry<Card, Integer> entry : ultron.getCommanderDamage()) {
            Card cmd = entry.getKey();
            if (opponent.getCommanders().contains(cmd)
                    || opponent.equals(cmd.getOwner())
                    || opponent.equals(cmd.getController())) {
                cmdDamageDealt += entry.getValue();
            }
        }
        // commanderValue: pressure from commander combat damage already dealt + commander on field
        b.commanderValue = Math.min(100, cmdDamageDealt * 4 + commanderPower * 5);

        // boardValue: composite score — commander pressure added
        b.boardValue = b.totalPower * 3
                + artifacts * 2
                + enchantments * 2
                + b.cardsInHand * 2
                + untappedLands
                + engineKeywords * 3
                + b.commanderValue / 5;

        // engineValue: heavy draws / repeatable effects
        b.engineValue = engineKeywords * 5 + artifacts + enchantments;

        // comboThreat: suspicious artifact/enchantment density + large graveyard
        int comboRaw = (artifacts + enchantments) * 5 + graveyardCreatures * 3;
        b.comboThreat = Math.min(100, comboRaw);

        // combatThreatToUltron: evasive punches through, non-evasive partially
        b.combatThreatToUltron = b.evasivePower + b.totalPower / 2;

        // lethalThreatToUltron
        int lethalRaw = 0;
        if (b.combatThreatToUltron >= ultronLife) lethalRaw = 95;
        else if (b.combatThreatToUltron >= ultronLife * 2 / 3) lethalRaw = 70;
        else if (b.combatThreatToUltron >= ultronLife / 2) lethalRaw = 40;
        // Commander damage already dealt escalates the threat: 15+ damage means one more attack kill
        if (cmdDamageDealt >= 15) lethalRaw = Math.max(lethalRaw, 80);
        b.lethalThreatToUltron = lethalRaw;

        // vulnerability: low life + weak board (calibrated for 20-life Battlebox format)
        int vulnRaw = 0;
        if (b.life <= 8) vulnRaw = 95;
        else if (b.life <= 13) vulnRaw = 70;
        else if (b.life <= 15 && b.totalPower == 0 && b.cardsInHand <= 2) vulnRaw = 50;
        else if (b.life <= 16 && b.battlefieldCount <= 2) vulnRaw = 25;
        b.vulnerability = vulnRaw;

        b.canLikelyKillUltronSoon = b.lethalThreatToUltron >= 70;
        // isLeader and isMostDangerousToUltron set later by UltronTableThreatSummary

        return new UltronOpponentProfile(b);
    }

    /** Return a copy with isLeader=true. */
    UltronOpponentProfile withLeader(boolean leader) {
        Builder b = copyBuilder();
        b.isLeader = leader;
        return new UltronOpponentProfile(b);
    }

    /** Return a copy with isMostDangerousToUltron=true. */
    UltronOpponentProfile withMostDangerous(boolean dangerous) {
        Builder b = copyBuilder();
        b.isMostDangerousToUltron = dangerous;
        return new UltronOpponentProfile(b);
    }

    private Builder copyBuilder() {
        Builder b = new Builder();
        b.player = player; b.life = life; b.poison = poison; b.cardsInHand = cardsInHand;
        b.battlefieldCount = battlefieldCount; b.graveyardCount = graveyardCount;
        b.openManaEstimate = openManaEstimate; b.creatureCount = creatureCount;
        b.totalPower = totalPower; b.untappedPower = untappedPower; b.evasivePower = evasivePower;
        b.boardValue = boardValue; b.engineValue = engineValue; b.commanderValue = commanderValue;
        b.comboThreat = comboThreat; b.combatThreatToUltron = combatThreatToUltron;
        b.lethalThreatToUltron = lethalThreatToUltron; b.vulnerability = vulnerability;
        b.isLeader = isLeader; b.isMostDangerousToUltron = isMostDangerousToUltron;
        b.canLikelyKillUltronSoon = canLikelyKillUltronSoon;
        return b;
    }

    private static final class Builder {
        Player player;
        int life, poison, cardsInHand, battlefieldCount, graveyardCount, openManaEstimate;
        int creatureCount, totalPower, untappedPower, evasivePower;
        int boardValue, engineValue, commanderValue;
        int comboThreat, combatThreatToUltron, lethalThreatToUltron, vulnerability;
        boolean isLeader, isMostDangerousToUltron, canLikelyKillUltronSoon;
    }
}
