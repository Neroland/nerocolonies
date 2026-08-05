package za.co.neroland.nerocolonies.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import za.co.neroland.nerocolonies.command.NeroColoniesCommands;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * Fabric side of the three server-side hooks NeroColonies needs: the command tree, and the two ends
 * of a server's life.
 *
 * <p>The {@code /nerocolonies} tree itself is loader-agnostic and is built in common — neither the
 * build context nor the dedicated/integrated selection changes it. The lifecycle pair exists because
 * this mod's job board, life-support registry, definition cache and content cache are {@code static}
 * and would otherwise outlive the world that filled them; see
 * {@link ServerStateReset} for why that matters in single-player.
 */
public final class FabricColonyEvents {

    private FabricColonyEvents() {
    }

    /** Called once from the Fabric entry point. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerStateReset::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerStateReset.serverStopped());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NeroColoniesCommands.register(dispatcher));
    }
}
