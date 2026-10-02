package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.content.Blueprint;

/**
 * Covers {@link Construction#faceBeacon}: the rotation that turns a blueprint's front (+Z, south)
 * towards the beacon for a site offset {@code (dx, dz)} from it. Checks the four cardinal offsets,
 * every quadrant with either axis dominant, diagonal ties (Z wins), the beacon's own cell, that only
 * the direction matters and not the distance, and that the chosen rotation really puts the front row
 * on the beacon's side of the footprint. Site search and placement need a world and are not covered.
 */
class ConstructionFaceBeaconTest {

    private static Rotation opposite(Rotation rotation) {
        return switch (rotation) {
            case NONE -> Rotation.CLOCKWISE_180;
            case CLOCKWISE_180 -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
        };
    }

    @Test
    @DisplayName("North of the beacon the front already faces it; south of it the building turns round")
    void northAndSouth() {
        assertEquals(Rotation.NONE, Construction.faceBeacon(0, -1));
        assertEquals(Rotation.NONE, Construction.faceBeacon(0, -9));
        assertEquals(Rotation.CLOCKWISE_180, Construction.faceBeacon(0, 1));
        assertEquals(Rotation.CLOCKWISE_180, Construction.faceBeacon(0, 9));
    }

    @Test
    @DisplayName("East of the beacon the front turns west; west of it, east")
    void eastAndWest() {
        assertEquals(Rotation.CLOCKWISE_90, Construction.faceBeacon(1, 0));
        assertEquals(Rotation.CLOCKWISE_90, Construction.faceBeacon(9, 0));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, Construction.faceBeacon(-1, 0));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, Construction.faceBeacon(-9, 0));
    }

    @Test
    @DisplayName("In every quadrant the larger offset decides")
    void quadrantsFollowTheDominantAxis() {
        // South-east.
        assertEquals(Rotation.CLOCKWISE_180, Construction.faceBeacon(2, 5));
        assertEquals(Rotation.CLOCKWISE_90, Construction.faceBeacon(5, 2));
        // South-west.
        assertEquals(Rotation.CLOCKWISE_180, Construction.faceBeacon(-2, 5));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, Construction.faceBeacon(-5, 2));
        // North-east.
        assertEquals(Rotation.NONE, Construction.faceBeacon(2, -5));
        assertEquals(Rotation.CLOCKWISE_90, Construction.faceBeacon(5, -2));
        // North-west.
        assertEquals(Rotation.NONE, Construction.faceBeacon(-2, -5));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, Construction.faceBeacon(-5, -2));
    }

    @Test
    @DisplayName("On an exact diagonal the Z offset wins")
    void tiesGoToZ() {
        assertEquals(Rotation.CLOCKWISE_180, Construction.faceBeacon(3, 3));
        assertEquals(Rotation.CLOCKWISE_180, Construction.faceBeacon(-3, 3));
        assertEquals(Rotation.NONE, Construction.faceBeacon(3, -3));
        assertEquals(Rotation.NONE, Construction.faceBeacon(-3, -3));
    }

    @Test
    @DisplayName("On the beacon's own cell there is nothing to face: no rotation")
    void origin() {
        assertEquals(Rotation.NONE, Construction.faceBeacon(0, 0));
    }

    @Test
    @DisplayName("Only the direction matters, not the distance")
    void scaleInvariant() {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                Rotation near = Construction.faceBeacon(dx, dz);
                for (int scale : new int[] {2, 7, 100}) {
                    assertEquals(near, Construction.faceBeacon(dx * scale, dz * scale),
                            "(" + dx + ", " + dz + ") x " + scale);
                }
            }
        }
    }

    @Test
    @DisplayName("The site on the opposite side of the beacon faces the opposite way")
    void oppositeSitesFaceEachOther() {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                assertEquals(opposite(Construction.faceBeacon(dx, dz)), Construction.faceBeacon(-dx, -dz),
                        "(" + dx + ", " + dz + ")");
            }
        }
    }

    @Test
    @DisplayName("The chosen rotation puts the blueprint's front row on the beacon's side")
    void frontRowEndsUpNearestTheBeacon() {
        int width = 5;
        int depth = 3;
        BlockPos front = new BlockPos(2, 0, depth - 1);

        // Site north of the beacon: the beacon is to the south, the high-Z edge.
        BlockPos north = Blueprint.rotate(front, width, depth, Construction.faceBeacon(0, -6));
        assertEquals(depth - 1, north.getZ());
        // Site south of the beacon: the beacon is to the north, the low-Z edge.
        BlockPos south = Blueprint.rotate(front, width, depth, Construction.faceBeacon(0, 6));
        assertEquals(0, south.getZ());
        // Site east of the beacon: the beacon is to the west, the low-X edge.
        BlockPos east = Blueprint.rotate(front, width, depth, Construction.faceBeacon(6, 0));
        assertEquals(0, east.getX());
        // Site west of the beacon: the beacon is to the east, the high-X edge (the footprint is now `depth` wide).
        BlockPos west = Blueprint.rotate(front, width, depth, Construction.faceBeacon(-6, 0));
        assertEquals(depth - 1, west.getX());
    }
}
