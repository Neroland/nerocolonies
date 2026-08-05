package za.co.neroland.nerocolonies.colony;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ItemTarget;

/**
 * The colony's shared working stock: what job stations draw their inputs from, what their outputs go
 * into, and what a research node's cost is paid out of.
 *
 * <h2>One store, many doors</h2>
 *
 * <p>There is exactly one stock per colony (in {@link ColonyStores}), reached through several blocks:
 * the colony beacon exposes it as a standard item capability, and every colony depot placed inside
 * the claim is another window onto the same goods. Adding a depot therefore adds <em>access</em>, not
 * capacity — capacity comes from {@code CAPACITY} upgrade modules on the beacon, which is where a
 * colony-wide upgrade belongs.
 *
 * <h2>The usable gate</h2>
 *
 * <p>{@link #usableSlots(int)} is {@value #BASE_SLOTS} slots plus {@value #SLOTS_PER_MODULE} per
 * capacity module, capped at the backing size. Slots past the gate refuse insertion but still read
 * and extract, so removing a module strands nothing — it just stops anything new going in until the
 * overflow has been drawn down.
 *
 * <h2>Everything here is server-side</h2>
 *
 * <p>Every method takes a {@link MinecraftServer}: colony goods are server state and the client is
 * shown counts, never contents. Nothing in this class is reachable from a client path.
 */
public final class ColonyStorage {

    /** Slots a colony has before any capacity module. */
    public static final int BASE_SLOTS = 18;

    /** Slots each {@code CAPACITY} module on the beacon adds. */
    public static final int SLOTS_PER_MODULE = 9;

    private ColonyStorage() {
    }

    /** The usable slot count for a colony with {@code capacityModules} capacity modules installed. */
    public static int usableSlots(int capacityModules) {
        int slots = BASE_SLOTS + Math.max(0, capacityModules) * SLOTS_PER_MODULE;
        return Math.clamp(slots, 0, ColonyStores.STORAGE_SLOTS);
    }

    /**
     * The usable slot count for a colony, read from the capacity modules in its beacon. Falls back to
     * the unupgraded size if the beacon is not loaded — which cannot happen on the colony tick (the
     * beacon is what drives it) but can from a command.
     */
    public static int usableSlots(net.minecraft.server.level.ServerLevel level, Colony colony) {
        if (level.getBlockEntity(colony.beaconPos())
                instanceof za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity beacon) {
            return usableSlots(beacon.capacityModules());
        }
        return usableSlots(0);
    }

    /** A vanilla {@link net.minecraft.world.Container} window onto this colony's working stock. */
    public static ColonyContainer container(MinecraftServer server, UUID colonyId,
            java.util.function.IntSupplier capacityModules) {
        return new ColonyContainer(server, colonyId, ColonyContainer.Region.STORAGE,
                () -> usableSlots(capacityModules.getAsInt()), true);
    }

    // --- reads --------------------------------------------------------------

    /** How many items matching {@code target} the colony holds. */
    public static int count(MinecraftServer server, UUID colonyId, ItemTarget target) {
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colonyId).storage();
        int total = 0;
        for (ItemStack stack : items) {
            if (target.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Whether every one of {@code inputs} is available in full. */
    public static boolean hasAll(MinecraftServer server, UUID colonyId, List<ItemTarget> inputs) {
        for (ItemTarget input : inputs) {
            if (count(server, colonyId, input) < input.count()) {
                return false;
            }
        }
        return true;
    }

    /** How many of one exact item the colony holds. */
    public static int count(MinecraftServer server, UUID colonyId, ItemAmount amount) {
        ItemStack wanted = amount.toStack();
        if (wanted.isEmpty()) {
            return 0;
        }
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colonyId).storage();
        int total = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && stack.getItem() == wanted.getItem()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Whether the colony holds every line of a research node's cost in full. */
    public static boolean hasAmounts(MinecraftServer server, UUID colonyId, List<ItemAmount> cost) {
        for (ItemAmount amount : cost) {
            if (count(server, colonyId, amount) < amount.count()) {
                return false;
            }
        }
        return true;
    }

    // --- writes -------------------------------------------------------------

    /**
     * Removes one full research cost, all or nothing — the same reasoning as {@link #consume}: a
     * partly-paid unlock would take the goods and give nothing back.
     *
     * @return {@code true} if the cost was paid
     */
    public static boolean payAmounts(MinecraftServer server, UUID colonyId, List<ItemAmount> cost) {
        if (cost.isEmpty()) {
            return true;
        }
        if (!hasAmounts(server, colonyId, cost)) {
            return false;
        }
        ColonyStores stores = ColonyStores.get(server);
        NonNullList<ItemStack> items = stores.store(colonyId).storage();
        for (ItemAmount amount : cost) {
            ItemStack wanted = amount.toStack();
            int remaining = amount.count();
            for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
                ItemStack stack = items.get(slot);
                if (stack.isEmpty() || stack.getItem() != wanted.getItem()) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
                remaining -= take;
            }
        }
        stores.touch();
        return true;
    }

    /**
     * Removes one full set of {@code inputs}, all or nothing.
     *
     * <p>All-or-nothing matters: a job that consumed two of its three inputs and then found the third
     * missing would quietly destroy goods every cycle. The availability check runs first over the
     * whole list, and only then does anything leave the store.
     *
     * @return {@code true} if the inputs were consumed
     */
    public static boolean consume(MinecraftServer server, UUID colonyId, List<ItemTarget> inputs) {
        if (inputs.isEmpty()) {
            return true;
        }
        if (!hasAll(server, colonyId, inputs)) {
            return false;
        }
        ColonyStores stores = ColonyStores.get(server);
        NonNullList<ItemStack> items = stores.store(colonyId).storage();
        for (ItemTarget input : inputs) {
            int remaining = input.count();
            for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
                ItemStack stack = items.get(slot);
                if (!input.matches(stack)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
                remaining -= take;
            }
        }
        stores.touch();
        return true;
    }

    /**
     * Inserts a stack into the colony's working stock, merging into partial stacks first.
     *
     * @return the number of items that did <b>not</b> fit; zero means everything went in
     */
    public static int insert(MinecraftServer server, UUID colonyId, ItemStack stack, int usableSlots) {
        if (stack.isEmpty()) {
            return 0;
        }
        ColonyStores stores = ColonyStores.get(server);
        NonNullList<ItemStack> items = stores.store(colonyId).storage();
        int limit = Math.clamp(usableSlots, 0, items.size());
        int remaining = stack.getCount();
        boolean changed = false;

        // Merge pass: topping up a partial stack is always better than opening a new slot.
        for (int slot = 0; slot < limit && remaining > 0; slot++) {
            ItemStack existing = items.get(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int room = existing.getMaxStackSize() - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, remaining);
            existing.grow(moved);
            remaining -= moved;
            changed = true;
        }
        // Fill pass.
        for (int slot = 0; slot < limit && remaining > 0; slot++) {
            if (!items.get(slot).isEmpty()) {
                continue;
            }
            ItemStack placed = stack.copyWithCount(Math.min(remaining, stack.getMaxStackSize()));
            items.set(slot, placed);
            remaining -= placed.getCount();
            changed = true;
        }
        if (changed) {
            stores.touch();
        }
        return remaining;
    }

    /** Whether a whole {@link ItemAmount} would fit right now (a dry run of {@link #insert}). */
    public static boolean fits(MinecraftServer server, UUID colonyId, ItemAmount amount,
            int usableSlots) {
        ItemStack stack = amount.toStack();
        if (stack.isEmpty()) {
            return false;
        }
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colonyId).storage();
        int limit = Math.clamp(usableSlots, 0, items.size());
        int room = 0;
        for (int slot = 0; slot < limit && room < stack.getCount(); slot++) {
            ItemStack existing = items.get(slot);
            if (existing.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                room += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return room >= stack.getCount();
    }
}
