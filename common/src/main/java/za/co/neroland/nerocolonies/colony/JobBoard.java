package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.JobDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The colony's work: which stations are running, who is working them, and what they produce.
 *
 * <h2>Production runs on the colony tick, not on the block</h2>
 *
 * <p>A job station does <b>not</b> run its own recipe. It files itself here (exactly as an oxygen
 * generator files itself with {@link LifeSupport}) and the colony's own cycle drives every station it
 * owns, inside the one {@code colonyTickBudgetMs} watchdog. The alternative — N block entities each
 * ticking their own recipe — would put a colony's whole production cost outside the budget that
 * exists to bound it, and would make "twenty stations" a server problem rather than a design choice.
 *
 * <p>The registry is <b>session state</b>: nothing here is persisted. A station's real existence is
 * its block; a registration that stops being refreshed (broken, unloaded, chunk gone) expires on its
 * own, and a reloaded world rebuilds the board from the stations that re-register.
 *
 * <h2>Throughput</h2>
 *
 * <pre>{@code
 * progress += elapsedTicks
 *           * jobBaseRateMultiplier      // server-wide scalar
 *           * moraleMultiplier           // 0.25..1.0 from colony morale
 *           * workers                    // assigned colonists (1 for an unstaffed job)
 *           * speedMultiplier            // SPEED modules on the station
 *           * powerFactor;               // 1.0 powered, 0.35 unpowered
 * }</pre>
 *
 * <p><b>Unpowered is slow, not stopped.</b> That is the same graceful-failure rule the rest of the
 * mod follows: a colony whose cable was cut keeps working badly rather than stopping dead, because a
 * problem you can see and fix is better than a colony that simply went quiet.
 *
 * <h2>Job slots</h2>
 *
 * <p>Only {@code jobSlotsPerColony} stations (plus whatever research adds) work at once, first-fit in
 * a stable position order so the set does not churn between ticks. Outposts get their own small
 * budget of {@code outpostJobSlots} each, which is what stops an outpost being a way to buy more
 * colony throughput than research allows.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing here is player-shaped. Stations are positions, workers are interchangeable colonists
 * that carry no identity, and everything logged is a count.
 */
public final class JobBoard {

    /** How long a station registration stays valid without a refresh, in ticks. */
    private static final long STATION_TTL_TICKS = 200L;

    /** Crafts one station may complete in a single colony cycle, so a backlog cannot spike a tick. */
    private static final int MAX_CRAFTS_PER_CYCLE = 8;

    /** Throughput multiplier for a station with no power. Slow, never stopped. */
    private static final double UNPOWERED_RATE = 0.35D;

    /** Registered stations: colony id -> packed position -> station. */
    private static final Map<UUID, Map<Long, Station>> STATIONS = new ConcurrentHashMap<>();

    private JobBoard() {
    }

    // --- station state ------------------------------------------------------

    /** One registered job station's live state. Session-only; see the class notes. */
    public static final class Station {

        private final long packedPos;

        /** The outpost this station stands in, or {@code null} when it is in the colony proper. */
        @Nullable
        private UUID outpostId;

        private long lastSeen;
        private double progress;
        private double cycleTicks = 1.0D;

        @Nullable
        private Identifier jobId;

        private boolean active;
        private int assigned;
        private int required;
        private boolean blocked;

        Station(long packedPos, @Nullable UUID outpostId, long now) {
            this.packedPos = packedPos;
            this.outpostId = outpostId;
            this.lastSeen = now;
        }

        /** The job this station is currently set up to run, or {@code null}. */
        @Nullable
        public Identifier jobId() {
            return this.jobId;
        }

        /** Whether the station holds one of the colony's job slots this cycle. */
        public boolean active() {
            return this.active;
        }

        /** Whether the station is held up by missing inputs or a full destination. */
        public boolean blocked() {
            return this.blocked;
        }

        /** Colonists currently working it. */
        public int assigned() {
            return this.assigned;
        }

        /** Colonists the job asks for. */
        public int required() {
            return this.required;
        }

        /** Progress toward the next output, 0..1. */
        public double progressFraction() {
            return this.cycleTicks <= 0.0D ? 0.0D : Math.clamp(this.progress / this.cycleTicks, 0.0D, 1.0D);
        }

        /** Whether this station belongs to an outpost rather than the colony proper. */
        public boolean isOutpost() {
            return this.outpostId != null;
        }

        /** This station's block position, packed. Server-side callers only. */
        public long packedPos() {
            return this.packedPos;
        }
    }

    // --- registry -----------------------------------------------------------

    /**
     * Files (or refreshes) a station against its colony. Called from the station's own tick, which is
     * the only thing that keeps the registration alive.
     *
     * @param outpostId the outpost the station stands in, or {@code null} for the colony proper
     */
    public static void register(UUID colonyId, @Nullable UUID outpostId, ServerLevel level, BlockPos pos) {
        if (colonyId == null) {
            return;
        }
        long packed = pos.asLong();
        long now = level.getGameTime();
        Map<Long, Station> stations = STATIONS.computeIfAbsent(colonyId, key -> new ConcurrentHashMap<>());
        Station station = stations.get(packed);
        if (station == null) {
            stations.put(packed, new Station(packed, outpostId, now));
            return;
        }
        station.lastSeen = now;
        station.outpostId = outpostId;
    }

    /** Drops a station immediately (broken, or it changed colony). */
    public static void unregister(UUID colonyId, BlockPos pos) {
        if (colonyId == null) {
            return;
        }
        Map<Long, Station> stations = STATIONS.get(colonyId);
        if (stations != null) {
            stations.remove(pos.asLong());
            if (stations.isEmpty()) {
                STATIONS.remove(colonyId);
            }
        }
    }

    /** Clears all session state. Called when a server stops so a second world starts clean. */
    public static void reset() {
        STATIONS.clear();
    }

    /** How many stations are currently filed against this colony (including its outposts). */
    public static int stationCount(UUID colonyId) {
        Map<Long, Station> stations = STATIONS.get(colonyId);
        return stations == null ? 0 : stations.size();
    }

    /** How many of them are holding a job slot this cycle. */
    public static int activeCount(UUID colonyId) {
        Map<Long, Station> stations = STATIONS.get(colonyId);
        if (stations == null) {
            return 0;
        }
        int active = 0;
        for (Station station : stations.values()) {
            if (station.active) {
                active++;
            }
        }
        return active;
    }

    /**
     * Every station filed against this colony, in a stable position order — the same order the
     * production cycle uses, so an index into this list means the same thing twice running.
     *
     * <p>Used by the link module, which reports stations by index rather than by position: a
     * companion client has no business being handed a base's coordinates, and an index is enough to
     * name one station in a list the same client was just sent.
     */
    public static List<Station> stationsOf(@Nullable UUID colonyId) {
        Map<Long, Station> stations = colonyId == null ? null : STATIONS.get(colonyId);
        if (stations == null || stations.isEmpty()) {
            return List.of();
        }
        List<Station> out = new ArrayList<>(stations.values());
        out.sort((a, b) -> Long.compare(a.packedPos, b.packedPos));
        return List.copyOf(out);
    }

    /** One station's live state, for its own block entity's readouts. */
    @Nullable
    public static Station station(@Nullable UUID colonyId, BlockPos pos) {
        Map<Long, Station> stations = colonyId == null ? null : STATIONS.get(colonyId);
        return stations == null ? null : stations.get(pos.asLong());
    }

    // --- the colony's production cycle --------------------------------------

    /**
     * Runs every station this colony owns. Called from {@code ColonyTicker} between population and
     * morale, so production sees this cycle's roster and last cycle's morale.
     *
     * @param elapsedTicks how much game time this cycle represents
     * @return the colony record (unchanged: production moves goods, not colony state)
     */
    public static Colony tick(ServerLevel level, Colony colony, int elapsedTicks) {
        Map<Long, Station> registered = STATIONS.get(colony.colonyId());
        if (registered == null || registered.isEmpty()) {
            return colony;
        }
        MinecraftServer server = level.getServer();
        Map<Identifier, JobDefinition> jobs = ColonyDefinitions.jobsForServer(server);
        long now = level.getGameTime();

        List<Station> live = new ArrayList<>(registered.size());
        Map<Long, JobStationBlockEntity> blocks = new LinkedHashMap<>(registered.size());
        for (Station station : registered.values()) {
            if (now - station.lastSeen > STATION_TTL_TICKS) {
                registered.remove(station.packedPos);
                continue;
            }
            BlockPos pos = BlockPos.of(station.packedPos);
            if (!level.isLoaded(pos)) {
                continue; // the chunk is simply away; not evidence the station is gone
            }
            if (!(level.getBlockEntity(pos) instanceof JobStationBlockEntity block)) {
                registered.remove(station.packedPos);
                continue;
            }
            live.add(station);
            blocks.put(station.packedPos, block);
        }
        if (registered.isEmpty()) {
            STATIONS.remove(colony.colonyId());
        }
        if (live.isEmpty()) {
            return colony;
        }
        // A stable order, so which stations hold the job slots does not churn tick to tick.
        live.sort((a, b) -> Long.compare(a.packedPos, b.packedPos));

        resolveJobs(level, colony, jobs, live, blocks);
        allocateSlots(colony, live);
        assignWorkers(level, colony, jobs, live);
        produce(level, colony, jobs, live, blocks, elapsedTicks);
        return colony;
    }

    /** Picks each station's job from the loaded content: the first unlocked job naming its block. */
    private static void resolveJobs(ServerLevel level, Colony colony, Map<Identifier, JobDefinition> jobs,
            List<Station> live, Map<Long, JobStationBlockEntity> blocks) {
        for (Station station : live) {
            JobStationBlockEntity block = blocks.get(station.packedPos);
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(block.getBlockPos()).getBlock());
            Identifier chosen = null;
            for (JobDefinition job : jobs.values()) {
                if (!job.station().equals(blockId) || !ResearchEffects.jobUnlocked(colony, job.id())) {
                    continue;
                }
                chosen = job.id();
                // Prefer a job whose inputs are actually available, so a station with two candidate
                // recipes runs the one it can rather than sulking on the first.
                if (ColonyStorage.hasAll(level.getServer(), colony.colonyId(), job.inputs())) {
                    break;
                }
            }
            station.jobId = chosen;
            JobDefinition job = chosen == null ? null : jobs.get(chosen);
            station.cycleTicks = job == null ? 1.0D : job.ticks();
            station.required = job == null ? 0 : job.colonists();
        }
    }

    /** First-fit over the colony's job slots, plus a separate small budget per outpost. */
    private static void allocateSlots(Colony colony, List<Station> live) {
        boolean stopped = Morale.workStopped(colony);
        int colonyBudget = stopped ? 0 : ResearchEffects.jobSlots(colony);
        int outpostBudget = stopped ? 0 : Math.max(0, NeroColoniesConfig.OUTPOST_JOB_SLOTS.get());
        Map<UUID, Integer> perOutpost = new LinkedHashMap<>();

        for (Station station : live) {
            station.active = false;
            station.blocked = false;
            if (station.jobId == null) {
                continue;
            }
            if (station.outpostId == null) {
                if (colonyBudget > 0) {
                    colonyBudget--;
                    station.active = true;
                }
                continue;
            }
            int used = perOutpost.getOrDefault(station.outpostId, 0);
            if (used < outpostBudget) {
                perOutpost.put(station.outpostId, used + 1);
                station.active = true;
            }
        }
    }

    /**
     * Hands colonists to the active stations, first-fit, and clears the assignment of everyone left
     * over so a colonist whose station was broken stops walking to a hole in the ground.
     *
     * <p>Outpost stations are staffed <b>on paper</b> from the parent's roster up to
     * {@code outpostColonistCap}: an outpost may be half a kilometre from the colony, and marching
     * colonists across that gap every cycle would be a pathfinding bill with nothing to show for it.
     * A remote work site being staffed nominally is the honest simplification.
     */
    private static void assignWorkers(ServerLevel level, Colony colony,
            Map<Identifier, JobDefinition> jobs, List<Station> live) {
        List<ColonistEntity> roster = Population.colonistsOf(level, colony);
        int outpostCap = Math.max(0, NeroColoniesConfig.OUTPOST_COLONIST_CAP.get());
        int cursor = 0;

        for (Station station : live) {
            JobDefinition job = station.jobId == null ? null : jobs.get(station.jobId);
            if (job == null || !station.active) {
                station.assigned = 0;
                continue;
            }
            if (station.outpostId != null) {
                // Nominal staffing; capped, and zero if the parent colony has nobody at all.
                station.assigned = colony.population() <= 0
                        ? 0
                        : Math.min(Math.max(1, job.colonists()), outpostCap);
                continue;
            }
            int wanted = Math.max(0, job.colonists());
            if (wanted == 0) {
                station.assigned = 0; // fully automated: the rate uses one notional worker
                continue;
            }
            int taken = 0;
            BlockPos pos = BlockPos.of(station.packedPos);
            while (taken < wanted && cursor < roster.size()) {
                ColonistEntity colonist = roster.get(cursor++);
                colonist.setJobStationPos(pos);
                colonist.setJobId(station.jobId);
                taken++;
            }
            station.assigned = taken;
        }
        for (int i = cursor; i < roster.size(); i++) {
            roster.get(i).setJobStationPos(null);
            roster.get(i).setJobId(null);
        }
    }

    /** Advances every active station and completes whatever crafts it has earned. */
    private static void produce(ServerLevel level, Colony colony, Map<Identifier, JobDefinition> jobs,
            List<Station> live, Map<Long, JobStationBlockEntity> blocks, int elapsedTicks) {
        MinecraftServer server = level.getServer();
        double baseRate = NeroColoniesConfig.JOB_BASE_RATE_MULTIPLIER.get();
        double moraleMultiplier = Morale.outputMultiplier(colony);
        int storageSlots = ColonyStorage.usableSlots(level, colony);

        for (Station station : live) {
            JobDefinition job = station.jobId == null ? null : jobs.get(station.jobId);
            if (job == null) {
                station.progress = 0.0D;
                continue;
            }
            if (!station.active) {
                continue; // idle stations hold their progress; they simply do not advance
            }
            if (colony.morale() < job.moraleFloor()) {
                station.blocked = true;
                continue;
            }
            JobStationBlockEntity block = blocks.get(station.packedPos);
            int workers = Math.max(1, station.assigned);
            if (job.colonists() > 0 && station.assigned <= 0) {
                station.blocked = true;
                continue; // a job that needs hands and has none does nothing
            }
            double power = block.hasCraftEnergy() ? 1.0D : UNPOWERED_RATE;
            station.progress += elapsedTicks * baseRate * moraleMultiplier * workers
                    * block.modifiers().speedMultiplier() * power;

            int crafts = 0;
            while (station.progress >= job.ticks() && crafts < MAX_CRAFTS_PER_CYCLE) {
                if (!runOnce(server, colony, job, block, storageSlots)) {
                    station.blocked = true;
                    break;
                }
                station.progress -= job.ticks();
                crafts++;
            }
            // Never bank more than one cycle of unspent progress: a station blocked for an hour must
            // not fire an hour's worth of output the instant it is unblocked.
            station.progress = Math.min(station.progress, job.ticks());
        }
    }

    /**
     * One craft, all or nothing: destination room is checked first, then the inputs are consumed,
     * then the outputs are placed. Checking room first is what keeps a full colony from quietly
     * eating its own inputs.
     *
     * @return {@code true} if a craft completed
     */
    private static boolean runOnce(MinecraftServer server, Colony colony, JobDefinition job,
            JobStationBlockEntity block, int storageSlots) {
        UUID colonyId = colony.colonyId();
        // The job's own flag says what the recipe is for; the station's switch says what this colony
        // is doing with it today. Either one routes the output to the buffer.
        boolean toExports = job.export() || block.exportOutput();
        for (ItemAmount output : job.outputs()) {
            boolean room = toExports
                    ? ExportBuffer.fits(server, colonyId, output)
                    : ColonyStorage.fits(server, colonyId, output, storageSlots);
            if (!room) {
                return false;
            }
        }
        if (!ColonyStorage.consume(server, colonyId, job.inputs())) {
            return false;
        }
        block.consumeCraftEnergy();
        for (ItemAmount output : job.outputs()) {
            ItemStack stack = output.toStack();
            if (stack.isEmpty()) {
                continue;
            }
            int leftover = toExports
                    ? ExportBuffer.insert(server, colonyId, stack)
                    : ColonyStorage.insert(server, colonyId, stack, storageSlots);
            if (leftover > 0) {
                // The room check passed and the insert did not fit: something else wrote to the
                // store between the two. Put the remainder in the working stock rather than voiding
                // it — nothing this mod produces is ever destroyed by a race.
                ColonyStorage.insert(server, colonyId, stack.copyWithCount(leftover),
                        ColonyStores.STORAGE_SLOTS);
            }
        }
        return true;
    }
}
