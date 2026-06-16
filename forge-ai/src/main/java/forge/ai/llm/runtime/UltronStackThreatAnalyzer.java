package forge.ai.llm.runtime;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.Set;

/**
 * Classifies the top-of-stack spell or ability from Ultron's perspective.
 * Fast: no heap-intensive iteration beyond direct Forge API calls.
 */
public final class UltronStackThreatAnalyzer {

    // Known extra-turn card names
    private static final Set<String> EXTRA_TURN_NAMES = Set.of(
            "Time Warp", "Time Walk", "Temporal Manipulation",
            "Capture of Jingzhou", "Temporal Mastery", "Nexus of Fate",
            "Expropriate", "Beacon of Tomorrows", "Part the Waterveil",
            "Alrund's Epiphany", "Karn's Temporal Sundering", "Savor the Moment"
    );

    // Known game-winning permanents / spells
    private static final Set<String> GAME_WINNER_NAMES = Set.of(
            "Thassa's Oracle", "Jace, Wielder of Mysteries", "Laboratory Maniac",
            "Aetherflux Reservoir", "Approach of the Second Sun",
            "Heliod, Sun-Crowned", "Revel in Riches", "Mechanized Production",
            "Felidar Sovereign", "Darksteel Reactor"
    );

    // Known tutors and combo facilitators
    private static final Set<String> COMBO_PIECE_NAMES = Set.of(
            "Demonic Tutor", "Vampiric Tutor", "Imperial Seal", "Mystical Tutor",
            "Enlightened Tutor", "Worldly Tutor", "Survival of the Fittest",
            "Tainted Pact", "Intuition", "Ad Nauseam", "Necropotence",
            "Underworld Breach", "Yawgmoth's Will", "Past in Flames",
            "Dualcaster Mage", "Peer into the Abyss"
    );

    // Known mass-reanimation spells
    private static final Set<String> MASS_REANIMATE_NAMES = Set.of(
            "Living Death", "Living End", "Twilight's Call", "Patriarch's Bidding",
            "Balthor the Defiled", "Immortal Servitude", "Haunting Voyage",
            "Rise of the Dark Realms", "Finale of Devastation"
    );

    // Tutor detection keywords in oracle text / card name
    private static final Set<String> TUTOR_KEYWORDS = Set.of("tutor", "search", "fetch");

    private UltronStackThreatAnalyzer() {}

    /**
     * Classify a stack ability from Ultron's perspective.
     *
     * @param sa     the spell or ability on top of the stack
     * @param ultron the Ultron player
     * @param table  current table summary (may be null; degrades gracefully)
     */
    public static UltronStackThreat classify(SpellAbility sa, Player ultron,
                                              UltronTableThreatSummary table) {
        if (sa == null) return UltronStackThreat.NONE;

        Card host = sa.getHostCard();
        if (host == null) {
            return new UltronStackThreat(UltronStackThreatType.UNKNOWN_HIGH_IMPACT, 60,
                    sa.getActivatingPlayer(), sa, "null host card");
        }

        String name = host.getName();
        Player caster = sa.getActivatingPlayer();
        ApiType api = sa.getApi();
        UltronOpponentProfile casterProfile = table != null ? table.profileFor(caster) : null;

        // --- Name-based fast path ---
        if (EXTRA_TURN_NAMES.contains(name))
            return makeThreat(UltronStackThreatType.EXTRA_TURN, severityExtraTurn(casterProfile),
                    caster, sa, "extra turn spell");

        if (GAME_WINNER_NAMES.contains(name))
            return makeThreat(UltronStackThreatType.GAME_WINNING_EFFECT, 97,
                    caster, sa, "known game-winner");

        if (MASS_REANIMATE_NAMES.contains(name))
            return makeThreat(UltronStackThreatType.MASS_REANIMATION,
                    severityMassReanimate(casterProfile), caster, sa, "mass reanimation");

        if (COMBO_PIECE_NAMES.contains(name))
            return makeThreat(UltronStackThreatType.COMBO_PIECE,
                    severityCombo(casterProfile), caster, sa, "known combo/tutor piece");

        // --- API-based classification ---
        if (api == null) {
            return makeThreat(UltronStackThreatType.UNKNOWN_HIGH_IMPACT, 55,
                    caster, sa, "null api");
        }

        return switch (api) {
            case AddTurn -> makeThreat(UltronStackThreatType.EXTRA_TURN,
                    severityExtraTurn(casterProfile), caster, sa, "AddTurn api");

            case DestroyAll, DamageAll -> classifyBoardWipe(sa, caster, table);

            case ChangeZoneAll -> classifyMassZoneChange(sa, caster, casterProfile, table);

            case Counter -> makeThreat(UltronStackThreatType.COUNTER_WAR, 55,
                    caster, sa, "counter on stack");

            case LoseLife -> classifyLoseLife(sa, ultron, caster, casterProfile, table);

            case DealDamage -> classifyDealDamage(sa, ultron, caster, casterProfile, table);

            case Destroy -> classifyRemoval(sa, ultron, caster, host);

            case ChangeZone -> classifyZoneChange(sa, ultron, caster, casterProfile, name);

            case Draw -> classifyDraw(caster, casterProfile, sa);

            default -> makeThreat(UltronStackThreatType.LOW_VALUE, 10, caster, sa, "default");
        };
    }

    // -----------------------------------------------------------------------
    // Classification sub-methods
    // -----------------------------------------------------------------------

    private static UltronStackThreat classifyBoardWipe(SpellAbility sa, Player caster,
                                                        UltronTableThreatSummary table) {
        int sev;
        if (table == null) {
            sev = 65;
        } else if (table.ultronIsAhead) {
            // Bad for Ultron — destroy it
            sev = 85;
        } else if (table.leader != null && table.leader.player.equals(caster)) {
            // Leader's board wipe saves the leader — also bad
            sev = 70;
        } else {
            // Board wipe from weak player while Ultron is behind may be welcome
            sev = 35;
        }
        return makeThreat(UltronStackThreatType.BOARD_WIPE, sev, caster, sa, "board wipe");
    }

    private static UltronStackThreat classifyMassZoneChange(SpellAbility sa, Player caster,
                                                             UltronOpponentProfile casterProfile,
                                                             UltronTableThreatSummary table) {
        String origin = sa.getParam("Origin");
        String dest   = sa.getParam("Destination");

        if (origin != null && origin.contains("Battlefield")) {
            // Mass bounce
            int sev = table != null && table.ultronIsAhead ? 75 : 40;
            return makeThreat(UltronStackThreatType.MASS_BOUNCE, sev, caster, sa, "mass bounce");
        }
        if ("Graveyard".equals(origin) && "Battlefield".equals(dest)) {
            return makeThreat(UltronStackThreatType.MASS_REANIMATION,
                    severityMassReanimate(casterProfile), caster, sa, "mass reanimate api");
        }
        return makeThreat(UltronStackThreatType.UNKNOWN_HIGH_IMPACT, 50, caster, sa, "mass zone change");
    }

    private static UltronStackThreat classifyLoseLife(SpellAbility sa, Player ultron,
                                                       Player caster, UltronOpponentProfile profile,
                                                       UltronTableThreatSummary table) {
        // Check if it targets Ultron
        boolean targetsUltron = targetingPlayer(sa, ultron);
        if (!targetsUltron && !isAllPlayers(sa)) {
            return makeThreat(UltronStackThreatType.LOW_VALUE, 15, caster, sa, "life loss not targeting ultron");
        }
        if (isLethalAmount(sa, ultron.getLife())) {
            return makeThreat(UltronStackThreatType.LETHAL_LIFE_LOSS, 98, caster, sa, "lethal life loss");
        }
        int sev = table != null && profile != null && profile.isLeader ? 65 : 45;
        return makeThreat(UltronStackThreatType.LETHAL_LIFE_LOSS, sev, caster, sa, "life loss");
    }

    private static UltronStackThreat classifyDealDamage(SpellAbility sa, Player ultron,
                                                          Player caster, UltronOpponentProfile profile,
                                                          UltronTableThreatSummary table) {
        if (!targetingPlayer(sa, ultron)) {
            return makeThreat(UltronStackThreatType.LOW_VALUE, 10, caster, sa, "damage not at ultron");
        }
        if (isLethalAmount(sa, ultron.getLife())) {
            return makeThreat(UltronStackThreatType.LETHAL_DAMAGE, 99, caster, sa, "lethal damage to ultron");
        }
        return makeThreat(UltronStackThreatType.REMOVAL_TARGETING_ULTRON, 45, caster, sa, "damage to ultron");
    }

    private static UltronStackThreat classifyRemoval(SpellAbility sa, Player ultron,
                                                       Player caster, Card host) {
        Card targeted = sa.getTargets() != null ? sa.getTargets().getFirstTargetedCard() : null;
        if (targeted == null) return makeThreat(UltronStackThreatType.LOW_VALUE, 20, caster, sa, "removal, unknown target");
        if (!targeted.getController().equals(ultron)) {
            return makeThreat(UltronStackThreatType.LOW_VALUE, 10, caster, sa, "removal not targeting ultron");
        }
        boolean keyPermanent = !targeted.isCreature() || targeted.getCMC() >= 4;
        if (keyPermanent) {
            return makeThreat(UltronStackThreatType.REMOVAL_TARGETING_KEY_PERMANENT, 75,
                    caster, sa, "removal on key permanent");
        }
        return makeThreat(UltronStackThreatType.REMOVAL_TARGETING_ULTRON, 55,
                caster, sa, "removal on ultron creature");
    }

    private static UltronStackThreat classifyZoneChange(SpellAbility sa, Player ultron,
                                                          Player caster, UltronOpponentProfile profile,
                                                          String name) {
        String origin = sa.getParam("Origin");
        String dest   = sa.getParam("Destination");

        // Single-target reanimation from a graveyard-heavy/dangerous player
        if ("Graveyard".equals(origin) && "Battlefield".equals(dest)) {
            int sev = profile != null && (profile.isLeader || profile.comboThreat >= 50) ? 75 : 40;
            return makeThreat(UltronStackThreatType.GRAVEYARD_EXPLOSION, sev, caster, sa, "reanimate single");
        }

        // Tutor
        if ("Library".equals(origin)) {
            if (isTutor(name)) {
                int sev = profile != null && (profile.isLeader || profile.comboThreat >= 50) ? 82 : 55;
                return makeThreat(UltronStackThreatType.TUTOR, sev, caster, sa, "tutor");
            }
        }

        // Something targeting Ultron's card
        Card targeted = sa.getTargets() != null ? sa.getTargets().getFirstTargetedCard() : null;
        if (targeted != null && targeted.getController().equals(ultron)) {
            return makeThreat(UltronStackThreatType.REMOVAL_TARGETING_ULTRON, 60, caster, sa, "zone change on ultron card");
        }

        return makeThreat(UltronStackThreatType.LOW_VALUE, 15, caster, sa, "generic zone change");
    }

    private static UltronStackThreat classifyDraw(Player caster, UltronOpponentProfile profile,
                                                   SpellAbility sa) {
        if (profile != null && profile.isLeader) {
            return makeThreat(UltronStackThreatType.VALUE_ENGINE, 55, caster, sa, "draw by table leader");
        }
        return makeThreat(UltronStackThreatType.LOW_VALUE, 12, caster, sa, "draw spell");
    }

    // -----------------------------------------------------------------------
    // Severity helpers
    // -----------------------------------------------------------------------

    private static int severityExtraTurn(UltronOpponentProfile profile) {
        if (profile == null) return 80;
        if (profile.isLeader) return 90;
        if (profile.comboThreat >= 50) return 85;
        return 75;
    }

    private static int severityMassReanimate(UltronOpponentProfile profile) {
        if (profile == null) return 72;
        int graveyardBonus = Math.min(20, profile.graveyardCount / 2);
        return Math.min(95, 65 + (profile.isLeader ? 15 : 0) + graveyardBonus);
    }

    private static int severityCombo(UltronOpponentProfile profile) {
        if (profile == null) return 80;
        return profile.isLeader ? 90 : 80;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static UltronStackThreat makeThreat(UltronStackThreatType type, int severity,
                                                  Player caster, SpellAbility sa, String reason) {
        return new UltronStackThreat(type, severity, caster, sa, reason);
    }

    private static boolean targetingPlayer(SpellAbility sa, Player target) {
        if (sa.getTargets() == null) return false;
        for (Player p : sa.getTargets().getTargetPlayers()) {
            if (p.equals(target)) return true;
        }
        return false;
    }

    private static boolean isAllPlayers(SpellAbility sa) {
        String each = sa.getParam("Defined");
        return each != null && (each.contains("Each") || each.contains("AllPlayers"));
    }

    private static boolean isLethalAmount(SpellAbility sa, int targetLife) {
        String amountParam = sa.getParam("Amount");
        if (amountParam == null) return targetLife <= 5; // X spells: assume lethal if low life
        try {
            return Integer.parseInt(amountParam) >= targetLife;
        } catch (NumberFormatException e) {
            return targetLife <= 5;
        }
    }

    private static boolean isTutor(String name) {
        String lower = name.toLowerCase();
        for (String kw : TUTOR_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }
}
