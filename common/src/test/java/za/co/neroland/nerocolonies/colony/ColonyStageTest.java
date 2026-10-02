package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ColonyStage}: the persisted ordinal order, lenient parsing of data-file names,
 * clamped lookup by ordinal, and the {@code atLeast}/{@code next} ladder helpers.
 */
class ColonyStageTest {

    @Test
    @DisplayName("Ordinal order is growth order (it is persisted, so it must not change)")
    void ordinalOrderIsStable() {
        assertArrayEquals(new ColonyStage[] {ColonyStage.FOUNDING, ColonyStage.SETTLED, ColonyStage.GROWING,
            ColonyStage.THRIVING, ColonyStage.METROPOLIS}, ColonyStage.values());
    }

    @Test
    @DisplayName("Keys are lower-case names and parse back to the same stage")
    void keyRoundTrips() {
        assertEquals("founding", ColonyStage.FOUNDING.key());
        assertEquals("metropolis", ColonyStage.METROPOLIS.key());
        for (ColonyStage stage : ColonyStage.values()) {
            assertSame(stage, ColonyStage.parse(stage.key()));
        }
    }

    @Test
    @DisplayName("Parsing ignores case and surrounding whitespace; unknown or missing means settled")
    void parseIsLenient() {
        assertSame(ColonyStage.THRIVING, ColonyStage.parse("  Thriving "));
        assertSame(ColonyStage.FOUNDING, ColonyStage.parse("FOUNDING"));
        assertSame(ColonyStage.SETTLED, ColonyStage.parse("no such stage"));
        assertSame(ColonyStage.SETTLED, ColonyStage.parse(""));
        assertSame(ColonyStage.SETTLED, ColonyStage.parse(null));
    }

    @Test
    @DisplayName("Ordinals round-trip and out-of-range ordinals clamp to the ends")
    void byOrdinalClamps() {
        for (ColonyStage stage : ColonyStage.values()) {
            assertSame(stage, ColonyStage.byOrdinal(stage.ordinal()));
        }
        assertSame(ColonyStage.FOUNDING, ColonyStage.byOrdinal(-1));
        assertSame(ColonyStage.FOUNDING, ColonyStage.byOrdinal(Integer.MIN_VALUE));
        assertSame(ColonyStage.METROPOLIS, ColonyStage.byOrdinal(99));
        assertSame(ColonyStage.METROPOLIS, ColonyStage.byOrdinal(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("atLeast follows ordinal order and next climbs one stage, stopping at the top")
    void ladder() {
        for (ColonyStage a : ColonyStage.values()) {
            assertTrue(a.atLeast(a));
            for (ColonyStage b : ColonyStage.values()) {
                assertEquals(a.ordinal() >= b.ordinal(), a.atLeast(b));
            }
        }
        assertFalse(ColonyStage.SETTLED.atLeast(ColonyStage.GROWING));
        assertSame(ColonyStage.SETTLED, ColonyStage.FOUNDING.next());
        assertSame(ColonyStage.GROWING, ColonyStage.SETTLED.next());
        assertSame(ColonyStage.THRIVING, ColonyStage.GROWING.next());
        assertSame(ColonyStage.METROPOLIS, ColonyStage.THRIVING.next());
        assertSame(ColonyStage.METROPOLIS, ColonyStage.METROPOLIS.next());
    }
}
