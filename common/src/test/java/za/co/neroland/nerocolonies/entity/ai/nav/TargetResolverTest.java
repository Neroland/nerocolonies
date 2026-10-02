package za.co.neroland.nerocolonies.entity.ai.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TargetResolver#candidates}, the pure preference order of spots next to a target: the
 * four sides at foot level, then raised, then lowered, then the diagonals at the same three heights,
 * then the top of the target itself. {@code standable}, {@code resolve} and {@code nextAlternate}
 * read block states from a level and are not covered here.
 */
class TargetResolverTest {

    private static final BlockPos TARGET = new BlockPos(10, 64, -3);

    /** Candidates as offsets from the target. */
    private static List<BlockPos> offsets(BlockPos target) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos candidate : TargetResolver.candidates(target)) {
            out.add(new BlockPos(candidate.getX() - target.getX(), candidate.getY() - target.getY(),
                    candidate.getZ() - target.getZ()));
        }
        return out;
    }

    private static Set<BlockPos> sidesAt(int dy) {
        return Set.of(new BlockPos(1, dy, 0), new BlockPos(-1, dy, 0), new BlockPos(0, dy, 1),
                new BlockPos(0, dy, -1));
    }

    @Test
    @DisplayName("There are 25 distinct candidates, none of them the target itself")
    void distinctAndNeverTheTarget() {
        List<BlockPos> candidates = TargetResolver.candidates(TARGET);
        assertEquals(25, candidates.size());
        assertEquals(25, new HashSet<>(candidates).size());
        assertFalse(candidates.contains(TARGET));
    }

    @Test
    @DisplayName("Every candidate touches the target: at most one block away on each axis")
    void allAdjacent() {
        for (BlockPos offset : offsets(TARGET)) {
            assertTrue(Math.abs(offset.getX()) <= 1 && Math.abs(offset.getY()) <= 1
                    && Math.abs(offset.getZ()) <= 1, "too far: " + offset);
        }
    }

    @Test
    @DisplayName("The four sides come first: at foot level, then raised, then lowered")
    void sidesFirst() {
        List<BlockPos> offsets = offsets(TARGET);
        assertEquals(sidesAt(0), new HashSet<>(offsets.subList(0, 4)));
        assertEquals(sidesAt(1), new HashSet<>(offsets.subList(4, 8)));
        assertEquals(sidesAt(-1), new HashSet<>(offsets.subList(8, 12)));
    }

    @Test
    @DisplayName("The sides are tried in the same order at each height")
    void sidesKeepTheirOrderAcrossHeights() {
        List<BlockPos> offsets = offsets(TARGET);
        for (int i = 0; i < 4; i++) {
            BlockPos foot = offsets.get(i);
            assertEquals(new BlockPos(foot.getX(), 1, foot.getZ()), offsets.get(4 + i));
            assertEquals(new BlockPos(foot.getX(), -1, foot.getZ()), offsets.get(8 + i));
        }
    }

    @Test
    @DisplayName("Then the diagonals, at foot level, raised and lowered")
    void diagonalsNext() {
        List<BlockPos> offsets = offsets(TARGET);
        int index = 12;
        for (int dy : new int[] {0, 1, -1}) {
            assertEquals(new BlockPos(1, dy, 1), offsets.get(index++));
            assertEquals(new BlockPos(-1, dy, 1), offsets.get(index++));
            assertEquals(new BlockPos(1, dy, -1), offsets.get(index++));
            assertEquals(new BlockPos(-1, dy, -1), offsets.get(index++));
        }
    }

    @Test
    @DisplayName("Standing on top of the target is the last resort")
    void topOfTargetIsLast() {
        List<BlockPos> offsets = offsets(TARGET);
        assertEquals(new BlockPos(0, 1, 0), offsets.get(offsets.size() - 1));
        assertEquals(24, offsets.indexOf(new BlockPos(0, 1, 0)));
    }

    @Test
    @DisplayName("The spot directly beneath the target is never offered")
    void neverBeneathTheTarget() {
        assertFalse(offsets(TARGET).contains(new BlockPos(0, -1, 0)));
    }

    @Test
    @DisplayName("The order is the same wherever the target is")
    void orderIsTranslationInvariant() {
        List<BlockPos> reference = offsets(TARGET);
        assertEquals(reference, offsets(BlockPos.ZERO));
        assertEquals(reference, offsets(new BlockPos(-1_000, -60, 2_500)));
    }
}
