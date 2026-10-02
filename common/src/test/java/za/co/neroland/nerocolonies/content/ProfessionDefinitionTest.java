package za.co.neroland.nerocolonies.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure parts of {@link ProfessionDefinition}: the level curve {@code levelFor} (level
 * {@code n} needs {@code xpPerLevel * n * (n - 1) / 2} xp in total, 1-based, capped at the maximum
 * level), the instance form that uses the record's own numbers, the constructor's clamping of
 * out-of-range data, and the translation-key fallback. Tool lookups need the item registry and are
 * not covered here.
 */
class ProfessionDefinitionTest {

    private static final Identifier FARMER = Identifier.fromNamespaceAndPath("nerocolonies", "farmer");

    /** Total xp the documented curve asks for to reach {@code level}. */
    private static int xpFor(int level, int xpPerLevel) {
        return xpPerLevel * level * (level - 1) / 2;
    }

    /** A trade with no tool, stations or outputs, so nothing but plain values is involved. */
    private static ProfessionDefinition trade(Identifier id, String name, int xpPerLevel, int maxLevel) {
        return new ProfessionDefinition(id, name, FARMER, Optional.empty(), List.of(), 10, 2, 8, xpPerLevel,
                maxLevel, 10, List.of(), 0.5D);
    }

    // --- levelFor -------------------------------------------------------------------

    @Test
    @DisplayName("No experience is level 1")
    void startsAtLevelOne() {
        assertEquals(1, ProfessionDefinition.levelFor(0, 100, 5));
        assertEquals(1, ProfessionDefinition.levelFor(99, 100, 5));
        assertEquals(1, ProfessionDefinition.levelFor(-50, 100, 5));
    }

    @Test
    @DisplayName("Each level costs one step more than the last: 100, 300, 600, 1000")
    void thresholds() {
        assertEquals(2, ProfessionDefinition.levelFor(100, 100, 5));
        assertEquals(2, ProfessionDefinition.levelFor(299, 100, 5));
        assertEquals(3, ProfessionDefinition.levelFor(300, 100, 5));
        assertEquals(3, ProfessionDefinition.levelFor(599, 100, 5));
        assertEquals(4, ProfessionDefinition.levelFor(600, 100, 5));
        assertEquals(4, ProfessionDefinition.levelFor(999, 100, 5));
        assertEquals(5, ProfessionDefinition.levelFor(1_000, 100, 5));
    }

    @Test
    @DisplayName("Level n is reached at exactly xpPerLevel * n * (n - 1) / 2")
    void matchesTheDocumentedFormula() {
        for (int xpPerLevel : new int[] {1, 7, 100, 250}) {
            for (int level = 2; level <= 10; level++) {
                int needed = xpFor(level, xpPerLevel);
                assertEquals(level, ProfessionDefinition.levelFor(needed, xpPerLevel, 10),
                        "at " + needed + " xp, " + xpPerLevel + " per level");
                assertEquals(level - 1, ProfessionDefinition.levelFor(needed - 1, xpPerLevel, 10),
                        "just below " + needed + " xp, " + xpPerLevel + " per level");
            }
        }
    }

    @Test
    @DisplayName("The level is capped at the maximum")
    void cappedAtMaxLevel() {
        assertEquals(5, ProfessionDefinition.levelFor(1_000_000, 100, 5));
        assertEquals(5, ProfessionDefinition.levelFor(Integer.MAX_VALUE, 100, 5));
        assertEquals(3, ProfessionDefinition.levelFor(1_000, 100, 3));
        assertEquals(1, ProfessionDefinition.levelFor(1_000_000, 100, 1));
    }

    @Test
    @DisplayName("More experience never lowers the level, and the level stays within 1..max")
    void monotonicAndBounded() {
        int previous = 1;
        for (int xp = 0; xp <= 1_200; xp++) {
            int level = ProfessionDefinition.levelFor(xp, 100, 5);
            assertTrue(level >= previous, "dropped at " + xp);
            assertTrue(level >= 1 && level <= 5, "out of range at " + xp);
            previous = level;
        }
    }

    // --- the record -----------------------------------------------------------------

    @Test
    @DisplayName("The instance form uses the trade's own xp step and maximum level")
    void instanceLevelFor() {
        ProfessionDefinition farmer = trade(FARMER, "", 50, 3);
        assertEquals(1, farmer.levelFor(49));
        assertEquals(2, farmer.levelFor(50));
        assertEquals(3, farmer.levelFor(150));
        assertEquals(3, farmer.levelFor(10_000));
        for (int xp = 0; xp <= 400; xp += 7) {
            assertEquals(ProfessionDefinition.levelFor(xp, 50, 3), farmer.levelFor(xp));
        }
    }

    @Test
    @DisplayName("Out-of-range numbers are clamped and missing lists become empty")
    void constructorClamps() {
        ProfessionDefinition low = new ProfessionDefinition(FARMER, null, FARMER, Optional.empty(), null, -1, -1,
                -1, 0, 0, 0, null, -1.0D);
        assertEquals("", low.name());
        assertEquals(List.of(), low.stations());
        assertEquals(List.of(), low.outputs());
        assertEquals(0, low.priority());
        assertEquals(0, low.perBuilding());
        assertEquals(0, low.maxPerColony());
        assertEquals(1, low.xpPerLevel());
        assertEquals(1, low.maxLevel());
        assertEquals(1, low.workRadius());
        assertEquals(0.0D, low.outputChance(), 0.0D);

        ProfessionDefinition high = new ProfessionDefinition(FARMER, "x", FARMER, Optional.empty(), List.of(),
                1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, List.of(), 1_000_000.0D);
        assertEquals(10_000, high.priority());
        assertEquals(64, high.perBuilding());
        assertEquals(256, high.maxPerColony());
        assertEquals(100_000, high.xpPerLevel());
        assertEquals(10, high.maxLevel());
        assertEquals(32, high.workRadius());
        assertEquals(64.0D, high.outputChance(), 0.0D);
    }

    @Test
    @DisplayName("A blank name falls back to a key built from the id; withId changes only the id")
    void nameKeyAndWithId() {
        assertEquals("profession.custom.farmer", trade(FARMER, "profession.custom.farmer", 100, 5).nameKey());
        assertEquals("profession.nerocolonies.farmer", trade(FARMER, "", 100, 5).nameKey());
        Identifier nested = Identifier.fromNamespaceAndPath("nerocolonies", "trades/master_farmer");
        assertEquals("profession.nerocolonies.trades.master_farmer", trade(nested, "  ", 100, 5).nameKey());

        ProfessionDefinition unnamed = trade(ProfessionDefinition.UNNAMED, "", 100, 5);
        ProfessionDefinition named = unnamed.withId(FARMER);
        assertEquals(FARMER, named.id());
        assertEquals(trade(FARMER, "", 100, 5), named);
    }
}
