package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the request key of a budgeted export payout: it must be a key NeroEconomy accepts (1 to 64
 * characters of {@code [A-Za-z0-9_.:/#-]}, never UUID-shaped, never 32 hex digits in a row), unique per
 * colony and counter, and stable for the same colony and counter so a retry is a duplicate.
 */
class ExportSaleKeyTest {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.:/#-]{1,64}");
    private static final Pattern UUID_SHAPED =
            Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX_RUN = Pattern.compile("(?i)[0-9a-f]{32}");

    @Test
    @DisplayName("Keys are valid NeroEconomy request keys, even at the extremes")
    void keysAreValid() {
        UUID[] ids = {new UUID(-1L, -1L), new UUID(0L, 0L), UUID.randomUUID(),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")};
        for (UUID id : ids) {
            for (long counter : new long[] {0L, 1L, Long.MAX_VALUE}) {
                String key = ExportBuffer.saleKey(id, counter);
                assertTrue(ALLOWED.matcher(key).matches(), key);
                assertFalse(UUID_SHAPED.matcher(key).find(), key);
                assertFalse(HEX_RUN.matcher(key).find(), key);
                assertFalse(key.contains(id.toString()), key);
            }
        }
    }

    @Test
    @DisplayName("Same colony and counter give the same key; anything else differs")
    void keysAreStableAndUnique() {
        UUID a = UUID.randomUUID();
        UUID b = new UUID(a.getMostSignificantBits(), a.getLeastSignificantBits() + 1L);
        assertEquals(ExportBuffer.saleKey(a, 7L), ExportBuffer.saleKey(a, 7L));
        assertNotEquals(ExportBuffer.saleKey(a, 7L), ExportBuffer.saleKey(a, 8L));
        assertNotEquals(ExportBuffer.saleKey(a, 7L), ExportBuffer.saleKey(b, 7L));
    }
}
