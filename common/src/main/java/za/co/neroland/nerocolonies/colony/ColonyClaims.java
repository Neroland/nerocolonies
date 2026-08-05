package za.co.neroland.nerocolonies.colony;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The claim and permission rules. Core has no claim layer, so this is NeroColonies' own — and it is
 * deliberately the whole model: an owner UUID, an access list, and an operator override. Capture,
 * contest and faction interaction are explicitly out of scope for 0.1.0 (NeroFactions will extend
 * the colony record when it exists).
 *
 * <p>Everything here is <b>server-side</b>. Nothing in this class is reachable from a client path,
 * and nothing it returns names a player: placement failures are translated messages, and permission
 * questions are answered {@code true}/{@code false}.
 */
public final class ColonyClaims {

    private ColonyClaims() {
    }

    // --- permission ---------------------------------------------------------

    /** Operator override: permission level 2 ({@code Commands.LEVEL_GAMEMASTERS}) or better. */
    public static boolean isGamemaster(@Nullable Player player) {
        return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && serverPlayer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * May this player act on this colony? Owner, access-list member, or operator. This is the single
     * predicate every gated interaction (GUI, job assignment, export toggle, link action) calls.
     */
    public static boolean canAccess(@Nullable Player player, @Nullable Colony colony) {
        if (player == null || colony == null) {
            return false;
        }
        return colony.isMember(player.getUUID()) || isGamemaster(player);
    }

    /**
     * May this player build at this position? Unclaimed ground is always buildable — NeroColonies
     * claims exist to run a colony, not to fence off the world — so this only ever refuses inside
     * somebody else's claim.
     */
    public static boolean canBuild(@Nullable Player player, ServerLevel level, BlockPos pos) {
        if (player == null) {
            return false;
        }
        Colony colony = ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos);
        return colony == null || canAccess(player, colony);
    }

    /**
     * May this player dissolve the beacon standing at {@code pos}? Owner or operator only, and only
     * while crouching — the "are you sure?" prompt is the sneak, because a colony beacon knocked out
     * by a stray pickaxe swing would take the whole colony record with it.
     */
    public static boolean mayDissolve(ServerLevel level, BlockPos pos, @Nullable Player player) {
        if (player == null) {
            return false;
        }
        Colony colony = colonyAtBeacon(level, pos);
        if (colony == null) {
            return true; // not a registered beacon: an ordinary block, break away
        }
        if (!player.isCrouching()) {
            return false;
        }
        return colony.isOwner(player.getUUID()) || isGamemaster(player);
    }

    /** The colony whose beacon is exactly this block, or {@code null}. */
    @Nullable
    public static Colony colonyAtBeacon(ServerLevel level, BlockPos pos) {
        Colony colony = ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos);
        return colony != null && colony.beaconPos().equals(pos) ? colony : null;
    }

    // --- placement validation ----------------------------------------------

    /**
     * Validates founding a colony at {@code pos}.
     *
     * @return {@code null} when the placement is allowed, otherwise the translated reason to show
     *         the player. The reason never names another player — "too close to an existing colony"
     *         is as specific as it gets, which is also all a prospective settler needs.
     */
    @Nullable
    public static Component validatePlacement(ServerLevel level, BlockPos pos, UUID owner) {
        MinecraftServer server = level.getServer();
        ColonyState state = ColonyState.get(server);

        int total = NeroColoniesConfig.MAX_COLONIES_TOTAL.get();
        if (state.size() >= total) {
            return Component.translatable("message.nerocolonies.claim.cap_total", total);
        }

        int perPlayer = NeroColoniesConfig.MAX_COLONIES_PER_PLAYER.get();
        if (perPlayer <= 0) {
            return Component.translatable("message.nerocolonies.claim.founding_disabled");
        }
        if (state.ownedCount(owner) >= perPlayer) {
            return Component.translatable("message.nerocolonies.claim.cap_player", perPlayer);
        }

        int radius = NeroColoniesConfig.CLAIM_RADIUS.get();
        int spacing = NeroColoniesConfig.MIN_COLONY_SPACING.get();
        long spacingSqr = (long) spacing * spacing;
        for (Colony other : state.coloniesIn(level.dimension())) {
            if (other.horizontalDistanceSqr(pos) < spacingSqr) {
                return Component.translatable("message.nerocolonies.claim.too_close", spacing);
            }
            if (claimsOverlap(other, pos, radius)) {
                return Component.translatable("message.nerocolonies.claim.overlap");
            }
        }
        return null;
    }

    // --- outpost placement --------------------------------------------------

    /**
     * The outcome of validating an outpost placement: either a parent colony, or a translated reason
     * the player cannot have one here. Exactly one of the two is non-null.
     */
    public record OutpostPlacement(@Nullable Colony parent, @Nullable Component refusal) {

        static OutpostPlacement refused(String key, Object... args) {
            return new OutpostPlacement(null, Component.translatable(key, args));
        }

        static OutpostPlacement accepted(Colony parent) {
            return new OutpostPlacement(parent, null);
        }
    }

    /**
     * Validates founding an outpost at {@code pos} and works out which colony would parent it.
     *
     * <p>The parent is the <b>nearest colony the placer may act on</b> within
     * {@code outpostMaxDistance} in the same dimension. Choosing by proximity rather than asking the
     * player is the one rule that makes outposts placeable without a UI, and "nearest of yours" is
     * what a player means every time.
     *
     * <p>An outpost may not stand inside any colony claim (it would be doing nothing the colony was
     * not already doing) nor inside another outpost's claim.
     */
    public static OutpostPlacement validateOutpostPlacement(ServerLevel level, BlockPos pos,
            @Nullable Player placer) {
        int perColony = NeroColoniesConfig.OUTPOSTS_PER_COLONY.get();
        if (perColony <= 0) {
            return OutpostPlacement.refused("message.nerocolonies.outpost.disabled");
        }
        ColonyState state = ColonyState.get(level.getServer());
        if (state.colonyAt(level.dimension(), pos) != null) {
            return OutpostPlacement.refused("message.nerocolonies.outpost.inside_claim");
        }
        if (state.outpostAt(level.dimension(), pos) != null) {
            return OutpostPlacement.refused("message.nerocolonies.outpost.overlap");
        }

        long maxDistance = NeroColoniesConfig.OUTPOST_MAX_DISTANCE.get();
        long maxDistanceSqr = maxDistance * maxDistance;
        Colony nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (Colony colony : state.coloniesIn(level.dimension())) {
            if (!canAccess(placer, colony)) {
                continue;
            }
            double distanceSqr = colony.horizontalDistanceSqr(pos);
            if (distanceSqr <= maxDistanceSqr && distanceSqr < nearestSqr) {
                nearest = colony;
                nearestSqr = distanceSqr;
            }
        }
        if (nearest == null) {
            return OutpostPlacement.refused("message.nerocolonies.outpost.no_parent", maxDistance);
        }
        if (nearest.outpostIds().size() >= perColony) {
            return OutpostPlacement.refused("message.nerocolonies.outpost.cap", perColony);
        }
        return OutpostPlacement.accepted(nearest);
    }

    /** The outpost whose beacon is exactly this block, or {@code null}. */
    @Nullable
    public static Outpost outpostAtBeacon(ServerLevel level, BlockPos pos) {
        Outpost outpost = ColonyState.get(level.getServer()).outpostAt(level.dimension(), pos);
        return outpost != null && outpost.pos().equals(pos) ? outpost : null;
    }

    /** May this player dissolve the outpost standing here? Same rule as a colony beacon. */
    public static boolean mayRemoveOutpost(ServerLevel level, BlockPos pos, @Nullable Player player) {
        if (player == null) {
            return false;
        }
        Outpost outpost = outpostAtBeacon(level, pos);
        if (outpost == null) {
            return true;
        }
        if (!player.isCrouching()) {
            return false;
        }
        Colony parent = ColonyState.get(level.getServer()).colony(outpost.parentColonyId());
        return parent == null || parent.isOwner(player.getUUID()) || isGamemaster(player);
    }

    /** The configured outpost claim radius plus whatever RANGE upgrade modules add. */
    public static int effectiveOutpostRadius(int rangeBonus) {
        return Math.max(1, NeroColoniesConfig.OUTPOST_CLAIM_RADIUS.get() + Math.max(0, rangeBonus));
    }

    /** Whether a new square claim of {@code radius} at {@code pos} would touch an existing one. */
    private static boolean claimsOverlap(Colony existing, BlockPos pos, int radius) {
        int reach = existing.claimRadius() + radius;
        int dx = Math.abs(pos.getX() - existing.beaconPos().getX());
        int dz = Math.abs(pos.getZ() - existing.beaconPos().getZ());
        return dx <= reach && dz <= reach;
    }

    /** The configured base claim radius plus whatever RANGE upgrade modules add. */
    public static int effectiveClaimRadius(int rangeBonus) {
        return Math.max(1, NeroColoniesConfig.CLAIM_RADIUS.get() + Math.max(0, rangeBonus));
    }
}
