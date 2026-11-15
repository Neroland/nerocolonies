package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyBuildings;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStorageBlocks;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Quartermaster: walks a round of the colony's stores (every depot and crate, the beacon and the
 * Gratitude Cache pavilion) and keeps the store in order: at each stop it merges part stacks and
 * closes gaps, which frees slots for the colony's goods. Packing only ever merges and moves stacks
 * forward, so nothing is created, lost or pushed out. The cache itself is stocked on the colony cycle.
 */
final class StoreWork {

    private StoreWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        List<BlockPos> round = round(level, colony, anchor);
        WorkMemory memory = neran.workMemory();
        if (memory.roundCursor >= round.size()) {
            memory.roundCursor = 0;
        }
        BlockPos stop = round.get(memory.roundCursor);
        if (!WorkSupport.reach(neran, stop)) {
            return false;
        }
        WorkSupport.strike(neran, stop);
        tidy(level, colony);
        memory.roundCursor++;
        return true;
    }

    private static List<BlockPos> round(ServerLevel level, Colony colony, BlockPos anchor) {
        List<BlockPos> stops = new ArrayList<>();
        stops.add(anchor);
        for (ColonyStorageBlocks.Box box : ColonyStorageBlocks.get(level.getServer()).boxes(colony.colonyId())) {
            if (box.dimension().equals(level.dimension()) && WorkSupport.inClaim(level, colony, box.pos())) {
                stops.add(box.pos());
            }
        }
        if (WorkSupport.inClaim(level, colony, colony.beaconPos())) {
            stops.add(colony.beaconPos());
        }
        for (ColonyBuildings.Placed cache : ColonyBuildings.withRole(level, colony, ColonyBuildings.ROLE_CACHE)) {
            stops.add(cache.access() != null ? cache.access() : cache.centre());
        }
        return stops;
    }

    /** Merges part stacks and closes gaps in colony storage, if it needs it. */
    static void tidy(ServerLevel level, Colony colony) {
        ColonyStores stores = ColonyStores.get(level.getServer());
        ColonyStores.Store store = stores.peek(colony.colonyId());
        if (store == null || !untidy(store.storage())) {
            return;
        }
        NonNullList<ItemStack> items = store.storage();
        List<ItemStack> overflow = ColonyStorage.pack(items, items.size());
        stores.touch();
        for (ItemStack rest : overflow) {
            WorkSupport.store(level, colony, rest, colony.beaconPos()); // cannot happen; never voided
        }
    }

    /** Whether a gap precedes a stack, or two part stacks of the same goods could merge. */
    private static boolean untidy(List<ItemStack> items) {
        boolean gap = false;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                gap = true;
                continue;
            }
            if (gap) {
                return true;
            }
            if (stack.getCount() >= stack.getMaxStackSize()) {
                continue;
            }
            for (int j = i + 1; j < items.size(); j++) {
                ItemStack other = items.get(j);
                if (!other.isEmpty() && ItemStack.isSameItemSameComponents(stack, other)) {
                    return true;
                }
            }
        }
        return false;
    }
}
