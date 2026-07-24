package forge.screens.match.views;

import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JPanel;
import javax.swing.SwingConstants;

import net.miginfocom.swing.MigLayout;

import forge.game.card.CardView;
import forge.gui.CardPicturePanel;
import forge.gui.UiCommand;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FSkin;
import forge.util.Localizer;

/**
 * Reusable Planechase content: the current plane (rotated upright) plus a "Roll Planar Die" button.
 * Shared by the docked {@link VPlanechase} tab and the floating Planechase window so both stay in
 * sync from a single controller.
 */
public class PlanechasePanel extends JPanel {
    private final CardPicturePanel planePicture = new CardPicturePanel();
    private final FLabel planeName = new FLabel.Builder().fontAlign(SwingConstants.CENTER).build();
    private final FButton rollButton = new FButton(Localizer.getInstance().getMessage("lblRollPlanarDie"));

    /**
     * @param rollAction        invoked when the roll button is pressed
     * @param focusPlaneAction  invoked when the plane image is hovered/clicked (feeds the zoom)
     */
    public PlanechasePanel(final Runnable rollAction, final Runnable focusPlaneAction) {
        // Single column that fills width; the plane cell grows so the name + button sink to the
        // bottom, and the button sits flush against the bottom edge (insets 0).
        super(new MigLayout("insets 0, gap 0, wrap 1", "[grow,fill]", "[grow,fill][]2[]"));
        setOpaque(false);

        planePicture.setOpaque(false);
        // Let the panel (and its window) shrink freely — the image otherwise pins a large minimum.
        planePicture.setMinimumSize(new Dimension(0, 0));
        // Planes are landscape cards stored portrait; rotate 90 clockwise to show them upright.
        planePicture.setRotation(90);
        // Hovering/clicking the plane feeds it to the card detail/zoom so the zoom shortcut enlarges it.
        planePicture.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(final MouseEvent e) {
                focusPlaneAction.run();
            }
            @Override
            public void mousePressed(final MouseEvent e) {
                focusPlaneAction.run();
            }
        });

        planeName.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        rollButton.setCommand((UiCommand) rollAction::run);
        rollButton.setEnabled(false);

        add(planePicture, "grow, push");
        add(planeName, "align center, gaptop 2, gapbottom 2");
        add(rollButton, "growx, h 30px!");
    }

    /** @param plane the active plane's view (or null); @param canRoll whether the roll is available. */
    public void updatePlane(final CardView plane, final boolean canRoll) {
        if (plane != null) {
            planePicture.setCard(plane.getCurrentState(), true);
            planePicture.setVisible(true);
            planeName.setText(plane.getCurrentState().getName());
        } else {
            planePicture.setVisible(false);
            planeName.setText(Localizer.getInstance().getMessage("lblNoActivePlane"));
        }
        rollButton.setEnabled(canRoll);
    }
}
