package za.co.neroland.nerocolonies.colony;

import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * Offline catch-up: what happens to a colony while nobody is there.
 *
 * <h2>The decision, and why</h2>
 *
 * <p>Colonies tick <b>only while their beacon chunk is loaded</b>. When the chunk comes back, this
 * class works out how long the colony was away, clamps that to {@code catchUpMaxHours} (24 by
 * default) and applies the missed cycles in one aggregate step at {@code catchUpEfficiency} (0.5).
 *
 * <p>The alternative — ticking every colony on the server forever — was rejected on three counts,
 * and it is worth being explicit because it is the question players ask first:
 * <ul>
 *   <li><b>Cost.</b> Always-on ticking makes the mod's worst case the number of colonies ever
 *       founded rather than the number currently being played, which is exactly the shape of
 *       performance problem that makes a colony mod unusable on a real server.</li>
 *   <li><b>Exploit surface.</b> If offline colonies produced at full rate there would be no reason
 *       ever to visit one, and every reason to found as many as the cap allows and walk away.</li>
 *   <li><b>Honesty.</b> A colony that keeps producing while its chunks are unloaded has to invent
 *       its inputs, because the machines that would have supplied them were not running either.</li>
 * </ul>
 *
 * <p>The 0.5 yield is the compromise: coming back to a colony that has done <em>something</em> is a
 * reward, and it is always strictly worse than having been there.
 *
 * <p>Everything is applied in one aggregate step — no loop over 17,000 skipped cycles — so a colony
 * that has been away for the full 24 hours costs the same as one away for a minute.
 */
public final class ColonyCatchUp {

    private static final long TICKS_PER_HOUR = 72_000L;

    /** Below this many missed cycles the catch-up is not worth reporting. */
    private static final int LOG_THRESHOLD_CYCLES = 20;

    private ColonyCatchUp() {
    }

    /**
     * Applies the missed cycles to a colony whose beacon has just come back.
     *
     * @param comfortRatio housing comfort to use for the morale term (the last committed sweep)
     * @param capacity     housing capacity to use for the crowding term
     * @return the colony record brought up to date, with {@code lastTick} reset
     */
    public static Colony apply(ServerLevel level, Colony colony, double comfortRatio, int capacity) {
        long now = level.getGameTime();
        long elapsed = now - colony.lastTick();
        if (elapsed <= 0) {
            // A fresh colony, or a world whose game time went backwards (a restored backup).
            return colony.withLastTick(now);
        }

        long cap = (long) NeroColoniesConfig.CATCH_UP_MAX_HOURS.get() * TICKS_PER_HOUR;
        if (cap <= 0) {
            return colony.withLastTick(now); // catch-up disabled entirely
        }
        long window = Math.min(elapsed, cap);
        int interval = Math.max(1, NeroColoniesConfig.COLONY_TICK_INTERVAL_TICKS.get());
        int cycles = (int) Math.min(Integer.MAX_VALUE, window / interval);
        if (cycles <= 0) {
            return colony.withLastTick(now);
        }

        double yield = NeroColoniesConfig.CATCH_UP_EFFICIENCY.get();
        Colony updated = colony;

        // Consumption first: a colony that would have starved while away should be found starving,
        // not found fed and then starving one tick later.
        updated = FoodSupply.consume(updated, cycles, yield);

        // Life support over the whole window in one step: the generators were not running either, so
        // this is the honest outcome — a colony left with no atmosphere comes back in FAILED.
        updated = LifeSupport.tick(level, updated, (int) Math.min(Integer.MAX_VALUE, window));

        // Morale last, so it reacts to the state the colony is actually in now. The change is capped
        // by the same per-cycle rate, so a long absence moves morale a long way but never instantly.
        updated = Morale.apply(level, updated, comfortRatio, capacity, cycles);

        if (cycles >= LOG_THRESHOLD_CYCLES) {
            // Counts only — never which colony belongs to whom (POPIA/GDPR).
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] A colony caught up on {} cycle(s) at {}x yield.", cycles, yield);
        }
        return updated.withLastTick(now);
    }
}
