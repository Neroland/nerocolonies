package za.co.neroland.nerocolonies.forge;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import za.co.neroland.nerocolonies.command.NeroColoniesCommands;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * Forge side of the three server-side hooks NeroColonies needs. Forge 26.x has no single global
 * event bus — each event class owns a static {@code BUS} — so listeners are attached per event type.
 *
 * <p>The {@code /nerocolonies} tree itself is loader-agnostic and is built in common. The lifecycle
 * pair exists because this mod's job board, life-support registry, definition cache and content
 * cache are {@code static} and would otherwise outlive the world that filled them; see
 * {@link ServerStateReset}.
 */
public final class ForgeColonyEvents {

    private ForgeColonyEvents() {
    }

    /** Called once from the Forge entry point. */
    public static void register() {
        ServerStartedEvent.BUS.addListener(event -> ServerStateReset.serverStarted(event.getServer()));
        ServerStoppedEvent.BUS.addListener(event -> ServerStateReset.serverStopped());

        RegisterCommandsEvent.BUS.addListener(event ->
                NeroColoniesCommands.register(event.getDispatcher()));
    }
}
