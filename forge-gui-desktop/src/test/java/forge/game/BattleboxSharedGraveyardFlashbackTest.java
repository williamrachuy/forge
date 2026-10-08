package forge.game;

import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.card.Card;
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
 * Battlebox's graveyard is shared, so any player may flash back a card in it, including one an
 * opponent cast. The activator is not the card's controller there, so {@code Spell.canPlayFromHost}
 * checks restrictions against an LKI copy, which has no current zone — the shared-graveyard
 * exemption to the owner-only zone rule has to recognise it by its last known zone.
 */
public class BattleboxSharedGraveyardFlashbackTest {
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
    public void anyPlayerCanFlashBackAnOpponentsSpell() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        final Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);

        final Card sevinne = putInGraveyard(game, "Sevinne's Reclamation", opponent);
        Assert.assertSame(sevinne.getOwner(), opponent, "test setup: the opponent should still own it");
        Assert.assertTrue(caster.isBattleboxSharedGraveyardCard(sevinne), "test setup: the graveyard should be shared");

        Assert.assertTrue(hasPlayableFlashback(sevinne, caster),
                caster + " must be able to flash back Sevinne's Reclamation from the shared graveyard");
    }

    /** The cast itself goes through: the caster claims the card, it resolves, and flashback exiles it. */
    @Test
    public void flashingBackAnOpponentsSpellResolvesAndExilesIt() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        final Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);
        for (int i = 0; i < 5; i++) {
            putOnBattlefield(game, "Plains", caster);
        }

        final Card bears = putInGraveyard(game, "Grizzly Bears", opponent);
        final Card sevinne = putInGraveyard(game, "Sevinne's Reclamation", opponent);
        SpellAbility flashback = null;
        for (final SpellAbility sa : sevinne.getAllPossibleAbilities(caster, true)) {
            if (sa.isFlashback()) {
                flashback = sa;
            }
        }
        Assert.assertNotNull(flashback, "test setup: flashback should be offered");

        Assert.assertTrue(ComputerUtil.handlePlayingSpellAbility(caster, flashback, sa -> sa.getTargets().add(bears)),
                caster + " should be able to cast and pay for the flashback");
        final Card onStack = game.getCardState(sevinne);
        Assert.assertTrue(onStack.isInZone(ZoneType.Stack), "Sevinne's Reclamation should be on the stack");
        Assert.assertSame(onStack.getController(), caster, "the caster should control the spell");

        game.getStack().resolveStack();

        Assert.assertTrue(game.getCardState(sevinne).isInZone(ZoneType.Exile), "flashback should exile the spell");
        final Card returned = game.getCardState(bears);
        Assert.assertTrue(returned.isInZone(ZoneType.Battlefield), "the target should be returned to the battlefield");
        Assert.assertSame(returned.getController(), caster, "the caster should get the returned permanent");
    }

    @Test
    public void ownerCanStillFlashBack() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);

        final Card sevinne = putInGraveyard(game, "Sevinne's Reclamation", caster);
        Assert.assertTrue(hasPlayableFlashback(sevinne, caster),
                "the owner must still be able to flash back their own spell");
    }

    /** Sorcery timing still applies: not your main phase, no flashback. */
    @Test
    public void flashbackStillRespectsSorceryTiming() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        final Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent, false, 1);

        final Card sevinne = putInGraveyard(game, "Sevinne's Reclamation", opponent);
        Assert.assertFalse(hasPlayableFlashback(sevinne, caster),
                "a sorcery cannot be flashed back during another player's turn");
    }

    // ---------------------------------------------------------------- helpers

    private static boolean hasPlayableFlashback(final Card card, final Player player) {
        for (final SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
            if (sa.isFlashback()) {
                return true;
            }
        }
        return false;
    }

    private static Card putInGraveyard(final Game game, final String name, final Player owner) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, owner);
        return game.getAction().moveTo(ZoneType.Graveyard, c, null, null);
    }

    private static Card putOnBattlefield(final Game game, final String name, final Player controller) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, controller);
        return game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
    }

    private static Game newBattleboxGame() throws Exception {
        final Deck deck = new Deck("Test Battlebox Flashback Deck");
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
