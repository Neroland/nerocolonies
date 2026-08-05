package za.co.neroland.nerocolonies.colony;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

import za.co.neroland.nerolandcore.event.ThresholdEvents;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.link.ColonyLinkEvents;
import za.co.neroland.nerocolonies.progression.ColonyGates;

/**
 * The colony tick: one place where a colony's whole cycle happens, and one budget that bounds it.
 *
 * <h2>Where the tick comes from</h2>
 *
 * <p>The colony beacon's block entity drives this, once per game tick. That is deliberate rather
 * than a server-tick event, and it gives three things for free: the tick exists exactly while the
 * beacon's chunk is loaded (which is the catch-up design's whole premise), it needs no per-loader
 * event wiring at all, and a colony whose beacon has been destroyed simply stops without anything
 * having to notice.
 *
 * <h2>Staggering and the budget</h2>
 *
 * <p>A colony's cycle runs every {@code colonyTickIntervalTicks}, offset by its own id's hash, so
 * two hundred colonies never land on the same game tick. On top of that, {@link Budget} caps the
 * total colony work done in any one game tick at {@code colonyTickBudgetMs}: a colony that is due
 * when the budget is spent stays due and runs on a later tick. Deferrals are counted and reported
 * periodically as one line, never per colony — a throttle message that itself spams the log is
 * worse than the throttling it reports.
 *
 * <h2>Order of operations, and why it is this order</h2>
 *
 * <ol>
 *   <li><b>Life support</b> — the colony's physical situation, before anything reacts to it.</li>
 *   <li><b>Food</b> — intake from the beacon's supply slots, then the cycle's consumption.</li>
 *   <li><b>Population</b> — growth is gated on the two above, so it sees this cycle's truth.</li>
 *   <li><b>Jobs</b> — production, budgeted centrally (the seam is here; the work arrives with the
 *       job stations).</li>
 *   <li><b>Morale</b> — last, because it is a reaction to everything above.</li>
 *   <li><b>Threshold events</b> — published only on an actual crossing, scoped to the colony id.</li>
 * </ol>
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>The threshold events published here carry a colony id as their scope and <b>never a player</b>.
 * That is not incidental: Core's event bus is a broadcast surface that any mod can subscribe to, and
 * a colony id identifies a place, not a person. Log lines from this class are counts only.
 */
public final class ColonyTicker {

    /** Core threshold channel: the colony's stored rations. */
    public static final Identifier CHANNEL_FOOD_STOCK =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "food_stock");

    /** Core threshold channel: whether life support is holding (1) or failed (0). */
    public static final Identifier CHANNEL_OXYGEN =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "oxygen");

    /** Core threshold channel: the colony's morale, 0..100. */
    public static final Identifier CHANNEL_MORALE =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "morale");

    /** Morale level whose crossing is worth publishing — the work-stop threshold is the interesting one. */
    private static final long MORALE_THRESHOLD_FALLBACK = 20L;

    /** Ticks between throttle reports. Long on purpose: this is a diagnostic, not an alarm. */
    private static final long THROTTLE_REPORT_INTERVAL = 6_000L;

    private ColonyTicker() {
    }

    // --- per-colony state ---------------------------------------------------

    /**
     * One colony's ticking state. Owned by its beacon's block entity, so it lives exactly as long as
     * the beacon is loaded and never needs saving — a reloaded beacon catches up from
     * {@code Colony.lastTick} instead, which is the persisted truth.
     */
    public static final class State {

        private final HousingScan.State housing = new HousingScan.State();

        private boolean caughtUp;
        private boolean due;

        private boolean lastLifeSupportOk = true;
        private boolean lastStarving;
        private boolean lastWorkStopped;
        private LifeSupport.State lastLifeState = LifeSupport.State.OK;
        private boolean lastExportsFull;

        /** The housing sweep state, for the beacon to expose to its GUI. */
        public HousingScan.State housing() {
            return this.housing;
        }

        /** Forces a fresh catch-up and housing cycle (used when the claim radius changes). */
        public void invalidate() {
            this.caughtUp = false;
            this.housing.restart();
        }
    }

    // --- the tick -----------------------------------------------------------

    /**
     * Advances one colony by one game tick. Almost every call does the housing sweep's countdown and
     * nothing else.
     *
     * @param supply the beacon's food supply slots
     * @return the colony record, updated if anything happened this tick
     */
    public static Colony tick(ServerLevel level, Colony colony, State state, Container supply) {
        // The housing sweep runs on its own cadence and is budgeted internally.
        HousingScan.tick(level, colony, state.housing);

        if (!state.caughtUp) {
            state.caughtUp = true;
            Colony rejoined = ColonyCatchUp.apply(level, colony, state.housing.comfortRatio(),
                    state.housing.capacity());
            // Catch-up is a resumption, not a change: seeding the "last seen" values from it means a
            // colony that reloads already starving does not announce that it has just started.
            remember(level, state, rejoined);
            return rejoined;
        }

        long gameTime = level.getGameTime();
        int interval = Math.max(1, NeroColoniesConfig.COLONY_TICK_INTERVAL_TICKS.get());
        // Stagger by the colony's own id, so N colonies never share a game tick.
        long offset = Math.floorMod(colony.colonyId().hashCode(), interval);
        if (Math.floorMod(gameTime, interval) == offset) {
            state.due = true;
        }
        if (!state.due) {
            return colony;
        }
        if (!Budget.claim(gameTime)) {
            return colony; // still due; it will run as soon as a tick has room
        }
        state.due = false;

        long started = System.nanoTime();
        Colony updated = runCycle(level, colony, state, supply);
        Budget.spend(System.nanoTime() - started);
        return updated;
    }

    /** One colony cycle. See the class notes for why the steps are in this order. */
    private static Colony runCycle(ServerLevel level, Colony colony, State state, Container supply) {
        // Pick up a /reload cheaply: the common case is one reference comparison.
        ColonyDefinitions.refreshIfReloaded(level.getServer());

        int interval = Math.max(1, NeroColoniesConfig.COLONY_TICK_INTERVAL_TICKS.get());
        Colony updated = colony;

        // 1. Life support.
        updated = LifeSupport.tick(level, updated, interval);

        // 2. Food: what arrived, then what was eaten.
        updated = FoodSupply.intake(supply, updated);
        updated = FoodSupply.consume(updated, 1, 1.0D);

        // 3. Population.
        updated = Population.tick(level, updated, state.housing.capacity(), state.housing.homes());
        if (updated.housingCapacity() != state.housing.capacity()) {
            updated = updated.withHousingCapacity(state.housing.capacity());
        }

        // 4. Jobs. Job stations run their recipes on the COLONY tick so throughput is budgeted in one
        //    place rather than per block entity.
        updated = tickJobs(level, updated, interval);

        // 5. Morale, reacting to everything above.
        updated = Morale.apply(level, updated, state.housing.comfortRatio(), state.housing.capacity(), 1);

        // 5b. NeroColonies' own soft progression gate, written for other mods to read. Never read
        //     here, and never a requirement for anything.
        ColonyGates.tick(level, updated);

        // 6. Threshold events and companion-client events, on crossings only.
        publishCrossings(state, updated);
        publishLinkEvents(level, state, updated);
        remember(level, state, updated);

        return updated.withLastTick(level.getGameTime());
    }

    /**
     * Job-station production.
     *
     * <p>Stations register themselves with {@link JobBoard} and the board runs them all from here, so
     * a colony's whole production cost is inside the one budget rather than spread across N
     * block-entity tickers.
     *
     * <p>Work stopping is checked twice on purpose. Here it is the <b>fast exit</b>: a demoralised
     * colony does no production work at all, not even the bookkeeping. Inside {@link JobBoard} it is
     * checked again when allocating job slots, so a station's own state still updates to say why it
     * is idle — a player who opens a station wants to be told "morale is too low", not left looking
     * at a silent machine.
     */
    private static Colony tickJobs(ServerLevel level, Colony colony, int elapsedTicks) {
        if (Morale.workStopped(colony)) {
            // Work stops. Colonists idle; nothing is destroyed and nobody is removed.
            return JobBoard.tick(level, colony, 0);
        }
        return JobBoard.tick(level, colony, elapsedTicks);
    }

    // --- threshold events ---------------------------------------------------

    /**
     * Publishes a Core threshold crossing for each of the three colony signals that changed state.
     * Crossings only — a colony that has been starving for an hour publishes nothing further, which
     * is what makes these usable as NeroQuests objective triggers.
     */
    private static void publishCrossings(State state, Colony colony) {
        if (!NeroColoniesConfig.THRESHOLD_EVENTS_ENABLED.get()) {
            return;
        }
        String scope = colony.colonyId().toString(); // a place, never a person
        try {
            boolean starving = FoodSupply.starving(colony);
            if (starving != state.lastStarving) {
                ThresholdEvents.fire(new ThresholdEvents.ThresholdCrossing(
                        CHANNEL_FOOD_STOCK, scope, colony.foodStock(), 0L, !starving));
            }
            if (colony.lifeSupportOk() != state.lastLifeSupportOk) {
                ThresholdEvents.fire(new ThresholdEvents.ThresholdCrossing(
                        CHANNEL_OXYGEN, scope, colony.lifeSupportOk() ? 1L : 0L, 1L,
                        colony.lifeSupportOk()));
            }
            boolean stopped = Morale.workStopped(colony);
            if (stopped != state.lastWorkStopped) {
                long threshold = Math.round(NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get());
                ThresholdEvents.fire(new ThresholdEvents.ThresholdCrossing(
                        CHANNEL_MORALE, scope, Math.round(colony.morale()),
                        threshold == 0 ? MORALE_THRESHOLD_FALLBACK : threshold, !stopped));
            }
        } catch (RuntimeException | LinkageError e) {
            // A subscriber that throws must not take the colony tick with it.
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] A threshold-event subscriber failed; the colony tick continued.", e);
        }
    }

    /**
     * Publishes the same four crossings to a companion client, owner-scoped.
     *
     * <p>Separate from {@link #publishCrossings} on purpose: Core's threshold bus and the NeroLink
     * bridge are different audiences with different switches ({@code thresholdEventsEnabled} versus
     * {@code linkModuleEnabled}) and different privacy rules — a threshold crossing is scoped to a
     * colony id and read by any mod, while a link event goes to one person's sessions and may
     * therefore say rather more.
     */
    private static void publishLinkEvents(ServerLevel level, State state, Colony colony) {
        try {
            LifeSupport.State lifeState = LifeSupport.stateOf(colony);
            if (lifeState != state.lastLifeState) {
                ColonyLinkEvents.lifeSupportChanged(level, colony, lifeState);
            }
            boolean starving = FoodSupply.starving(colony);
            if (starving != state.lastStarving) {
                ColonyLinkEvents.foodChanged(level, colony, starving);
            }
            boolean stopped = Morale.workStopped(colony);
            if (stopped != state.lastWorkStopped) {
                ColonyLinkEvents.workStopChanged(level, colony, stopped);
            }
            boolean full = exportsFull(level, colony);
            if (full != state.lastExportsFull) {
                ColonyLinkEvents.exportBufferChanged(level, colony, full);
            }
        } catch (RuntimeException | LinkageError e) {
            // A companion bridge that throws must not take the colony tick with it.
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] A NeroLink publisher failed; the colony tick continued.", e);
        }
    }

    private static boolean exportsFull(ServerLevel level, Colony colony) {
        return level.getServer() != null && ExportBuffer.isFull(level.getServer(), colony.colonyId());
    }

    private static void remember(ServerLevel level, State state, Colony colony) {
        state.lastLifeSupportOk = colony.lifeSupportOk();
        state.lastStarving = FoodSupply.starving(colony);
        state.lastWorkStopped = Morale.workStopped(colony);
        state.lastLifeState = LifeSupport.stateOf(colony);
        state.lastExportsFull = exportsFull(level, colony);
    }

    // --- the per-game-tick budget -------------------------------------------

    /**
     * The server-wide colony-processing budget for one game tick.
     *
     * <p>Static because the budget is a property of the <em>server tick</em>, not of any one colony:
     * the whole point is that a hundred colonies coming due together cannot between them blow the
     * tick. Server-thread only, like everything else in this package, so nothing here is
     * synchronised.
     */
    private static final class Budget {

        private static long tick = Long.MIN_VALUE;
        private static long spentNanos;
        private static long deferrals;
        private static long lastReportTick;

        private Budget() {
        }

        /** Whether a colony may run its cycle on this game tick. Counts a deferral when it may not. */
        static boolean claim(long gameTime) {
            if (gameTime != tick) {
                tick = gameTime;
                spentNanos = 0L;
                report(gameTime);
            }
            long budgetNanos = NeroColoniesConfig.COLONY_TICK_BUDGET_MS.get() * 1_000_000L;
            if (spentNanos >= budgetNanos) {
                deferrals++;
                return false;
            }
            return true;
        }

        static void spend(long nanos) {
            spentNanos += Math.max(0L, nanos);
        }

        /** One aggregate line, at most every {@value #THROTTLE_REPORT_INTERVAL} ticks. */
        private static void report(long gameTime) {
            if (deferrals <= 0 || gameTime - lastReportTick < THROTTLE_REPORT_INTERVAL) {
                return;
            }
            lastReportTick = gameTime;
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] Colony processing deferred {} time(s) since the last report "
                            + "(colonyTickBudgetMs = {}). Colonies still ticked, just later.",
                    deferrals, NeroColoniesConfig.COLONY_TICK_BUDGET_MS.get());
            deferrals = 0L;
        }
    }
}
