package forge.screens.match.controllers;

import java.util.ArrayList;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gui.framework.ICDoc;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.VPlanechase;
import forge.util.collect.FCollectionView;

/**
 * Controls the Planechase panel in the match UI: shows the current plane and lets the local player
 * roll the planar die. The panel is a normal dockable tab ({@link VPlanechase}) that can be dragged
 * between cells like the card-detail view; {@code CMatchUI.showPlanechasePanel} surfaces it on demand.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 */
public class CPlanechase implements ICDoc {

    private final CMatchUI matchUI;
    private final VPlanechase view;
    private CardView currentPlane;

    public CPlanechase(final CMatchUI matchUI) {
        this.matchUI = matchUI;
        this.view = new VPlanechase(this);
    }

    public VPlanechase getView() {
        return view;
    }

    @Override
    public void register() {
    }

    @Override
    public void initialize() {
    }

    @Override
    public void update() {
        final GameView game = matchUI.getGameView();
        CardView plane = null;
        if (game != null) {
            final FCollectionView<CardView> planes = game.getActivePlanes();
            if (planes != null && !planes.isEmpty()) {
                plane = planes.iterator().next();
            }
        }
        currentPlane = plane;
        view.updatePlane(plane, findRollEffect() != null);
    }

    /** Sends the current plane to the card detail/zoom area, so hovering it and pressing the zoom
     *  shortcut shows it enlarged and centered (rotated by the zoomer's plane handling). */
    public void focusPlane() {
        if (currentPlane != null) {
            matchUI.setCard(currentPlane);
        }
    }

    /** Invoked by the "Roll Planar Die" button. Activates the local player's Planar Dice effect,
     *  exactly as clicking it in the playable zone would. */
    public void rollPlanarDie() {
        final CardView rollEffect = findRollEffect();
        if (rollEffect != null) {
            matchUI.getGameController().selectCard(rollEffect, new ArrayList<>(), null);
        }
    }

    /** @return the local player's Planar Dice effect if it is currently activatable, else null.
     *  Exposed directly on the view so the die no longer appears as a card in the playable zone. */
    private CardView findRollEffect() {
        final PlayerView local = matchUI.getCurrentPlayer();
        return local == null ? null : local.getPlanarRollCard();
    }
}
