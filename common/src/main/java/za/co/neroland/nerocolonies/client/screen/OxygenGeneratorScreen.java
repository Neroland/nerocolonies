package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.nerocolonies.menu.OxygenGeneratorMenu;

/**
 * The oxygen generator's screen: a power gauge, an oxygen gauge and a one-line status.
 *
 * <p>The status line is the part that earns its keep. A generator on a breathable dimension is
 * working perfectly and doing nothing useful, which looks identical to a broken one unless the
 * screen says so — hence the explicit "this dimension has an atmosphere" reading, which is exactly
 * what the Nerospace adapter reports (and what it always reports when Nerospace is absent).
 */
public class OxygenGeneratorScreen extends NeroColoniesScreen<OxygenGeneratorMenu> {

    private static final int ACCENT = 0xFF6FD3E8; // oxygen cyan
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    public OxygenGeneratorScreen(OxygenGeneratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = HEIGHT - 94;
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        label(g, Component.translatable("gui.nerocolonies.stat.power"), 8, 20, SUBTLE);
        segGauge(g, 8, 30, 136, 5, this.menu.energyPermille() / (float) this.menu.energyScale(), ACCENT);

        label(g, Component.translatable("gui.nerocolonies.stat.oxygen"), 8, 41, SUBTLE);
        hGauge(g, 8, 51, 136, 6, this.menu.gasPermille() / (float) this.menu.gasScale(), ACCENT);

        if (!this.menu.lifeSupportNeeded()) {
            label(g, Component.translatable("gui.nerocolonies.oxygen.not_needed"), 8, 64, SUBTLE);
        } else if (this.menu.running()) {
            label(g, Component.translatable("gui.nerocolonies.oxygen.running"), 8, 64, GOOD);
        } else {
            label(g, Component.translatable("gui.nerocolonies.oxygen.stalled"), 8, 64, BAD);
        }
    }
}
