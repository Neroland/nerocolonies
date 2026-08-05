package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.nerocolonies.menu.ColonyStorageMenu;

/**
 * The colony depot's screen: the colony's shared stock, with the slots it has not unlocked greyed
 * out.
 *
 * <p>Greying rather than hiding is the point — a locked slot is a visible reason to build a capacity
 * module, and a storage window whose shape changed when a module was swapped would be disorienting.
 */
public class ColonyStorageScreen extends NeroColoniesScreen<ColonyStorageMenu> {

    private static final int ACCENT = 0xFFD9A64F; // depot amber
    private static final int WIDTH = 176;
    private static final int HEIGHT = ColonyStorageMenu.HOTBAR_Y + 24;

    /** Wash drawn over a slot the colony has not unlocked. */
    private static final int LOCKED = 0xA0101820;

    public ColonyStorageScreen(ColonyStorageMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = ColonyStorageMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        if (!this.menu.bound()) {
            label(g, Component.translatable("gui.nerocolonies.depot.unclaimed"), 8, 5 + 10, BAD);
        }
        int usable = this.menu.usableSlots();
        for (int slot = usable; slot < ColonyStorageMenu.SLOTS; slot++) {
            int col = slot % ColonyStorageMenu.COLUMNS;
            int row = slot / ColonyStorageMenu.COLUMNS;
            int x = this.leftPos + ColonyStorageMenu.GRID_X + col * 18 - 1;
            int y = this.topPos + ColonyStorageMenu.GRID_Y + row * 18 - 1;
            g.fill(x, y, x + 18, y + 18, LOCKED);
        }
        label(g, Component.translatable("gui.nerocolonies.depot.slots", usable,
                ColonyStorageMenu.SLOTS), 8, ColonyStorageMenu.INVENTORY_Y - 22, SUBTLE);
    }
}
