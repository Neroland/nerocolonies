package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The accounting window that keeps real work and the colony tick's rolled outputs apart. */
class WorkAccountingTest {

    @Test
    @DisplayName("the window is two colony cycles, never zero")
    void window() {
        assertEquals(200L, WorkAccounting.window(100));
        assertEquals(2L, WorkAccounting.window(0));
        assertEquals(2L, WorkAccounting.window(-5));
    }

    @Test
    @DisplayName("work inside the window counts, outside it does not")
    void recent() {
        long window = WorkAccounting.window(100);
        assertTrue(WorkAccounting.recent(1_000L, 1_000L, window));
        assertTrue(WorkAccounting.recent(1_000L, 1_200L, window));
        assertFalse(WorkAccounting.recent(1_000L, 1_201L, window));
    }

    @Test
    @DisplayName("never having worked, or a clock that went backwards, never counts")
    void neverAndBackwards() {
        assertFalse(WorkAccounting.recent(WorkAccounting.NEVER, 0L, 200L));
        assertFalse(WorkAccounting.recent(WorkAccounting.NEVER, Long.MAX_VALUE, 200L));
        assertFalse(WorkAccounting.recent(5_000L, 4_999L, 200L));
        assertFalse(WorkAccounting.recent(5_000L, 5_000L, 0L));
    }
}
