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

import za.co.neroland.nerocolonies.block.entity.ResearchStationBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The research station's menu: no slots of its own, the player inventory, and the station's machine
 * gauges.
 *
 * <h2>Two sync paths, on purpose</h2>
 *
 * <p>The <b>machine</b> state (power, whether the station is inside a claim, how many nodes the
 * colony has) rides the menu's data slots, because it is small, numeric and needs to update every
 * tick while the screen is open.
 *
 * <p>The <b>research graph</b> does not: it is a list of nodes with costs and prerequisites, it
 * changes only on {@code /reload}, and it does not fit through a 16-bit data slot. That arrives on
 * NeroColonies' own channel and is read from {@code ClientColonyDefinitions} /
 * {@code ClientColonySnapshot} by the screen. Using the right channel for each is what keeps the
 * per-tick sync cost at six shorts.
 *
 * <p>A menu with no slots still needs {@code addDataSlots}, and it still needs a container to answer
 * {@link #stillValid} — that is what keeps the screen closing when the player walks away.
 */
public class ResearchMenu extends AbstractContainerMenu {

    /** Layout constants shared with the screen so the two cannot drift. */
    public static final int WIDTH = 256;
    public static final int INVENTORY_X = 48;
    public static final int INVENTORY_Y = 140;
    public static final int HOTBAR_Y = 198;
    public static final int HEIGHT = 222;

    private final Container container;
    private final ContainerData data;

    /** Client-side factory: an empty stand-in the server's data slots are synced into. */
    public ResearchMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(0),
                new SimpleContainerData(ResearchStationBlockEntity.DATA_SIZE));
    }

    public ResearchMenu(int id, Inventory playerInventory, Container container, ContainerData data) {
        super(NeroColoniesMenus.RESEARCH_STATION.get(), id);
        this.container = container;
        this.data = data;

        // The research screen is wider than a chest, so the inventory is centred in it rather than
        // left-aligned at the usual x=8.
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
        return this.data.get(ResearchStationBlockEntity.DATA_ENERGY_PERMILLE);
    }

    public int energyScale() {
        return Math.max(1, this.data.get(ResearchStationBlockEntity.DATA_ENERGY_SCALE));
    }

    /** Whether the station stands inside a colony (or outpost) claim. */
    public boolean bound() {
        return this.data.get(ResearchStationBlockEntity.DATA_BOUND) != 0;
    }

    public int unlockedCount() {
        return this.data.get(ResearchStationBlockEntity.DATA_UNLOCKED);
    }

    public int jobSlots() {
        return this.data.get(ResearchStationBlockEntity.DATA_JOB_SLOTS);
    }

    /** Whether the buffer holds a full unlock's worth of energy. */
    public boolean powered() {
        return this.data.get(ResearchStationBlockEntity.DATA_POWERED) != 0;
    }

    // --- vanilla plumbing ---------------------------------------------------

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    /** No slots of our own, so a shift-click has nowhere to go and must be a no-op. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
