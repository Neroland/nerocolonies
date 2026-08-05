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
 * status line is the whole point of this screen existing.
 */
public class OutpostScreen extends NeroColoniesScreen<OutpostMenu> {

    private static final int ACCENT = 0xFF4FB3D9; // colony cyan, dimmer sibling of the beacon
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    public OutpostScreen(OutpostMenu menu, Inventory playerInventory, Component title) {
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

        if (!this.menu.bound()) {
            label(g, Component.translatable("gui.nerocolonies.outpost.unbound"), 8, 43, BAD);
            return;
        }
        if (!this.menu.parentAlive()) {
            label(g, Component.translatable("gui.nerocolonies.outpost.orphaned"), 8, 43, BAD);
            label(g, Component.translatable("gui.nerocolonies.outpost.orphaned_hint"), 8, 54, SUBTLE);
            return;
        }
        label(g, Component.translatable("gui.nerocolonies.outpost.linked"), 8, 43, GOOD);
        label(g, Component.translatable("gui.nerocolonies.stat.claim_radius", this.menu.claimRadius()),
                8, 54, SUBTLE);
        label(g, Component.translatable("gui.nerocolonies.outpost.caps",
                this.menu.colonistCap(), this.menu.jobSlots()), 8, 65, SUBTLE);
        label(g, Component.translatable("gui.nerocolonies.outpost.stations", this.menu.stations()),
                8, 76, SUBTLE);
    }
}
