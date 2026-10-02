package za.co.neroland.nerocolonies.colony;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.network.ColonySync;

/**
 * The one place a colony's role lists change. The {@code /nerocolonies colony role} commands and the
 * beacon's Roles tab both end up here, so the rules for who may promote, demote or mark whom cannot
 * drift between a keyboard and a GUI.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li>Nobody changes the owner's role, and nobody changes their own.</li>
 *   <li><b>Allies</b> are added and removed by the owner or a Chief. Removing an Ally who is also a
 *       Chief takes the owner: a Chief cannot remove another Chief.</li>
 *   <li><b>Chiefs</b> are made and unmade by the owner alone. Making somebody a Chief gives them
 *       access first if they had none; unmaking one leaves them an Ally.</li>
 *   <li><b>Enemies</b> are marked and cleared by the owner or a Chief. Operators cannot be marked.
 *       Marking a member is a demotion, so it has to be asked for twice ({@link Result#NEEDS_CONFIRM})
 *       and, for a Chief, takes the owner.</li>
 *   <li>Adding somebody as an Ally or Chief clears any Enemy mark on them.</li>
 * </ul>
 *
 * <p>The two member-gated colony settings — sharing the Gratitude Cache and prioritising a need —
 * live here too, for the same reason: one rule, two front ends.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Everything here handles plain Minecraft game UUIDs, in memory, for the length of one call. No
 * name is ever passed in, looked up or returned: callers resolve whatever the player typed before
 * they get here, and a {@link Result} says what happened without saying to whom. Nothing is logged
 * except the rows the optional access log already wrote for a grant or a revoke — the same two
 * actions, the same three fields, off by default. Chief and Enemy changes add no log rows at all.
 *
 * <p>Server thread only.
 */
public final class ColonyMembership {

    /** What a requested change came to. */
    public enum Result {
        /** The change was made. */
        DONE,
        /** The actor's rank does not allow it. */
        NOT_ALLOWED,
        /** The target owns the colony. */
        IS_OWNER,
        /** The actor named themselves. */
        IS_SELF,
        /** The target is a server operator, who cannot be marked as an Enemy. */
        IS_OPERATOR,
        /** The target already holds that role. */
        ALREADY,
        /** The target does not hold that role. */
        ABSENT,
        /** The list is at its cap. */
        FULL,
        /** The target is a member; marking them as an Enemy has to be confirmed. Nothing changed. */
        NEEDS_CONFIRM
    }

    private ColonyMembership() {
    }

    /** The translation key that tells a player what a {@link Result} means. Never names anybody. */
    public static String messageKey(Result result) {
        return "message.nerocolonies.role." + result.name().toLowerCase(Locale.ROOT);
    }

    // --- allies -----------------------------------------------------------------

    /**
     * Adds or removes an Ally.
     *
     * @param actor   the acting role (an operator acts as {@link ColonyPermissions.Role#OWNER})
     * @param actorId the acting player, or {@code null} for the server console
     */
    public static Result setAlly(MinecraftServer server, Colony colony, ColonyPermissions.Role actor,
            @Nullable UUID actorId, UUID target, boolean add) {
        ColonyState state = ColonyState.get(server);
        Colony live = state.colony(colony.colonyId());
        if (live == null) {
            return Result.ABSENT;
        }
        Result refusal = refusal(live, actor, ColonyPermissions.Action.MANAGE_MEMBERS, actorId, target);
        if (refusal != null) {
            return refusal;
        }
        UUID id = live.colonyId();
        ColonyRoles roles = ColonyRoles.get(server);
        boolean listed = live.accessList().contains(target);
        if (add) {
            if (listed) {
                // Listed and still marked means stale data: clearing the mark is the whole change.
                return roles.removeEnemy(id, target) ? done(server, id) : Result.ALREADY;
            }
            if (live.accessList().size() >= Colony.MAX_ACCESS_LIST) {
                return Result.FULL;
            }
            roles.removeEnemy(id, target);
            grant(state, live, target);
            return done(server, id);
        }
        if (!listed) {
            return Result.ABSENT;
        }
        if (roles.isChief(id, target) && !may(actor, ColonyPermissions.Action.MANAGE_CHIEFS)) {
            return Result.NOT_ALLOWED;
        }
        revoke(server, state, roles, live, target);
        return done(server, id);
    }

    // --- chiefs -----------------------------------------------------------------

    /** Makes or unmakes a Chief. Owner only; see {@link #setAlly} for the parameters. */
    public static Result setChief(MinecraftServer server, Colony colony, ColonyPermissions.Role actor,
            @Nullable UUID actorId, UUID target, boolean add) {
        ColonyState state = ColonyState.get(server);
        Colony live = state.colony(colony.colonyId());
        if (live == null) {
            return Result.ABSENT;
        }
        Result refusal = refusal(live, actor, ColonyPermissions.Action.MANAGE_CHIEFS, actorId, target);
        if (refusal != null) {
            return refusal;
        }
        UUID id = live.colonyId();
        ColonyRoles roles = ColonyRoles.get(server);
        boolean listed = live.accessList().contains(target);
        boolean chief = roles.isChief(id, target);
        if (!add) {
            if (!chief) {
                return Result.ABSENT;
            }
            roles.removeChief(id, target);
            return done(server, id);
        }
        if (chief && listed) {
            return roles.removeEnemy(id, target) ? done(server, id) : Result.ALREADY;
        }
        if (!listed && live.accessList().size() >= Colony.MAX_ACCESS_LIST) {
            return Result.FULL;
        }
        if (!chief && roles.counts(id)[0] >= Colony.MAX_ACCESS_LIST) {
            return Result.FULL;
        }
        roles.removeEnemy(id, target);
        if (!listed) {
            grant(state, live, target);
        }
        if (!chief && !roles.addChief(id, target)) {
            return Result.FULL;
        }
        return done(server, id);
    }

    // --- enemies ----------------------------------------------------------------

    /**
     * Marks or clears an Enemy.
     *
     * @param confirm          whether the actor has confirmed demoting a member; without it a member
     *                         target answers {@link Result#NEEDS_CONFIRM} and nothing changes
     * @param targetIsOperator whether the target is a server operator (the caller knows; this class
     *                         never looks a player up)
     */
    public static Result setEnemy(MinecraftServer server, Colony colony, ColonyPermissions.Role actor,
            @Nullable UUID actorId, UUID target, boolean add, boolean confirm, boolean targetIsOperator) {
        ColonyState state = ColonyState.get(server);
        Colony live = state.colony(colony.colonyId());
        if (live == null) {
            return Result.ABSENT;
        }
        Result refusal = refusal(live, actor, ColonyPermissions.Action.MANAGE_MEMBERS, actorId, target);
        if (refusal != null) {
            return refusal;
        }
        UUID id = live.colonyId();
        ColonyRoles roles = ColonyRoles.get(server);
        boolean enemy = roles.isEnemy(id, target);
        if (!add) {
            if (!enemy) {
                return Result.ABSENT;
            }
            roles.removeEnemy(id, target);
            return done(server, id);
        }
        if (targetIsOperator) {
            return Result.IS_OPERATOR;
        }
        boolean listed = live.accessList().contains(target);
        if (enemy && !listed) {
            return Result.ALREADY;
        }
        if (!enemy && roles.counts(id)[1] >= Colony.MAX_ACCESS_LIST) {
            return Result.FULL;
        }
        if (listed) {
            if (roles.isChief(id, target) && !may(actor, ColonyPermissions.Action.MANAGE_CHIEFS)) {
                return Result.NOT_ALLOWED;
            }
            if (!confirm) {
                return Result.NEEDS_CONFIRM;
            }
            revoke(server, state, roles, live, target);
        }
        if (!enemy && !roles.addEnemy(id, target)) {
            return Result.FULL;
        }
        return done(server, id);
    }

    // --- settings ---------------------------------------------------------------

    /**
     * Shares the Gratitude Cache with Allies, or stops sharing it. Owner only.
     *
     * @return {@link Result#DONE}, {@link Result#ALREADY} when it was already so, or a refusal
     */
    public static Result setCacheShared(MinecraftServer server, Colony colony, ColonyPermissions.Role actor,
            boolean shared) {
        if (!may(actor, ColonyPermissions.Action.OWNER_SETTINGS)) {
            return Result.NOT_ALLOWED;
        }
        UUID id = colony.colonyId();
        if (ColonyState.get(server).colony(id) == null) {
            return Result.ABSENT;
        }
        ColonyLife store = ColonyLife.get(server);
        ColonyLife.Life life = store.life(id);
        if (life.cacheShared() == shared) {
            return Result.ALREADY;
        }
        life.setCacheShared(shared);
        store.touch();
        return done(server, id);
    }

    /**
     * Prioritises one need, or clears the priority when {@code item} is {@code null}. Owner or Chief.
     *
     * @return {@link Result#DONE}, {@link Result#ALREADY} when nothing changed, or a refusal
     */
    public static Result setPriorityNeed(MinecraftServer server, Colony colony,
            ColonyPermissions.Role actor, @Nullable Identifier item) {
        if (!may(actor, ColonyPermissions.Action.PLAN)) {
            return Result.NOT_ALLOWED;
        }
        UUID id = colony.colonyId();
        if (ColonyState.get(server).colony(id) == null) {
            return Result.ABSENT;
        }
        ColonyLife store = ColonyLife.get(server);
        ColonyLife.Life life = store.life(id);
        if (Objects.equals(life.priorityNeed(), item)) {
            return Result.ALREADY;
        }
        life.setPriorityNeed(item);
        store.touch();
        return done(server, id);
    }

    // --- shared steps -----------------------------------------------------------

    /** The checks every role change starts with, or {@code null} when it may go ahead. */
    @Nullable
    private static Result refusal(Colony colony, ColonyPermissions.Role actor,
            ColonyPermissions.Action needed, @Nullable UUID actorId, @Nullable UUID target) {
        if (!may(actor, needed) || target == null || Colony.SERVER_OWNER.equals(target)) {
            return Result.NOT_ALLOWED;
        }
        if (colony.isOwner(target)) {
            return Result.IS_OWNER;
        }
        if (target.equals(actorId)) {
            return Result.IS_SELF;
        }
        return null;
    }

    private static boolean may(ColonyPermissions.Role actor, ColonyPermissions.Action action) {
        return ColonyPermissions.allows(actor, action, false);
    }

    /** Puts {@code target} on the access list and writes the access log's grant row. */
    private static void grant(ColonyState state, Colony colony, UUID target) {
        state.put(colony.grantAccess(target));
        state.log(colony.colonyId(), target, AccessLog.Action.ACCESS_GRANT);
    }

    /**
     * Takes {@code target} off the access list (and out of the Chiefs with it), writes the access
     * log's revoke row, and blanks the colony view of somebody who has just stopped being a member.
     */
    private static void revoke(MinecraftServer server, ColonyState state, ColonyRoles roles, Colony colony,
            UUID target) {
        roles.removeChief(colony.colonyId(), target);
        state.put(colony.revokeAccess(target));
        state.log(colony.colonyId(), target, AccessLog.Action.ACCESS_REVOKE);
        ColonySync.clearView(server, target);
    }

    private static Result done(MinecraftServer server, UUID colonyId) {
        ColonySync.refresh(server, colonyId);
        return Result.DONE;
    }
}
