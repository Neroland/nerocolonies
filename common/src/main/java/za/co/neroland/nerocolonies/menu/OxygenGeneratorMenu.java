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

import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The oxygen generator's menu: two upgrade-module slots, the player inventory, and the machine's
 * synced gauges.
 *
 * <p><b>{@code addDataSlots} is not optional.</b> A gauge whose backing value is never added to the
 * menu's data slots reads zero on every client while looking perfectly correct on the integrated
 * server. Every value the screen draws is in the block entity's {@link ContainerData} and every one
 * of them is registered below.
 */
public class OxygenGeneratorMenu extends AbstractContainerMenu {

    private static final int UPGRADE_SLOTS = OxygenGeneratorBlockEntity.UPGRADE_SLOTS;

    /**
     * Layout constants shared with the screen so the two cannot drift — the screen paints a well and
     * a tray from these very coordinates rather than from a duplicate set of its own.
     */
    public static final int MODULE_X = 152;
    public static final int MODULE_Y = 22;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 97;
    public static final int HOTBAR_Y = 153;

    private final Container container;
    private final ContainerData data;

    /** Client-side factory: an empty stand-in container the server's contents are synced into. */
    public OxygenGeneratorMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(UPGRADE_SLOTS),
                new SimpleContainerData(OxygenGeneratorBlockEntity.DATA_SIZE));
    }

    public OxygenGeneratorMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        super(NeroColoniesMenus.OXYGEN_GENERATOR.get(), id);
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

    // --- synced machine state ----------------------------------------------

    public int energyPermille() {
        return this.data.get(OxygenGeneratorBlockEntity.DATA_ENERGY_PERMILLE);
    }

    public int energyScale() {
        return Math.max(1, this.data.get(OxygenGeneratorBlockEntity.DATA_ENERGY_SCALE));
    }

    public int gasPermille() {
        return this.data.get(OxygenGeneratorBlockEntity.DATA_GAS_PERMILLE);
    }

    public int gasScale() {
        return Math.max(1, this.data.get(OxygenGeneratorBlockEntity.DATA_GAS_SCALE));
    }

    public boolean running() {
        return this.data.get(OxygenGeneratorBlockEntity.DATA_RUNNING) != 0;
    }

    /** Whether this dimension actually needs life support (false makes the machine advisory only). */
    public boolean lifeSupportNeeded() {
        return this.data.get(OxygenGeneratorBlockEntity.DATA_NEEDED) != 0;
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
