package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Forester: walks to the nearest tree in the claim, fells all of it one log at a time (drops to
 * colony storage), replants a sapling from storage where the trunk stood, and gathers the saplings,
 * sticks and apples the tree's leaves drop as they decay.
 *
 * <p>A tree is a connected mass of at most {@value WorkGeometry#TREE_CAP} logs standing on dirt with
 * natural (non-persistent) leaves on it. Logs inside the colony's own buildings or the active site,
 * protected logs and anything with a block entity are never touched, and a log mass with only
 * player-placed leaves, or bigger than the cap, is somebody's build and is left alone.
 */
final class ForestWork {

    /** How far from the workplace a Forester looks for trees (it never leaves the claim). */
    private static final int SEARCH_RADIUS = 20;

    private static final List<int[]> COLUMNS = WorkGeometry.ringOffsets(0, SEARCH_RADIUS);

    private static final int COLUMNS_PER_CALL = 48;

    /** Trunk bases are looked for this far below and above the workplace. */
    private static final int BASE_BELOW = 4;
    private static final int BASE_ABOVE = 6;

    /** Ticks to wait after a full search found no tree. */
    private static final long RETRY_TICKS = 1200L;

    /** Calls spent failing to reach one log before the tree is given up. */
    private static final int MAX_STALL = 30;

    /** Saplings tried when the tree's own kind is not in storage. */
    private static final List<String> SAPLINGS = List.of("oak_sapling", "birch_sapling", "spruce_sapling",
            "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling",
            "mangrove_propagule");

    private ForestWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        WorkMemory memory = neran.workMemory();
        long now = level.getGameTime();

        // 1. Fell the current tree, one log per call.
        while (!memory.tree.isEmpty()) {
            BlockPos log = memory.tree.get(0);
            BlockState state = level.getBlockState(log);
            if (!fellable(level, colony, log, state)) {
                memory.tree.remove(0);
                continue;
            }
            if (!inReach(neran, log)) {
                if (++memory.stall > MAX_STALL) {
                    giveUp(memory, now);
                    return false;
                }
                BlockPos foot = memory.stumps.isEmpty() ? log : memory.stumps.get(0);
                WorkSupport.reach(neran, new BlockPos(log.getX(), foot.getY(), log.getZ()), 0.0D);
                return false;
            }
            memory.stall = 0;
            WorkSupport.strike(neran, log);
            Construction.breakToStorage(level, colony, log, state);
            memory.tree.remove(0);
            neran.workMemory().markProductive(now);
            return true;
        }

        // 2. Replant where the trunk stood, and gather what the tree dropped.
        if (!memory.stumps.isEmpty()) {
            BlockPos stump = memory.stumps.get(0);
            if (!WorkSupport.reach(neran, stump)) {
                if (++memory.stall > MAX_STALL) {
                    giveUp(memory, now);
                }
                return false;
            }
            memory.stall = 0;
            memory.stumps.remove(0);
            replant(level, colony, stump, memory.treeSapling);
            gather(level, colony, stump, 6);
            WorkSupport.strike(neran, stump);
            return true;
        }

        // 3. Find the next tree.
        if (now < memory.treeRetryAt) {
            if (now % 100L < 20L) {
                gather(level, colony, anchor, SEARCH_RADIUS);
            }
            return WorkSupport.atPost(neran, anchor, profession.workRadius());
        }
        findTree(level, colony, anchor, memory, now);
        return WorkSupport.atPost(neran, anchor, profession.workRadius());
    }

    private static void giveUp(WorkMemory memory, long now) {
        memory.tree.clear();
        memory.stumps.clear();
        memory.stall = 0;
        memory.treeRetryAt = now + RETRY_TICKS / 4;
    }

    /** Horizontally within three blocks of the log's column, and not far below or above it. */
    private static boolean inReach(ColonistEntity neran, BlockPos log) {
        double dx = neran.getX() - (log.getX() + 0.5D);
        double dz = neran.getZ() - (log.getZ() + 0.5D);
        int dy = log.getY() - neran.getBlockY();
        return dx * dx + dz * dz <= 9.0D && dy >= -3 && dy <= 14;
    }

    private static boolean fellable(ServerLevel level, Colony colony, BlockPos pos, BlockState state) {
        return state.is(BlockTags.LOGS) && WorkSupport.mayBreak(level, colony, pos, state)
                && !WorkSupport.builtUp(level, colony, pos, 0);
    }

    // --- finding a tree -------------------------------------------------------------

    private static void findTree(ServerLevel level, Colony colony, BlockPos anchor, WorkMemory memory, long now) {
        for (int n = 0; n < COLUMNS_PER_CALL; n++) {
            if (memory.treeSearchCursor >= COLUMNS.size()) {
                memory.treeSearchCursor = 0;
                memory.treeRetryAt = now + RETRY_TICKS;
                return;
            }
            int[] column = COLUMNS.get(memory.treeSearchCursor++);
            for (int dy = -BASE_BELOW; dy <= BASE_ABOVE; dy++) {
                BlockPos pos = anchor.offset(column[0], dy, column[1]);
                if (!WorkSupport.inClaim(level, colony, pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (!state.is(BlockTags.LOGS) || !level.getBlockState(pos.below()).is(BlockTags.DIRT)
                        || !fellable(level, colony, pos, state)) {
                    continue;
                }
                List<BlockPos> logs = WorkGeometry.collectTree(pos,
                        p -> WorkSupport.inClaim(level, colony, p) && fellable(level, colony, p, level.getBlockState(p)),
                        WorkGeometry.TREE_CAP);
                if (logs == null || logs.isEmpty() || !(natural(level, logs) || sandbox(level, colony))) {
                    continue;
                }
                memory.tree.clear();
                memory.tree.addAll(logs);
                memory.stumps.clear();
                for (BlockPos log : logs) {
                    if (level.getBlockState(log.below()).is(BlockTags.DIRT)) {
                        memory.stumps.add(log);
                    }
                }
                Identifier logId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                memory.treeSapling = "minecraft".equals(logId.getNamespace())
                        ? WorkGeometry.saplingFor(logId.getPath()) : null;
                memory.treeSearchCursor = 0;
                memory.stall = 0;
                return;
            }
        }
    }

    /** The gallery's sandbox colony shows bare demonstration trunks; there they count as trees. */
    private static boolean sandbox(ServerLevel level, Colony colony) {
        return ColonyLife.isSandbox(level.getServer(), colony.colonyId());
    }

    /** Whether natural (decaying) leaves touch any of the logs: a grown tree, not a build. */
    private static boolean natural(ServerLevel level, List<BlockPos> logs) {
        for (BlockPos log : logs) {
            for (Direction side : Direction.values()) {
                BlockState state = level.getBlockState(log.relative(side));
                if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)
                        && !state.getValue(LeavesBlock.PERSISTENT)) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- replanting and gathering -----------------------------------------------------

    private static void replant(ServerLevel level, Colony colony, BlockPos pos, @Nullable String preferred) {
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).is(BlockTags.DIRT)
                || !WorkSupport.mayPlace(level, colony, pos)) {
            return;
        }
        if (preferred != null && plant(level, colony, pos, preferred)) {
            return;
        }
        for (String name : SAPLINGS) {
            if (plant(level, colony, pos, name)) {
                return;
            }
        }
    }

    private static boolean plant(ServerLevel level, Colony colony, BlockPos pos, String name) {
        Item item = WorkSupport.item(name);
        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState sapling = blockItem.getBlock().defaultBlockState();
        if (!sapling.canSurvive(level, pos) || !ColonyStorage.consume(level.getServer(), colony.colonyId(),
                List.of(WorkSupport.target(name, 1)))) {
            return false;
        }
        level.setBlock(pos, sapling, Block.UPDATE_ALL);
        return true;
    }

    /** Puts loose saplings, sticks and apples lying in the claim near {@code centre} into storage. */
    private static void gather(ServerLevel level, Colony colony, BlockPos centre, int radius) {
        AABB box = new AABB(centre).inflate(radius, 8, radius);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && colony.contains(e.blockPosition()) && treeLitter(e.getItem()))) {
            ItemStack stack = drop.getItem();
            int left = ColonyStorage.insert(level.getServer(), colony.colonyId(), stack.copy(),
                    WorkSupport.slots(level, colony));
            if (left <= 0) {
                drop.discard();
            } else if (left < stack.getCount()) {
                drop.setItem(stack.copyWithCount(left));
            }
        }
    }

    private static boolean treeLitter(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.APPLE));
    }
}
