package za.co.neroland.nerocolonies.entity.ai.profession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pure geometry of the working trades: search order, fields, quarries and trees. */
class WorkGeometryTest {

    // --- search order ---------------------------------------------------------------

    @Test
    @DisplayName("ring offsets cover each ring exactly once, nearest ring first")
    void ringOffsets() {
        List<int[]> offsets = WorkGeometry.ringOffsets(2, 5);
        assertEquals(11 * 11 - 3 * 3, offsets.size());
        Set<Long> seen = new HashSet<>();
        int lastRing = 0;
        for (int[] o : offsets) {
            int ring = Math.max(Math.abs(o[0]), Math.abs(o[1]));
            assertTrue(ring >= 2 && ring <= 5);
            assertTrue(ring >= lastRing, "rings must not go back");
            lastRing = ring;
            assertTrue(seen.add(((long) o[0] << 32) ^ (o[1] & 0xffffffffL)), "no offset twice");
        }
        assertEquals(1, WorkGeometry.ringOffsets(0, 0).size());
    }

    @Test
    @DisplayName("ring order is deterministic")
    void ringOrderStable() {
        List<int[]> a = WorkGeometry.ringOffsets(0, 6);
        List<int[]> b = WorkGeometry.ringOffsets(0, 6);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i)[0], b.get(i)[0]);
            assertEquals(a.get(i)[1], b.get(i)[1]);
        }
    }

    // --- fields -----------------------------------------------------------------------

    @Test
    @DisplayName("a 9x9 field has 80 cells around its water, all kept moist by it")
    void fieldCells() {
        List<int[]> cells = WorkGeometry.fieldCells(WorkGeometry.FIELD_HALF);
        assertEquals(80, cells.size());
        for (int[] c : cells) {
            assertFalse(c[0] == 0 && c[1] == 0, "the centre is the water");
            assertTrue(WorkGeometry.hydratedBy(c[0], c[1]));
        }
        assertFalse(WorkGeometry.hydratedBy(5, 0));
    }

    @Test
    @DisplayName("a site needs 60% workable ground to become a field")
    void acceptField() {
        assertTrue(WorkGeometry.acceptField(48, 80));
        assertFalse(WorkGeometry.acceptField(47, 80));
        assertFalse(WorkGeometry.acceptField(0, 0));
        assertTrue(WorkGeometry.acceptField(15, 25));
        assertFalse(WorkGeometry.acceptField(14, 25));
    }

    @Test
    @DisplayName("seed choice is stable per cell and always in range")
    void seedIndex() {
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                int i = WorkGeometry.seedIndex(x, z, 3);
                assertTrue(i >= 0 && i < 3);
                assertEquals(i, WorkGeometry.seedIndex(x, z, 3));
                assertEquals(0, WorkGeometry.seedIndex(x, z, 1));
            }
        }
    }

    // --- quarries ---------------------------------------------------------------------

    @Test
    @DisplayName("the rim of a 5x5 quarry is 16 cells, each step beside the next")
    void rim() {
        int side = WorkGeometry.QUARRY_SIDE;
        int length = WorkGeometry.rimLength(side);
        assertEquals(16, length);
        int[][] byIndex = new int[length][];
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                int index = WorkGeometry.rimIndex(dx, dz, side);
                boolean interior = dx > 0 && dz > 0 && dx < side - 1 && dz < side - 1;
                assertEquals(interior, index < 0);
                if (index >= 0) {
                    assertNull(byIndex[index], "rim index used twice");
                    byIndex[index] = new int[] {dx, dz};
                }
            }
        }
        for (int i = 1; i < length; i++) {
            assertNotNull(byIndex[i]);
            int step = Math.abs(byIndex[i][0] - byIndex[i - 1][0]) + Math.abs(byIndex[i][1] - byIndex[i - 1][1]);
            assertEquals(1, step, "rim cells " + (i - 1) + " and " + i + " must be side by side");
        }
        assertEquals(-1, WorkGeometry.rimIndex(-1, 0, side));
        assertEquals(-1, WorkGeometry.rimIndex(0, side, side));
    }

    @Test
    @DisplayName("the rim is a stair: each step one layer deeper, down to the floor")
    void stair() {
        int side = WorkGeometry.QUARRY_SIDE;
        int depth = WorkGeometry.QUARRY_DEPTH;
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                int rim = WorkGeometry.rimIndex(dx, dz, side);
                int dig = WorkGeometry.digDepth(dx, dz, side, depth);
                assertEquals(rim < 0 ? depth : Math.min(rim + 1, depth), dig);
                assertTrue(dig >= 1 && dig <= depth);
            }
        }
        // the deepest rim step reaches the floor
        assertEquals(depth, WorkGeometry.digDepth(0, 1, side, depth));
        assertEquals(1, WorkGeometry.digDepth(0, 0, side, depth));
    }

    @Test
    @DisplayName("quarry cells are dug top layer first, each cell once")
    void quarryOrder() {
        int side = WorkGeometry.QUARRY_SIDE;
        int depth = WorkGeometry.QUARRY_DEPTH;
        List<int[]> order = WorkGeometry.quarryOrder(side, depth);
        int expected = 9 * depth;
        for (int i = 0; i < WorkGeometry.rimLength(side); i++) {
            expected += Math.min(i + 1, depth);
        }
        assertEquals(expected, order.size());
        Set<String> seen = new HashSet<>();
        int lastLayer = 0;
        for (int[] cell : order) {
            assertTrue(cell[1] >= lastLayer, "never back up a layer");
            lastLayer = cell[1];
            assertTrue(cell[1] < WorkGeometry.digDepth(cell[0], cell[2], side, depth));
            assertTrue(seen.add(cell[0] + "," + cell[1] + "," + cell[2]));
        }
        // the whole top layer goes first
        for (int i = 0; i < side * side; i++) {
            assertEquals(0, order.get(i)[1]);
        }
    }

    // --- trees ------------------------------------------------------------------------

    @Test
    @DisplayName("a tree is its connected logs, branches included, trunk first")
    void collectTree() {
        Set<BlockPos> logs = new HashSet<>();
        BlockPos base = new BlockPos(10, 64, 10);
        for (int y = 0; y < 6; y++) {
            logs.add(base.above(y));
        }
        logs.add(base.offset(1, 4, 1)); // diagonal branch
        logs.add(base.offset(2, 5, 2));
        logs.add(new BlockPos(30, 64, 30)); // another tree, not connected
        List<BlockPos> tree = WorkGeometry.collectTree(base, logs::contains, WorkGeometry.TREE_CAP);
        assertNotNull(tree);
        assertEquals(8, tree.size());
        assertEquals(base, tree.get(0));
        for (int i = 1; i < tree.size(); i++) {
            assertTrue(tree.get(i).getY() >= tree.get(i - 1).getY(), "lowest first");
        }
        assertFalse(tree.contains(new BlockPos(30, 64, 30)));
    }

    @Test
    @DisplayName("a log mass over the cap is a build, not a tree")
    void overCap() {
        BlockPos base = new BlockPos(0, 64, 0);
        List<BlockPos> tree = WorkGeometry.collectTree(base, p -> p.getX() == 0 && p.getZ() == 0
                && p.getY() >= 64 && p.getY() < 64 + 70, WorkGeometry.TREE_CAP);
        assertNull(tree);
        assertTrue(WorkGeometry.collectTree(base, p -> false, 64).isEmpty());
    }

    @Test
    @DisplayName("logs map to the sapling they grow from")
    void saplings() {
        assertEquals("oak_sapling", WorkGeometry.saplingFor("oak_log"));
        assertEquals("dark_oak_sapling", WorkGeometry.saplingFor("dark_oak_log"));
        assertEquals("birch_sapling", WorkGeometry.saplingFor("stripped_birch_log"));
        assertEquals("mangrove_propagule", WorkGeometry.saplingFor("mangrove_log"));
        assertEquals("spruce_sapling", WorkGeometry.saplingFor("spruce_wood"));
        assertNull(WorkGeometry.saplingFor("crimson_stem"));
        assertNull(WorkGeometry.saplingFor("_log"));
    }
}
