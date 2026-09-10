package forge.game;

import forge.GuiDesktop;
import forge.ai.LobbyPlayerAi;
import forge.card.GamePieceType;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The Battlebox playable (Flashback) zone shows the shared land station and nothing else from the
 * command zone: no monarch emblem, no Planar Dice, no commander pool. A station land must leave the
 * zone — model and view — the instant it is played.
 */
public class BattleboxPlayableZoneTest {
    private static boolean initialized = false;

    @BeforeClass
    public static void init() {
        if (!initialized) {
            GuiBase.setInterface(new GuiDesktop());
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            initialized = true;
        }
    }

    @Test
    public void monarchEmblemNeverAppearsInThePlayableZone() throws Exception {
        final Game game = newBattleboxGame();
        final Player monarch = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, monarch, false, 1);

        game.getAction().becomeMonarch(monarch, "CN2");
        Assert.assertSame(game.getMonarch(), monarch, "test setup: player should be the monarch");
        Assert.assertTrue(hasCard(monarch.getPersonalCommandZone().getCards(false), "The Monarch"),
                "test setup: the monarch emblem should be in the personal command zone");

        for (final Player viewer : game.getPlayers()) {
            Assert.assertFalse(hasCard(viewer.getCardsForFlashbackView(), "The Monarch"),
                    "the monarch emblem must not be listed in the playable zone of " + viewer);
            Assert.assertFalse(hasCardView(viewer, "The Monarch"),
                    "the monarch emblem must not reach the playable zone view of " + viewer);
        }

        // It must remain in the Command collection: the desktop UI finds it there to draw the
        // decorative battlefield marker (PlayArea.findMonarchMarker).
        Assert.assertTrue(hasCard(monarch.getCardsIn(ZoneType.Command), "The Monarch"),
                "the monarch emblem must stay in the command zone for the battlefield marker");
    }

    @Test
    public void effectsAndCommandersAreExcludedButTheLandStationIsShown() throws Exception {
        final Game game = newBattleboxGame();
        final Player p0 = game.getPlayers().get(0);

        final List<Card> station = stationLands(p0);
        Assert.assertFalse(station.isEmpty(), "test setup: the land station should not be empty");

        for (final Card land : station) {
            Assert.assertTrue(contains(p0.getCardsForFlashbackView(), land),
                    "station land " + land + " should be playable from the playable zone");
        }

        // Any effect card parked in the shared command zone (Planar Dice, emblems) stays out.
        final Card effect = new Card(90_001, game);
        effect.setName("Planar Dice");
        effect.setOwner(p0);
        effect.setGamePieceType(GamePieceType.EFFECT);
        p0.getZone(ZoneType.Command).add(effect);
        Assert.assertFalse(contains(p0.getCardsForFlashbackView(), effect),
                "command-zone effect cards must not be listed in the playable zone");
    }

    @Test
    public void stationLandLeavesThePlayableZoneForEverySeatWhenPlayed() throws Exception {
        final Game game = newBattleboxGame();
        final Player active = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active, false, 1);

        final Card land = stationLands(active).get(0);
        for (final Player viewer : game.getPlayers()) {
            Assert.assertTrue(contains(viewer.getCardsForFlashbackView(), land),
                    "before the play, " + viewer + " should see " + land + " in the playable zone");
        }

        active.playLand(land, null);
        Assert.assertTrue(land.isInZone(ZoneType.Battlefield), "test setup: the land should be in play");

        for (final Player viewer : game.getPlayers()) {
            Assert.assertFalse(contains(viewer.getCardsForFlashbackView(), land),
                    "after the play, " + viewer + " must not see " + land + " in the playable zone");
            Assert.assertFalse(viewFlashbackIds(viewer).contains(land.getId()),
                    "after the play, the playable zone view of " + viewer + " must have dropped " + land);
        }
    }

    /** A land can reach play without going through {@link Player#playLand}; the playable zone must
     *  drop it on that path too. */
    @Test
    public void stationLandMovedToPlayOutsidePlayLandAlsoLeavesThePlayableZone() throws Exception {
        final Game game = newBattleboxGame();
        final Player active = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active, false, 1);

        final Card land = stationLands(active).get(1);
        Assert.assertTrue(contains(active.getCardsForFlashbackView(), land), "test setup");

        game.getAction().moveToPlay(land, active, null, null);
        Assert.assertTrue(land.isInZone(ZoneType.Battlefield), "test setup: the land should be in play");

        for (final Player viewer : game.getPlayers()) {
            Assert.assertFalse(contains(viewer.getCardsForFlashbackView(), land),
                    "a land moved to play by any means must leave the playable zone of " + viewer);
            Assert.assertFalse(viewFlashbackIds(viewer).contains(land.getId()),
                    "the playable zone view of " + viewer + " must have dropped " + land);
        }
    }

    /**
     * Simulates the stale case behind "sometimes the land isn't removed": the card's own zone
     * already points at the battlefield while the shared station's list still holds it, so the
     * removal inside the next move hits the wrong zone and fires no change event. The model must
     * never list such a card, and the next move must scrub it out of the station.
     */
    @Test
    public void aStaleStationEntryIsNeverListedAndIsPurgedOnTheNextMove() throws Exception {
        final Game game = newBattleboxGame();
        final Player active = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active, false, 1);

        final Card land = stationLands(active).get(2);
        final forge.game.zone.PlayerZone station = active.getZone(ZoneType.Command);

        // Stale state: on the battlefield as far as the card knows, still in the station's list.
        active.getZone(ZoneType.Battlefield).add(land);
        Assert.assertTrue(station.contains(land), "test setup: the station should still list the land");
        Assert.assertTrue(land.isInZone(ZoneType.Battlefield), "test setup: the land should be in play");

        for (final Player viewer : game.getPlayers()) {
            Assert.assertFalse(contains(viewer.getCardsForFlashbackView(), land),
                    "a land already in play must not be listed in the playable zone of " + viewer);
        }

        game.getAction().moveTo(ZoneType.Graveyard, land, null, null);
        Assert.assertFalse(station.contains(land),
                "the next move must scrub the stale entry out of the shared station");
    }

    // ---------------------------------------------------------------- helpers

    private static Game newBattleboxGame() throws Exception {
        final File deckFile = new File(ForgeConstants.DECK_BATTLEBOX_DIR + "BattleBox.dck");
        if (!deckFile.exists()) {
            throw new SkipException("No Battlebox deck at " + deckFile);
        }
        final Deck deck = DeckSerializer.fromFile(deckFile);

        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final BattleboxConfig config = BattleboxConfig.fromDeck(deck);
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setStartingLife(config.getStartingLife());
            rp.setStartingHand(config.getStartingHandSize());
            rp.setMaxHand(config.getMaxHandSize());
            rp.setPlayer(new LobbyPlayerAi("p" + (i + 1), null));
            players.add(rp);
        }

        final GameRules rules = new GameRules(GameType.Battlebox);
        rules.addAppliedVariant(GameType.Battlebox);
        rules.setBattleboxMonarchEnabled(true);
        final Match match = new Match(rules, players, "Battlebox");
        final Game game = match.createGame();
        game.setBattleboxMonarchChoiceMade(true);
        game.setBattleboxMonarchEnabled(true);

        final Method prepareAllZones = Match.class.getDeclaredMethod("prepareAllZones", Game.class);
        prepareAllZones.setAccessible(true);
        prepareAllZones.invoke(match, game);
        return game;
    }

    private static List<Card> stationLands(final Player p) {
        final List<Card> lands = new ArrayList<>();
        for (final Card c : p.getZone(ZoneType.Command).getCards(false)) {
            if (p.isBattleboxSharedLandStationCard(c)) {
                lands.add(c);
            }
        }
        return lands;
    }

    private static boolean contains(final Iterable<Card> cards, final Card wanted) {
        for (final Card c : cards) {
            if (c == wanted) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCard(final Iterable<Card> cards, final String name) {
        for (final Card c : cards) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCardView(final Player p, final String name) {
        for (final CardView cv : p.getView().getFlashback()) {
            if (name.equals(cv.getName())) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> viewFlashbackIds(final Player p) {
        final List<Integer> ids = new ArrayList<>();
        for (final CardView cv : p.getView().getFlashback()) {
            ids.add(cv.getId());
        }
        return ids;
    }
}
