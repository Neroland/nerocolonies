package za.co.neroland.nerocolonies.entity.ai.profession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The conversion tables behind the Cook, Researcher, Toolsmith and Farmer. */
class TradeRecipesTest {

    @Test
    @DisplayName("every conversion has inputs, a different output and a positive count")
    void conversionsAreSane() {
        for (TradeRecipes.Conversion c : TradeRecipes.COOKING) {
            check(c);
        }
        for (Map.Entry<String, TradeRecipes.Conversion> e : TradeRecipes.RESEARCH_PREP.entrySet()) {
            check(e.getValue());
            assertEquals(e.getKey(), e.getValue().output(), "research prep is keyed by its output");
        }
    }

    private static void check(TradeRecipes.Conversion c) {
        assertFalse(c.inputs().isEmpty());
        assertTrue(c.count() > 0);
        for (TradeRecipes.Part part : c.inputs()) {
            assertTrue(part.count() > 0);
            assertFalse(part.item().equals(c.output()), "a conversion never makes its own input");
            assertFalse(part.item().contains(":"), "vanilla item paths only");
        }
    }

    @Test
    @DisplayName("shipped trade tools cost their vanilla recipe; anything else one iron ingot")
    void toolCosts() {
        assertEquals(2, TradeRecipes.toolCost("minecraft", "iron_hoe").get(0).count());
        assertEquals(3, TradeRecipes.toolCost("minecraft", "iron_pickaxe").get(0).count());
        assertEquals("paper", TradeRecipes.toolCost("minecraft", "book").get(0).item());
        assertSame(TradeRecipes.FALLBACK_TOOL_COST, TradeRecipes.toolCost("minecraft", "diamond_hoe"));
        assertSame(TradeRecipes.FALLBACK_TOOL_COST, TradeRecipes.toolCost("othermod", "iron_hoe"));
        for (String tool : new String[] {"iron_hoe", "iron_axe", "iron_pickaxe", "iron_shovel", "iron_sword"}) {
            assertNotNull(TradeRecipes.TOOL_COSTS.get(tool), tool);
        }
    }

    @Test
    @DisplayName("every seed grows a crop, and every crop replants from that seed")
    void seedsRoundTrip() {
        for (TradeRecipes.Part seed : TradeRecipes.SEEDS) {
            String crop = TradeRecipes.cropFor(seed.item());
            assertNotNull(crop, seed.item());
            assertEquals(seed.item(), TradeRecipes.seedFor(crop));
        }
        assertEquals(null, TradeRecipes.cropFor("stick"));
        assertEquals(null, TradeRecipes.seedFor("melon_stem"));
    }
}
