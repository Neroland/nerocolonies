package za.co.neroland.nerocolonies.colony;

import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerocolonies.compat.CompatRegistry;
import za.co.neroland.nerocolonies.compat.NerospaceCompat;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The morale engine: a weighted sum, moved toward gradually, with two outputs.
 *
 * <h2>The formula</h2>
 *
 * <pre>{@code
 * target = base
 *        + w_housing * comfortRatio
 *        + w_food    * foodRatio
 *        + w_life    * lifeSupportFactor
 *        - w_crowd   * overcrowding
 *        - w_hazard  * hazardPenalty
 *        + researchMoraleBonus
 * }</pre>
 *
 * <p><b>Every weight is a config key.</b> That is not a hedge — it is the answer to a question the
 * design notes left open, and it means a server can make morale a gentle nudge or the whole game
 * without a code change. Morale then moves toward the target by {@code moraleChangeRate} per colony
 * cycle and is <b>never snapped</b>, so a single bad tick cannot collapse a colony and a single
 * repair cannot instantly redeem one.
 *
 * <h2>The two outputs, and the failure curve</h2>
 *
 * <ul>
 *   <li>{@link #outputMultiplier(Colony)} — a smooth production scalar from
 *       {@code moraleMinMultiplier} at zero morale to {@code 1.0} at full.</li>
 *   <li>{@link #workStopped(Colony)} — a hard floor at {@code moraleWorkStopThreshold} below which
 *       jobs halt and colonists idle.</li>
 * </ul>
 *
 * <p>Those are the <em>only</em> consequences. The failure curve runs <b>life support loss → morale
 * decay → work stop → idle</b> and stops there. No colonist is ever deleted, no building is ever
 * destroyed, and nothing is ever lost that cannot be recovered by fixing the cause. A colony that
 * has gone wrong is a problem to solve, not a save file to mourn.
 *
 * <h2>The hazard term</h2>
 *
 * <p>{@code w_hazard} is the only weight that can be inert: it applies solely when Nerospace is
 * installed and reports a {@code HEAT} or {@code COLD} planet. On Earth, and on every dimension
 * without Nerospace, the term is exactly zero.
 */
public final class Morale {

    /** Life-support contribution by state: holding, coasting, failed. */
    private static final double LIFE_FACTOR_OK = 1.0D;
    private static final double LIFE_FACTOR_DEGRADED = 0.5D;
    private static final double LIFE_FACTOR_FAILED = 0.0D;

    private Morale() {
    }

    /**
     * The morale this colony is heading toward, 0..100.
     *
     * @param comfortRatio capacity-weighted mean housing comfort from the last housing sweep, 0..1
     * @param capacity     housing capacity from the last housing sweep
     */
    public static double target(ServerLevel level, Colony colony, double comfortRatio, int capacity) {
        double value = NeroColoniesConfig.MORALE_BASE.get();
        value += NeroColoniesConfig.MORALE_WEIGHT_HOUSING.get() * Math.clamp(comfortRatio, 0.0D, 1.0D);
        value += NeroColoniesConfig.MORALE_WEIGHT_FOOD.get() * FoodSupply.foodRatio(colony);
        value += NeroColoniesConfig.MORALE_WEIGHT_LIFE_SUPPORT.get() * lifeFactor(colony);
        value -= NeroColoniesConfig.MORALE_WEIGHT_CROWDING.get() * overcrowding(colony, capacity);
        value -= NeroColoniesConfig.MORALE_WEIGHT_HAZARD.get() * hazardPenalty(level);
        value += ResearchEffects.moraleBonus(colony);
        return Math.clamp(value, 0.0D, 100.0D);
    }

    /**
     * Moves the colony's morale toward its target by at most {@code moraleChangeRate} per cycle.
     *
     * @param cycles how many cycles to apply (1 normally; more during offline catch-up)
     * @return the colony record with its morale updated (possibly the same instance)
     */
    public static Colony apply(ServerLevel level, Colony colony, double comfortRatio, int capacity,
            int cycles) {
        if (cycles <= 0) {
            return colony;
        }
        double goal = target(level, colony, comfortRatio, capacity);
        double step = NeroColoniesConfig.MORALE_CHANGE_RATE.get() * cycles;
        double current = colony.morale();
        double next = current < goal
                ? Math.min(goal, current + step)
                : Math.max(goal, current - step);
        // Rounded so a fraction of a point never marks the store dirty on every single cycle.
        if (Math.abs(next - current) < 0.001D) {
            return colony;
        }
        return colony.withMorale(next);
    }

    /** Production scalar: {@code moraleMinMultiplier} at zero morale, {@code 1.0} at full. */
    public static double outputMultiplier(Colony colony) {
        double floor = NeroColoniesConfig.MORALE_MIN_MULTIPLIER.get();
        double fraction = Math.clamp(colony.morale() / 100.0D, 0.0D, 1.0D);
        return floor + (1.0D - floor) * fraction;
    }

    /** Whether morale has fallen below the hard work-stop threshold. */
    public static boolean workStopped(Colony colony) {
        return colony.morale() < NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get();
    }

    private static double lifeFactor(Colony colony) {
        return switch (LifeSupport.stateOf(colony)) {
            case OK -> LIFE_FACTOR_OK;
            case DEGRADED -> LIFE_FACTOR_DEGRADED;
            case FAILED -> LIFE_FACTOR_FAILED;
        };
    }

    /** How far past its housing the colony is packed, 0..1. Zero when everyone has a bunk. */
    private static double overcrowding(Colony colony, int capacity) {
        int population = colony.population();
        if (population <= 0 || population <= capacity) {
            return 0.0D;
        }
        return Math.clamp((population - capacity) / (double) population, 0.0D, 1.0D);
    }

    /** 1.0 on a hazardous planet, 0.0 everywhere else (and always 0.0 without Nerospace). */
    private static double hazardPenalty(ServerLevel level) {
        if (level == null) {
            return 0.0D;
        }
        return CompatRegistry.hazard(level.dimension()) == NerospaceCompat.Hazard.NONE ? 0.0D : 1.0D;
    }
}
