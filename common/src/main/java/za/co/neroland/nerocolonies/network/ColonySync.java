package za.co.neroland.nerocolonies.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * The server side of colony syncing: what gets sent to whom, and when.
 *
 * <h2>On demand, not on join</h2>
 *
 * <p>Both payloads are sent when a player <b>opens a colony interface</b> and after any action that
 * changes what they are looking at. There is deliberately no join hook and no timer:
 *
 * <ul>
 *   <li>a player who never opens a colony screen never needs either payload, and on a server with two
 *       hundred colonies that is most players most of the time;</li>
 *   <li>{@code /reload} needs no listener — {@link #open} compares
 *       {@link ColonyDefinitions#generation()} on every open, so the next screen a player opens after
 *       a reload gets the new content and every earlier one is already closed;</li>
 *   <li>and it needs no per-loader event wiring at all, which is three fewer places for the three
 *       loaders to diverge.</li>
 * </ul>
 *
 * <p>The definition payload is identical for every player and expensive to build, so it is cached
 * against the content generation. The snapshot is per-player by construction and is built fresh.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>{@link #refresh} fans a change out only to players who are <b>members of that colony</b>, and
 * each one is sent their own snapshot. Nothing is broadcast, and no snapshot contains another
 * player's identity — see {@link ColonySnapshotPayload} for what is and is not in one.
 */
public final class ColonySync {

    private static MinecraftServer cachedFor;
    private static int cachedGeneration = -1;
    private static ColonyDefinitionsPayload cachedDefinitions = ColonyDefinitionsPayload.EMPTY;

    private ColonySync() {
    }

    /**
     * Everything a player needs to draw a colony screen: the content set, then their snapshot.
     *
     * @param anchor the block they opened — it travels in the snapshot so the screen knows which
     *               block its intents are about
     */
    public static void open(ServerPlayer player, @Nullable Colony colony, BlockPos anchor) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, definitions(server));
        sendSnapshot(player, colony, anchor);
    }

    /** One player's snapshot of one colony (or the "nothing open" payload). */
    public static void sendSnapshot(ServerPlayer player, @Nullable Colony colony, BlockPos anchor) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, colony == null
                ? ColonySnapshotPayload.EMPTY
                : ColonySnapshotPayload.of(server, player, colony, anchor));
    }

    /**
     * Re-sends the snapshot of one colony to every online member of it — the call to make after a
     * research unlock, an access change or a sale, so a second player watching the same colony is not
     * left looking at a stale screen.
     *
     * <p>The anchor used for the fan-out is the colony's beacon: a player who is looking at some other
     * block of the colony will re-anchor the moment they act, and pointing an idle screen at the
     * beacon is always a valid thing to point it at.
     */
    public static void refresh(MinecraftServer server, UUID colonyId) {
        Colony colony = ColonyState.get(server).colony(colonyId);
        if (colony == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (colony.isMember(player.getUUID())) {
                Services.NETWORK.sendToPlayer(player,
                        ColonySnapshotPayload.of(server, player, colony, colony.beaconPos()));
            }
        }
    }

    /** The content snapshot for this server, rebuilt only when the definitions were re-read. */
    private static synchronized ColonyDefinitionsPayload definitions(MinecraftServer server) {
        ColonyDefinitions.refreshIfReloaded(server);
        int generation = ColonyDefinitions.generation();
        if (server != cachedFor || generation != cachedGeneration) {
            cachedDefinitions = ColonyDefinitionsPayload.of(server);
            cachedFor = server;
            cachedGeneration = ColonyDefinitions.generation();
        }
        return cachedDefinitions;
    }

    /** Drops the cache so a second world in the same JVM does not serve the first world's content. */
    public static synchronized void forgetServer() {
        cachedFor = null;
        cachedGeneration = -1;
        cachedDefinitions = ColonyDefinitionsPayload.EMPTY;
    }
}
