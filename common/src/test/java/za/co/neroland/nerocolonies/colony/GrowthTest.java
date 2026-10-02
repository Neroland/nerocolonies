package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure half of {@link Growth}: the growth score, the logistic curve, the three scaled
 * quantities (build multiplier, breeding chance, claim bonus) with their caps passed in explicitly,
 * the stage ladder with explicit thresholds, and the breeding preconditions. Nothing here reads
 * config, so every cap is a {@code double} or {@code int} argument of the pure overloads.
 */
class GrowthTest {

    private static final double EPS = 1e-9;

    /** {growingPop, growingStructures, thrivingPop, thrivingStructures, metropolisPop, metropolisStructures}. */
    private static final int[] THRESHOLDS = {6, 4, 12, 10, 24, 20};

    private static final int MIDPOINT = (int) Growth.MIDPOINT;

    // --- score ------------------------------------------------------------------

    @Test
    @DisplayName("Score is population plus twice the structures")
    void scoreFormula() {
        assertEquals(0, Growth.score(0, 0));
        assertEquals(5, Growth.score(5, 0));
        assertEquals(6, Growth.score(0, 3));
        assertEquals(11, Growth.score(5, 3));
    }

    @Test
    @DisplayName("Negative population or structures count as zero")
    void scoreClampsNegatives() {
        assertEquals(6, Growth.score(-4, 3));
        assertEquals(5, Growth.score(5, -2));
        assertEquals(0, Growth.score(-1, -1));
    }

    // --- curve ------------------------------------------------------------------

    @Test
    @DisplayName("The curve is exactly half way at the midpoint")
    void curveMidpoint() {
        assertEquals(0.5D, Growth.curve(20), EPS);
        assertEquals(0.5D, Growth.curve(MIDPOINT), EPS);
    }

    @Test
    @DisplayName("The curve stays within 0..1, strictly inside it for any realistic score")
    void curveBounded() {
        for (int score = -100; score <= 300; score++) {
            double g = Growth.curve(score);
            assertTrue(g >= 0.0D && g <= 1.0D, "curve(" + score + ") = " + g);
        }
        for (int score = -50; score <= 150; score++) {
            double g = Growth.curve(score);
            assertTrue(g > 0.0D && g < 1.0D, "curve(" + score + ") = " + g);
        }
        for (int extreme : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            double g = Growth.curve(extreme);
            assertFalse(Double.isNaN(g));
            assertTrue(g >= 0.0D && g <= 1.0D, "curve(" + extreme + ") = " + g);
        }
    }

    @Test
    @DisplayName("A bigger score never grows slower")
    void curveStrictlyIncreasing() {
        for (int score = -50; score < 150; score++) {
            assertTrue(Growth.curve(score + 1) > Growth.curve(score), "not increasing at " + score);
        }
    }

    @Test
    @DisplayName("The curve is symmetric about the midpoint")
    void curveSymmetric() {
        for (int d = 0; d <= 40; d++) {
            assertEquals(1.0D, Growth.curve(MIDPOINT + d) + Growth.curve(MIDPOINT - d), EPS);
        }
    }

    @Test
    @DisplayName("A fresh colony sits near the bottom of the curve")
    void curveAtZero() {
        double expected = 1.0D / (1.0D + Math.exp(Growth.STEEPNESS * Growth.MIDPOINT));
        assertEquals(expected, Growth.curve(0), EPS);
        assertTrue(Growth.curve(0) < 0.1D);
    }

    // --- build multiplier -------------------------------------------------------

    @Test
    @DisplayName("Build multiplier is 1 + (cap - 1) x curve")
    void buildMultiplierFormula() {
        assertEquals(2.0D, Growth.buildMultiplier(MIDPOINT, 3.0D), EPS);
        for (int score : new int[] {0, 7, 20, 33, 60}) {
            assertEquals(1.0D + 3.0D * Growth.curve(score), Growth.buildMultiplier(score, 4.0D), EPS);
        }
    }

    @Test
    @DisplayName("Build multiplier starts just above 1 and approaches the cap")
    void buildMultiplierLowAndHigh() {
        double cap = 3.0D;
        double low = Growth.buildMultiplier(0, cap);
        double high = Growth.buildMultiplier(200, cap);
        assertTrue(low > 1.0D && low < 1.0D + (cap - 1.0D) * 0.1D, "low = " + low);
        assertEquals(cap, high, 1e-6);
        for (int score = 0; score < 100; score++) {
            assertTrue(Growth.buildMultiplier(score + 1, cap) > Growth.buildMultiplier(score, cap));
        }
    }

    @Test
    @DisplayName("Build multiplier is never below 1 or above its cap")
    void buildMultiplierBounds() {
        for (double cap : new double[] {1.0D, 1.5D, 3.0D, 10.0D}) {
            for (int score = -50; score <= 300; score++) {
                double m = Growth.buildMultiplier(score, cap);
                assertTrue(m >= 1.0D, "below 1 at score " + score + ", cap " + cap);
                assertTrue(m <= cap + 1e-12, "above cap at score " + score + ", cap " + cap);
            }
        }
    }

    @Test
    @DisplayName("A cap below 1 is treated as 1: growth never slows construction")
    void buildMultiplierCapBelowOne() {
        for (double cap : new double[] {0.5D, 0.0D, -3.0D}) {
            for (int score : new int[] {0, 20, 200}) {
                assertEquals(1.0D, Growth.buildMultiplier(score, cap), 0.0D);
            }
        }
    }

    // --- breeding chance --------------------------------------------------------

    @Test
    @DisplayName("Breeding chance is base x (0.25 + 0.75 x curve)")
    void breedingChanceFormula() {
        assertEquals(0.125D, Growth.breedingChance(MIDPOINT, 0.2D), EPS);
        for (int score : new int[] {0, 7, 33, 60}) {
            assertEquals(0.4D * (0.25D + 0.75D * Growth.curve(score)), Growth.breedingChance(score, 0.4D), EPS);
        }
    }

    @Test
    @DisplayName("Breeding chance runs from a quarter of the base up to the base")
    void breedingChanceRange() {
        double base = 0.2D;
        for (int score = -50; score <= 300; score++) {
            double chance = Growth.breedingChance(score, base);
            assertTrue(chance >= 0.25D * base - EPS, "below a quarter at " + score);
            assertTrue(chance <= base + EPS, "above base at " + score);
        }
        assertEquals(0.25D * base, Growth.breedingChance(-200, base), EPS);
        assertEquals(base, Growth.breedingChance(300, base), EPS);
    }

    @Test
    @DisplayName("Breeding chance scales linearly with the base and rises with the score")
    void breedingChanceScaling() {
        for (int score : new int[] {0, 20, 45}) {
            assertEquals(2.0D * Growth.breedingChance(score, 0.2D), Growth.breedingChance(score, 0.4D), EPS);
        }
        for (int score = 0; score < 100; score++) {
            assertTrue(Growth.breedingChance(score + 1, 0.2D) > Growth.breedingChance(score, 0.2D));
        }
    }

    @Test
    @DisplayName("The base chance is clamped to 0..1")
    void breedingChanceClampsBase() {
        assertEquals(Growth.breedingChance(MIDPOINT, 1.0D), Growth.breedingChance(MIDPOINT, 5.0D), 0.0D);
        assertEquals(0.625D, Growth.breedingChance(MIDPOINT, 5.0D), EPS);
        assertEquals(0.0D, Growth.breedingChance(MIDPOINT, -1.0D), 0.0D);
        assertEquals(0.0D, Growth.breedingChance(300, 0.0D), 0.0D);
    }

    // --- claim bonus ------------------------------------------------------------

    @Test
    @DisplayName("Claim bonus is the cap x curve, rounded to the nearest block")
    void claimBonusRounding() {
        assertEquals(8, Growth.claimBonus(MIDPOINT, 16));
        // 7.5 rounds up.
        assertEquals(8, Growth.claimBonus(MIDPOINT, 15));
        for (int score = 0; score <= 80; score++) {
            double exact = 16 * Growth.curve(score);
            assertTrue(Math.abs(Growth.claimBonus(score, 16) - exact) <= 0.5D, "not nearest at " + score);
        }
    }

    @Test
    @DisplayName("Claim bonus stays within 0..cap, reaches the cap and never shrinks as the colony grows")
    void claimBonusBounds() {
        for (int cap : new int[] {0, 1, 16, 64}) {
            int previous = 0;
            for (int score = -50; score <= 300; score++) {
                int bonus = Growth.claimBonus(score, cap);
                assertTrue(bonus >= 0 && bonus <= cap, "out of range at score " + score + ", cap " + cap);
                assertTrue(bonus >= previous, "shrank at score " + score + ", cap " + cap);
                previous = bonus;
            }
            assertEquals(cap, Growth.claimBonus(300, cap));
        }
    }

    @Test
    @DisplayName("A negative claim cap gives no bonus")
    void claimBonusNegativeCap() {
        assertEquals(0, Growth.claimBonus(0, -8));
        assertEquals(0, Growth.claimBonus(300, -8));
    }

    // --- stages -----------------------------------------------------------------

    @Test
    @DisplayName("Without the Starter Works a colony is still founding, however big")
    void earnedStageFounding() {
        assertEquals(ColonyStage.FOUNDING, Growth.earnedStage(false, 0, 0, THRESHOLDS));
        assertEquals(ColonyStage.FOUNDING, Growth.earnedStage(false, 1_000, 1_000, THRESHOLDS));
    }

    @Test
    @DisplayName("Each stage is earned exactly at its thresholds")
    void earnedStageThresholds() {
        assertEquals(ColonyStage.SETTLED, Growth.earnedStage(true, 0, 0, THRESHOLDS));
        assertEquals(ColonyStage.GROWING, Growth.earnedStage(true, 6, 4, THRESHOLDS));
        assertEquals(ColonyStage.THRIVING, Growth.earnedStage(true, 12, 10, THRESHOLDS));
        assertEquals(ColonyStage.METROPOLIS, Growth.earnedStage(true, 24, 20, THRESHOLDS));
        assertEquals(ColonyStage.METROPOLIS, Growth.earnedStage(true, 1_000, 1_000, THRESHOLDS));
    }

    @Test
    @DisplayName("A stage needs both its population and its structures")
    void earnedStageNeedsBoth() {
        assertEquals(ColonyStage.SETTLED, Growth.earnedStage(true, 5, 4, THRESHOLDS));
        assertEquals(ColonyStage.SETTLED, Growth.earnedStage(true, 6, 3, THRESHOLDS));
        assertEquals(ColonyStage.GROWING, Growth.earnedStage(true, 11, 10, THRESHOLDS));
        assertEquals(ColonyStage.GROWING, Growth.earnedStage(true, 12, 9, THRESHOLDS));
        assertEquals(ColonyStage.THRIVING, Growth.earnedStage(true, 23, 20, THRESHOLDS));
        assertEquals(ColonyStage.THRIVING, Growth.earnedStage(true, 24, 19, THRESHOLDS));
        // Plenty of one never makes up for the other.
        assertEquals(ColonyStage.SETTLED, Growth.earnedStage(true, 1_000, 3, THRESHOLDS));
        assertEquals(ColonyStage.GROWING, Growth.earnedStage(true, 1_000, 9, THRESHOLDS));
        assertEquals(ColonyStage.SETTLED, Growth.earnedStage(true, 5, 1_000, THRESHOLDS));
    }

    @Test
    @DisplayName("More colonists or more structures never earn a lower stage")
    void earnedStageMonotonic() {
        for (int population = 0; population <= 30; population++) {
            for (int structures = 0; structures <= 25; structures++) {
                ColonyStage here = Growth.earnedStage(true, population, structures, THRESHOLDS);
                assertTrue(Growth.earnedStage(true, population + 1, structures, THRESHOLDS).atLeast(here));
                assertTrue(Growth.earnedStage(true, population, structures + 1, THRESHOLDS).atLeast(here));
                assertTrue(here.atLeast(ColonyStage.SETTLED));
            }
        }
    }

    // --- breeding preconditions -------------------------------------------------

    /** Baseline that is allowed: 4 colonists eating 2 a cycle with 3 cycles in hand need 24 food. */
    private static boolean allowed(ColonyStage stage, int adults, int freeBeds, int foodStock, double morale) {
        return Growth.breedingAllowed(stage, adults, freeBeds, foodStock, 4, 2, 3, morale, 0.5D);
    }

    @Test
    @DisplayName("Breeding starts at the growing stage")
    void breedingStageGate() {
        assertFalse(allowed(ColonyStage.FOUNDING, 2, 1, 24, 0.5D));
        assertFalse(allowed(ColonyStage.SETTLED, 2, 1, 24, 0.5D));
        assertTrue(allowed(ColonyStage.GROWING, 2, 1, 24, 0.5D));
        assertTrue(allowed(ColonyStage.THRIVING, 2, 1, 24, 0.5D));
        assertTrue(allowed(ColonyStage.METROPOLIS, 2, 1, 24, 0.5D));
    }

    @Test
    @DisplayName("Breeding needs two adults, a free bed and morale at or above the floor")
    void breedingNeedsAdultsBedAndMorale() {
        assertTrue(allowed(ColonyStage.GROWING, 2, 1, 24, 0.5D));
        assertFalse(allowed(ColonyStage.GROWING, 1, 1, 24, 0.5D));
        assertFalse(allowed(ColonyStage.GROWING, 0, 1, 24, 0.5D));
        assertFalse(allowed(ColonyStage.GROWING, 2, 0, 24, 0.5D));
        assertFalse(allowed(ColonyStage.GROWING, 2, 1, 24, 0.49D));
        assertTrue(allowed(ColonyStage.GROWING, 8, 5, 24, 1.0D));
    }

    @Test
    @DisplayName("Breeding needs food for every colonist for the surplus cycles")
    void breedingFoodSurplus() {
        assertFalse(allowed(ColonyStage.GROWING, 2, 1, 23, 0.5D));
        assertTrue(allowed(ColonyStage.GROWING, 2, 1, 24, 0.5D));
        assertTrue(allowed(ColonyStage.GROWING, 2, 1, 1_000, 0.5D));
    }

    @Test
    @DisplayName("Population and surplus cycles count as at least one; a negative ration counts as zero")
    void breedingFoodClamps() {
        // Population 0 is treated as 1: 2 x 1 x 3 = 6.
        assertFalse(Growth.breedingAllowed(ColonyStage.GROWING, 2, 1, 5, 0, 2, 3, 0.5D, 0.5D));
        assertTrue(Growth.breedingAllowed(ColonyStage.GROWING, 2, 1, 6, 0, 2, 3, 0.5D, 0.5D));
        // Surplus cycles 0 is treated as 1: 2 x 4 x 1 = 8.
        assertFalse(Growth.breedingAllowed(ColonyStage.GROWING, 2, 1, 7, 4, 2, 0, 0.5D, 0.5D));
        assertTrue(Growth.breedingAllowed(ColonyStage.GROWING, 2, 1, 8, 4, 2, 0, 0.5D, 0.5D));
        // A negative ration needs nothing.
        assertTrue(Growth.breedingAllowed(ColonyStage.GROWING, 2, 1, 0, 4, -2, 3, 0.5D, 0.5D));
    }

    @Test
    @DisplayName("The food requirement does not overflow for very large colonies")
    void breedingFoodDoesNotOverflow() {
        // 100 000 x 100 000 x 10 = 10^11, far beyond an int; no stock an int can hold is enough.
        assertFalse(Growth.breedingAllowed(ColonyStage.GROWING, 2, 1, Integer.MAX_VALUE, 100_000, 100_000, 10,
                0.5D, 0.5D));
    }
}
