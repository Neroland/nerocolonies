package za.co.neroland.nerocolonies.colony;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

/**
 * The one place a colony decides what a player may do. Every block, menu, Neran interaction, cache,
 * needs board, planner, command and link action asks here, server-side.
 *
 * <p>The hierarchy is <b>Owner ⊃ Chief ⊃ Ally</b>; everyone else is a stranger, and an Enemy is a
 * stranger the guards attack. Operators (permission level 2) are treated as owners for
 * administration, as they always were.
 */
public final class ColonyPermissions {

    /** What a player is to a colony. Ordinal order is rank order for the member roles. */
    public enum Role {
        STRANGER, ENEMY, ALLY, CHIEF, OWNER;

        /** Whether this role is a colony member (may interact at all). */
        public boolean member() {
            return this == ALLY || this == CHIEF || this == OWNER;
        }

        /** Lower-case name for payloads and lang keys. */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Things a player may try to do to a colony. */
    public enum Action {
        /** Open colony blocks and menus, feed the beacon, use the depot. */
        INTERACT,
        /** Put items into the Needs Board or depot towards the needs list. */
        CONTRIBUTE,
        /** Rename a Neran. */
        NERAN,
        /** Open the Gratitude Cache (allies only when the owner has shared it). */
        OPEN_CACHE,
        /** Place buildings with the Colony Planner or Planning Table. */
        PLAN,
        /** Add or remove Allies and Enemies. */
        MANAGE_MEMBERS,
        /** Add or remove Chiefs. */
        MANAGE_CHIEFS,
        /** Share the cache, prioritise a need, rename the colony. */
        OWNER_SETTINGS
    }

    private ColonyPermissions() {
    }

    /**
     * Pure rule table: whether {@code role} may perform {@code action}. Unit-tested.
     *
     * @param cacheShared whether the owner has shared the Gratitude Cache with allies
     */
    public static boolean allows(Role role, Action action, boolean cacheShared) {
        return switch (action) {
            case INTERACT, CONTRIBUTE, NERAN -> role.member();
            case OPEN_CACHE -> role == Role.OWNER || (cacheShared && role.member());
            case PLAN, MANAGE_MEMBERS -> role == Role.OWNER || role == Role.CHIEF;
            case MANAGE_CHIEFS, OWNER_SETTINGS -> role == Role.OWNER;
        };
    }

    /** The role a player id holds in a colony (no operator elevation). */
    public static Role roleOf(MinecraftServer server, Colony colony, UUID player) {
        if (colony.isOwner(player)) {
            return Role.OWNER;
        }
        ColonyRoles roles = ColonyRoles.get(server);
        if (roles.isEnemy(colony.colonyId(), player)) {
            return Role.ENEMY;
        }
        if (colony.accessList().contains(player)) {
            return roles.isChief(colony.colonyId(), player) ? Role.CHIEF : Role.ALLY;
        }
        return Role.STRANGER;
    }

    /** The role a player holds, with operators elevated to owner. */
    public static Role effectiveRole(Player player, Colony colony) {
        if (!(player.level() instanceof ServerLevel level)) {
            return Role.STRANGER;
        }
        if (ColonyClaims.isGamemaster(player)) {
            return Role.OWNER;
        }
        return roleOf(level.getServer(), colony, player.getUUID());
    }

    /** May this player do this to this colony? Server-side; false for nulls. */
    public static boolean can(@Nullable Player player, @Nullable Colony colony, Action action) {
        if (player == null || colony == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        Role role = effectiveRole(player, colony);
        boolean shared = action == Action.OPEN_CACHE
                && ColonyLife.get(level.getServer()).life(colony.colonyId()).cacheShared();
        return allows(role, action, shared);
    }

    /** {@link #can} that tells the player politely when the answer is no. */
    public static boolean check(@Nullable Player player, @Nullable Colony colony, Action action) {
        boolean allowed = can(player, colony, action);
        if (!allowed && player != null) {
            boolean member = colony != null && effectiveRole(player, colony).member();
            player.sendOverlayMessage(Component.translatable(member
                    ? "message.nerocolonies.permission.rank" : "message.nerocolonies.permission.stranger"));
        }
        return allowed;
    }

    /** Whether a player id is a member (owner, chief or ally). */
    public static boolean isMember(Colony colony, UUID player) {
        return colony.isMember(player);
    }
}
