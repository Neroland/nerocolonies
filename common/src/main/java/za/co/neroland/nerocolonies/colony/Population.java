package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesEntityTypes;

/**
 * Neran roster management: how many Nerans a colony has, and where they come from.
 *
 * <h2>The rules, and why they are these rules</h2>
 *
 * <ul>
 *   <li><b>Founders bootstrap.</b> {@code founderColonistCount} Nerans arrive with the beacon and
 *       are held on the roster <em>regardless of housing</em>. Without them the loop cannot start:
 *       housing is what lets Nerans arrive, and building housing is what Nerans do. They are a
 *       floor under the roster, not an exemption — they still count toward
 *       {@code colonistsPerColony} and {@code maxLoadedColonists}, and they take exactly the same
 *       life-support and morale treatment as anybody else.</li>
 *   <li><b>Housing is the cap.</b> Above the founder floor a colony grows toward
 *       {@code min(housingCapacity, colonistsPerColony)}, one Neran per colony tick.</li>
 *   <li><b>Survival is the gate.</b> Nobody arrives while life support has failed or the food store
 *       is empty. A colony in trouble stops growing before it starts shrinking.</li>
 *   <li><b>Losing housing shrinks the roster, and only the roster.</b> Surplus Nerans leave,
 *       youngest arrival first and founders last. This is the <em>only</em> path by which a Neran
 *       is ever removed, and it never runs on a guess: until the first housing sweep after a load
 *       has closed, the roster is neither grown nor shrunk (see {@link #tick}).</li>
 * </ul>
 *
 * <p><b>Nerans are never removed as a punishment.</b> Morale collapse stops work and leaves
 * everyone idle (see {@code Morale}); starvation and life-support failure decay morale. None of
 * those three ever deletes a Neran.
 *
 * <p><b>Privacy:</b> nothing here reads or writes anything player-shaped. Nerans carry no owner.
 */
public final class Population {

    /** Attempts made to find a standable spawn position before giving up until the next tick. */
    private static final int SPAWN_ATTEMPTS = 12;

    /** Horizontal spread around the beacon a new Neran may arrive in. */
    private static final int SPAWN_SPREAD = 6;

    /**
     * How far beyond the claim edge a Neran still counts toward the roster. A Neran that has
     * strayed a little is walked back by {@code HoldClaimGoal}; respawning a replacement for it
     * would inflate the colony and then shed someone when it returned.
     */
    static final int ROSTER_MARGIN = 16;

    /** Path search reach used to check that a spawn spot can walk to the beacon. */
    private static final int SPAWN_REACH = 2;

    /** The roster of each colony, cached for the game tick it was read on. */
    private static final Map<UUID, Roster> ROSTERS = new HashMap<>();

    /** Server-wide sum of stored populations, recomputed at most once per game tick. */
    private static long populationSumTick = Long.MIN_VALUE;
    private static int populationSum;

    private Population() {
    }

    private record Roster(ServerLevel level, long gameTime, List<ColonistEntity> colonists) {
    }

    /**
     * Brings the colony's roster in line with its housing. Called once per colony tick.
     *
     * @param homes   housing positions from the last completed housing sweep, for home assignment
     * @param settled whether a full housing sweep has closed since the beacon loaded. Until it has,
     *                the capacity is only the seeded saved value, entity sections may still be
     *                loading, and the home list is empty — so the roster is left exactly as it is.
     * @return the colony record with its population count refreshed (possibly the same instance)
     */
    public static Colony tick(ServerLevel level, Colony colony, int housingCapacity,
            List<BlockPos> homes, boolean settled, boolean arrivals) {
        if (!settled) {
            return colony;
        }
        List<ColonistEntity> present = new ArrayList<>(colonistsOf(level, colony));
        int target = targetPopulation(level, colony, housingCapacity);

        if (present.size() > target) {
            // Housing was lost (or the cap was lowered). Surplus Nerans leave: the most recent
            // arrival first, founders only once everyone else has gone.
            present.sort(DEPARTURE_ORDER);
            for (int i = present.size() - 1; i >= target; i--) {
                present.get(i).discard();
            }
            present = new ArrayList<>(present.subList(0, Math.max(0, target)));
            invalidate(colony.colonyId());
        } else if ((arrivals || present.size() < founderFloor()) && present.size() < target
                && mayGrow(colony, present.size())) {
            ColonistEntity arrival = spawnOne(level, colony, false);
            if (arrival != null) {
                present.add(arrival);
            }
        }

        assignHomes(present, homes);

        int count = present.size();
        return count == colony.population() ? colony : colony.withPopulation(count);
    }

    /**
     * Whether a newcomer may arrive this cycle. Before a colony is Growing every cycle is an arrival
     * cycle. Once it can have children, newcomers come only every {@code immigrationIntervalCycles}
     * cycles, so the colony grows mostly from within.
     */
    public static boolean arrivalDue(ServerLevel level, ColonyStage stage) {
        if (!NeroColoniesConfig.BREEDING_ENABLED.get() || !stage.atLeast(ColonyStage.GROWING)) {
            return true;
        }
        int every = Math.max(1, NeroColoniesConfig.IMMIGRATION_INTERVAL_CYCLES.get());
        long cycle = level.getGameTime() / Math.max(1, NeroColoniesConfig.colonyTickInterval());
        return cycle % every == 0L;
    }

    /** Founders last, then earliest arrival first; a stable order across loads. */
    private static final Comparator<ColonistEntity> DEPARTURE_ORDER = Comparator
            .comparing(ColonistEntity::isFounder).reversed()
            .thenComparingLong(ColonistEntity::arrivedAt)
            .thenComparing(ColonistEntity::getStringUUID);

    /**
     * Puts a brand-new colony's founders on the ground, next to the beacon that has just been placed.
     *
     * <p><b>Life support is not consulted.</b> Founders get the same treatment everyone else does —
     * the graceful curve of life support failing, morale decaying, work stopping and Nerans idling.
     *
     * @return how many founders were actually placed (fewer if there was nowhere to stand)
     */
    public static int spawnFounders(ServerLevel level, Colony colony) {
        int wanted = founderFloor();
        int placed = 0;
        for (int i = 0; i < wanted; i++) {
            if (spawnOne(level, colony, true) == null) {
                break; // nowhere to stand right now; the colony tick tops the roster up later
            }
            placed++;
        }
        if (placed > 0) {
            NeroColoniesCommon.LOGGER.info("[NeroColonies] {} founder(s) arrived at a new colony.",
                    placed);
        }
        return placed;
    }

    /**
     * Every loaded Neran bound to this colony within the claim plus {@value #ROSTER_MARGIN} blocks,
     * cached for the current game tick so the population, job and construction passes of one colony
     * cycle share a single entity query.
     */
    public static List<ColonistEntity> colonistsOf(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        Roster cached = ROSTERS.get(colony.colonyId());
        if (cached != null && cached.level() == level && cached.gameTime() == now) {
            return cached.colonists();
        }
        int radius = colony.claimRadius() + ROSTER_MARGIN;
        BlockPos beacon = colony.beaconPos();
        AABB box = new AABB(
                beacon.getX() - radius, level.getMinY(), beacon.getZ() - radius,
                beacon.getX() + radius + 1, level.getMaxY() + 1, beacon.getZ() + radius + 1);
        List<ColonistEntity> found = List.copyOf(level.getEntitiesOfClass(ColonistEntity.class, box,
                colonist -> colonist.isAlive() && colony.colonyId().equals(colonist.colonyId())));
        ROSTERS.put(colony.colonyId(), new Roster(level, now, found));
        return found;
    }

    /** Drops a colony's cached roster after Nerans were added or removed. */
    public static void invalidate(UUID colonyId) {
        ROSTERS.remove(colonyId);
    }

    /** Clears every JVM-lifetime cache. Called when the server stops. */
    public static void clearCaches() {
        ROSTERS.clear();
        populationSumTick = Long.MIN_VALUE;
        populationSum = 0;
    }

    /**
     * How many Nerans this colony should have: its housing capacity or the founder floor,
     * whichever is larger, capped by {@code colonistsPerColony} and by whatever room is left under
     * the server-wide {@code maxLoadedColonists} budget.
     */
    private static int targetPopulation(ServerLevel level, Colony colony, int housingCapacity) {
        int perColony = NeroColoniesConfig.COLONISTS_PER_COLONY.get();
        int target = Math.max(Math.min(Math.max(0, housingCapacity), perColony), founderFloor());

        int globalCap = NeroColoniesConfig.MAX_LOADED_COLONISTS.get();
        int othersElsewhere = Math.max(0, serverPopulation(level) - colony.population());
        return Math.clamp(target, 0, Math.max(0, globalCap - othersElsewhere));
    }

    /**
     * The sum of every colony's stored population. Recomputed once per game tick rather than once
     * per colony cycle, which turns an O(colonies²) loop into O(colonies) per tick. Being one tick
     * out of date is of no consequence for a ceiling.
     */
    private static int serverPopulation(ServerLevel level) {
        long now = level.getGameTime();
        if (now != populationSumTick) {
            populationSumTick = now;
            int sum = 0;
            for (Colony other : ColonyState.get(level.getServer()).colonies()) {
                sum += other.population();
            }
            populationSum = sum;
        }
        return populationSum;
    }

    /**
     * The roster size a colony is held at with no housing at all: {@code founderColonistCount},
     * never above the per-colony cap.
     */
    public static int founderFloor() {
        return Math.min(Math.max(0, NeroColoniesConfig.FOUNDER_COLONIST_COUNT.get()),
                Math.max(0, NeroColoniesConfig.COLONISTS_PER_COLONY.get()));
    }

    /**
     * Growth gate: life support holding and something in the food store. Replacing a lost
     * <b>founder</b> is exempt, bounded by {@code founderColonistCount}.
     */
    private static boolean mayGrow(Colony colony, int present) {
        return growthBlocker(colony, present) == GrowthBlocker.NONE;
    }

    /**
     * The growth state the beacon shows: growing, or the first reason it is not. Ordinal is the
     * synced data-slot value, so append only.
     */
    public enum GrowthStatus {
        GROWING, LIFE_SUPPORT, FOOD, HOUSING_FULL, COLONY_CAP, SERVER_CAP
    }

    /** The beacon's growth readout for a colony. Cheap: no entity query. */
    public static GrowthStatus growthStatus(ServerLevel level, Colony colony) {
        int present = colony.population();
        switch (growthBlocker(colony, present)) {
            case LIFE_SUPPORT:
                return GrowthStatus.LIFE_SUPPORT;
            case FOOD:
                return GrowthStatus.FOOD;
            default:
                break;
        }
        int perColony = NeroColoniesConfig.COLONISTS_PER_COLONY.get();
        if (present >= perColony) {
            return GrowthStatus.COLONY_CAP;
        }
        if (present >= Math.max(colony.housingCapacity(), founderFloor())) {
            return GrowthStatus.HOUSING_FULL;
        }
        if (present >= targetPopulation(level, colony, colony.housingCapacity())) {
            return GrowthStatus.SERVER_CAP;
        }
        return GrowthStatus.GROWING;
    }

    /** Why a colony is not taking new arrivals right now, for the beacon GUI. */
    public enum GrowthBlocker {
        NONE, LIFE_SUPPORT, FOOD
    }

    /** The first reason, in priority order, that arrivals are paused, or {@code NONE}. */
    public static GrowthBlocker growthBlocker(Colony colony, int present) {
        if (present < founderFloor()) {
            return GrowthBlocker.NONE;
        }
        if (!colony.lifeSupportOk()) {
            return GrowthBlocker.LIFE_SUPPORT;
        }
        if (colony.foodStock() <= 0 && NeroColoniesConfig.FOOD_PER_COLONIST_PER_CYCLE.get() > 0) {
            return GrowthBlocker.FOOD;
        }
        return GrowthBlocker.NONE;
    }

    /**
     * Places one Neran near the beacon, or returns {@code null} if there is nowhere to stand.
     *
     * <p>A spot must be dry, sturdy, hazard-free floor with two clear blocks above, outside the
     * structure the colony is currently building, and able to walk to the beacon. If no candidate
     * can reach the beacon (it may be walled in), the first otherwise-safe candidate is used rather
     * than refusing the arrival outright.
     */
    @Nullable
    private static ColonistEntity spawnOne(ServerLevel level, Colony colony, boolean founder) {
        BlockPos beacon = colony.beaconPos();
        RandomSource random = level.getRandom();
        MinecraftServer server = level.getServer();
        BlockPos fallback = null;
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int x = beacon.getX() + random.nextInt(SPAWN_SPREAD * 2 + 1) - SPAWN_SPREAD;
            int z = beacon.getZ() + random.nextInt(SPAWN_SPREAD * 2 + 1) - SPAWN_SPREAD;
            int y = beacon.getY() + random.nextInt(5) - 2;
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunk(x >> 4, z >> 4) || !standable(level, pos)) {
                continue;
            }
            if (Construction.insideActiveSite(server, colony.colonyId(), pos)) {
                continue; // never arrive where blocks are about to go
            }
            if (fallback == null) {
                fallback = pos;
            }
            if (canWalkToBeacon(level, pos, beacon)) {
                return place(level, colony, pos, founder);
            }
        }
        return fallback == null ? null : place(level, colony, fallback, founder);
    }

    /** Whether a Neran standing at {@code pos} could path to the beacon. */
    private static boolean canWalkToBeacon(ServerLevel level, BlockPos pos, BlockPos beacon) {
        ColonistEntity probe = NeroColoniesEntityTypes.COLONIST.get().create(level, EntitySpawnReason.EVENT);
        if (probe == null) {
            return false;
        }
        probe.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        probe.setOnGround(true); // ground navigation refuses to plan for a mob in mid-air
        Path path = probe.getNavigation().createPath(beacon, SPAWN_REACH);
        return path != null && path.canReach();
    }

    @Nullable
    private static ColonistEntity place(ServerLevel level, Colony colony, BlockPos pos,
            boolean founder) {
        ColonistEntity colonist = NeroColoniesEntityTypes.COLONIST.get()
                .create(level, EntitySpawnReason.EVENT);
        if (colonist == null) {
            return null;
        }
        colonist.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        colonist.bind(colony.colonyId());
        colonist.markArrival(level.getGameTime(), founder);
        colonist.assignGeneratedName();
        if (!level.addFreshEntity(colonist)) {
            return null;
        }
        invalidate(colony.colonyId());
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A Neran arrived (roster now growing).");
        return colonist;
    }

    // --- breeding -----------------------------------------------------------------

    /**
     * The colony's slow natural growth: once per cycle, if the colony is at least Growing, has two
     * adults, a free bed, several cycles of food surplus and decent morale, it rolls a chance that
     * grows with colony size (see {@link Growth}). A success is "a new Neran was born": a child Neran
     * appears at home (or near the beacon), grows up after {@code childGrowthDays} in-game days, and
     * then takes a trade like anybody else. Nothing more to it than that.
     *
     * @return the colony with its population refreshed if a child was born
     */
    public static Colony breed(ServerLevel level, Colony colony, ColonyStage stage, int housingCapacity,
            boolean settled) {
        if (!settled || !NeroColoniesConfig.BREEDING_ENABLED.get()) {
            return colony;
        }
        List<ColonistEntity> roster = colonistsOf(level, colony);
        int adults = 0;
        for (ColonistEntity neran : roster) {
            if (!neran.isChildNeran()) {
                adults++;
            }
        }
        int cap = Math.min(Math.max(0, housingCapacity), NeroColoniesConfig.COLONISTS_PER_COLONY.get());
        int freeBeds = cap - roster.size();
        if (!Growth.breedingAllowed(stage, adults, freeBeds, colony.foodStock(), roster.size(),
                NeroColoniesConfig.FOOD_PER_COLONIST_PER_CYCLE.get(),
                NeroColoniesConfig.BREEDING_SURPLUS_CYCLES.get(), colony.morale(),
                NeroColoniesConfig.BREEDING_MORALE_FLOOR.get())) {
            return colony;
        }
        if (serverPopulation(level) >= NeroColoniesConfig.MAX_LOADED_COLONISTS.get()) {
            return colony;
        }
        int structures = Construction.structuresBuilt(level.getServer(), colony.colonyId());
        if (level.getRandom().nextDouble() >= Growth.breedingChanceFor(roster.size(), structures)) {
            return colony;
        }
        ColonistEntity child = spawnOne(level, colony, false);
        if (child == null) {
            return colony;
        }
        child.makeChild(level.getGameTime());
        ColonyLife store = ColonyLife.get(level.getServer());
        store.life(colony.colonyId()).countBirth(level.getGameTime());
        store.touch();
        com.google.gson.JsonObject extra = new com.google.gson.JsonObject();
        extra.addProperty("population", roster.size() + 1);
        za.co.neroland.nerocolonies.link.ColonyLinkEvents.colonyEvent(colony,
                za.co.neroland.nerocolonies.link.ColonyLinkModule.TOPIC_BIRTH, extra);
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A Neran was born.");
        return colony.withPopulation(roster.size() + 1);
    }

    /** Sturdy, dry, hazard-free floor with two blocks of air above. */
    static boolean standable(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP) || !floor.getFluidState().isEmpty()) {
            return false;
        }
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(BlockTags.CAMPFIRES)
                || floor.is(BlockTags.FIRE)) {
            return false;
        }
        return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
    }

    /**
     * Gives every Neran a home, round-robin over the housing positions found by the last sweep.
     */
    private static void assignHomes(List<ColonistEntity> colonists, List<BlockPos> homes) {
        if (colonists.isEmpty()) {
            return;
        }
        if (homes.isEmpty()) {
            for (ColonistEntity colonist : colonists) {
                colonist.setHomePos(null);
            }
            return;
        }
        for (int i = 0; i < colonists.size(); i++) {
            colonists.get(i).setHomePos(homes.get(i % homes.size()));
        }
    }
}
