package za.co.neroland.nerocolonies.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.DataResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyStage;

/**
 * Covers the pure geometry of {@link Blueprint}: where a cell lands under each rotation of a
 * non-square footprint, that every cell stays inside the turned footprint and none collide, that Y
 * is untouched, that quarter-turns compose (four make a full turn) and that width and depth swap
 * under a quarter-turn. Also covers {@link Blueprint#grid}, which pads and validates a palette and
 * layer list without touching any registry. Block lookups ({@code stateAt}, {@code buildOrder},
 * {@code blockCount}) need the game's registries and are not covered here.
 */
class BlueprintRotateTest {

    /** A deliberately non-square footprint: 5 wide (X) by 3 deep (Z). */
    private static final int WIDTH = 5;
    private static final int DEPTH = 3;

    private static final Rotation[] ALL = {
        Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
    };

    private static boolean quarterTurn(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    private static BlockPos turn(int x, int y, int z, Rotation rotation) {
        return Blueprint.rotate(new BlockPos(x, y, z), WIDTH, DEPTH, rotation);
    }

    /** A blueprint with a footprint and nothing else; no palette, so no registry is touched. */
    private static Blueprint sized(int width, int depth) {
        return new Blueprint(Blueprint.UNNAMED, "", Blueprint.Category.OTHER, 0, 0, Optional.empty(), Map.of(),
                List.of(), List.of(), width, depth, 1, ColonyStage.SETTLED, Optional.empty(), 1, List.of(),
                Blueprint.Capacity.NONE, List.of(), Optional.empty(), true);
    }

    // --- rotate ---------------------------------------------------------------------

    @Test
    @DisplayName("No rotation leaves every cell where it is")
    void noneIsIdentity() {
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                assertEquals(new BlockPos(x, 2, z), turn(x, 2, z, Rotation.NONE));
            }
        }
    }

    @Test
    @DisplayName("Clockwise 90: the four corners")
    void clockwiseCorners() {
        assertEquals(new BlockPos(2, 0, 0), turn(0, 0, 0, Rotation.CLOCKWISE_90));
        assertEquals(new BlockPos(2, 0, 4), turn(4, 0, 0, Rotation.CLOCKWISE_90));
        assertEquals(new BlockPos(0, 0, 0), turn(0, 0, 2, Rotation.CLOCKWISE_90));
        assertEquals(new BlockPos(0, 0, 4), turn(4, 0, 2, Rotation.CLOCKWISE_90));
    }

    @Test
    @DisplayName("180: the four corners swap with their opposites")
    void halfTurnCorners() {
        assertEquals(new BlockPos(4, 0, 2), turn(0, 0, 0, Rotation.CLOCKWISE_180));
        assertEquals(new BlockPos(0, 0, 2), turn(4, 0, 0, Rotation.CLOCKWISE_180));
        assertEquals(new BlockPos(4, 0, 0), turn(0, 0, 2, Rotation.CLOCKWISE_180));
        assertEquals(new BlockPos(0, 0, 0), turn(4, 0, 2, Rotation.CLOCKWISE_180));
    }

    @Test
    @DisplayName("Counter-clockwise 90: the four corners")
    void counterClockwiseCorners() {
        assertEquals(new BlockPos(0, 0, 4), turn(0, 0, 0, Rotation.COUNTERCLOCKWISE_90));
        assertEquals(new BlockPos(0, 0, 0), turn(4, 0, 0, Rotation.COUNTERCLOCKWISE_90));
        assertEquals(new BlockPos(2, 0, 4), turn(0, 0, 2, Rotation.COUNTERCLOCKWISE_90));
        assertEquals(new BlockPos(2, 0, 0), turn(4, 0, 2, Rotation.COUNTERCLOCKWISE_90));
    }

    @Test
    @DisplayName("Every cell lands inside the turned footprint and no two cells collide")
    void staysInsideAndIsOneToOne() {
        for (Rotation rotation : ALL) {
            int turnedWidth = quarterTurn(rotation) ? DEPTH : WIDTH;
            int turnedDepth = quarterTurn(rotation) ? WIDTH : DEPTH;
            Set<BlockPos> seen = new HashSet<>();
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    BlockPos out = turn(x, 0, z, rotation);
                    assertTrue(out.getX() >= 0 && out.getX() < turnedWidth, rotation + " X of " + out);
                    assertTrue(out.getZ() >= 0 && out.getZ() < turnedDepth, rotation + " Z of " + out);
                    assertTrue(seen.add(out), rotation + " maps two cells to " + out);
                }
            }
            assertEquals(WIDTH * DEPTH, seen.size());
        }
    }

    @Test
    @DisplayName("Y is never touched")
    void yIsUntouched() {
        for (Rotation rotation : ALL) {
            for (int y : new int[] {-3, 0, 1, 63}) {
                assertEquals(y, turn(1, y, 2, rotation).getY(), rotation + " at y " + y);
            }
        }
    }

    @Test
    @DisplayName("Four quarter-turns bring every cell back, swapping width and depth each time")
    void fourQuarterTurnsAreIdentity() {
        for (Rotation quarter : new Rotation[] {Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90}) {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    BlockPos start = new BlockPos(x, 5, z);
                    BlockPos cell = start;
                    int width = WIDTH;
                    int depth = DEPTH;
                    for (int i = 0; i < 4; i++) {
                        cell = Blueprint.rotate(cell, width, depth, quarter);
                        int swap = width;
                        width = depth;
                        depth = swap;
                    }
                    assertEquals(start, cell, quarter + " from " + start);
                }
            }
        }
    }

    @Test
    @DisplayName("Two clockwise quarter-turns are a half-turn; three are a counter-clockwise quarter-turn")
    void quarterTurnsCompose() {
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                BlockPos start = new BlockPos(x, 0, z);
                BlockPos once = Blueprint.rotate(start, WIDTH, DEPTH, Rotation.CLOCKWISE_90);
                BlockPos twice = Blueprint.rotate(once, DEPTH, WIDTH, Rotation.CLOCKWISE_90);
                BlockPos thrice = Blueprint.rotate(twice, WIDTH, DEPTH, Rotation.CLOCKWISE_90);
                assertEquals(Blueprint.rotate(start, WIDTH, DEPTH, Rotation.CLOCKWISE_180), twice);
                assertEquals(Blueprint.rotate(start, WIDTH, DEPTH, Rotation.COUNTERCLOCKWISE_90), thrice);
            }
        }
    }

    @Test
    @DisplayName("A turn is undone by its opposite; a half-turn undoes itself")
    void oppositeTurnsCancel() {
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                BlockPos start = new BlockPos(x, 0, z);
                BlockPos clockwise = Blueprint.rotate(start, WIDTH, DEPTH, Rotation.CLOCKWISE_90);
                assertEquals(start, Blueprint.rotate(clockwise, DEPTH, WIDTH, Rotation.COUNTERCLOCKWISE_90));
                BlockPos half = Blueprint.rotate(start, WIDTH, DEPTH, Rotation.CLOCKWISE_180);
                assertEquals(start, Blueprint.rotate(half, WIDTH, DEPTH, Rotation.CLOCKWISE_180));
            }
        }
    }

    @Test
    @DisplayName("The front (south, +Z) row turns to the west, north and east edges")
    void frontRowFollowsTheRotation() {
        for (int x = 0; x < WIDTH; x++) {
            // Unrotated, the front is the last row.
            assertEquals(DEPTH - 1, turn(x, 0, DEPTH - 1, Rotation.NONE).getZ());
            // Clockwise: south turns to west, the low-X edge.
            assertEquals(0, turn(x, 0, DEPTH - 1, Rotation.CLOCKWISE_90).getX());
            // Half-turn: south turns to north, the low-Z edge.
            assertEquals(0, turn(x, 0, DEPTH - 1, Rotation.CLOCKWISE_180).getZ());
            // Counter-clockwise: south turns to east, the high-X edge of a footprint now DEPTH wide.
            assertEquals(DEPTH - 1, turn(x, 0, DEPTH - 1, Rotation.COUNTERCLOCKWISE_90).getX());
        }
    }

    @Test
    @DisplayName("A blueprint's width and depth swap under a quarter-turn, and its rotate uses its own size")
    void blueprintFootprintSwaps() {
        Blueprint blueprint = sized(WIDTH, DEPTH);
        assertEquals(WIDTH, blueprint.width(Rotation.NONE));
        assertEquals(DEPTH, blueprint.depth(Rotation.NONE));
        assertEquals(WIDTH, blueprint.width(Rotation.CLOCKWISE_180));
        assertEquals(DEPTH, blueprint.depth(Rotation.CLOCKWISE_180));
        assertEquals(DEPTH, blueprint.width(Rotation.CLOCKWISE_90));
        assertEquals(WIDTH, blueprint.depth(Rotation.CLOCKWISE_90));
        assertEquals(DEPTH, blueprint.width(Rotation.COUNTERCLOCKWISE_90));
        assertEquals(WIDTH, blueprint.depth(Rotation.COUNTERCLOCKWISE_90));
        BlockPos cell = new BlockPos(3, 1, 2);
        for (Rotation rotation : ALL) {
            assertEquals(Blueprint.rotate(cell, WIDTH, DEPTH, rotation), blueprint.rotate(cell, rotation));
        }
    }

    // --- grid -----------------------------------------------------------------------

    @Test
    @DisplayName("A grid takes its size from the tallest, deepest and widest parts and pads the rest")
    void gridPadsToARectangle() {
        Map<Character, String> palette = Map.of('#', "minecraft:stone");
        Blueprint.Grid grid = Blueprint.grid(palette, List.of(List.of("###", "#"), List.of("##"))).getOrThrow();
        assertEquals(3, grid.width());
        assertEquals(2, grid.depth());
        assertEquals(2, grid.height());
        assertEquals(List.of("###", "#  ", "## ", "   "), grid.layout());
        assertEquals(palette, grid.palette());
    }

    @Test
    @DisplayName("Every grid row is exactly the grid's width, one per layer and depth")
    void gridRowsAreUniform() {
        Blueprint.Grid grid = Blueprint.grid(Map.of(),
                List.of(List.of("a", "bcd", ""), List.of("ef"), List.of("", "", "", "g"))).getOrThrow();
        assertEquals(grid.height() * grid.depth(), grid.layout().size());
        for (String row : grid.layout()) {
            assertEquals(grid.width(), row.length());
        }
        assertEquals(3, grid.width());
        assertEquals(4, grid.depth());
        assertEquals(3, grid.height());
    }

    @Test
    @DisplayName("A grid with no layers or no cells is an error")
    void gridRejectsEmpty() {
        assertTrue(Blueprint.grid(Map.of(), List.of()).result().isEmpty());
        assertTrue(Blueprint.grid(Map.of(), List.of(List.of())).result().isEmpty());
        assertTrue(Blueprint.grid(Map.of(), List.of(List.of(""))).result().isEmpty());
    }

    @Test
    @DisplayName("A grid at the format ceilings is accepted; one past them is an error")
    void gridCeilings() {
        String widest = "#".repeat(Blueprint.MAX_EXTENT);
        DataResult<Blueprint.Grid> atWidth = Blueprint.grid(Map.of(), List.of(List.of(widest)));
        assertEquals(Blueprint.MAX_EXTENT, atWidth.getOrThrow().width());
        assertTrue(Blueprint.grid(Map.of(), List.of(List.of(widest + "#"))).result().isEmpty());

        List<String> deepest = Collections.nCopies(Blueprint.MAX_EXTENT, "#");
        assertEquals(Blueprint.MAX_EXTENT, Blueprint.grid(Map.of(), List.of(deepest)).getOrThrow().depth());
        List<String> tooDeep = Collections.nCopies(Blueprint.MAX_EXTENT + 1, "#");
        assertTrue(Blueprint.grid(Map.of(), List.of(tooDeep)).result().isEmpty());

        List<List<String>> tallest = Collections.nCopies(Blueprint.MAX_HEIGHT, List.of("#"));
        assertEquals(Blueprint.MAX_HEIGHT, Blueprint.grid(Map.of(), tallest).getOrThrow().height());
        List<List<String>> tooTall = Collections.nCopies(Blueprint.MAX_HEIGHT + 1, List.of("#"));
        assertTrue(Blueprint.grid(Map.of(), tooTall).result().isEmpty());
    }
}
