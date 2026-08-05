package za.co.neroland.nerocolonies.config;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * NeroColonies config schema, built on Neroland Core's config framework (file
 * {@code config/nerocolonies.properties}, hot-reloadable via {@code /neroland config reload}).
 * Registered once from {@link NeroColoniesCommon#init()}, before anything else — every other
 * subsystem reads it, including telemetry's opt-out flag.
 *
 * <p>The whole schema lands here in one go even though later stages are the ones that read most of
 * it: a config file that grows key by key across stages is a config file players have to keep
 * re-reading. Every gameplay key is {@code serverAuthoritative} — colonies are decided by the
 * server and clients are told the values rather than choosing them.
 *
 * <p><b>POPIA/GDPR:</b> {@code telemetryEnabled} is deliberately <b>not</b> server-authoritative —
 * anonymous crash reporting is a per-client opt-out that a server must never force on or off.
 * {@code accessLogEnabled} defaults to <b>false</b>: nothing player-shaped is recorded unless an
 * operator turns it on, and what it records then is bounded by {@code accessLogRetentionDays}.
 */
public final class NeroColoniesConfig {

    public static final ConfigSchema SCHEMA =
            ConfigSchema.create(NeroColoniesCommon.MOD_ID, "NeroColonies configuration.");

    // --- Crash telemetry (client-local opt-out) -----------------------------

    private static final ConfigValue<Boolean> TELEMETRY = SCHEMA.bool(
            "telemetryEnabled", true, false,
            "send anonymous, NeroColonies-only crash reports (Sentry, EU servers) - stack trace, "
                    + "mod/MC/loader/OS/Java versions, your other installed mods, this mod's config, "
                    + "recent in-game actions, anonymous stability/timing; no IP, username, UUID, world "
                    + "data, colony ownership or chat; file paths scrubbed of your account name. "
                    + "false = opt out of all of it. See PRIVACY.md");

    // --- Claims and caps (server-authoritative) -----------------------------

    public static final ConfigValue<Integer> MAX_COLONIES_PER_PLAYER = SCHEMA.intRange(
            "maxColoniesPerPlayer", 3, 0, 64, true,
            "How many colonies one player may own at once. 0 disables founding new colonies.");

    public static final ConfigValue<Integer> MAX_COLONIES_TOTAL = SCHEMA.intRange(
            "maxColoniesTotal", 200, 1, 10000, true,
            "Server-wide colony cap. The safety net behind the per-player cap; also bounds how much "
                    + "work the colony tick can ever create.");

    public static final ConfigValue<Integer> CLAIM_RADIUS = SCHEMA.intRange(
            "claimRadius", 48, 8, 512, true,
            "Beacon claim radius in blocks. RANGE upgrade modules add to this per colony.");

    public static final ConfigValue<Integer> MIN_COLONY_SPACING = SCHEMA.intRange(
            "minColonySpacing", 192, 0, 8192, true,
            "Minimum distance between two colony beacons in the same dimension. A placement inside "
                    + "this radius is refused with a translated message rather than silently allowed.");

    // --- Population and performance (server-authoritative) ------------------

    public static final ConfigValue<Integer> COLONISTS_PER_COLONY = SCHEMA.intRange(
            "colonistsPerColony", 24, 0, 256, true,
            "Population cap per colony. Housing capacity can never raise the roster above this.");

    public static final ConfigValue<Integer> MAX_LOADED_COLONISTS = SCHEMA.intRange(
            "maxLoadedColonists", 300, 0, 5000, true,
            "Global cap on colonist entities alive at once across all loaded colonies.");

    public static final ConfigValue<Integer> COLONY_TICK_INTERVAL_TICKS = SCHEMA.intRange(
            "colonyTickIntervalTicks", 100, 20, 12000, true,
            "How often a colony processes production, food and morale. Colonies are staggered across "
                    + "this interval so N colonies never tick on the same game tick.");

    public static final ConfigValue<Integer> COLONY_TICK_BUDGET_MS = SCHEMA.intRange(
            "colonyTickBudgetMs", 5, 1, 200, true,
            "Millisecond budget for colony processing per game tick. The remainder of a batch is "
                    + "deferred to the next tick rather than blowing the tick time.");

    public static final ConfigValue<Integer> AI_ACTIVE_RADIUS = SCHEMA.intRange(
            "aiActiveRadius", 64, 0, 512, true,
            "Distance from an owner or access-list member within which colonist AI runs at full rate. "
                    + "Beyond it the goal selector runs at a quarter rate and pathfinding is suspended.");

    public static final ConfigValue<Integer> HOUSING_SCAN_INTERVAL_TICKS = SCHEMA.intRange(
            "housingScanIntervalTicks", 600, 100, 24000, true,
            "How often the claim is rescanned for housing blocks to recompute capacity and comfort.");

    // --- Offline catch-up (server-authoritative) ----------------------------

    public static final ConfigValue<Integer> CATCH_UP_MAX_HOURS = SCHEMA.intRange(
            "catchUpMaxHours", 24, 0, 720, true,
            "Cap on the offline window a colony catches up on when its chunk reloads. 0 disables "
                    + "catch-up entirely (colonies then only ever produce while loaded).");

    public static final ConfigValue<Double> CATCH_UP_EFFICIENCY = SCHEMA.doubleRange(
            "catchUpEfficiency", 0.5D, 0.0D, 1.0D, true,
            "Multiplier applied to production and consumption during offline catch-up. Below 1.0 so "
                    + "there is no incentive to chunk-load a planet for free yield.");

    // --- Life support and food (server-authoritative) -----------------------

    public static final ConfigValue<Integer> FOOD_PER_COLONIST_PER_CYCLE = SCHEMA.intRange(
            "foodPerColonistPerCycle", 1, 0, 64, true,
            "Food items consumed per colonist per colony tick. 0 makes colonies never hungry.");

    public static final ConfigValue<Integer> OXYGEN_MB_PER_COLONIST_PER_CYCLE = SCHEMA.intRange(
            "oxygenMbPerColonistPerCycle", 20, 0, 10000, true,
            "Millibuckets of oxygen gas burnt per colonist per colony tick to hold life support.");

    public static final ConfigValue<Long> OXYGEN_GENERATOR_ENERGY_PER_TICK = SCHEMA.longRange(
            "oxygenGeneratorEnergyPerTick", 40L, 0L, 1_000_000L, true,
            "Energy per tick the colony oxygen generator draws while running.");

    public static final ConfigValue<Integer> LIFE_SUPPORT_GRACE_TICKS = SCHEMA.intRange(
            "lifeSupportGraceTicks", 1200, 0, 72000, true,
            "How long life support stays DEGRADED before it is considered FAILED. Failure drives "
                    + "morale decay; it never kills a colonist.");

    // --- Morale (server-authoritative) --------------------------------------

    public static final ConfigValue<Double> MORALE_BASE = SCHEMA.doubleRange(
            "moraleBase", 50.0D, 0.0D, 100.0D, true,
            "Morale baseline before any weighted term is applied.");

    public static final ConfigValue<Double> MORALE_WEIGHT_HOUSING = SCHEMA.doubleRange(
            "moraleWeightHousing", 20.0D, 0.0D, 100.0D, true,
            "Weight of the housing-comfort term in the morale sum.");

    public static final ConfigValue<Double> MORALE_WEIGHT_FOOD = SCHEMA.doubleRange(
            "moraleWeightFood", 20.0D, 0.0D, 100.0D, true,
            "Weight of the food-stock term in the morale sum.");

    public static final ConfigValue<Double> MORALE_WEIGHT_LIFE_SUPPORT = SCHEMA.doubleRange(
            "moraleWeightLifeSupport", 30.0D, 0.0D, 100.0D, true,
            "Weight of the life-support term in the morale sum.");

    public static final ConfigValue<Double> MORALE_WEIGHT_CROWDING = SCHEMA.doubleRange(
            "moraleWeightCrowding", 15.0D, 0.0D, 100.0D, true,
            "Weight of the overcrowding penalty in the morale sum.");

    public static final ConfigValue<Double> MORALE_WEIGHT_HAZARD = SCHEMA.doubleRange(
            "moraleWeightHazard", 10.0D, 0.0D, 100.0D, true,
            "Weight of the planet-hazard penalty in the morale sum. Only ever non-zero when Nerospace "
                    + "is installed and reports a hazardous planet.");

    public static final ConfigValue<Double> MORALE_CHANGE_RATE = SCHEMA.doubleRange(
            "moraleChangeRate", 2.0D, 0.01D, 100.0D, true,
            "Points morale moves toward its target per colony tick. Morale is never snapped.");

    public static final ConfigValue<Double> MORALE_WORK_STOP_THRESHOLD = SCHEMA.doubleRange(
            "moraleWorkStopThreshold", 20.0D, 0.0D, 100.0D, true,
            "Below this morale jobs halt and colonists idle. Colonists are never deleted.");

    public static final ConfigValue<Double> MORALE_MIN_MULTIPLIER = SCHEMA.doubleRange(
            "moraleMinMultiplier", 0.25D, 0.0D, 1.0D, true,
            "Output multiplier floor at zero morale. Production is a curve down to this, not a cliff.");

    // --- Jobs and exports (server-authoritative) ----------------------------

    public static final ConfigValue<Integer> JOB_SLOTS_PER_COLONY = SCHEMA.intRange(
            "jobSlotsPerColony", 4, 0, 64, true,
            "Base number of simultaneously worked job slots per colony. Research raises it.");

    public static final ConfigValue<Double> JOB_BASE_RATE_MULTIPLIER = SCHEMA.doubleRange(
            "jobBaseRateMultiplier", 1.0D, 0.0D, 100.0D, true,
            "Global scalar on every job's production rate.");

    public static final ConfigValue<Integer> EXPORT_BUFFER_SLOTS = SCHEMA.intRange(
            "exportBufferSlots", 18, 1, 54, true,
            "Slots in the beacon's export buffer. Overflow blocks further export production rather "
                    + "than voiding items.");

    public static final ConfigValue<Double> EXPORT_VALUE_MULTIPLIER = SCHEMA.doubleRange(
            "exportValueMultiplier", 1.0D, 0.0D, 1000.0D, true,
            "Scalar on the credits paid when an export entry is sold.");

    // --- Outposts (server-authoritative) ------------------------------------

    public static final ConfigValue<Integer> OUTPOSTS_PER_COLONY = SCHEMA.intRange(
            "outpostsPerColony", 4, 0, 64, true,
            "How many outposts one colony may parent.");

    public static final ConfigValue<Integer> OUTPOST_CLAIM_RADIUS = SCHEMA.intRange(
            "outpostClaimRadius", 16, 4, 256, true,
            "Claim radius of an outpost beacon.");

    public static final ConfigValue<Integer> OUTPOST_COLONIST_CAP = SCHEMA.intRange(
            "outpostColonistCap", 2, 0, 64, true,
            "Colonists an outpost may hold.");

    public static final ConfigValue<Integer> OUTPOST_JOB_SLOTS = SCHEMA.intRange(
            "outpostJobSlots", 1, 0, 16, true,
            "Job slots an outpost may work.");

    public static final ConfigValue<Integer> OUTPOST_MAX_DISTANCE = SCHEMA.intRange(
            "outpostMaxDistance", 512, 16, 16384, true,
            "Maximum distance between an outpost and its parent colony, same dimension only.");

    // --- Privacy (server-authoritative except where noted) ------------------

    public static final ConfigValue<Boolean> ACCESS_LOG_ENABLED = SCHEMA.bool(
            "accessLogEnabled", false, true,
            "OFF by default. When on, a colony records {player UUID, action, timestamp} rows for "
                    + "administrative review - never chat, never IP, never coordinates. Rows expire "
                    + "after accessLogRetentionDays. See PRIVACY.md.");

    public static final ConfigValue<Integer> ACCESS_LOG_RETENTION_DAYS = SCHEMA.intRange(
            "accessLogRetentionDays", 7, 1, 365, true,
            "How long an access-log row is kept before the retention sweep deletes it.");

    public static final ConfigValue<String> ERASURE_OWNED_COLONY_POLICY = SCHEMA.string(
            "erasureOwnedColonyPolicy", "transfer_to_server", true,
            "What happens to colonies owned by a player who requests erasure: transfer_to_server "
                    + "(the colony keeps running, ownerless - a co-op server is not griefed) or "
                    + "dissolve (the colony record is deleted).");

    // --- Ecosystem integration (server-authoritative) -----------------------

    public static final ConfigValue<Boolean> GATE_WRITES_ENABLED = SCHEMA.bool(
            "gateWritesEnabled", true, true,
            "Whether founding a colony opens Core's first_colony progression gate. NeroColonies never "
                    + "REQUIRES a gate to be open; this only controls the write.");

    public static final ConfigValue<Boolean> THRESHOLD_EVENTS_ENABLED = SCHEMA.bool(
            "thresholdEventsEnabled", true, true,
            "Whether colony food/oxygen/morale threshold crossings are published on Core's event bus "
                    + "for other mods (e.g. NeroQuests objectives). Scope is a colony id, never a person.");

    public static final ConfigValue<Boolean> LINK_MODULE_ENABLED = SCHEMA.bool(
            "linkModuleEnabled", true, true,
            "Whether the NeroLink companion module is registered. Snapshots are per-player scoped and "
                    + "never enumerate other players.");

    private NeroColoniesConfig() {
    }

    /**
     * Whether anonymous NeroColonies-only crash reporting is on (default true, opt-out). Read once at
     * bootstrap by {@code NeroColoniesTelemetry.init()}; changes take effect on restart.
     */
    public static boolean isTelemetryEnabled() {
        return TELEMETRY.get();
    }

    /** True when the erasure policy is to dissolve owned colonies rather than transfer them. */
    public static boolean erasureDissolves() {
        return "dissolve".equalsIgnoreCase(ERASURE_OWNED_COLONY_POLICY.get());
    }

    /** Registers the schema with Core's ConfigManager. Called once from common init. */
    public static void init() {
        ConfigManager.register(SCHEMA);
    }
}
