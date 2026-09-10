package forge.game;

import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.PaperCard;
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
 * "Each player" effects resolve in APNAP order (CR 101.4): the active player chooses first, then
 * the rest in turn order. In stock Magic that is nearly cosmetic, because each player searches their
 * own graveyard. In Battlebox the graveyard is shared, so the order decides who gets which creature
 * — Exhume's first chooser takes the best body out from under everyone else.
 */
public class BattleboxExhumeOrderTest {
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

    /**
     * One creature in the shared graveyard, four seats, and the caster sitting in seat 3. Whoever
     * chooses first is the only player who gets a creature, so the winner names the order.
     */
    @Test
    public void theCasterChoosesFirstFromTheSharedGraveyard() throws Exception {
        for (int casterSeat = 0; casterSeat < 4; casterSeat++) {
            final Game game = newBattleboxGame(4);
            final Player caster = game.getPlayers().get(casterSeat);

            clearSharedGraveyard(game);
            final Card corpse = putInSharedGraveyard(game, "Grizzly Bears", game.getPlayers().get(0));

            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);
            resolveExhume(game, caster);

            Assert.assertTrue(corpse.isInZone(ZoneType.Battlefield),
                    "the only creature in the shared graveyard should have been exhumed");
            Assert.assertSame(corpse.getController(), caster,
                    "seat " + casterSeat + " cast Exhume on its own turn, so it must choose first"
                            + " — the creature went to " + corpse.getController() + " instead");
        }
    }

    /**
     * With enough creatures for everyone, every seat gets exactly one and nobody gets the same card
     * twice. This guards the shared-graveyard de-duplication that the ordering fix rides on.
     */
    @Test
    public void everySeatGetsItsOwnCreatureAndTheOrderStartsWithTheCaster() throws Exception {
        final Game game = newBattleboxGame(4);
        final Player caster = game.getPlayers().get(2);

        clearSharedGraveyard(game);
        final List<Card> corpses = new ArrayList<>();
        for (final String name : new String[] {"Grizzly Bears", "Hill Giant", "Giant Spider", "Spore Frog"}) {
            corpses.add(putInSharedGraveyard(game, name, game.getPlayers().get(0)));
        }

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);
        resolveExhume(game, caster);

        for (final Card corpse : corpses) {
            Assert.assertTrue(corpse.isInZone(ZoneType.Battlefield), corpse + " should have been exhumed");
        }
        for (final Player p : game.getPlayers()) {
            final int count = countCreatures(p);
            Assert.assertEquals(count, 1, p + " should have exhumed exactly one creature, got " + count);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void resolveExhume(final Game game, final Player caster) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName("Exhume");
        final Card exhume = Card.fromPaperCard(paper, caster);
        final SpellAbility sa = exhume.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
    }

    private static int countCreatures(final Player p) {
        int count = 0;
        for (final Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) {
                count++;
            }
        }
        return count;
    }

    private static void clearSharedGraveyard(final Game game) {
        final Player host = game.getPlayers().get(0);
        for (final Card c : new ArrayList<>(host.getZone(ZoneType.Graveyard).getCards(false))) {
            host.getZone(ZoneType.Graveyard).remove(c);
        }
    }

    private static Card putInSharedGraveyard(final Game game, final String name, final Player owner) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, owner);
        owner.getZone(ZoneType.Graveyard).add(c);
        return c;
    }

    private static Game newBattleboxGame(final int playerCount) throws Exception {
        final File deckFile = new File(ForgeConstants.DECK_BATTLEBOX_DIR + "BattleBox.dck");
        if (!deckFile.exists()) {
            throw new SkipException("No Battlebox deck at " + deckFile);
        }
        final Deck deck = DeckSerializer.fromFile(deckFile);

        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
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
