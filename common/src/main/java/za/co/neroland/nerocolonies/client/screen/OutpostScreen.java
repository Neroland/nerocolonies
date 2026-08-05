package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.nerocolonies.menu.OutpostMenu;

/**
 * The outpost beacon's screen: claim radius, its two small caps, and — the line that matters —
 * whether the parent colony is still there.
 *
 * <p>An orphaned outpost is indistinguishable from a working one by looking at the block, so the
 * status line is the whole point of this screen existing. It and its follow-up instruction are the
 * two longest strings here and are both drawn wrapped, so neither can run past the hull.
 */
public class OutpostScreen extends NeroColoniesScreen<OutpostMenu> {

    private static final int ACCENT = 0xFF4FB3D9; // colony cyan, dimmer sibling of the beacon
    private static final int WIDTH = 176;
    private static final int HEIGHT = 184;

    private static final int CONTENT_X = 8;
    private static final int CONTENT_WIDTH = WIDTH - 2 * CONTENT_X;
    private static final int GAUGE_WIDTH = 136;

    private static final int STATUS_Y = 44;
    private static final int LINE_1 = 56;
    private static final int LINE_2 = 67;
    private static final int LINE_3 = 78;

    public OutpostScreen(OutpostMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = OutpostMenu.INVENTORY_X;
        this.inventoryLabelY = 92;
    }

    @Override
    protected void paintTrays(GuiGraphicsExtractor g) {
        slotTray(g, OutpostMenu.MODULE_X, OutpostMenu.MODULE_Y, 1, 1);
        playerInventoryTray(g, OutpostMenu.INVENTORY_X, OutpostMenu.INVENTORY_Y, OutpostMenu.HOTBAR_Y);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        labelRight(g, Component.translatable("gui.nerocolonies.slots.modules"), CONTENT_X,
                CONTENT_WIDTH, this.titleLabelY, SUBTLE);

        gaugeRow(g, CONTENT_X, 22, GAUGE_WIDTH, Component.translatable("gui.nerocolonies.stat.power"),
                percent(this.menu.energyPermille(), this.menu.energyScale()),
                frac(this.menu.energyPermille(), this.menu.energyScale()), ACCENT, true);

        if (!this.menu.bound()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.outpost.unbound"),
                    CONTENT_X, STATUS_Y, CONTENT_WIDTH, 2, BAD);
            return;
        }
        if (!this.menu.parentAlive()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.outpost.orphaned"),
                    CONTENT_X, STATUS_Y, CONTENT_WIDTH, 1, BAD);
            wrappedLabel(g, Component.translatable("gui.nerocolonies.outpost.orphaned_hint"),
                    CONTENT_X, LINE_1, CONTENT_WIDTH, 3, SUBTLE);
            return;
        }
        clampedLabel(g, Component.translatable("gui.nerocolonies.outpost.linked"),
                CONTENT_X, STATUS_Y, CONTENT_WIDTH, GOOD);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.claim_radius",
                this.menu.claimRadius()), CONTENT_X, LINE_1, CONTENT_WIDTH, SUBTLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.outpost.caps",
                this.menu.colonistCap(), this.menu.jobSlots()), CONTENT_X, LINE_2, CONTENT_WIDTH, SUBTLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.outpost.stations",
                this.menu.stations()), CONTENT_X, LINE_3, CONTENT_WIDTH, SUBTLE);
    }
}
