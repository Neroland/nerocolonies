package za.co.neroland.nerocolonies.colony;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * How fast a colony grows: slowly at first, then faster, then levelling off.
 *
 * <h2>The curve</h2>
 *
 * <p>A colony's <b>growth score</b> is {@code population + 2 × structures}. The score feeds a logistic
 * curve
 *
 * <pre>{@code
 * g(score) = 1 / (1 + e^(-k * (score - midpoint)))        k = 0.15, midpoint = 20
 * }</pre>
 *
 * <p>which is near 0 for a fresh colony, 0.5 at the midpoint and approaches 1 for a large one. Three
 * things scale with it, each capped by config:
 *
 * <ul>
 *   <li>construction speed: {@code 1 + (growthMaxBuildMultiplier - 1) × g};</li>
 *   <li>breeding chance per colony cycle: {@code breedingBaseChance × (0.25 + 0.75 × g)};</li>
 *   <li>claim radius bonus: {@code round(growthMaxClaimBonus × g)} blocks on top of the base claim.</li>
 * </ul>
 *
 * <p>So a ten-block colony builds a little faster than a new one, a thirty-block colony two to
 * three times as fast, and a sixty-block colony close to the cap. Every number lives in config.
 *
 * <p>Pure arithmetic plus config reads; the pure halves are unit-tested.
 */
public final class Growth {

    /** Steepness of the logistic. */
    static final double STEEPNESS = 0.15D;

    /** Score at which growth is half way to its cap. */
    static final double MIDPOINT = 20.0D;

    private Growth() {
    }

    /** The growth score: population plus twice the structures built. */
    public static int score(int population, int structures) {
        return Math.max(0, population) + 2 * Math.max(0, structures);
    }

    /** The logistic {@code g(score)} in (0, 1). Pure. */
    public static double curve(int score) {
        return 1.0D / (1.0D + Math.exp(-STEEPNESS * (score - MIDPOINT)));
    }

    /** Construction speed multiplier for a score and cap. Pure. */
    public static double buildMultiplier(int score, double maxMultiplier) {
        return 1.0D + (Math.max(1.0D, maxMultiplier) - 1.0D) * curve(score);
    }

    /** Breeding chance per cycle for a score and base chance. Pure. */
    public static double breedingChance(int score, double baseChance) {
        return Math.clamp(baseChance, 0.0D, 1.0D) * (0.25D + 0.75D * curve(score));
    }

    /** Claim radius bonus in blocks for a score and cap. Pure. */
    public static int claimBonus(int score, int maxBonus) {
        return (int) Math.round(Math.max(0, maxBonus) * curve(score));
    }

    // --- config-backed --------------------------------------------------------

    public static double buildMultiplierFor(int population, int structures) {
        return buildMultiplier(score(population, structures),
                NeroColoniesConfig.GROWTH_MAX_BUILD_MULTIPLIER.get());
    }

    public static double breedingChanceFor(int population, int structures) {
        return breedingChance(score(population, structures), NeroColoniesConfig.BREEDING_BASE_CHANCE.get());
    }

    public static int claimBonusFor(int population, int structures) {
        return claimBonus(score(population, structures), NeroColoniesConfig.GROWTH_MAX_CLAIM_BONUS.get());
    }

    // --- stages ----------------------------------------------------------------

    /**
     * The stage a colony has earned, given whether its Starter Works stand and how big it is. Never
     * goes backwards on its own (the caller keeps the higher of this and the current stage). Pure.
     *
     * @param thresholds {@code [growingPop, growingStructures, thrivingPop, thrivingStructures,
     *                   metropolisPop, metropolisStructures]}
     */
    public static ColonyStage earnedStage(boolean starterWorksDone, int population, int structures,
            int[] thresholds) {
        if (!starterWorksDone) {
            return ColonyStage.FOUNDING;
        }
        if (population >= thresholds[4] && structures >= thresholds[5]) {
            return ColonyStage.METROPOLIS;
        }
        if (population >= thresholds[2] && structures >= thresholds[3]) {
            return ColonyStage.THRIVING;
        }
        if (population >= thresholds[0] && structures >= thresholds[1]) {
            return ColonyStage.GROWING;
        }
        return ColonyStage.SETTLED;
    }

    /** The stage thresholds from config, in {@link #earnedStage} order. */
    public static int[] thresholds() {
        return new int[] {
            NeroColoniesConfig.STAGE_GROWING_POPULATION.get(),
            NeroColoniesConfig.STAGE_GROWING_STRUCTURES.get(),
            NeroColoniesConfig.STAGE_THRIVING_POPULATION.get(),
            NeroColoniesConfig.STAGE_THRIVING_STRUCTURES.get(),
            NeroColoniesConfig.STAGE_METROPOLIS_POPULATION.get(),
            NeroColoniesConfig.STAGE_METROPOLIS_STRUCTURES.get()
        };
    }

    /** The next milestone for a stage, as {population, structures}; null at the top or while founding. */
    public static int[] nextMilestone(ColonyStage stage) {
        int[] t = thresholds();
        return switch (stage) {
            case SETTLED -> new int[] {t[0], t[1]};
            case GROWING -> new int[] {t[2], t[3]};
            case THRIVING -> new int[] {t[4], t[5]};
            default -> null;
        };
    }

    // --- breeding --------------------------------------------------------------

    /**
     * Whether conditions allow a birth this cycle (before the dice): two adults, a free bed, a food
     * surplus of several cycles, decent morale, and the colony past founding. Pure; unit-tested.
     */
    public static boolean breedingAllowed(ColonyStage stage, int adults, int freeBeds, int foodStock,
            int population, int foodPerColonistPerCycle, int surplusCycles, double morale,
            double moraleFloor) {
        if (!stage.atLeast(ColonyStage.GROWING)) {
            return false;
        }
        if (adults < 2 || freeBeds < 1 || morale < moraleFloor) {
            return false;
        }
        long needed = (long) Math.max(0, foodPerColonistPerCycle) * Math.max(1, population)
                * Math.max(1, surplusCycles);
        return foodStock >= needed;
    }
}
