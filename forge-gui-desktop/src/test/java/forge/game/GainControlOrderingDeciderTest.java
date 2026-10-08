package forge.game;

import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterKeywordType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Cards entering the battlefield under {@code GainControl$ True} are ordered by the player gaining
 * control: the activator. {@code GameActionUtil.orderCardsByTheirOwners} used to resolve "True"
 * through {@code getDefinedPlayers}, which does not know it and falls through to every player, so
 * seat 0 was asked to order them whoever activated the ability.
 */
public class GainControlOrderingDeciderTest {
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

    /** Records which player is asked to order cards entering a zone. */
    private static final class RecordingController extends PlayerControllerAi {
        private final List<Player> askedToOrder;

        RecordingController(final Game game, final Player p, final List<Player> askedToOrder) {
            super(game, p, p.getController().getLobbyPlayer());
            this.askedToOrder = askedToOrder;
        }

        @Override
        public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone, final SpellAbility source) {
            askedToOrder.add(player);
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
    }

    @Test
    public void ghostVacuumIsOrderedByItsActivator() throws Exception {
        final Game game = newGame();
        final Player seat0 = game.getPlayers().get(0);
        final Player seat1 = game.getPlayers().get(1);
        final Player activator = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, activator, false, 1);

        final List<Player> askedToOrder = new ArrayList<>();
        for (final Player p : game.getPlayers()) {
            p.dangerouslySetController(new RecordingController(game, p, askedToOrder));
        }

        final Card vacuum = create(game, "Ghost Vacuum", activator, ZoneType.Battlefield);
        // Creatures owned by other seats, including seat 0, so the old fall-through is visible.
        final Card bears = exileWith(game, "Grizzly Bears", seat0, vacuum, activator);
        final Card elves = exileWith(game, "Llanowar Elves", seat1, vacuum, activator);
        final SpellAbility reanimate = vacuum.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.ChangeZoneAll).findFirst().orElseThrow();
        reanimate.setActivatingPlayer(activator);
        AbilityUtils.resolve(reanimate);

        Assert.assertFalse(askedToOrder.isEmpty(), "test setup: someone should have ordered the cards");
        for (final Player asked : askedToOrder) {
            Assert.assertSame(asked, activator, "only the activator should order the cards it gains control of");
        }
        for (final Card c : new Card[] { bears, elves }) {
            final Card onField = game.getCardState(c);
            Assert.assertTrue(onField.isInZone(ZoneType.Battlefield), c + " should be on the battlefield");
            Assert.assertSame(onField.getController(), activator, c + " should be under the activator's control");
            Assert.assertEquals(onField.getCounters(CounterKeywordType.get("Flying")), 1, c + " should have a flying counter");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** What Ghost Vacuum's first ability leaves behind: the card in exile, remembered as exiled with it. */
    private static Card exileWith(final Game game, final String name, final Player owner, final Card source, final Player by) {
        final Card c = create(game, name, owner, ZoneType.Exile);
        c.setExiledWith(source);
        c.setExiledBy(by);
        source.addExiledCard(c);
        return c;
    }

    private static Card create(final Game game, final String name, final Player owner, final ZoneType zone) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, owner);
        return game.getAction().moveTo(zone, c, null, null);
    }

    private static Game newGame() throws Exception {
        final Deck deck = new Deck("Test Battlebox Deck");
        deck.getOrCreate(DeckSection.Main).add("Grizzly Bears", 200);
        deck.getOrCreate(DeckSection.LandStation).add("Plains", 10);

        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
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
        final Match match = new Match(rules, players, "Battlebox");
        final Game game = match.createGame();
        game.setBattleboxMonarchChoiceMade(true);

        final Method prepareAllZones = Match.class.getDeclaredMethod("prepareAllZones", Game.class);
        prepareAllZones.setAccessible(true);
        prepareAllZones.invoke(match, game);
        return game;
    }
}
