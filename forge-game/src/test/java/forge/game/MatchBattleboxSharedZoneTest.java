package forge.game;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardDamageMap;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.collect.FCollectionView;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public class MatchBattleboxSharedZoneTest {
    @Test
    public void sharedGraveyardIsFreshForEachBattleboxGame() throws Exception {
        final Match match = createMatch(2, true);

        final Game firstGame = match.createGame();
        prepareBattleboxSharedGraveyard(firstGame.getPlayers());
        final Player firstPlayer = firstGame.getPlayers().get(0);
        final Player secondPlayer = firstGame.getPlayers().get(1);
        Assert.assertSame(firstPlayer.getZone(ZoneType.Graveyard), secondPlayer.getZone(ZoneType.Graveyard));

        final Card oldCard = new Card(10_000, firstGame);
        oldCard.setName("Old Graveyard Card");
        oldCard.setOwner(firstPlayer);
        oldCard.setController(firstPlayer, firstGame.getNextTimestamp());
        firstPlayer.getZone(ZoneType.Graveyard).add(oldCard);
        Assert.assertEquals(firstGame.getCardsIn(ZoneType.Graveyard).size(), 1);

        final Game secondGame = match.createGame();
        prepareBattleboxSharedGraveyard(secondGame.getPlayers());
        final Player newFirstPlayer = secondGame.getPlayers().get(0);
        final Player newSecondPlayer = secondGame.getPlayers().get(1);
        Assert.assertSame(newFirstPlayer.getZone(ZoneType.Graveyard), newSecondPlayer.getZone(ZoneType.Graveyard));
        Assert.assertNotSame(firstPlayer.getZone(ZoneType.Graveyard), newFirstPlayer.getZone(ZoneType.Graveyard));
        Assert.assertTrue(secondGame.getCardsIn(ZoneType.Graveyard).isEmpty());
        Assert.assertTrue(newFirstPlayer.getView().getGraveyard().isEmpty());
        Assert.assertTrue(newFirstPlayer.getView().getFlashback().isEmpty());
    }

    @Test
    public void battleboxSkipsStartingPlayerFirstDrawInMultiplayer() throws Exception {
        final Game game = createMatch(3, true).createGame();
        game.getPhaseHandler().devModeSet(PhaseType.DRAW, game.getPlayers().get(0), false, 1);

        Assert.assertTrue(isSkippingPhase(game, PhaseType.DRAW));
    }

    @Test
    public void battleboxBaseGameTypeSkipsStartingPlayerFirstDrawInMultiplayer() throws Exception {
        final Game game = createMatch(4, false, true).createGame();
        game.getPhaseHandler().devModeSet(PhaseType.DRAW, game.getPlayers().get(0), false, 1);

        Assert.assertTrue(isSkippingPhase(game, PhaseType.DRAW));
    }

    @Test
    public void multiplayerStartingPlayerDoesNotDrawDuringActualFirstDrawStep() throws Exception {
        assertStartingPlayerDoesNotDrawDuringActualFirstDrawStep(createMatch(4, false).createGame());
    }

    @Test
    public void battleboxStartingPlayerDoesNotDrawDuringActualFirstDrawStep() throws Exception {
        assertStartingPlayerDoesNotDrawDuringActualFirstDrawStep(createMatch(4, true).createGame());
    }

    @Test
    public void battleboxSharedLandStationLandCanBePlayedWithoutMayPlayCache() throws Exception {
        final Game game = createMatch(4, true).createGame();
        final Player activePlayer = game.getPlayers().get(0);
        final Card stationLand = addSharedStationLand(game, game.getPlayers().get(1));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, activePlayer, false, 1);

        Assert.assertTrue(activePlayer.isBattleboxSharedLandStationCard(stationLand));
        Assert.assertTrue(stationLand.mayPlay(activePlayer).isEmpty());
        Assert.assertTrue(activePlayer.canPlayLand(stationLand, false, null));
    }

    @Test
    public void battleboxSharedGraveyardAbilityCanBeActivatedByNonController() throws Exception {
        final Game game = createMatch(2, true).createGame();
        prepareBattleboxSharedGraveyard(game.getPlayers());
        final Player activePlayer = game.getPlayers().get(0);
        final Player staleController = game.getPlayers().get(1);
        final Card sharedGraveyardCard = addSharedGraveyardCard(game, staleController);
        final SpellAbility ability = AbilityFactory.getAbility(
                "AB$ Token | Cost$ ExileFromGrave<1/CARDNAME> | ActivationZone$ Graveyard"
                        + " | TokenAmount$ 1 | TokenScript$ r_1_1_elemental | TokenOwner$ You"
                        + " | SpellDescription$ Create a 1/1 red Elemental creature token.",
                sharedGraveyardCard);
        sharedGraveyardCard.addSpellAbility(ability, false);

        Assert.assertSame(sharedGraveyardCard.getController(), staleController);
        Assert.assertTrue(activePlayer.isBattleboxSharedGraveyardCard(sharedGraveyardCard));
        Assert.assertTrue(sharedGraveyardCard.getSpellAbilities().contains(ability));
        Assert.assertSame(ability.getRestrictions().getZone(), ZoneType.Graveyard);
    }

    @Test
    public void battleboxFirstCombatDamageToOpponentMakesDamagingPlayerMonarch() throws Exception {
        final Game game = createMatch(2, true).createGame();
        game.setBattleboxMonarchChoice(true);
        final Player damagingPlayer = game.getPlayers().get(0);
        final Player damagedPlayer = game.getPlayers().get(1);
        final Card attacker = addBattlefieldCreature(game, damagingPlayer);
        final CardDamageMap damageMap = new CardDamageMap();
        damageMap.put(attacker, damagedPlayer, 1);

        assignBattleboxMonarchOnFirstCombatDamage(game, damageMap);

        Assert.assertSame(game.getMonarch(), damagingPlayer);
    }

    @Test
    public void battleboxPlayerLossDoesNotRemoveSharedZoneCardsOwnedByLoser() {
        final Game game = createMatch(4, true).createGame();
        installSharedZone(game, ZoneType.Library);
        installSharedZone(game, ZoneType.Command);
        installSharedZone(game, ZoneType.Graveyard);
        final Player losingPlayer = game.getPlayers().get(0);
        final Player nextPlayer = game.getPlayers().get(1);
        final Card libraryCard = addSharedZoneCard(game, losingPlayer, ZoneType.Library, 60_000, "Shared Library Card");
        final Card commandCard = addSharedZoneCard(game, losingPlayer, ZoneType.Command, 60_001, "Shared Command Card");
        final Card graveyardCard = addSharedZoneCard(game, losingPlayer, ZoneType.Graveyard, 60_002, "Shared Graveyard Card");

        game.onPlayerLost(losingPlayer);

        Assert.assertTrue(nextPlayer.getZone(ZoneType.Library).contains(libraryCard));
        Assert.assertTrue(nextPlayer.getZone(ZoneType.Command).contains(commandCard));
        Assert.assertTrue(nextPlayer.getZone(ZoneType.Graveyard).contains(graveyardCard));
        Assert.assertSame(libraryCard.getOwner(), nextPlayer);
        Assert.assertSame(commandCard.getOwner(), nextPlayer);
        Assert.assertSame(graveyardCard.getOwner(), nextPlayer);
        Assert.assertEquals(game.getCardsIn(ZoneType.Library).size(), 1);
        Assert.assertEquals(game.getCardsIn(ZoneType.Command).size(), 1);
        Assert.assertEquals(game.getCardsIn(ZoneType.Graveyard).size(), 1);
    }

    private static void assertStartingPlayerDoesNotDrawDuringActualFirstDrawStep(final Game game) {
        final Player startingPlayer = game.getPlayers().get(0);
        game.setStartingPlayer(startingPlayer);
        final SharedPlayerZone sharedLibrary = new SharedPlayerZone(ZoneType.Library, startingPlayer);
        for (final Player player : game.getPlayers()) {
            sharedLibrary.addPlayer(player);
            player.setSharedLibraryZone(sharedLibrary);
        }

        final Card libraryCard = new Card(20_000, game);
        libraryCard.setName("Shared Library Card");
        libraryCard.setOwner(startingPlayer);
        libraryCard.setController(startingPlayer, game.getNextTimestamp());
        startingPlayer.getZone(ZoneType.Library).add(libraryCard);

        game.getPhaseHandler().setupFirstTurn(startingPlayer, null);
        game.getPhaseHandler().devAdvanceToPhase(PhaseType.DRAW);

        Assert.assertEquals(startingPlayer.getZone(ZoneType.Hand).size(), 0);
        Assert.assertEquals(startingPlayer.getZone(ZoneType.Library).size(), 1);
    }

    private static Card addSharedStationLand(final Game game, final Player owner) throws Exception {
        final SharedPlayerZone sharedCommand = new SharedPlayerZone(ZoneType.Command, game.getPlayers().get(0));
        for (final Player player : game.getPlayers()) {
            sharedCommand.addPlayer(player);
            setSharedCommandZone(player, sharedCommand);
        }

        final Card stationLand = new Card(30_000, game);
        stationLand.setName("Shared Station Land");
        stationLand.addType("Land");
        stationLand.setOwner(owner);
        stationLand.setController(owner, game.getNextTimestamp());
        stationLand.setZone(sharedCommand);
        return stationLand;
    }

    private static Card addSharedGraveyardCard(final Game game, final Player owner) {
        final Card card = new Card(40_000, game);
        card.setName("Shared Graveyard Ability Card");
        card.setOwner(owner);
        card.setController(owner, game.getNextTimestamp());
        owner.getZone(ZoneType.Graveyard).add(card);
        return card;
    }

    private static Card addBattlefieldCreature(final Game game, final Player owner) {
        final Card card = new Card(50_000, game);
        card.setName("Battlebox Attacker");
        card.addType("Creature");
        card.setOwner(owner);
        card.setController(owner, game.getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static Card addSharedZoneCard(final Game game, final Player owner, final ZoneType zoneType,
            final int id, final String name) {
        final Card card = new Card(id, game);
        card.setName(name);
        card.setOwner(owner);
        card.setController(owner, game.getNextTimestamp());
        owner.getZone(zoneType).add(card);
        return card;
    }

    private static void installSharedZone(final Game game, final ZoneType zoneType) {
        final SharedPlayerZone sharedZone = new SharedPlayerZone(zoneType, game.getPlayers().get(0));
        for (final Player player : game.getPlayers()) {
            sharedZone.addPlayer(player);
            if (zoneType == ZoneType.Library) {
                player.setSharedLibraryZone(sharedZone);
            } else if (zoneType == ZoneType.Command) {
                player.setSharedCommandZone(sharedZone);
            } else if (zoneType == ZoneType.Graveyard) {
                player.setSharedGraveyardZone(sharedZone);
            }
        }
    }

    private static void setSharedCommandZone(final Player player, final SharedPlayerZone sharedCommand) throws Exception {
        final Field sharedCommandZone = Player.class.getDeclaredField("sharedCommandZone");
        sharedCommandZone.setAccessible(true);
        sharedCommandZone.set(player, sharedCommand);
    }

    @Test
    public void multiplayerSkipsStartingPlayerFirstDraw() throws Exception {
        final Game game = createMatch(3, false).createGame();
        game.getPhaseHandler().devModeSet(PhaseType.DRAW, game.getPlayers().get(0), false, 1);

        Assert.assertTrue(isSkippingPhase(game, PhaseType.DRAW));
    }

    private static Match createMatch(final int players, final boolean battlebox) {
        return createMatch(players, battlebox, false);
    }

    private static Match createMatch(final int players, final boolean battlebox, final boolean battleboxBaseGameType) {
        Localizer.getInstance().initialize("en-US", "forge-gui/res/languages");
        Lang.createInstance("en-US");

        final RegisteredPlayer[] registeredPlayers = new RegisteredPlayer[players];
        for (int i = 0; i < players; i++) {
            registeredPlayers[i] = new RegisteredPlayer(new Deck()).setPlayer(new TestLobbyPlayer("p" + (i + 1)));
        }
        final GameRules rules = new GameRules(battleboxBaseGameType ? GameType.Battlebox : GameType.Constructed);
        if (battlebox) {
            rules.addAppliedVariant(GameType.Battlebox);
        }
        return new Match(rules, Arrays.asList(registeredPlayers), battlebox ? "Battlebox" : "Constructed");
    }

    private static void prepareBattleboxSharedGraveyard(final FCollectionView<Player> players) throws Exception {
        final Method method = Match.class.getDeclaredMethod("prepareBattleboxSharedGraveyard", FCollectionView.class);
        method.setAccessible(true);
        method.invoke(null, players);
    }

    private static boolean isSkippingPhase(final Game game, final PhaseType phase) throws Exception {
        final Method method = game.getPhaseHandler().getClass().getDeclaredMethod("isSkippingPhase", PhaseType.class);
        method.setAccessible(true);
        return (boolean) method.invoke(game.getPhaseHandler(), phase);
    }

    private static void assignBattleboxMonarchOnFirstCombatDamage(final Game game, final CardDamageMap damageMap) throws Exception {
        final Method method = GameAction.class.getDeclaredMethod("assignBattleboxMonarchOnFirstCombatDamage", CardDamageMap.class);
        method.setAccessible(true);
        method.invoke(game.getAction(), damageMap);
    }

    private static final class TestLobbyPlayer extends LobbyPlayer implements IGameEntitiesFactory {
        private TestLobbyPlayer(final String name) {
            super(name);
        }

        @Override
        public PlayerController createMindSlaveController(final Player master, final Player slave) {
            return null;
        }

        @Override
        public Player createIngamePlayer(final Game game, final int id) {
            return new Player(getName(), game, id);
        }

        @Override
        public void hear(final LobbyPlayer player, final String message) {
        }
    }
}
