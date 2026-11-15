package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure halves of the storage rework: how much storage a colony's depots, crates and
 * capacity modules give it ({@link ColonyStorage#usableSlots(int, int)}), and how a building's
 * materials are drawn from storage as its blocks are laid ({@link Construction#remainingMaterials(int,
 * int, int)}). The SavedData and the world-facing halves need a running server.
 */
class ColonyStorageCapacityTest {

    @Test
    @DisplayName("No depot or crate means no storage, whatever modules the beacon has")
    void noBoxNoStorage() {
        assertEquals(0, ColonyStorage.usableSlots(0, 0));
        assertEquals(0, ColonyStorage.usableSlots(0, 4));
    }

    @Test
    @DisplayName("Boxes add their slots and modules add nine each")
    void boxesAndModules() {
        assertEquals(ColonyStorageBlocks.DEPOT_SLOTS, ColonyStorage.usableSlots(ColonyStorageBlocks.DEPOT_SLOTS, 0));
        assertEquals(27 + 18, ColonyStorage.usableSlots(ColonyStorageBlocks.CRATE_SLOTS, 2));
        assertEquals(27 * 2 + 18 + 9, ColonyStorage.usableSlots(
                ColonyStorageBlocks.CRATE_SLOTS * 2 + ColonyStorageBlocks.DEPOT_SLOTS, 1));
    }

    @Test
    @DisplayName("Capacity is capped at the backing size")
    void capped() {
        assertEquals(ColonyStores.STORAGE_SLOTS, ColonyStorage.usableSlots(10_000, 4));
    }

    @Test
    @DisplayName("Nothing is used before the first block and everything by the last")
    void materialsEnds() {
        assertEquals(12, Construction.remainingMaterials(12, 0, 40));
        assertEquals(0, Construction.remainingMaterials(12, 40, 40));
        assertEquals(0, Construction.remainingMaterials(12, 99, 40));
        assertEquals(0, Construction.remainingMaterials(0, 10, 40));
        assertEquals(5, Construction.remainingMaterials(5, 3, 0));
    }

    @Test
    @DisplayName("Materials are used in proportion to the blocks laid, rounding the use down")
    void materialsProportional() {
        assertEquals(6, Construction.remainingMaterials(12, 20, 40));
        // 3 of 10 laid uses floor(7 * 3 / 10) = 2 of 7, leaving 5.
        assertEquals(5, Construction.remainingMaterials(7, 3, 10));
        // A material scarcer than the blocks is only used once enough blocks are down.
        assertEquals(1, Construction.remainingMaterials(1, 39, 40));
    }

    @Test
    @DisplayName("What is still needed never rises as the build goes on")
    void materialsMonotonic() {
        for (int count = 1; count <= 40; count++) {
            int last = count;
            for (int cursor = 0; cursor <= 37; cursor++) {
                int left = Construction.remainingMaterials(count, cursor, 37);
                assertEquals(true, left <= last, "count " + count + " cursor " + cursor);
                last = left;
            }
        }
    }
}
