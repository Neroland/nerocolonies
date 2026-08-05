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
 * That only works if the slots have frames in the first place, which is what the shared base class's
 * wells are for; the lock wash goes on top of them in the foreground pass.
 */
public class ColonyStorageScreen extends NeroColoniesScreen<ColonyStorageMenu> {

    private static final int ACCENT = 0xFFD9A64F; // depot amber
    private static final int WIDTH = 176;
    private static final int HEIGHT = 236;

    private static final int CONTENT_X = 8;
    private static final int CONTENT_WIDTH = WIDTH - 2 * CONTENT_X;

    /** Wash drawn over a slot the colony has not unlocked. */
    private static final int LOCKED = 0xC00E1520;

    public ColonyStorageScreen(ColonyStorageMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = ColonyStorageMenu.INVENTORY_X;
        this.inventoryLabelY = ColonyStorageMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void paintTrays(GuiGraphicsExtractor g) {
        slotTray(g, ColonyStorageMenu.GRID_X, ColonyStorageMenu.GRID_Y,
                ColonyStorageMenu.COLUMNS, ColonyStorageMenu.ROWS);
        playerInventoryTray(g, ColonyStorageMenu.INVENTORY_X, ColonyStorageMenu.INVENTORY_Y,
                ColonyStorageMenu.HOTBAR_Y);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        // One status row, and it says whichever of the two things is true. A depot outside a claim
        // has no unlocked-slot count worth printing, so the warning takes the whole row.
        if (!this.menu.bound()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.depot.unclaimed"),
                    CONTENT_X, ColonyStorageMenu.STATUS_Y, CONTENT_WIDTH, 1, BAD);
        } else {
            clampedLabel(g, Component.translatable("gui.nerocolonies.depot.slots",
                    this.menu.usableSlots(), ColonyStorageMenu.SLOTS),
                    CONTENT_X, ColonyStorageMenu.STATUS_Y, CONTENT_WIDTH, SUBTLE);
        }

        int usable = this.menu.usableSlots();
        for (int slot = usable; slot < ColonyStorageMenu.SLOTS; slot++) {
            int col = slot % ColonyStorageMenu.COLUMNS;
            int row = slot / ColonyStorageMenu.COLUMNS;
            int x = this.leftPos + ColonyStorageMenu.GRID_X + col * 18 - 1;
            int y = this.topPos + ColonyStorageMenu.GRID_Y + row * 18 - 1;
            g.fill(x, y, x + 18, y + 18, LOCKED);
        }
    }
}
