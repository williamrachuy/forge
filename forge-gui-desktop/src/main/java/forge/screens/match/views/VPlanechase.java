package forge.screens.match.views;

import net.miginfocom.swing.MigLayout;

import forge.game.card.CardView;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.screens.match.controllers.CPlanechase;
import forge.util.Localizer;

/**
 * Docked Planechase tab: shows the current plane and a button to roll the planar die.
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 */
public class VPlanechase implements IVDoc<CPlanechase> {

    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblPlanechaseTab"));

    private final CPlanechase controller;
    private final PlanechasePanel panel;

    public VPlanechase(final CPlanechase controller) {
        this.controller = controller;
        this.panel = new PlanechasePanel(controller::rollPlanarDie, controller::focusPlane);
    }

    //========== Overridden methods

    @Override
    public void populate() {
        parentCell.getBody().removeAll();
        parentCell.getBody().setLayout(new MigLayout("insets 0, gap 0, wrap, fill"));
        parentCell.getBody().add(panel, "w 100%!, h 100%!");
    }

    @Override
    public void setParentCell(final DragCell cell0) {
        this.parentCell = cell0;
    }

    @Override
    public DragCell getParentCell() {
        return this.parentCell;
    }

    @Override
    public EDocID getDocumentID() {
        return EDocID.REPORT_PLANECHASE;
    }

    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    @Override
    public CPlanechase getLayoutControl() {
        return controller;
    }

    //========= Observer update methods

    public void updatePlane(final CardView plane, final boolean canRoll) {
        panel.updatePlane(plane, canRoll);
    }
}
