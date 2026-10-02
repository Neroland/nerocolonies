package za.co.neroland.nerocolonies.entity.ai;

import net.minecraft.world.level.Level;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The Neran day, read from the dimension's clock rather than the sky.
 *
 * <p>{@code Level.isDarkOutside()} reads sky darkness, which rain and thunder raise and which a
 * fixed-time dimension pins to "never dark". The clock has none of those problems.
 *
 * <h2>The schedule</h2>
 *
 * <pre>
 *   0     ─ 6000   WORK
 *   6000  ─ 7000   EAT        (at a granary or canteen, if the colony has one)
 *   7000  ─ 11000  WORK
 *   11000 ─ 12500  SOCIAL     (near the plaza, or the beacon)
 *   12500 ─ 23500  SLEEP      (at home)
 * </pre>
 *
 * <p>Night has a one-second margin ({@value #HYSTERESIS} ticks) on whichever boundary would flip the
 * current decision, so a Neran never trades goals on the threshold tick. In a fixed-time dimension
 * every hour is WORK, or SLEEP if {@code fixedTimeIsDay} is off.
 */
public final class DayCycle {

    /** What a Neran's day says it should be doing. */
    public enum Phase {
        WORK, EAT, SOCIAL, SLEEP
    }

    static final long NIGHT_START = 12_500L;
    static final long NIGHT_END = 23_500L;
    static final long HYSTERESIS = 20L;
    static final long DAY_LENGTH = 24_000L;
    static final long EAT_START = 6_000L;
    static final long EAT_END = 7_000L;
    static final long SOCIAL_START = 11_000L;

    private DayCycle() {
    }

    /** Whether it is night for a Neran. */
    public static boolean isNight(Level level, boolean wasNight) {
        if (level.dimensionType().hasFixedTime()) {
            return !NeroColoniesConfig.FIXED_TIME_IS_DAY.get();
        }
        return isNight(level.getDefaultClockTime(), wasNight);
    }

    /** Pure form of {@link #isNight(Level, boolean)} over a raw clock value. */
    public static boolean isNight(long clockTime, boolean wasNight) {
        long t = Math.floorMod(clockTime, DAY_LENGTH);
        if (wasNight) {
            return t >= NIGHT_START - HYSTERESIS && t < NIGHT_END + HYSTERESIS;
        }
        return t >= NIGHT_START + HYSTERESIS && t < NIGHT_END - HYSTERESIS;
    }

    /** The phase of the day for a Neran, given the phase it was in. */
    public static Phase phase(Level level, Phase previous) {
        if (level.dimensionType().hasFixedTime()) {
            return NeroColoniesConfig.FIXED_TIME_IS_DAY.get() ? Phase.WORK : Phase.SLEEP;
        }
        return phase(level.getDefaultClockTime(), previous);
    }

    /** Pure form of {@link #phase(Level, Phase)}. Unit-tested. */
    public static Phase phase(long clockTime, Phase previous) {
        if (isNight(clockTime, previous == Phase.SLEEP)) {
            return Phase.SLEEP;
        }
        long t = Math.floorMod(clockTime, DAY_LENGTH);
        if (t >= EAT_START && t < EAT_END) {
            return Phase.EAT;
        }
        if (t >= SOCIAL_START && t < NIGHT_START + HYSTERESIS) {
            return Phase.SOCIAL;
        }
        return Phase.WORK;
    }
}
