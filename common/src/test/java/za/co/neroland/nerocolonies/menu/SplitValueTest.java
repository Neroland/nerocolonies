package za.co.neroland.nerocolonies.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Locks the two-slot split behind the gauge tooltips: it survives the short sync and clamps. */
class SplitValueTest {

    @Test
    void roundTripsThroughShorts() {
        for (long v : new long[] {0L, 1L, 32_767L, 32_768L, 50_000L, 1_000_000L, SplitValue.MAX}) {
            int low = SplitValue.low(v);
            int high = SplitValue.high(v);
            assertTrue(low >= 0 && low <= Short.MAX_VALUE);
            assertTrue(high >= 0 && high <= Short.MAX_VALUE);
            assertEquals(v, SplitValue.join((short) low, (short) high));
        }
    }

    @Test
    void clamps() {
        assertEquals(0L, SplitValue.join(SplitValue.low(-5L), SplitValue.high(-5L)));
        assertEquals(SplitValue.MAX, SplitValue.join(SplitValue.low(Long.MAX_VALUE), SplitValue.high(Long.MAX_VALUE)));
    }
}
