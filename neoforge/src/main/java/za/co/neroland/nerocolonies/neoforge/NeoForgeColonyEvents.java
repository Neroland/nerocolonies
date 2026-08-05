package za.co.neroland.nerocolonies.neoforge;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import za.co.neroland.nerocolonies.command.NeroColoniesCommands;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * NeoForge side of the three server-side hooks NeroColonies needs: the command tree, and the two
 * ends of a server's life.
 *
 * <p>The {@code /nerocolonies} tree itself is loader-agnostic and is built in common. The lifecycle
 * pair exists because this mod's job board, life-support registry, definition cache and content
 * cache are {@code static} and would otherwise outlive the world that filled them; see
 * {@link ServerStateReset}.
 */
public final class NeoForgeColonyEvents {

    private NeoForgeColonyEvents() {
    }

    /** Called once from the NeoForge entry point. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                ServerStateReset.serverStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ServerStateReset.serverStopped());

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                NeroColoniesCommands.register(event.getDispatcher()));
    }
}
