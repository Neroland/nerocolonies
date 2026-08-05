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

import za.co.neroland.nerocolonies.block.entity.OutpostBeaconBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The outpost beacon's menu: one module slot, the player inventory, and the outpost's status.
 *
 * <p>Small, because an outpost is small. The one number that matters here is whether the parent
 * colony is still there — an orphaned outpost looks exactly like a working one from the outside.
 */
public class OutpostMenu extends AbstractContainerMenu {

    private static final int UPGRADE_SLOTS = OutpostBeaconBlockEntity.UPGRADE_SLOTS;

    /**
     * Layout constants shared with the screen so the two cannot drift — the screen paints a well and
     * a tray from these very coordinates rather than from a duplicate set of its own.
     */
    public static final int MODULE_X = 152;
    public static final int MODULE_Y = 22;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 103;
    public static final int HOTBAR_Y = 159;

    private final Container container;
    private final ContainerData data;

    /** Client-side factory: an empty stand-in container the server's contents are synced into. */
    public OutpostMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(UPGRADE_SLOTS),
                new SimpleContainerData(OutpostBeaconBlockEntity.DATA_SIZE));
    }

    public OutpostMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        super(NeroColoniesMenus.OUTPOST_BEACON.get(), id);
        this.container = container;
        this.data = data;

        for (int slot = 0; slot < UPGRADE_SLOTS; slot++) {
            this.addSlot(new Slot(container, slot, MODULE_X, MODULE_Y + slot * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return container.canPlaceItem(this.getContainerSlot(), stack);
                }
            });
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
    }

    // --- synced outpost state -----------------------------------------------

    public int energyPermille() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_ENERGY_PERMILLE);
    }

    public int energyScale() {
        return Math.max(1, this.data.get(OutpostBeaconBlockEntity.DATA_ENERGY_SCALE));
    }

    /** Whether this beacon anchors an outpost record at all. */
    public boolean bound() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_BOUND) != 0;
    }

    /** Whether the parent colony still exists — the one thing that makes an outpost inert. */
    public boolean parentAlive() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_PARENT_ALIVE) != 0;
    }

    public int claimRadius() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_CLAIM_RADIUS);
    }

    public int colonistCap() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_COLONIST_CAP);
    }

    public int jobSlots() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_JOB_SLOTS);
    }

    /** Stations filed against the parent colony, outposts included. */
    public int stations() {
        return this.data.get(OutpostBeaconBlockEntity.DATA_STATIONS);
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
            int invStart = UPGRADE_SLOTS;
            int invEnd = invStart + 36;
            if (index < invStart) {
                if (!this.moveItemStackTo(stack, invStart, invEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, invStart, false)) {
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
