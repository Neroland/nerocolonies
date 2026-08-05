package za.co.neroland.nerocolonies.colony;

import java.util.UUID;
import java.util.function.IntSupplier;

import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A plain vanilla {@link Container} view onto one region of one colony's {@link ColonyStores.Store}.
 *
 * <h2>Why a view rather than an inventory</h2>
 *
 * <p>Colony storage is a property of the <em>colony</em>, not of any block: the beacon, every colony
 * depot in the claim and every job station all address the same goods. Giving each block its own
 * inventory and synchronising them would be a distributed-state problem with no upside. Instead each
 * block hands out one of these — a stateless window that resolves the store on every call — so there
 * is exactly one copy of the goods and no way for two blocks to disagree about them.
 *
 * <p>Because it is an ordinary {@code Container}, the loader capability registrations that already
 * wrap our block entities ({@code ContainerStorage} on Fabric, {@code WorldlyContainerWrapper} on
 * NeoForge, {@code SidedInvWrapper} on Forge) expose colony storage and the export buffer to pipes,
 * hoppers, AE2 and Create with <b>no NeroColonies-specific API at all</b>. That is the whole point of
 * the design: interoperability by using the vanilla shape rather than by publishing a bridge.
 *
 * <h2>The usable gate</h2>
 *
 * <p>The backing lists are always at their maximum size; {@link #usable} says how much of that the
 * colony has actually unlocked (base slots plus {@code CAPACITY} upgrade modules, or the configured
 * export-buffer size). Slots at or past the gate read empty and refuse insertion — but anything
 * already sitting in them is still readable and extractable, so lowering the gate strands nothing.
 */
public final class ColonyContainer implements Container {

    /** Which half of a colony's store this window addresses. */
    public enum Region {
        STORAGE,
        EXPORTS
    }

    private final MinecraftServer server;
    private final UUID colonyId;
    private final Region region;
    private final IntSupplier usable;
    private final boolean insertable;

    public ColonyContainer(MinecraftServer server, UUID colonyId, Region region, IntSupplier usable,
            boolean insertable) {
        this.server = server;
        this.colonyId = colonyId;
        this.region = region;
        this.usable = usable;
        this.insertable = insertable;
    }

    private NonNullList<ItemStack> items() {
        ColonyStores.Store store = ColonyStores.get(this.server).store(this.colonyId);
        return this.region == Region.STORAGE ? store.storage() : store.exports();
    }

    private void dirty() {
        ColonyStores.get(this.server).touch();
    }

    /** How many of this window's slots the colony has currently unlocked. */
    public int usableSlots() {
        return Math.clamp(this.usable.getAsInt(), 0, getContainerSize());
    }

    /** Whether {@code slot} is inside the unlocked region. */
    public boolean isUsable(int slot) {
        return slot >= 0 && slot < usableSlots();
    }

    // --- Container ----------------------------------------------------------

    @Override
    public int getContainerSize() {
        return this.region == Region.STORAGE ? ColonyStores.STORAGE_SLOTS : ColonyStores.EXPORT_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items()) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        NonNullList<ItemStack> items = items();
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        NonNullList<ItemStack> items = items();
        if (slot < 0 || slot >= items.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            dirty();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        NonNullList<ItemStack> items = items();
        if (slot < 0 || slot >= items.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            dirty();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        NonNullList<ItemStack> items = items();
        if (slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, stack);
        dirty();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return this.insertable && isUsable(slot);
    }

    @Override
    public void setChanged() {
        dirty();
    }

    @Override
    public boolean stillValid(Player player) {
        // Validity is the owning block entity's business; the window itself is always live.
        return true;
    }

    @Override
    public void clearContent() {
        NonNullList<ItemStack> items = items();
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, ItemStack.EMPTY);
        }
        dirty();
    }
}
