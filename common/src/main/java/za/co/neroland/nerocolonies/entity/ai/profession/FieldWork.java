package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Farmer: keeps a real field. It harvests ripe crops (around its workplace, which is where the
 * farm buildings keep their farmland, and in its own field) and replants them, sows bare farmland
 * from the colony's seeds, and lays out and keeps up a 9 x 9 field of its own near the workplace: a
 * water source in the middle when there is none, and grass or dirt tilled to farmland. Only the
 * harvest puts goods in storage, so only the harvest counts as productive work.
 */
final class FieldWork {

    private static final List<int[]> CELLS = WorkGeometry.fieldCells(WorkGeometry.FIELD_HALF);

    /** Field centres tried, nearest the workplace first, from just clear of it out to 14 blocks. */
    private static final List<int[]> SITES = WorkGeometry.ringOffsets(WorkGeometry.FIELD_HALF + 2, 14);

    private static final int SITES_PER_CALL = 3;

    /** Ticks to wait after a full search found no field. */
    private static final long RETRY_TICKS = 1200L;

    private FieldWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        WorkMemory memory = neran.workMemory();
        int radius = profession.workRadius();
        long now = level.getGameTime();
        BlockPos field = memory.field;

        // 1. Harvest: the field first, then the farm buildings' farmland around the workplace.
        BlockPos ripe = field == null ? null : scanField(level, colony, field, FieldWork::ripe);
        if (ripe == null) {
            ripe = nearest(level, colony, anchor, radius, 1, 1, (pos, state) -> ripe(pos, state));
        }
        if (ripe != null) {
            if (!WorkSupport.reach(neran, ripe)) {
                return false;
            }
            return harvest(level, neran, colony, ripe);
        }

        // 2. Sow bare farmland from stored seeds.
        List<String> seeds = storedSeeds(level.getServer(), colony);
        if (!seeds.isEmpty()) {
            BlockPos self = neran.blockPosition();
            BiPredicate<BlockPos, BlockState> bare = (pos, state) -> state.isAir() && !pos.equals(self)
                    && level.getBlockState(pos.below()).is(Blocks.FARMLAND);
            BlockPos plot = field == null ? null : scanField(level, colony, field, bare);
            if (plot == null) {
                plot = nearest(level, colony, anchor, radius, 1, 1, bare);
            }
            if (plot != null) {
                if (!WorkSupport.reach(neran, plot)) {
                    return false;
                }
                return sow(level, neran, colony, plot, seeds);
            }
        }

        // 3. Keep up the field.
        if (field == null || WorkSupport.builtUp(level, colony, field, 0)) {
            memory.field = null;
            if (now >= memory.fieldRetryAt) {
                memory.field = searchField(level, colony, anchor, memory, now);
            }
            return WorkSupport.atPost(neran, anchor, radius);
        }
        if (!hydrated(level, field)) {
            BlockPos water = field;
            if (mayWater(level, colony, water)) {
                if (!WorkSupport.reach(neran, water)) {
                    return false;
                }
                WorkSupport.strike(neran, water);
                Construction.breakToStorage(level, colony, water, level.getBlockState(water));
                level.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                return true;
            }
        }
        BlockPos till = nextTillable(level, colony, field, memory);
        if (till != null) {
            if (!WorkSupport.reach(neran, till.above())) {
                return false;
            }
            WorkSupport.strike(neran, till);
            BlockState above = level.getBlockState(till.above());
            if (!above.isAir()) {
                Construction.breakToStorage(level, colony, till.above(), above);
            }
            level.setBlock(till, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        return WorkSupport.atPost(neran, field, WorkGeometry.FIELD_HALF + 3)
                || WorkSupport.atPost(neran, anchor, radius);
    }

    // --- harvest and sow --------------------------------------------------------

    private static boolean ripe(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private static boolean harvest(ServerLevel level, ColonistEntity neran, Colony colony, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)
                || !WorkSupport.mayBreak(level, colony, pos, state)) {
            return false;
        }
        WorkSupport.strike(neran, pos);
        Construction.breakToStorage(level, colony, pos, state);
        neran.workMemory().markProductive(level.getGameTime());
        // Replant from the seed the harvest just put in storage.
        String seed = TradeRecipes.seedFor(BuiltInRegistries.BLOCK.getKey(crop).getPath());
        if (seed != null && WorkSupport.item(seed) != null && level.getBlockState(pos).isAir()
                && WorkSupport.mayPlace(level, colony, pos)
                && ColonyStorage.consume(level.getServer(), colony.colonyId(), List.of(WorkSupport.target(seed, 1)))) {
            level.setBlock(pos, crop.getStateForAge(0), Block.UPDATE_ALL);
        }
        return true;
    }

    /** Seed items in storage whose crop exists, in {@link TradeRecipes#SEEDS} order. */
    private static List<String> storedSeeds(MinecraftServer server, Colony colony) {
        List<String> out = new ArrayList<>();
        for (TradeRecipes.Part seed : TradeRecipes.SEEDS) {
            if (cropBlock(seed.item()) != null && WorkSupport.stored(server, colony, seed.item()) > 0) {
                out.add(seed.item());
            }
        }
        return out;
    }

    @Nullable
    private static Block cropBlock(String seed) {
        String crop = TradeRecipes.cropFor(seed);
        if (crop == null) {
            return null;
        }
        Identifier id = Identifier.withDefaultNamespace(crop);
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.getValue(id) : null;
    }

    private static boolean sow(ServerLevel level, ColonistEntity neran, Colony colony, BlockPos pos,
            List<String> seeds) {
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).is(Blocks.FARMLAND)
                || !WorkSupport.mayPlace(level, colony, pos)) {
            return false;
        }
        String seed = seeds.get(WorkGeometry.seedIndex(pos.getX(), pos.getZ(), seeds.size()));
        Block crop = cropBlock(seed);
        if (crop == null || !ColonyStorage.consume(level.getServer(), colony.colonyId(),
                List.of(WorkSupport.target(seed, 1)))) {
            return false;
        }
        WorkSupport.strike(neran, pos);
        level.setBlock(pos, crop.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    // --- the field ----------------------------------------------------------------

    /** Grass, dirt or a path: ground a hoe turns to farmland. */
    private static boolean tillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.DIRT_PATH);
    }

    /** Air, or a plant that gives way (grass, flowers): what may stand on a field cell. */
    private static boolean clearAbove(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty()
                && !state.is(Construction.PROTECTED) && !state.hasBlockEntity());
    }

    /** The first field cell (crop level) matching {@code test}, or null. */
    @Nullable
    private static BlockPos scanField(ServerLevel level, Colony colony, BlockPos centre,
            BiPredicate<BlockPos, BlockState> test) {
        for (int[] cell : CELLS) {
            BlockPos pos = centre.offset(cell[0], 1, cell[1]);
            if (WorkSupport.inClaim(level, colony, pos) && test.test(pos, level.getBlockState(pos))) {
                return pos;
            }
        }
        return null;
    }

    /** Whether water lies within hydration range of the field's cells (at ground level). */
    private static boolean hydrated(ServerLevel level, BlockPos centre) {
        int half = WorkGeometry.FIELD_HALF;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                cursor.set(centre.getX() + dx, centre.getY(), centre.getZ() + dz);
                if (level.isLoaded(cursor) && level.getFluidState(cursor).is(FluidTags.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a water source may go in the field's centre without spilling. */
    private static boolean mayWater(ServerLevel level, Colony colony, BlockPos pos) {
        BlockState ground = level.getBlockState(pos);
        if (!(tillable(ground) || ground.is(Blocks.FARMLAND)) || !WorkSupport.mayBreak(level, colony, pos, ground)
                || !WorkSupport.mayPlace(level, colony, pos) || WorkSupport.builtUp(level, colony, pos, 0)
                || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (below.canBeReplaced() || !below.getFluidState().isEmpty()) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = pos.relative(side);
            BlockState state = level.getBlockState(next);
            if (!WorkSupport.inClaim(level, colony, next) || state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The next ground cell of the field to till, sweeping on from where the last one was. */
    @Nullable
    private static BlockPos nextTillable(ServerLevel level, Colony colony, BlockPos centre, WorkMemory memory) {
        int size = CELLS.size();
        for (int i = 0; i < size; i++) {
            int index = (memory.fieldCellCursor + i) % size;
            int[] cell = CELLS.get(index);
            BlockPos ground = centre.offset(cell[0], 0, cell[1]);
            if (!WorkSupport.inClaim(level, colony, ground) || !WorkSupport.inClaim(level, colony, ground.above())) {
                continue;
            }
            BlockState state = level.getBlockState(ground);
            if (!tillable(state) || !clearAbove(level.getBlockState(ground.above()))
                    || !WorkSupport.mayBreak(level, colony, ground, state)
                    || WorkSupport.occupied(level, ground) || WorkSupport.builtUp(level, colony, ground, 0)) {
                continue;
            }
            memory.fieldCellCursor = index;
            return ground;
        }
        return null;
    }

    /** Tries a few field sites; returns the centre (at ground level) of the first good one, or null. */
    @Nullable
    private static BlockPos searchField(ServerLevel level, Colony colony, BlockPos anchor, WorkMemory memory,
            long now) {
        for (int n = 0; n < SITES_PER_CALL; n++) {
            if (memory.fieldSearchCursor >= SITES.size()) {
                memory.fieldSearchCursor = 0;
                memory.fieldRetryAt = now + RETRY_TICKS;
                return null;
            }
            int[] offset = SITES.get(memory.fieldSearchCursor++);
            BlockPos centre = anchor.offset(offset[0], -1, offset[1]);
            if (goodField(level, colony, centre)) {
                memory.fieldSearchCursor = 0;
                memory.fieldCellCursor = 0;
                return centre.immutable();
            }
        }
        return null;
    }

    private static boolean goodField(ServerLevel level, Colony colony, BlockPos centre) {
        int half = WorkGeometry.FIELD_HALF;
        int workable = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos ground = centre.offset(dx, 0, dz);
                if (!WorkSupport.inClaim(level, colony, ground) || !WorkSupport.inClaim(level, colony, ground.above())) {
                    return false;
                }
                // Every other cell is enough to catch any building: none is narrower than two blocks.
                if ((dx % 2 == 0 && dz % 2 == 0) && WorkSupport.builtUp(level, colony, ground, 1)) {
                    return false;
                }
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockState state = level.getBlockState(ground);
                if ((tillable(state) || state.is(Blocks.FARMLAND)) && clearAbove(level.getBlockState(ground.above()))
                        && !state.is(Construction.PROTECTED)) {
                    workable++;
                }
            }
        }
        return WorkGeometry.acceptField(workable, CELLS.size());
    }

    // --- shared scan ----------------------------------------------------------------

    /** The nearest position around {@code centre} matching {@code test}, scanning a bounded cube. */
    @Nullable
    static BlockPos nearest(ServerLevel level, Colony colony, BlockPos centre, int radius, int below, int above,
            BiPredicate<BlockPos, BlockState> test) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -below; dy <= above; dy++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!WorkSupport.inClaim(level, colony, cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (test.test(cursor, state)) {
                        double distance = cursor.distSqr(centre);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }
}
