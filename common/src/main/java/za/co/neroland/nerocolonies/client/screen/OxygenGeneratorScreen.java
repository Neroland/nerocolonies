package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.nerocolonies.menu.OxygenGeneratorMenu;

/**
 * The oxygen generator's screen: a power gauge, an oxygen gauge, a module column and a wrapped
 * status line.
 *
 * <p>The status line is the part that earns its keep. A generator on a breathable dimension is
 * working perfectly and doing nothing useful, which looks identical to a broken one unless the
 * screen says so — hence the explicit "this dimension has an atmosphere" reading, which is exactly
 * what the Nerospace adapter reports (and what it always reports when Nerospace is absent). It is
 * also the longest string on the screen, so it is drawn wrapped rather than as one line that would
 * run off the hull.
 */
public class OxygenGeneratorScreen extends NeroColoniesScreen<OxygenGeneratorMenu> {

    private static final int ACCENT = 0xFF6FD3E8; // oxygen cyan
    private static final int WIDTH = 176;
    private static final int HEIGHT = 178;

    /** The gauges stop short of the module column; the status line runs the full width below it. */
    private static final int CONTENT_X = 8;
    private static final int GAUGE_WIDTH = 136;
    private static final int CONTENT_WIDTH = WIDTH - 2 * CONTENT_X;
    private static final int STATUS_Y = 62;

    public OxygenGeneratorScreen(OxygenGeneratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = OxygenGeneratorMenu.INVENTORY_X;
        this.inventoryLabelY = 86;
    }

    @Override
    protected void paintTrays(GuiGraphicsExtractor g) {
        slotTray(g, OxygenGeneratorMenu.MODULE_X, OxygenGeneratorMenu.MODULE_Y, 1, 2);
        playerInventoryTray(g, OxygenGeneratorMenu.INVENTORY_X, OxygenGeneratorMenu.INVENTORY_Y,
                OxygenGeneratorMenu.HOTBAR_Y);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        // The module column's caption lives in the title band, directly above the column it names —
        // the working area is too tight to spend a whole line on one word.
        labelRight(g, Component.translatable("gui.nerocolonies.slots.modules"), CONTENT_X,
                CONTENT_WIDTH, this.titleLabelY, SUBTLE);

        gaugeRow(g, CONTENT_X, 22, GAUGE_WIDTH, Component.translatable("gui.nerocolonies.stat.power"),
                percent(this.menu.energyPermille(), this.menu.energyScale()),
                frac(this.menu.energyPermille(), this.menu.energyScale()), ACCENT, true);

        gaugeRow(g, CONTENT_X, 42, GAUGE_WIDTH, Component.translatable("gui.nerocolonies.stat.oxygen"),
                percent(this.menu.gasPermille(), this.menu.gasScale()),
                frac(this.menu.gasPermille(), this.menu.gasScale()), ACCENT, false);

        Component status;
        int colour;
        if (!this.menu.lifeSupportNeeded()) {
            status = Component.translatable("gui.nerocolonies.oxygen.not_needed");
            colour = SUBTLE;
        } else if (this.menu.running()) {
            status = Component.translatable("gui.nerocolonies.oxygen.running");
            colour = GOOD;
        } else {
            status = Component.translatable("gui.nerocolonies.oxygen.stalled");
            colour = BAD;
        }
        wrappedLabel(g, status, CONTENT_X, STATUS_Y, CONTENT_WIDTH, 2, colour);
    }
}
