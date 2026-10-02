package za.co.neroland.nerocolonies.forge;

import java.util.function.Consumer;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import za.co.neroland.nerocolonies.colony.ColonyDefence;
import za.co.neroland.nerocolonies.colony.ColonyPlanner;
import za.co.neroland.nerocolonies.command.NeroColoniesCommands;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * Forge side of the server-side hooks NeroColonies needs (commands, server start/stop, a player's
 * death for colony defence, and a player leaving). Forge 26.x has no single global
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

        // Colony defence: an enemy who kills a colony's owner has had their retribution. Registered
        // as a plain consumer, so this listener can observe the death but never cancel it.
        Consumer<LivingDeathEvent> death = event ->
                ColonyDefence.onLivingDeath(event.getEntity(), event.getSource());
        LivingDeathEvent.BUS.addListener(death);
        // The planner's in-memory selection does not outlive the session.
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event ->
                ColonyPlanner.forget(event.getEntity().getUUID()));
    }
}
