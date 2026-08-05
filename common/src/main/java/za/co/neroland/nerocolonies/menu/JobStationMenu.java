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

import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * A job station's menu: two upgrade-module slots, the player inventory, and the station's synced
 * production state.
 *
 * <p>There are no input or output slots, and that is the design rather than an omission — a station
 * draws from colony storage and pushes back into it, so the only thing a player puts <em>in</em> a
 * station is a module.
 *
 * <p><b>{@code addDataSlots} is not optional.</b> Every value the screen draws is in the block
 * entity's {@link ContainerData} and every one of them is registered below; a gauge whose backing
 * value is not a data slot reads zero on every client while looking perfectly correct on the
 * integrated server.
 */
public class JobStationMenu extends AbstractContainerMenu {

    private static final int UPGRADE_SLOTS = JobStationBlockEntity.UPGRADE_SLOTS;

    /**
     * Layout constants shared with the screen so the two cannot drift — the screen paints a well and
     * a tray from these very coordinates rather than from a duplicate set of its own.
     */
    public static final int MODULE_X = 152;
    public static final int MODULE_Y = 22;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 113;
    public static final int HOTBAR_Y = 169;

    private final Container container;
    private final ContainerData data;

    /** Client-side factory: an empty stand-in container the server's contents are synced into. */
    public JobStationMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(UPGRADE_SLOTS),
                new SimpleContainerData(JobStationBlockEntity.DATA_SIZE));
    }

    public JobStationMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        super(NeroColoniesMenus.JOB_STATION.get(), id);
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

    // --- synced station state -----------------------------------------------

    public int energyPermille() {
        return this.data.get(JobStationBlockEntity.DATA_ENERGY_PERMILLE);
    }

    public int energyScale() {
        return Math.max(1, this.data.get(JobStationBlockEntity.DATA_ENERGY_SCALE));
    }

    public int progressPermille() {
        return this.data.get(JobStationBlockEntity.DATA_PROGRESS_PERMILLE);
    }

    public int progressScale() {
        return Math.max(1, this.data.get(JobStationBlockEntity.DATA_PROGRESS_SCALE));
    }

    /** Whether the station holds one of the colony's job slots this cycle. */
    public boolean active() {
        return this.data.get(JobStationBlockEntity.DATA_ACTIVE) != 0;
    }

    /** Whether it is held up by missing inputs, a full destination or low morale. */
    public boolean blocked() {
        return this.data.get(JobStationBlockEntity.DATA_BLOCKED) != 0;
    }

    public int assigned() {
        return this.data.get(JobStationBlockEntity.DATA_ASSIGNED);
    }

    public int required() {
        return this.data.get(JobStationBlockEntity.DATA_REQUIRED);
    }

    /** Whether any loaded job definition names this station's block. */
    public boolean hasJob() {
        return this.data.get(JobStationBlockEntity.DATA_HAS_JOB) != 0;
    }

    /** Whether the station stands inside a colony (or outpost) claim at all. */
    public boolean bound() {
        return this.data.get(JobStationBlockEntity.DATA_BOUND) != 0;
    }

    /** Whether it is an outpost station rather than one in the colony proper. */
    public boolean outpost() {
        return this.data.get(JobStationBlockEntity.DATA_OUTPOST) != 0;
    }

    /** Whether the station routes its output to the export buffer instead of colony storage. */
    public boolean exportOutput() {
        return this.data.get(JobStationBlockEntity.DATA_EXPORT) != 0;
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
