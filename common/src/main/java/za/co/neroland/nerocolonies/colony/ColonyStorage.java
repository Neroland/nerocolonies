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
 * <h2>One store, many doors, kept in boxes</h2>
 *
 * <p>There is exactly one stock per colony (in {@link ColonyStores}), reached through several blocks:
 * the colony beacon exposes it as a standard item capability, and every Colony Depot and Storage
 * Crate in the claim is another window onto the same goods. The depots and crates are also where
 * the goods are <em>kept</em>: they set the capacity ({@link ColonyStorageBlocks}), and a colony with
 * none has no storage at all.
 *
 * <h2>The usable gate</h2>
 *
 * <p>{@link #usableSlots(int, int)} is the boxes' slots plus {@value #SLOTS_PER_MODULE} per
 * capacity module (only while a box stands), capped at the backing size. Slots past the gate refuse
 * insertion but still read and extract, so removing a <em>module</em> strands nothing. Removing a
 * <em>box</em> is different: {@link #settle} packs the goods into what is left and drops the
 * overflow where the box stood.
 *
 * <h2>Everything here is server-side</h2>
 *
 * <p>Every method takes a {@link MinecraftServer}: colony goods are server state and the client is
 * shown counts, never contents. Nothing in this class is reachable from a client path.
 */
public final class ColonyStorage {

    /** Slots each {@code CAPACITY} module on the beacon adds. */
    public static final int SLOTS_PER_MODULE = 9;

    private ColonyStorage() {
    }

    /**
     * The usable slot count for a colony whose depots and crates hold {@code boxSlots} slots and whose
     * beacon has {@code capacityModules} capacity modules. No box, no storage: modules only extend
     * storage that exists. Pure; unit-tested.
     */
    public static int usableSlots(int boxSlots, int capacityModules) {
        if (boxSlots <= 0) {
            return 0;
        }
        int slots = boxSlots + Math.max(0, capacityModules) * SLOTS_PER_MODULE;
        return Math.clamp(slots, 0, ColonyStores.STORAGE_SLOTS);
    }

    /**
     * The usable slot count for a colony from its boxes and the given module count. The gallery's
     * sandbox colony has no real boxes and is treated as having one depot.
     */
    public static int usableSlots(MinecraftServer server, UUID colonyId, int capacityModules) {
        int boxes = ColonyStorageBlocks.get(server).boxSlots(colonyId);
        if (boxes <= 0 && ColonyLife.isSandbox(server, colonyId)) {
            boxes = ColonyStorageBlocks.DEPOT_SLOTS;
        }
        return usableSlots(boxes, capacityModules);
    }

    /**
     * The usable slot count for a colony, read from its boxes and the capacity modules in its beacon.
     * Modules count as none if the beacon is not loaded — which cannot happen on the colony tick (the
     * beacon is what drives it) but can from a command.
     */
    public static int usableSlots(net.minecraft.server.level.ServerLevel level, Colony colony) {
        MinecraftServer server = level.getServer();
        net.minecraft.server.level.ServerLevel home = server.getLevel(colony.dimension());
        int modules = 0;
        if (home != null && home.isLoaded(colony.beaconPos()) && home.getBlockEntity(colony.beaconPos())
                instanceof za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity beacon) {
            modules = beacon.capacityModules();
        }
        return usableSlots(server, colony.colonyId(), modules);
    }

    /** A vanilla {@link net.minecraft.world.Container} window onto this colony's working stock. */
    public static ColonyContainer container(MinecraftServer server, UUID colonyId,
            java.util.function.IntSupplier capacityModules) {
        return new ColonyContainer(server, colonyId, ColonyContainer.Region.STORAGE,
                () -> usableSlots(server, colonyId, capacityModules.getAsInt()), true);
    }

    /**
     * Packs the colony's goods into its first {@code usable} slots, merging stacks, and drops what no
     * longer fits at {@code pos}. Called when a depot or crate goes: the remaining boxes keep what
     * they can hold, and only the overflow spills (everything, if that was the last box). Nothing is
     * voided.
     *
     * @return how many stacks were dropped
     */
    public static int settle(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
            UUID colonyId, int usable) {
        ColonyStores stores = ColonyStores.get(level.getServer());
        ColonyStores.Store store = stores.peek(colonyId);
        if (store == null) {
            return 0;
        }
        NonNullList<ItemStack> items = store.storage();
        List<ItemStack> overflow = pack(items, usable);
        stores.touch();
        for (ItemStack stack : overflow) {
            net.minecraft.world.Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D,
                    pos.getZ() + 0.5D, stack);
        }
        return overflow.size();
    }

    /**
     * Re-seats every stack of {@code items} into its first {@code usable} slots, merging like stacks,
     * and returns what did not fit (the list is left holding only what fits). Pure apart from the
     * list it is given; unit-tested.
     */
    public static List<ItemStack> pack(List<ItemStack> items, int usable) {
        int limit = Math.clamp(usable, 0, items.size());
        List<ItemStack> all = new java.util.ArrayList<>();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty()) {
                all.add(stack);
            }
            items.set(slot, ItemStack.EMPTY);
        }
        List<ItemStack> overflow = new java.util.ArrayList<>();
        for (ItemStack stack : all) {
            int remaining = stack.getCount();
            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                ItemStack existing = items.get(slot);
                if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                    continue;
                }
                int moved = Math.min(existing.getMaxStackSize() - existing.getCount(), remaining);
                if (moved > 0) {
                    existing.grow(moved);
                    remaining -= moved;
                }
            }
            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                if (items.get(slot).isEmpty()) {
                    int moved = Math.min(stack.getMaxStackSize(), remaining);
                    items.set(slot, stack.copyWithCount(moved));
                    remaining -= moved;
                }
            }
            if (remaining > 0) {
                overflow.add(stack.copyWithCount(remaining));
            }
        }
        return overflow;
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
