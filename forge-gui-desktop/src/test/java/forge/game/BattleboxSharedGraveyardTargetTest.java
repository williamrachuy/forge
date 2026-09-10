package forge.game;

import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
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
 * Battlebox's graveyard is shared, so "target card in your graveyard" effects must reach everything
 * in it, including cards that died off an opponent's battlefield. Sevinne's Reclamation expresses
 * that the stock way — {@code ValidTgts$ Permanent.cmcLE3+YouCtrl} — because in normal Magic a
 * graveyard card's controller is its owner.
 */
public class BattleboxSharedGraveyardTargetTest {
    private static boolean initialized = false;

    /** Exactly the ValidTgts restriction from sevinnes_reclamation.txt. */
    private static final String SEVINNE_TGTS = "Permanent.cmcLE3+YouCtrl";

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
    public void sevinneCanTargetAPermanentThatDiedUnderAnOpponent() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        final Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);

        // A creature that lived and died on the opponent's battlefield.
        final Card corpse = putOnBattlefield(game, "Grizzly Bears", opponent);
        game.getAction().moveTo(ZoneType.Graveyard, corpse, null, null);
        final Card inYard = game.getCardState(corpse, null);
        Assert.assertTrue(inYard.isInZone(ZoneType.Graveyard), "test setup: the creature should be in the graveyard");
        Assert.assertSame(inYard.getOwner(), opponent, "test setup: the opponent should still own it");
        Assert.assertTrue(caster.isBattleboxSharedGraveyardCard(inYard),
                "test setup: the graveyard should be shared");

        final Card sevinne = putInHand(game, "Sevinne's Reclamation", caster);
        Assert.assertTrue(inYard.isValid(SEVINNE_TGTS, caster, sevinne, null),
                "Sevinne's Reclamation must be able to target a card that died under " + opponent
                        + " — the Battlebox graveyard is shared");
    }

    @Test
    public void sevinneCanStillTargetYourOwnDeadPermanent() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);

        final Card corpse = putOnBattlefield(game, "Grizzly Bears", caster);
        game.getAction().moveTo(ZoneType.Graveyard, corpse, null, null);
        final Card inYard = game.getCardState(corpse, null);

        final Card sevinne = putInHand(game, "Sevinne's Reclamation", caster);
        Assert.assertTrue(inYard.isValid(SEVINNE_TGTS, caster, sevinne, null),
                "Sevinne's Reclamation must still reach your own dead permanent");
    }

    /** A permanent still on an opponent's battlefield is not in the shared graveyard and stays off-limits. */
    @Test
    public void sevinneCannotTargetAPermanentStillOnTheBattlefield() throws Exception {
        final Game game = newBattleboxGame();
        final Player caster = game.getPlayers().get(2);
        final Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, false, 1);

        final Card alive = putOnBattlefield(game, "Grizzly Bears", opponent);
        final Card sevinne = putInHand(game, "Sevinne's Reclamation", caster);

        Assert.assertFalse(alive.isValid(SEVINNE_TGTS, caster, sevinne, null),
                "a creature still on " + opponent + "'s battlefield is not in the shared graveyard");
    }

    // ---------------------------------------------------------------- helpers

    private static Card putInHand(final Game game, final String name, final Player owner) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, owner);
        return game.getAction().moveTo(ZoneType.Hand, c, null, null);
    }

    private static Card putOnBattlefield(final Game game, final String name, final Player controller) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, controller);
        return game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
    }

    private static Game newBattleboxGame() throws Exception {
        final File deckFile = new File(ForgeConstants.DECK_BATTLEBOX_DIR + "BattleBox.dck");
        if (!deckFile.exists()) {
            throw new SkipException("No Battlebox deck at " + deckFile);
        }
        final Deck deck = DeckSerializer.fromFile(deckFile);

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
