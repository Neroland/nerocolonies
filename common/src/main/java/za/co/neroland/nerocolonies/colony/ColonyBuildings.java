package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;

/**
 * Where things are in a colony: the finished buildings, what each one is for, and the spot in front
 * of it a Neran walks to.
 *
 * <p>Buildings announce what they are for with {@code roles} in their blueprint ({@code eat},
 * {@code social}, {@code guard_post}, {@code kennel}, {@code golem_forge}, {@code gratitude_cache},
 * {@code planning}) and which trades they open with {@code unlocks}. Anchor lookups are cached per
 * colony for {@value #CACHE_TICKS} ticks or until the building count changes, because every Neran
 * asks several times a minute.
 */
public final class ColonyBuildings {

    public static final String ROLE_EAT = "eat";
    public static final String ROLE_SOCIAL = "social";
    public static final String ROLE_SLEEP = "sleep";
    public static final String ROLE_GUARD = "guard_post";
    public static final String ROLE_KENNEL = "kennel";
    public static final String ROLE_GOLEM = "golem_forge";
    public static final String ROLE_CACHE = "gratitude_cache";
    public static final String ROLE_PLANNING = "planning";
    public static final String ROLE_NEEDS = "needs_board";

    private static final long CACHE_TICKS = 600L;

    /** A finished building with its resolved blueprint and the spots that matter. */
    public record Placed(ColonyConstruction.Structure structure, Blueprint blueprint, BlockPos centre,
            @Nullable BlockPos access) {
    }

    private record Cached(long gameTime, int count, List<Placed> placed) {
    }

    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private ColonyBuildings() {
    }

    /** Every finished building of a colony, resolved. Cached. */
    public static List<Placed> placed(ServerLevel level, Colony colony) {
        MinecraftServer server = level.getServer();
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        if (plan == null) {
            return List.of();
        }
        List<ColonyConstruction.Structure> structures = plan.structures();
        long now = level.getGameTime();
        Cached cached = CACHE.get(colony.colonyId());
        if (cached != null && cached.count() == structures.size() && now - cached.gameTime() < CACHE_TICKS
                && now >= cached.gameTime()) {
            return cached.placed();
        }
        List<Placed> out = new ArrayList<>(structures.size());
        for (ColonyConstruction.Structure structure : structures) {
            Blueprint blueprint = ColonyDefinitions.blueprintsForServer(server).get(structure.blueprint());
            if (blueprint == null) {
                continue;
            }
            int w = blueprint.width(structure.rotation());
            int d = blueprint.depth(structure.rotation());
            BlockPos centre = structure.origin().offset(w / 2, 0, d / 2);
            BlockPos access = level.isLoaded(structure.origin())
                    ? Construction.accessSpot(level, colony, structure.origin(), blueprint, structure.rotation())
                    : null;
            out.add(new Placed(structure, blueprint, centre, access));
        }
        List<Placed> frozen = List.copyOf(out);
        CACHE.put(colony.colonyId(), new Cached(now, structures.size(), frozen));
        return frozen;
    }

    /** The access spot of the nearest building with {@code role}, or null if the colony has none. */
    @Nullable
    public static BlockPos anchor(ServerLevel level, Colony colony, String role, BlockPos near) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Placed placed : placed(level, colony)) {
            if (!placed.blueprint().hasRole(role)) {
                continue;
            }
            BlockPos spot = placed.access() != null ? placed.access() : placed.centre();
            double distance = spot.distSqr(near);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = spot;
            }
        }
        return best;
    }

    /** Whether the colony has at least one building with {@code role}. */
    public static boolean hasRole(ServerLevel level, Colony colony, String role) {
        for (Placed placed : placed(level, colony)) {
            if (placed.blueprint().hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    /** Buildings with a role. */
    public static List<Placed> withRole(ServerLevel level, Colony colony, String role) {
        List<Placed> out = new ArrayList<>();
        for (Placed placed : placed(level, colony)) {
            if (placed.blueprint().hasRole(role)) {
                out.add(placed);
            }
        }
        return out;
    }

    /** Buildings that unlock a profession. */
    public static List<Placed> unlocking(ServerLevel level, Colony colony, Identifier profession) {
        List<Placed> out = new ArrayList<>();
        for (Placed placed : placed(level, colony)) {
            if (placed.blueprint().unlocks().contains(profession)) {
                out.add(placed);
            }
        }
        return out;
    }

    /** Drops a colony's cache (a building finished, or the colony went away). */
    public static void invalidate(UUID colonyId) {
        CACHE.remove(colonyId);
    }

    /** Clears every cache. Called when the server stops. */
    public static void clearCaches() {
        CACHE.clear();
    }
}
