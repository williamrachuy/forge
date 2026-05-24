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
        for (final Player player : players) {
            player.updateZoneForView(this);
        }
    }
}
