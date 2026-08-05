package za.co.neroland.nerocolonies.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.block.entity.ColonyDepotBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The colony depot's menu: six rows of nine onto the colony's shared storage, plus the player
 * inventory.
 *
 * <h2>Locked slots</h2>
 *
 * <p>All 54 backing slots are always present; only the first {@code usableSlots} are unlocked (base
 * storage plus {@code CAPACITY} modules in the beacon). Locked slots refuse insertion through
 * {@link Slot#mayPlace} and the screen greys them out — but anything already sitting in one is still
 * takeable, so removing a capacity module strands nothing.
 *
 * <p>Showing the locked slots rather than resizing the window is deliberate: a storage GUI whose
 * shape changed when a module was swapped would be disorienting, and a visible locked slot is an
 * advertisement for the upgrade.
 */
public class ColonyStorageMenu extends AbstractContainerMenu {

    /** Backing slot count — the colony store's full size. */
    public static final int SLOTS = ColonyDepotBlockEntity.SLOTS;

    public static final int COLUMNS = 9;
    public static final int ROWS = SLOTS / COLUMNS;

    /** Layout constants shared with the screen so the two cannot drift. */
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int INVENTORY_Y = GRID_Y + ROWS * 18 + 13;
    public static final int HOTBAR_Y = INVENTORY_Y + 58;

    private final Container container;
    private final ContainerData data;

    /** Client-side factory: an empty stand-in container the server's contents are synced into. */
    public ColonyStorageMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(SLOTS),
                new SimpleContainerData(ColonyDepotBlockEntity.DATA_SIZE));
    }

    public ColonyStorageMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        super(NeroColoniesMenus.COLONY_STORAGE.get(), id);
        this.container = container;
        this.data = data;

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                this.addSlot(new Slot(container, row * COLUMNS + col,
                        GRID_X + col * 18, GRID_Y + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        // The unlocked count is a data slot, so this answers the same on both sides.
                        // Asking the container alone would not: the client's stand-in container
                        // accepts everything, and the two would disagree on every locked slot.
                        return this.getContainerSlot() < usableSlots()
                                && container.canPlaceItem(this.getContainerSlot(), stack);
                    }
                });
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y));
        }
        this.addDataSlots(data);
    }

    /** How many of the 54 slots this colony has unlocked. */
    public int usableSlots() {
        return Math.clamp(this.data.get(ColonyDepotBlockEntity.DATA_USABLE_SLOTS), 0, SLOTS);
    }

    /** Whether the depot stands inside a colony (or outpost) claim at all. */
    public boolean bound() {
        return this.data.get(ColonyDepotBlockEntity.DATA_BOUND) != 0;
    }

    // --- vanilla plumbing ---------------------------------------------------

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            int invStart = SLOTS;
            int invEnd = invStart + 36;
            if (index < invStart) {
                if (!this.moveItemStackTo(stack, invStart, invEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, usableSlots(), false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }
}
