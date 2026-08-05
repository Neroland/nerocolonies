package za.co.neroland.nerocolonies.lifecycle;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.link.ColonyLinkEvents;
import za.co.neroland.nerocolonies.network.ColonySync;

/**
 * The one place NeroColonies learns that a server started or stopped, invoked from each loader's own
 * lifecycle hook (the same {@code ServerStateReset} shape NeroLogistics and NeroAgriculture use).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Four caches in this mod are {@code static} and therefore live for the whole JVM, not for the
 * world: the job board's station registry, life support's generator registry and grace counters, the
 * definition-payload cache and the datapack content cache. In single-player that JVM outlives the
 * world — leave a world, load another, and the second world starts with the first world's stations
 * filed against colony ids that no longer exist and the first world's datapack content still cached.
 * Every one of them is a <em>lazily rebuilt</em> cache, so clearing them costs nothing: block
 * entities re-file themselves on their first tick and the definitions are re-read on first use.
 *
 * <p>Durable state is untouched. Colonies, outposts, access logs and colony stores are
 * {@code SavedData} and belong to the world, not to this class.
 *
 * <h2>The running server</h2>
 *
 * <p>Core's link API hands a snapshot provider a player {@link java.util.UUID} and nothing else — no
 * server, no level — so the link module needs somewhere to find the running server. That is the
 * second job of this class: {@link #serverStarted} records it and {@link #serverStopped} forgets it,
 * so a link snapshot taken before the first world is loaded (or after the last one closed) honestly
 * answers "nothing", rather than guessing from a stale reference.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing here holds, logs or clears player data. The registries
 * cleared are keyed by colony id and block position only.
 */
public final class ServerStateReset {

    /**
     * The running server, or {@code null} between worlds. Written on the server thread from the
     * loader hooks; {@code volatile} so an integrated-server restart in the same JVM is seen at once.
     */
    private static volatile MinecraftServer currentServer;

    private ServerStateReset() {
    }

    /** Records the server that has just finished starting. Called once per world load. */
    public static void serverStarted(@Nullable MinecraftServer server) {
        currentServer = server;
    }

    /** The running server, or {@code null} before the first world load / after the last one closed. */
    @Nullable
    public static MinecraftServer currentServer() {
        return currentServer;
    }

    /**
     * Clears every server-scoped static cache. Loaders fire it on the server thread after stop, so
     * the next world in this JVM starts from an empty board.
     */
    public static void serverStopped() {
        JobBoard.reset();
        LifeSupport.reset();
        ColonySync.forgetServer();
        ColonyDefinitions.forgetServer();
        ColonyLinkEvents.reset();
        currentServer = null;
    }
}
