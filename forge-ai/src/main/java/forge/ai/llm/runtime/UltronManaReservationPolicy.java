package forge.ai.llm.runtime;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Determines what mana Ultron should hold for instant-speed interaction.
 *
 * <p>Initial implementation is approximate:
 * - Inspect hand/candidates for counterspells, instant removal, protection
 * - Estimate available mana from battlefield
 * - Reserve based on intent flags
 */
public final class UltronManaReservationPolicy {

    private UltronManaReservationPolicy() {}

    /** Compute mana reservation given the current decision context. */
    public static UltronManaReservation compute(UltronDecisionContext ctx) {
        UltronTurnIntent intent = ctx.intent;
        Player player = ctx.player;

        boolean hasCounterspell = false;
        boolean hasInstantRemoval = false;
        boolean hasProtection = false;

        // Scan hand for relevant interaction
        for (var c : player.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() == ApiType.Counter) hasCounterspell = true;
                else if (isInstantRemoval(sa)) hasInstantRemoval = true;
                else if (isProtection(sa)) hasProtection = true;
            }
        }

        if (!hasCounterspell && !hasInstantRemoval && !hasProtection) {
            return UltronManaReservation.NONE;
        }

        // Desperate or behind: don't reserve
        if (intent.role == UltronRuntimeRole.DESPERATE
                || intent.role == UltronRuntimeRole.BEHIND) {
            return UltronManaReservation.NONE;
        }

        int blue = 0, black = 0, generic = 0;

        if (hasCounterspell && intent.reserveCounterspellMana) {
            blue    = 1;
            generic = 1; // typical UU or 1U counterspell
        }
        if (hasInstantRemoval && intent.reserveRemovalMana) {
            black   = Math.max(black, 1);
            generic = Math.max(generic, 1);
        }
        if (hasProtection && intent.reserveProtectionMana) {
            generic = Math.max(generic, 1);
        }

        if (blue == 0 && black == 0 && generic == 0) return UltronManaReservation.NONE;

        StringBuilder reason = new StringBuilder("reserve for");
        if (hasCounterspell && intent.reserveCounterspellMana) reason.append(" counterspell");
        if (hasInstantRemoval && intent.reserveRemovalMana) reason.append(" removal");
        if (hasProtection && intent.reserveProtectionMana) reason.append(" protection");

        UltronDecisionLog.log(ctx.player, UltronDecisionLog.MANA, reason.toString());
        return new UltronManaReservation(generic, 0, blue, black, 0, 0, reason.toString());
    }

    private static boolean isInstantRemoval(SpellAbility sa) {
        if (sa.getApi() != ApiType.Destroy && sa.getApi() != ApiType.ChangeZone) return false;
        Card host = sa.getHostCard();
        return host != null && (host.isInstant() || host.hasKeyword(Keyword.FLASH));
    }

    private static boolean isProtection(SpellAbility sa) {
        if (sa.getApi() != ApiType.Pump) return false;
        Card host = sa.getHostCard();
        return host != null && (host.isInstant() || host.hasKeyword(Keyword.FLASH));
    }
}
