package za.co.neroland.nerocolonies.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.block.entity.ColonyDepotBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The menu of a Colony Depot or Storage Crate: one page of six rows of nine onto the colony's shared
 * storage, plus the player inventory.
 *
 * <h2>Pages</h2>
 *
 * <p>Colony storage is larger than one screen (it grows with every depot, crate and capacity
 * module), so the grid shows one page of {@value #PAGE_SLOTS} at a time. The page is a data slot the
 * server owns and changes only through {@link #clickMenuButton}; the client's grid is a plain
 * {@value #PAGE_SLOTS}-slot stand-in the server's page is synced into.
 *
 * <h2>Locked slots</h2>
 *
 * <p>Only the first {@code usableSlots} of the colony's storage are unlocked. Locked slots refuse
 * insertion through {@link Slot#mayPlace} and the screen greys them out — but anything already
 * sitting in one is still takeable, so removing a capacity module strands nothing.
 */
public class ColonyStorageMenu extends AbstractContainerMenu {

    /** Backing slot count — the colony store's full size. */
    public static final int SLOTS = ColonyDepotBlockEntity.SLOTS;

    public static final int COLUMNS = 9;
    public static final int ROWS = 6;
    public static final int PAGE_SLOTS = COLUMNS * ROWS;
    public static final int PAGES = (SLOTS + PAGE_SLOTS - 1) / PAGE_SLOTS;

    /** {@link #clickMenuButton} ids. */
    public static final int BUTTON_PREVIOUS = 0;
    public static final int BUTTON_NEXT = 1;

    /**
     * Layout constants shared with the screen so the two cannot drift. The grid starts below a status
     * row rather than immediately under the title: the unlocked-slot count, the page buttons and the
     * "not in a claim" warning need somewhere to live that is not on top of the first row of slots.
     */
    public static final int GRID_X = 8;
    public static final int GRID_Y = 32;
    public static final int STATUS_Y = 20;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 156;
    public static final int HOTBAR_Y = 212;

    private final ContainerData data;
    private final Container stillValidHost;
    private final DataSlot page = DataSlot.standalone();

    /** Client-side factory: an empty one-page stand-in the server's page is synced into. */
    public ColonyStorageMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(PAGE_SLOTS),
                new SimpleContainerData(ColonyDepotBlockEntity.DATA_SIZE), false);
    }

    public ColonyStorageMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        this(id, playerInventory, container, data, true);
    }

    private ColonyStorageMenu(int id, Inventory playerInventory, Container container, ContainerData data,
            boolean paged) {
        super(NeroColoniesMenus.COLONY_STORAGE.get(), id);
        this.data = data;
        this.stillValidHost = container;
        Container grid = paged ? new PageView(container) : container;

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                this.addSlot(new Slot(grid, row * COLUMNS + col,
                        GRID_X + col * 18, GRID_Y + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        // The unlocked count and the page are data slots, so this answers the same
                        // on both sides. Asking the container alone would not: the client's stand-in
                        // accepts everything.
                        return absolute(this.getContainerSlot()) < usableSlots()
                                && grid.canPlaceItem(this.getContainerSlot(), stack);
                    }
                });
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
        this.addDataSlots(data);
        this.addDataSlot(this.page);
    }

    /** The colony-storage index a grid slot shows on the current page. */
    public int absolute(int gridSlot) {
        return page() * PAGE_SLOTS + gridSlot;
    }

    /** The page shown, 0-based. */
    public int page() {
        return Math.clamp(this.page.get(), 0, PAGES - 1);
    }

    /** How many of the storage slots this colony has unlocked. */
    public int usableSlots() {
        return Math.clamp(this.data.get(ColonyDepotBlockEntity.DATA_USABLE_SLOTS), 0, SLOTS);
    }

    /** Pages worth showing: every page that holds an unlocked slot, at least one. */
    public int pageCount() {
        return Math.clamp((usableSlots() + PAGE_SLOTS - 1) / PAGE_SLOTS, 1, PAGES);
    }

    /** Whether the box stands inside a colony (or outpost) claim at all. */
    public boolean bound() {
        return this.data.get(ColonyDepotBlockEntity.DATA_BOUND) != 0;
    }

    // --- vanilla plumbing ---------------------------------------------------

    @Override
    public boolean clickMenuButton(Player player, int id) {
        int current = page();
        int last = Math.max(pageCount(), current + 1) - 1;
        int next = switch (id) {
            case BUTTON_PREVIOUS -> Math.max(0, current - 1);
            case BUTTON_NEXT -> Math.min(last, current + 1);
            default -> current;
        };
        if (next == current) {
            return false;
        }
        this.page.set(next);
        this.broadcastChanges();
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.stillValidHost.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            int invStart = PAGE_SLOTS;
            int invEnd = invStart + 36;
            if (index < invStart) {
                if (!this.moveItemStackTo(stack, invStart, invEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                int open = Math.clamp(usableSlots() - page() * PAGE_SLOTS, 0, PAGE_SLOTS);
                if (!this.moveItemStackTo(stack, 0, open, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    /** One page of the colony-storage window, offset by the menu's current page. Server side only. */
    private final class PageView implements Container {

        private final Container backing;

        PageView(Container backing) {
            this.backing = backing;
        }

        private int map(int slot) {
            return absolute(slot);
        }

        private boolean valid(int slot) {
            return slot >= 0 && slot < PAGE_SLOTS && map(slot) < this.backing.getContainerSize();
        }

        @Override
        public int getContainerSize() {
            return PAGE_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < PAGE_SLOTS; slot++) {
                if (!getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return valid(slot) ? this.backing.getItem(map(slot)) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return valid(slot) ? this.backing.removeItem(map(slot), amount) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return valid(slot) ? this.backing.removeItemNoUpdate(map(slot)) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (valid(slot)) {
                this.backing.setItem(map(slot), stack);
            }
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return valid(slot) && this.backing.canPlaceItem(map(slot), stack);
        }

        @Override
        public void setChanged() {
            this.backing.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return this.backing.stillValid(player);
        }

        @Override
        public void clearContent() {
            // Never: the goods are the colony's.
        }
    }
}
