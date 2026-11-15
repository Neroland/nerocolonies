package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/**
 * The pure geometry behind the working trades: where a farmer lays out its field, the order a miner
 * digs its quarry in, which logs make one tree, and the order sites are searched in. Nothing here
 * touches a level, so all of it is unit-tested; the behaviours apply the land rules on top.
 */
public final class WorkGeometry {

    private WorkGeometry() {
    }

    // --- search order -----------------------------------------------------------

    /**
     * Every horizontal offset whose ring (Chebyshev distance) lies in {@code [minRing, maxRing]},
     * nearest ring first; within a ring by Euclidean distance, then x, then z. Deterministic, so the
     * same workplace always finds the same field, quarry or tree first.
     */
    public static List<int[]> ringOffsets(int minRing, int maxRing) {
        List<int[]> out = new ArrayList<>();
        int lo = Math.max(0, minRing);
        for (int dx = -maxRing; dx <= maxRing; dx++) {
            for (int dz = -maxRing; dz <= maxRing; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring >= lo && ring <= maxRing) {
                    out.add(new int[] {dx, dz});
                }
            }
        }
        out.sort(Comparator.<int[]>comparingInt(o -> Math.max(Math.abs(o[0]), Math.abs(o[1])))
                .thenComparingInt(o -> o[0] * o[0] + o[1] * o[1])
                .thenComparingInt(o -> o[0])
                .thenComparingInt(o -> o[1]));
        return out;
    }

    // --- the farmer's field -------------------------------------------------------

    /** Half the side of a field: fields are {@code 2 * FIELD_HALF + 1} square (9 x 9). */
    public static final int FIELD_HALF = 4;

    /** Share of a field's cells that must be workable ground for the site to be taken. */
    public static final double FIELD_MIN_SHARE = 0.6D;

    /** Farmland within this many blocks (horizontally) of water stays moist. */
    public static final int HYDRATION_RANGE = 4;

    /**
     * The cells of a field around its centre, as offsets, without the centre (which holds the water).
     * Row by row, so tilling and sowing sweep the field visibly.
     */
    public static List<int[]> fieldCells(int half) {
        List<int[]> out = new ArrayList<>();
        for (int dz = -half; dz <= half; dz++) {
            for (int dx = -half; dx <= half; dx++) {
                if (dx != 0 || dz != 0) {
                    out.add(new int[] {dx, dz});
                }
            }
        }
        return out;
    }

    /** Whether a site with {@code workable} of {@code total} usable cells is good enough for a field. */
    public static boolean acceptField(int workable, int total) {
        return total > 0 && workable >= Math.ceil(total * FIELD_MIN_SHARE);
    }

    /** Whether a cell at this offset from a water source is kept moist by it. */
    public static boolean hydratedBy(int dx, int dz) {
        return Math.abs(dx) <= HYDRATION_RANGE && Math.abs(dz) <= HYDRATION_RANGE;
    }

    /**
     * Which of {@code available} seed kinds to sow at a cell: a stable choice per cell so a field with
     * several seeds in store grows in strips rather than flickering between kinds.
     */
    public static int seedIndex(int x, int z, int available) {
        if (available <= 1) {
            return 0;
        }
        return Math.floorMod(Math.floorDiv(x, 3) * 31 + z, available);
    }

    // --- the miner's quarry -------------------------------------------------------

    /** Side of the quarry's square footprint. */
    public static final int QUARRY_SIDE = 5;

    /** Deepest layer of a quarry. */
    public static final int QUARRY_DEPTH = 16;

    /** Number of cells on a quarry's rim. */
    public static int rimLength(int side) {
        return side <= 1 ? 1 : 4 * (side - 1);
    }

    /**
     * The index of a footprint cell along the rim, walking from the corner (0,0) along +x, then +z,
     * then -x, then -z; or -1 for an interior cell. Consecutive indices are always side by side.
     */
    public static int rimIndex(int dx, int dz, int side) {
        int last = side - 1;
        if (dx < 0 || dz < 0 || dx > last || dz > last) {
            return -1;
        }
        if (dz == 0) {
            return dx;
        }
        if (dx == last) {
            return last + dz;
        }
        if (dz == last) {
            return 2 * last + (last - dx);
        }
        if (dx == 0) {
            return 3 * last + (last - dz);
        }
        return -1;
    }

    /**
     * How many layers are dug out of a footprint cell. The interior goes the full depth; the rim is a
     * spiral stair, one layer deeper per step, so a Neran can walk down to the floor and back out.
     */
    public static int digDepth(int dx, int dz, int side, int depth) {
        int rim = rimIndex(dx, dz, side);
        if (rim < 0) {
            return depth;
        }
        return Math.min(rim + 1, depth);
    }

    /**
     * Every cell of a quarry in the order it is dug, as {@code {dx, layer, dz}}: top layer first, and
     * within a layer row by row. A cell is in layer {@code L} if its dig depth is greater than L.
     */
    public static List<int[]> quarryOrder(int side, int depth) {
        List<int[]> out = new ArrayList<>();
        for (int layer = 0; layer < depth; layer++) {
            for (int dz = 0; dz < side; dz++) {
                for (int dx = 0; dx < side; dx++) {
                    if (digDepth(dx, dz, side, depth) > layer) {
                        out.add(new int[] {dx, layer, dz});
                    }
                }
            }
        }
        return out;
    }

    // --- the forester's tree ------------------------------------------------------

    /** Most logs one tree may have; a bigger connected mass is somebody's build, not a tree. */
    public static final int TREE_CAP = 64;

    /**
     * The logs connected to {@code start} (all 26 neighbours, so branches count), breadth first, at
     * most {@code cap}. Returns {@code null} when the mass is bigger than the cap: that is not a tree.
     * The result is ordered lowest first, then nearest the start, so the trunk goes before the crown.
     */
    public static List<BlockPos> collectTree(BlockPos start, Predicate<BlockPos> isLog, int cap) {
        if (!isLog.test(start)) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> out = new ArrayList<>();
        queue.add(start.immutable());
        seen.add(start.asLong());
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            out.add(pos);
            if (out.size() > cap) {
                return null;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (seen.add(next.asLong()) && isLog.test(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.distSqr(start))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return out;
    }

    /** The sapling a log grows from, by item path ({@code oak_log -> oak_sapling}), or null. */
    public static String saplingFor(String logPath) {
        String base;
        if (logPath.endsWith("_log")) {
            base = logPath.substring(0, logPath.length() - "_log".length());
        } else if (logPath.endsWith("_wood")) {
            base = logPath.substring(0, logPath.length() - "_wood".length());
        } else {
            return null;
        }
        if (base.startsWith("stripped_")) {
            base = base.substring("stripped_".length());
        }
        if (base.equals("mangrove")) {
            return "mangrove_propagule";
        }
        return base.isEmpty() ? null : base + "_sapling";
    }
}
