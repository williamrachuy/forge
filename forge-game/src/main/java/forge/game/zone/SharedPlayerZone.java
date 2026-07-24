package forge.game.zone;

import forge.game.player.Player;

import java.util.ArrayList;
import java.util.List;

public class SharedPlayerZone extends PlayerZone {
    private final List<Player> players = new ArrayList<>();

    public SharedPlayerZone(final ZoneType zone, final Player hostPlayer) {
        super(zone, hostPlayer);
    }

    public void addPlayer(final Player player) {
        if (!players.contains(player)) {
            players.add(player);
        }
    }

    @Override
    protected void onChanged() {
        if (players.isEmpty()) {
            super.onChanged();
            return;
        }
        if (game.isSimulationCopy()) {
            // See PlayerZone.onChanged(): simulation copies never have a GUI observing their
            // view state, and this fan-out multiplies the cost by every sharing player on every
            // single card add (TICKET-V4-001 / TICKET-V3-207).
            return;
        }
        for (final Player player : players) {
            player.updateZoneForView(this);
        }
    }
}
