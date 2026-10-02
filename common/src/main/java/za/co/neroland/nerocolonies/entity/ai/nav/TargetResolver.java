package za.co.neroland.nerocolonies.entity.ai.nav;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * Turns "walk to that block" into "walk to a spot you can stand on next to that block".
 *
 * <p>Homes, workstations and the beacon are solid blocks. Pathing to the block itself only works
 * when an open neighbour happens to sit on the side the path finder chose; a diagonal-only or lower
 * neighbour was unreachable and the Neran repathed forever. The resolver picks a standable neighbour
 * instead, in a fixed order of preference, and remembers the choice for that target until it stops
 * being standable (a block was placed there) — the cache invalidates itself on use.
 *
 * <p>{@link #candidates} is pure and unit-tested; the world checks are thin.
 */
public final class TargetResolver {

    @Nullable
    private BlockPos target;

    @Nullable
    private BlockPos resolved;

    private int alternate;

    /** Neighbour offsets in preference order: the four sides at foot level, then raised, then lowered, then diagonals. */
    public static List<BlockPos> candidates(BlockPos target) {
        List<BlockPos> out = new ArrayList<>(24);
        for (int dy : new int[] {0, 1, -1}) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                out.add(target.relative(side).above(dy));
            }
        }
        for (int dy : new int[] {0, 1, -1}) {
            out.add(target.offset(1, dy, 1));
            out.add(target.offset(-1, dy, 1));
            out.add(target.offset(1, dy, -1));
            out.add(target.offset(-1, dy, -1));
        }
        out.add(target.above());
        return out;
    }

    /** Whether a Neran can stand at {@code pos}: a solid top face below and two open blocks. */
    public static boolean standable(BlockGetter level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP) && !floor.isSolid()) {
            return false;
        }
        return open(level.getBlockState(pos)) && open(level.getBlockState(pos.above()));
    }

    private static boolean open(BlockState state) {
        return state.isAir() || !state.isSolid() || state.is(net.minecraft.tags.BlockTags.DOORS);
    }

    /**
     * The standable spot to walk to for {@code target}, or the target itself if none is standable
     * (the path finder then does its best). Cached per target and re-validated on every call.
     */
    public BlockPos resolve(BlockGetter level, BlockPos target) {
        if (!target.equals(this.target)) {
            this.target = target.immutable();
            this.resolved = null;
            this.alternate = 0;
        }
        if (this.resolved != null && standable(level, this.resolved)) {
            return this.resolved;
        }
        this.resolved = pick(level, target, this.alternate);
        return this.resolved != null ? this.resolved : target;
    }

    /**
     * Moves to the next standable neighbour (the stuck detector's "try another side" step).
     *
     * @return whether a different spot was found
     */
    public boolean nextAlternate(BlockGetter level) {
        if (this.target == null) {
            return false;
        }
        BlockPos before = this.resolved;
        this.alternate++;
        this.resolved = pick(level, this.target, this.alternate);
        return this.resolved != null && !this.resolved.equals(before);
    }

    /** Forgets the cached choice (a block changed, or the target did). */
    public void invalidate() {
        this.resolved = null;
    }

    @Nullable
    private static BlockPos pick(BlockGetter level, BlockPos target, int skip) {
        List<BlockPos> standable = new ArrayList<>();
        for (BlockPos candidate : candidates(target)) {
            if (standable(level, candidate)) {
                standable.add(candidate);
            }
        }
        if (standable.isEmpty()) {
            return null;
        }
        return standable.get(Math.floorMod(skip, standable.size())).immutable();
    }
}
