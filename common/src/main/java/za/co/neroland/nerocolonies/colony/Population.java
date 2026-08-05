package za.co.neroland.nerocolonies.colony;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesEntityTypes;

/**
 * Colonist roster management: how many colonists a colony has, and where they come from.
 *
 * <h2>The rules, and why they are these rules</h2>
 *
 * <ul>
 *   <li><b>Founders bootstrap.</b> {@code founderColonistCount} colonists arrive with the beacon and
 *       are held on the roster <em>regardless of housing</em>. Without them the loop cannot start:
 *       housing is what lets colonists arrive, and building housing is what colonists do. They are a
 *       floor under the roster, not an exemption — they still count toward
 *       {@code colonistsPerColony} and {@code maxLoadedColonists}, and they take exactly the same
 *       life-support and morale treatment as anybody else.</li>
 *   <li><b>Housing is the cap.</b> Above the founder floor a colony grows toward
 *       {@code min(housingCapacity, colonistsPerColony)}, one colonist per colony tick. Building
 *       housing is therefore the whole of the population game; there is no birth rate to tune and
 *       nothing to wait for beyond your colony's own construction.</li>
 *   <li><b>Survival is the gate.</b> Nobody arrives while life support has failed or the food store
 *       is empty. A colony in trouble stops growing before it starts shrinking.</li>
 *   <li><b>Losing housing shrinks the roster, and only the roster.</b> Surplus colonists leave —
 *       they are interchangeable labour, and a bunk that no longer exists cannot be slept in. This
 *       is the <em>only</em> path by which a colonist is ever removed.</li>
 * </ul>
 *
 * <p><b>Colonists are never removed as a punishment.</b> Morale collapse stops work and leaves
 * everyone idle (see {@code Morale}); starvation and life-support failure decay morale. None of
 * those three ever deletes a colonist. That distinction is the whole design of the failure curve:
 * a colony that goes wrong becomes useless, not empty, so the player still has something to rescue.
 *
 * <p><b>Privacy:</b> nothing here reads or writes anything player-shaped. Colonists carry no owner.
 */
public final class Population {

    /** Attempts made to find a standable spawn position before giving up until the next tick. */
    private static final int SPAWN_ATTEMPTS = 12;

    /** Horizontal spread around the beacon a new colonist may arrive in. */
    private static final int SPAWN_SPREAD = 6;

    private Population() {
    }

    /**
     * Brings the colony's roster in line with its housing. Called once per colony tick, from the
     * colony beacon's block entity.
     *
     * @param homes housing positions from the last completed housing sweep, for home assignment
     * @return the colony record with its population count refreshed (possibly the same instance)
     */
    public static Colony tick(ServerLevel level, Colony colony, int housingCapacity,
            List<BlockPos> homes) {
        List<ColonistEntity> present = colonistsOf(level, colony);
        int target = targetPopulation(level, colony, housingCapacity);

        if (present.size() > target) {
            // Housing was lost (or the cap was lowered). Surplus colonists leave, newest first so a
            // colonist who has been settled and working is the last to go.
            for (int i = present.size() - 1; i >= target; i--) {
                present.get(i).discard();
            }
            present = present.subList(0, Math.max(0, target));
        } else if (present.size() < target && mayGrow(colony, present.size())) {
            ColonistEntity arrival = spawnOne(level, colony);
            if (arrival != null) {
                present = colonistsOf(level, colony);
            }
        }

        assignHomes(present, homes);

        int count = present.size();
        return count == colony.population() ? colony : colony.withPopulation(count);
    }

    /**
     * Puts a brand-new colony's founders on the ground, next to the beacon that has just been placed.
     *
     * <p>Called from the placement flow rather than waiting for the first colony tick, because the
     * player is standing right there: colonists that appear a minute and a half later read as a bug,
     * and the whole point of founders is that placing the beacon visibly starts something.
     *
     * <p><b>Life support is not consulted.</b> Placing a beacon on an airless world means the player
     * is themselves standing on it, and founders get the same treatment everyone else does — the
     * graceful curve of life support failing, morale decaying, work stopping and colonists idling.
     * They are never refused at the door and never killed on arrival; see {@code LifeSupport}.
     *
     * @return how many founders were actually placed (fewer if there was nowhere to stand)
     */
    public static int spawnFounders(ServerLevel level, Colony colony) {
        int wanted = founderFloor();
        int placed = 0;
        for (int i = 0; i < wanted; i++) {
            if (spawnOne(level, colony) == null) {
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

    /** Every loaded colonist bound to this colony, in a stable order. */
    public static List<ColonistEntity> colonistsOf(ServerLevel level, Colony colony) {
        int radius = colony.claimRadius();
        BlockPos beacon = colony.beaconPos();
        AABB box = new AABB(
                beacon.getX() - radius, level.getMinY(), beacon.getZ() - radius,
                beacon.getX() + radius + 1, level.getMaxY() + 1, beacon.getZ() + radius + 1);
        return level.getEntitiesOfClass(ColonistEntity.class, box,
                colonist -> colony.colonyId().equals(colonist.colonyId()));
    }

    /**
     * How many colonists this colony should have: its housing capacity or the founder floor,
     * whichever is larger, capped by {@code colonistsPerColony} and by whatever room is left under
     * the server-wide {@code maxLoadedColonists} budget.
     *
     * <p>The server-wide figure is the sum of the stored colony populations rather than a live
     * entity count — it is O(colonies) instead of O(entities), it is already maintained, and being
     * one colony tick out of date is of no consequence for a ceiling.
     */
    private static int targetPopulation(ServerLevel level, Colony colony, int housingCapacity) {
        int perColony = NeroColoniesConfig.COLONISTS_PER_COLONY.get();
        int target = Math.max(Math.min(Math.max(0, housingCapacity), perColony), founderFloor());

        int globalCap = NeroColoniesConfig.MAX_LOADED_COLONISTS.get();
        int othersElsewhere = 0;
        for (Colony other : ColonyState.get(level.getServer()).colonies()) {
            if (!other.colonyId().equals(colony.colonyId())) {
                othersElsewhere += other.population();
            }
        }
        return Math.clamp(target, 0, Math.max(0, globalCap - othersElsewhere));
    }

    /**
     * The roster size a colony is held at with no housing at all: {@code founderColonistCount},
     * never above the per-colony cap. Zero disables founders (and with them the whole autonomous
     * bootstrap — a colony then waits for the player to build the first housing by hand).
     */
    public static int founderFloor() {
        return Math.min(Math.max(0, NeroColoniesConfig.FOUNDER_COLONIST_COUNT.get()),
                Math.max(0, NeroColoniesConfig.COLONISTS_PER_COLONY.get()));
    }

    /**
     * Growth gate: life support holding and something in the food store.
     *
     * <p>Replacing a lost <b>founder</b> is exempt. A colony below its founder floor has nothing left
     * that can fix the very problems this gate is testing for — nobody to build a farm, nobody to
     * build an oxygen generator — so gating the bootstrap on food and air would make a colony that
     * lost its founders permanently dead rather than merely in trouble. The exemption is bounded by
     * {@code founderColonistCount} and cannot grow a colony past it.
     */
    private static boolean mayGrow(Colony colony, int present) {
        if (present < founderFloor()) {
            return true;
        }
        return colony.lifeSupportOk()
                && (colony.foodStock() > 0 || NeroColoniesConfig.FOOD_PER_COLONIST_PER_CYCLE.get() <= 0);
    }

    /** Places one colonist near the beacon, or returns {@code null} if there is nowhere to stand. */
    private static ColonistEntity spawnOne(ServerLevel level, Colony colony) {
        BlockPos beacon = colony.beaconPos();
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int x = beacon.getX() + random.nextInt(SPAWN_SPREAD * 2 + 1) - SPAWN_SPREAD;
            int z = beacon.getZ() + random.nextInt(SPAWN_SPREAD * 2 + 1) - SPAWN_SPREAD;
            int y = beacon.getY() + random.nextInt(5) - 2;
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunk(x >> 4, z >> 4) || !standable(level, pos)) {
                continue;
            }
            ColonistEntity colonist = NeroColoniesEntityTypes.COLONIST.get()
                    .create(level, EntitySpawnReason.EVENT);
            if (colonist == null) {
                return null;
            }
            colonist.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                    random.nextFloat() * 360.0F, 0.0F);
            colonist.bind(colony.colonyId());
            if (!level.addFreshEntity(colonist)) {
                return null;
            }
            NeroColoniesCommon.LOGGER.debug("[NeroColonies] A colonist arrived (roster now growing).");
            return colonist;
        }
        return null;
    }

    /** Solid floor, two blocks of air above. */
    private static boolean standable(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos.below()).isAir()
                && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir();
    }

    /**
     * Gives every colonist a home, round-robin over the housing positions found by the last sweep.
     * Round-robin rather than "nearest free bunk" on purpose: colonists are interchangeable, so
     * there is no ownership to track, nothing to persist beyond the position itself, and no chance
     * of a colonist becoming homeless because another one got there first.
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
