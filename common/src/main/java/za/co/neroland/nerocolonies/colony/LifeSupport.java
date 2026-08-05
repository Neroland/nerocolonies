package za.co.neroland.nerocolonies.colony;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.compat.CompatRegistry;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The colony life-support state machine, and the registry of the generators that feed it.
 *
 * <h2>Whose oxygen this is</h2>
 *
 * <p>NeroColonies runs its <b>own</b> life support, at colony granularity. That is not duplication
 * of Nerospace: Nerospace's oxygen path is {@code Player}-typed end to end and has no NPC route at
 * all, so there was never an implementation to reuse. What Nerospace supplies — through
 * {@link CompatRegistry} and one reflective adapter — is the single fact this system needs from it:
 * whether the dimension has an atmosphere. With Nerospace absent, nothing does, every dimension is
 * breathable, and life support machinery is buildable but idle.
 *
 * <h2>The state machine</h2>
 *
 * <pre>
 *   OK  --(oxygen shortfall)-->  DEGRADED  --(grace expires)-->  FAILED
 *    ^                              |                              |
 *    +---------(oxygen restored)----+------------------------------+
 * </pre>
 *
 * <p>{@code DEGRADED} lasts {@code lifeSupportGraceTicks}, which exists so that a momentary power
 * cut is not a catastrophe. {@code FAILED} drives morale decay and nothing else — it does not, ever,
 * kill a colonist. Recovery from {@code FAILED} is immediate on the first tick oxygen is available
 * again; a colony that has been rescued should feel rescued.
 *
 * <h2>Why generators register themselves</h2>
 *
 * <p>The alternative is for the colony tick to search its claim for generators, which would be a
 * block scan on a hot path. Instead each running {@link OxygenGeneratorBlockEntity} files its
 * position here on a slow cadence and the entry expires if it stops (a broken, unloaded or
 * unpowered generator simply stops refreshing). The registry is session state — nothing here is
 * persisted, and nothing here is player-shaped.
 */
public final class LifeSupport {

    /** The gas life support burns. Nerospace's oxygen id, used as a shared value, not a class. */
    public static final Identifier OXYGEN = Identifier.fromNamespaceAndPath("nerospace", "oxygen");

    /** How long a registration stays valid without a refresh, in ticks. */
    private static final long SOURCE_TTL_TICKS = 200L;

    /** Registered generators: colony id -> packed position -> game time last seen. */
    private static final Map<UUID, Map<Long, Long>> SOURCES = new ConcurrentHashMap<>();

    /** Remaining DEGRADED grace per colony, in ticks. Session state (see the class notes). */
    private static final Map<UUID, Integer> GRACE = new ConcurrentHashMap<>();

    private LifeSupport() {
    }

    /** Life-support states, in the order a colony passes through them. */
    public enum State {
        OK,
        DEGRADED,
        FAILED
    }

    // --- generator registry -------------------------------------------------

    /** Files (or refreshes) a running generator against its colony. Called from the generator's tick. */
    public static void register(UUID colonyId, ServerLevel level, BlockPos pos) {
        if (colonyId == null) {
            return;
        }
        SOURCES.computeIfAbsent(colonyId, key -> new ConcurrentHashMap<>())
                .put(pos.asLong(), level.getGameTime());
    }

    /** Drops a generator immediately (broken, or it stopped running). */
    public static void unregister(UUID colonyId, BlockPos pos) {
        if (colonyId == null) {
            return;
        }
        Map<Long, Long> positions = SOURCES.get(colonyId);
        if (positions != null) {
            positions.remove(pos.asLong());
            if (positions.isEmpty()) {
                SOURCES.remove(colonyId);
            }
        }
    }

    /** Clears all session state. Called when a server stops so a second world starts clean. */
    public static void reset() {
        SOURCES.clear();
        GRACE.clear();
    }

    /** How many generators are currently feeding this colony (for the GUI and the link module). */
    public static int generatorCount(UUID colonyId) {
        Map<Long, Long> positions = SOURCES.get(colonyId);
        return positions == null ? 0 : positions.size();
    }

    // --- the tick -----------------------------------------------------------

    /**
     * Advances one colony's life support by one colony tick.
     *
     * @param elapsedTicks how much game time this colony tick represents (the tick interval, or the
     *                     whole catch-up window when a colony reloads)
     * @return the colony record with {@code lifeSupportOk} refreshed (possibly the same instance)
     */
    public static Colony tick(ServerLevel level, Colony colony, int elapsedTicks) {
        if (!CompatRegistry.requiresLifeSupport(level)) {
            // A breathable dimension: life support is never in trouble, whatever the machinery says.
            GRACE.remove(colony.colonyId());
            return colony.lifeSupportOk() ? colony : colony.withLifeSupport(true);
        }

        long required = requiredOxygenMb(colony);
        long supplied = required <= 0 ? 0L : drain(level, colony, required);

        if (required <= 0 || supplied >= required) {
            GRACE.remove(colony.colonyId());
            return colony.lifeSupportOk() ? colony : colony.withLifeSupport(true);
        }

        int graceLeft = GRACE.getOrDefault(colony.colonyId(),
                NeroColoniesConfig.LIFE_SUPPORT_GRACE_TICKS.get()) - Math.max(1, elapsedTicks);
        if (graceLeft > 0) {
            GRACE.put(colony.colonyId(), graceLeft);
            // Still DEGRADED: the colony is coasting on reserves and nothing has gone wrong yet.
            return colony.lifeSupportOk() ? colony : colony.withLifeSupport(true);
        }
        GRACE.put(colony.colonyId(), 0);
        return colony.lifeSupportOk() ? colony.withLifeSupport(false) : colony;
    }

    /** The colony's current life-support state, for display and for the link module. */
    public static State stateOf(Colony colony) {
        if (!colony.lifeSupportOk()) {
            return State.FAILED;
        }
        Integer grace = GRACE.get(colony.colonyId());
        return grace == null ? State.OK : State.DEGRADED;
    }

    /**
     * Oxygen this colony burns per cycle: {@code oxygenMbPerColonistPerCycle} per colonist, reduced
     * by whatever oxygen-efficiency research the colony has unlocked. Efficiency modules on the
     * generator itself are applied at the generator, not here.
     */
    public static long requiredOxygenMb(Colony colony) {
        int perColonist = NeroColoniesConfig.OXYGEN_MB_PER_COLONIST_PER_CYCLE.get();
        if (perColonist <= 0 || colony.population() <= 0) {
            return 0L;
        }
        double multiplier = ResearchEffects.oxygenEfficiency(colony);
        return Math.max(0L, Math.round(perColonist * (double) colony.population() * multiplier));
    }

    /**
     * Drains up to {@code wanted} millibuckets of oxygen from the colony's registered generators,
     * oldest registration first. Expired and vanished generators are pruned as they are met, so the
     * registry cleans itself without a sweep.
     */
    private static long drain(ServerLevel level, Colony colony, long wanted) {
        Map<Long, Long> positions = SOURCES.get(colony.colonyId());
        if (positions == null || positions.isEmpty()) {
            return 0L;
        }
        long now = level.getGameTime();
        long drained = 0L;
        for (Map.Entry<Long, Long> entry : new LinkedHashMap<>(positions).entrySet()) {
            if (drained >= wanted) {
                break;
            }
            long packed = entry.getKey();
            if (now - entry.getValue() > SOURCE_TTL_TICKS) {
                positions.remove(packed);
                continue;
            }
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                continue; // not evidence of anything: the generator's chunk is simply away
            }
            if (!(level.getBlockEntity(pos) instanceof OxygenGeneratorBlockEntity generator)) {
                positions.remove(packed);
                continue;
            }
            drained += generator.drainForLifeSupport(wanted - drained);
        }
        if (positions.isEmpty()) {
            SOURCES.remove(colony.colonyId());
        }
        return drained;
    }
}
