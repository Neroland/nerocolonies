package za.co.neroland.nerocolonies.colony;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

/**
 * The public query surface over colony ownership — and it is <b>boolean-only</b> by design.
 *
 * <p>Anything outside the server-side privileged path (another mod, a command's suggestion
 * provider, the link module, a client sync payload) asks its question here and gets a
 * {@code true}/{@code false} back. <b>No method on this class returns an owner UUID or a player
 * name</b>, and none ever will: the owner slot exists so the server can answer "may this person do
 * this?", not so anything can publish who lives where.
 *
 * <p>Non-identifying colony <em>state</em> (name, morale, population, food) is fair game and has its
 * own accessors here, because that is what a GUI and a companion client legitimately display.
 *
 * <p>Reads are cheap: the claim lookup is an O(1) chunk-index hit inside {@link ColonyState}.
 */
public final class ColonyApi {

    private ColonyApi() {
    }

    // --- claims -------------------------------------------------------------

    /** Whether any colony claims this position. */
    public static boolean isClaimed(ServerLevel level, BlockPos pos) {
        return ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos) != null;
    }

    /** Whether a colony's beacon stands exactly here. */
    public static boolean isBeacon(ServerLevel level, BlockPos pos) {
        return ColonyClaims.colonyAtBeacon(level, pos) != null;
    }

    /** Whether a colony with this id exists. */
    public static boolean exists(MinecraftServer server, UUID colonyId) {
        return ColonyState.get(server).colony(colonyId) != null;
    }

    // --- membership (boolean answers only) ---------------------------------

    /** Whether {@code player} owns {@code colony}. Never reveals who does if the answer is false. */
    public static boolean ownedBy(@Nullable Colony colony, @Nullable UUID player) {
        return colony != null && colony.isOwner(player);
    }

    /** Whether {@code player} owns the colony with this id. */
    public static boolean ownedBy(MinecraftServer server, UUID colonyId, UUID player) {
        return ownedBy(ColonyState.get(server).colony(colonyId), player);
    }

    /** Whether {@code player} may act on {@code colony} (owner, member, or operator). */
    public static boolean canAccess(@Nullable Player player, @Nullable Colony colony) {
        return ColonyClaims.canAccess(player, colony);
    }

    /** Whether {@code player} may act on the colony with this id. */
    public static boolean canAccess(@Nullable Player player, MinecraftServer server, UUID colonyId) {
        return ColonyClaims.canAccess(player, ColonyState.get(server).colony(colonyId));
    }

    /** Whether {@code player} may build at this position. */
    public static boolean canBuild(@Nullable Player player, ServerLevel level, BlockPos pos) {
        return ColonyClaims.canBuild(player, level, pos);
    }

    // --- non-identifying state ---------------------------------------------

    /** The colony claiming this position, or {@code null}. Server-side callers only. */
    @Nullable
    public static Colony colonyAt(ServerLevel level, BlockPos pos) {
        return ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos);
    }

    /** The colony's display name, or an empty string. Not personal data — it is player-chosen text. */
    public static String nameOf(@Nullable Colony colony) {
        return colony == null ? "" : colony.name();
    }

    /** How many colonies exist on this server. */
    public static int colonyCount(MinecraftServer server) {
        return ColonyState.get(server).size();
    }
}
