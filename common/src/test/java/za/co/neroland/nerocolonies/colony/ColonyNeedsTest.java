package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyNeeds.Need;
import za.co.neroland.nerocolonies.colony.ColonyNeeds.Reason;
import za.co.neroland.nerocolonies.content.ItemTarget;

/**
 * Covers the pure parts of {@link ColonyNeeds}: the solo estimate {@code etaMinutes} (nothing
 * missing, nobody gathering, whole cycles rounded up, never less than a minute, scaling with the
 * cycle length) and the needs-list ordering {@code ORDER} (priority, then reason, then most missing,
 * then label). The derived list itself needs a running server and is not covered here.
 */
class ColonyNeedsTest {

    /** One minute of game time, the unit {@code etaMinutes} reports in. */
    private static final int MINUTE = 1_200;

    private static Need need(String path, int needed, int have, Reason reason, boolean priority) {
        ItemTarget target = new ItemTarget(Optional.of(Identifier.withDefaultNamespace(path)), Optional.empty(), 1);
        return new Need(target, "minecraft:" + path, "item.minecraft." + path, needed, have, reason, -1,
                priority);
    }

    private static List<String> labels(List<Need> needs) {
        List<String> out = new ArrayList<>();
        for (Need need : needs) {
            out.add(need.label());
        }
        return out;
    }

    // --- etaMinutes ---------------------------------------------------------------

    @Test
    @DisplayName("Nothing missing takes no time, whatever the rate")
    void etaNothingMissing() {
        assertEquals(0, ColonyNeeds.etaMinutes(0, 1.0D, MINUTE));
        assertEquals(0, ColonyNeeds.etaMinutes(-5, 1.0D, MINUTE));
        assertEquals(0, ColonyNeeds.etaMinutes(0, 0.0D, MINUTE));
    }

    @Test
    @DisplayName("Nobody gathering it means -1: not without help")
    void etaNoGatherers() {
        assertEquals(-1, ColonyNeeds.etaMinutes(10, 0.0D, MINUTE));
        assertEquals(-1, ColonyNeeds.etaMinutes(10, -2.0D, MINUTE));
    }

    @Test
    @DisplayName("Minutes are cycles needed times the cycle length")
    void etaWholeMinutes() {
        assertEquals(10, ColonyNeeds.etaMinutes(10, 1.0D, MINUTE));
        assertEquals(5, ColonyNeeds.etaMinutes(10, 2.0D, MINUTE));
        assertEquals(40, ColonyNeeds.etaMinutes(10, 0.25D, MINUTE));
    }

    @Test
    @DisplayName("A part cycle counts as a whole one")
    void etaRoundsCyclesUp() {
        // 10 / 3 = 3.33 cycles -> 4.
        assertEquals(4, ColonyNeeds.etaMinutes(10, 3.0D, MINUTE));
        // 1 / 0.3 = 3.33 cycles -> 4.
        assertEquals(4, ColonyNeeds.etaMinutes(1, 0.3D, MINUTE));
        assertEquals(1, ColonyNeeds.etaMinutes(1, 50.0D, MINUTE));
    }

    @Test
    @DisplayName("Anything still missing takes at least a minute")
    void etaAtLeastOneMinute() {
        assertEquals(1, ColonyNeeds.etaMinutes(1, 100.0D, 20));
        assertEquals(1, ColonyNeeds.etaMinutes(1, 1.0D, 1));
        assertEquals(1, ColonyNeeds.etaMinutes(5, 1.0D, 100));
    }

    @Test
    @DisplayName("The estimate scales with the colony cycle length")
    void etaScalesWithCycleLength() {
        assertEquals(5, ColonyNeeds.etaMinutes(10, 1.0D, MINUTE / 2));
        assertEquals(20, ColonyNeeds.etaMinutes(10, 1.0D, MINUTE * 2));
        for (int cycles = 1; cycles <= 6; cycles++) {
            assertEquals(cycles * ColonyNeeds.etaMinutes(7, 1.0D, MINUTE),
                    ColonyNeeds.etaMinutes(7, 1.0D, MINUTE * cycles));
        }
    }

    @Test
    @DisplayName("Ticks round to the nearest minute")
    void etaRoundsToNearestMinute() {
        // One cycle of 1 700 ticks is 1.42 minutes; of 1 900 ticks, 1.58.
        assertEquals(1, ColonyNeeds.etaMinutes(1, 1.0D, 1_700));
        assertEquals(2, ColonyNeeds.etaMinutes(1, 1.0D, 1_900));
    }

    @Test
    @DisplayName("A cycle length of zero or less is treated as one tick")
    void etaNonPositiveCycleLength() {
        assertEquals(2, ColonyNeeds.etaMinutes(2 * MINUTE, 1.0D, 0));
        assertEquals(2, ColonyNeeds.etaMinutes(2 * MINUTE, 1.0D, -7));
        assertEquals(ColonyNeeds.etaMinutes(2 * MINUTE, 1.0D, 1), ColonyNeeds.etaMinutes(2 * MINUTE, 1.0D, 0));
    }

    @Test
    @DisplayName("More missing never takes less time; a faster rate never takes more")
    void etaMonotonic() {
        int previous = 0;
        for (int missing = 0; missing <= 200; missing++) {
            int eta = ColonyNeeds.etaMinutes(missing, 0.75D, MINUTE);
            assertTrue(eta >= previous, "shrank at missing " + missing);
            previous = eta;
        }
        int slower = Integer.MAX_VALUE;
        for (double rate = 0.25D; rate <= 16.0D; rate += 0.25D) {
            int eta = ColonyNeeds.etaMinutes(64, rate, MINUTE);
            assertTrue(eta <= slower, "grew at rate " + rate);
            assertTrue(eta >= 1);
            slower = eta;
        }
    }

    // --- Need and Reason ------------------------------------------------------------

    @Test
    @DisplayName("Missing is needed minus have, never negative")
    void missingNeverNegative() {
        assertEquals(7, need("stone", 10, 3, Reason.CONSTRUCTION, false).missing());
        assertEquals(0, need("stone", 10, 10, Reason.CONSTRUCTION, false).missing());
        assertEquals(0, need("stone", 10, 25, Reason.CONSTRUCTION, false).missing());
    }

    @Test
    @DisplayName("Reasons are declared in urgency order and keyed by lower-case name")
    void reasonOrderAndKeys() {
        assertArrayEquals(new Reason[] {Reason.STARTER, Reason.CONSTRUCTION, Reason.FOOD, Reason.TOOLS},
                Reason.values());
        assertEquals("starter", Reason.STARTER.key());
        assertEquals("construction", Reason.CONSTRUCTION.key());
        assertEquals("food", Reason.FOOD.key());
        assertEquals("tools", Reason.TOOLS.key());
    }

    // --- ORDER ----------------------------------------------------------------------

    @Test
    @DisplayName("The prioritised need comes first, whatever its reason or size")
    void orderPriorityFirst() {
        Need prioritisedTool = need("iron_hoe", 1, 0, Reason.TOOLS, true);
        Need starter = need("oak_log", 64, 0, Reason.STARTER, false);
        assertTrue(ColonyNeeds.ORDER.compare(prioritisedTool, starter) < 0);
        assertTrue(ColonyNeeds.ORDER.compare(starter, prioritisedTool) > 0);
    }

    @Test
    @DisplayName("Then by reason: starter, construction, food, tools")
    void orderByReason() {
        Need starter = need("d", 1, 0, Reason.STARTER, false);
        Need construction = need("c", 20, 0, Reason.CONSTRUCTION, false);
        Need food = need("b", 40, 0, Reason.FOOD, false);
        Need tools = need("a", 80, 0, Reason.TOOLS, false);
        assertTrue(ColonyNeeds.ORDER.compare(starter, construction) < 0);
        assertTrue(ColonyNeeds.ORDER.compare(construction, food) < 0);
        assertTrue(ColonyNeeds.ORDER.compare(food, tools) < 0);
        assertTrue(ColonyNeeds.ORDER.compare(tools, starter) > 0);
    }

    @Test
    @DisplayName("Then by most missing, counting what is still short rather than the total needed")
    void orderByMostMissing() {
        Need bigShortfall = need("z_planks", 10, 0, Reason.CONSTRUCTION, false);
        Need nearlyDone = need("a_stone", 100, 99, Reason.CONSTRUCTION, false);
        assertTrue(ColonyNeeds.ORDER.compare(bigShortfall, nearlyDone) < 0);
        assertTrue(ColonyNeeds.ORDER.compare(nearlyDone, bigShortfall) > 0);
    }

    @Test
    @DisplayName("Then by label, and identical keys compare equal")
    void orderByLabel() {
        Need glass = need("glass", 8, 0, Reason.CONSTRUCTION, false);
        Need stone = need("stone", 8, 0, Reason.CONSTRUCTION, false);
        assertTrue(ColonyNeeds.ORDER.compare(glass, stone) < 0);
        assertTrue(ColonyNeeds.ORDER.compare(stone, glass) > 0);
        // Same priority, reason, shortfall and label: a tie, even though needed/have differ.
        assertEquals(0, ColonyNeeds.ORDER.compare(glass, need("glass", 10, 2, Reason.CONSTRUCTION, false)));
    }

    @Test
    @DisplayName("A whole list sorts most urgent first")
    void orderSortsWholeList() {
        List<Need> needs = new ArrayList<>(List.of(
                need("iron_pickaxe", 2, 0, Reason.TOOLS, false),
                need("stone", 30, 10, Reason.CONSTRUCTION, false),
                need("bread", 50, 0, Reason.FOOD, false),
                need("glass", 30, 10, Reason.CONSTRUCTION, false),
                need("oak_planks", 64, 0, Reason.CONSTRUCTION, false),
                need("iron_hoe", 1, 0, Reason.TOOLS, true),
                need("oak_log", 16, 0, Reason.STARTER, false)));
        needs.sort(ColonyNeeds.ORDER);
        assertEquals(List.of(
                "minecraft:iron_hoe",
                "minecraft:oak_log",
                "minecraft:oak_planks",
                "minecraft:glass",
                "minecraft:stone",
                "minecraft:bread",
                "minecraft:iron_pickaxe"), labels(needs));
    }
}
