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

import za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The colony beacon's menu: three shared upgrade-module slots, six food supply slots, the player
 * inventory, and the colony's synced state.
 *
 * <p><b>{@code addDataSlots} is not optional.</b> A gauge whose backing value is never added to the
 * menu's data slots reads zero on every client while looking perfectly correct on the integrated
 * server — the classic "dead gauge" bug. Every value the screen draws is in
 * {@link ColonyBeaconBlockEntity}'s {@link ContainerData} and every one of them is registered below.
 *
 * <p>Nothing player-shaped travels here: the data slots carry morale, population, capacity, food
 * stock, claim radius, comfort and counts. The owner UUID and access list never leave the server.
 */
public class ColonyBeaconMenu extends AbstractContainerMenu {

    private static final int UPGRADE_SLOTS = ColonyBeaconBlockEntity.UPGRADE_SLOTS;
    private static final int SUPPLY_SLOTS = ColonyBeaconBlockEntity.SUPPLY_SLOTS;

    /**
     * Only the beacon's <em>local</em> slots get menu slots. The block entity's container is far
     * larger — it also carries windows onto colony storage and the export buffer so that automation
     * reaches them through the standard item capability — but those are not something a player pokes
     * at through the beacon: the depot GUI is the door for storage, and the export buffer is drained
     * by pipes or sold from the Trade tab.
     */
    private static final int LOCAL_SLOTS = ColonyBeaconBlockEntity.LOCAL_SLOTS;

    /**
     * Layout constants shared with the screen so the two cannot drift. The screen paints a well under
     * every slot from these very coordinates (see {@code NeroColoniesScreen#paintSlotWells}), so a
     * change here moves the frame with the slot rather than leaving the two out of step.
     *
     * <p>Both module and supply slots sit in one band at the foot of the tab content, above the
     * player inventory: they are the only things on this screen a player puts an item into, and a
     * player who cannot see where the food goes will not feed the colony.
     */
    public static final int SLOT_ROW_Y = 110;
    public static final int SUPPLY_ROW_X = 8;
    public static final int SUPPLY_ROW_Y = SLOT_ROW_Y;
    public static final int UPGRADE_ROW_X = 140;
    public static final int UPGRADE_ROW_Y = SLOT_ROW_Y;
    public static final int INVENTORY_X = 23;
    public static final int INVENTORY_Y = 156;
    public static final int HOTBAR_Y = 212;

    private final Container container;
    private final ContainerData data;

    /** Client-side factory: an empty stand-in container the server's contents are synced into. */
    public ColonyBeaconMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(LOCAL_SLOTS),
                new SimpleContainerData(ColonyBeaconBlockEntity.DATA_SIZE));
    }

    public ColonyBeaconMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        super(NeroColoniesMenus.COLONY_BEACON.get(), id);
        this.container = container;
        this.data = data;

        // Slot order is part of this menu's contract (quickMoveStack and the client's stand-in
        // container both index off it): three modules, then six supply, then the player inventory.
        for (int slot = 0; slot < UPGRADE_SLOTS; slot++) {
            this.addSlot(new Slot(container, slot, UPGRADE_ROW_X + slot * 18, UPGRADE_ROW_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return container.canPlaceItem(this.getContainerSlot(), stack);
                }
            });
        }
        for (int slot = 0; slot < SUPPLY_SLOTS; slot++) {
            this.addSlot(new Slot(container, UPGRADE_SLOTS + slot,
                    SUPPLY_ROW_X + slot * 18, SUPPLY_ROW_Y) {
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
        // Without this the client's gauges are all zero. See the class Javadoc.
        this.addDataSlots(data);
    }

    // --- synced colony state (client-safe readouts) -------------------------

    public int energyPermille() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_ENERGY_PERMILLE);
    }

    public int energyScale() {
        return Math.max(1, this.data.get(ColonyBeaconBlockEntity.DATA_ENERGY_SCALE));
    }

    public int morale() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_MORALE);
    }

    public int population() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_POPULATION);
    }

    public int housingCapacity() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_HOUSING_CAPACITY);
    }

    public int foodStock() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_FOOD_STOCK);
    }

    public boolean lifeSupportOk() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_LIFE_SUPPORT) != 0;
    }

    public int claimRadius() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_CLAIM_RADIUS);
    }

    public int researchCount() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_RESEARCH_COUNT);
    }

    public int outpostCount() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_OUTPOST_COUNT);
    }

    public boolean hasColony() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_HAS_COLONY) != 0;
    }

    /** Mean housing comfort, 0..100. */
    public int comfortPercent() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_COMFORT_PERCENT);
    }

    /** Whether morale has fallen below the work-stop threshold. */
    public boolean workStopped() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_WORK_STOPPED) != 0;
    }

    /** Life-support state ordinal: 0 = OK, 1 = DEGRADED, 2 = FAILED. */
    public int lifeSupportState() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_LIFE_STATE);
    }

    /** How many oxygen generators are currently feeding this colony. */
    public int generatorCount() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_GENERATORS);
    }

    /** Progress through the structure the colony is building itself, 0..100. Zero when idle. */
    public int buildPercent() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_BUILD_PERCENT);
    }

    /** How many structures this colony has built for itself. */
    public int structuresBuilt() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_STRUCTURES_BUILT);
    }

    /**
     * Whether the current structure's materials came out of colony storage. When false the colonists
     * are fabricating from scrap and the build is running at {@code constructionUnsuppliedFactor} —
     * which is the one thing on this screen a player can act on.
     */
    public boolean buildSupplied() {
        return this.data.get(ColonyBeaconBlockEntity.DATA_BUILD_SUPPLIED) != 0;
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
            int invStart = LOCAL_SLOTS;
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
