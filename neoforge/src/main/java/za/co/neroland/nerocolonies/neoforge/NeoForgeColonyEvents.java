package za.co.neroland.nerocolonies.neoforge;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import za.co.neroland.nerocolonies.colony.ColonyDefence;
import za.co.neroland.nerocolonies.colony.ColonyPlanner;
import za.co.neroland.nerocolonies.command.NeroColoniesCommands;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * NeoForge side of the server-side hooks NeroColonies needs: the command tree, the two ends of a
 * server's life, a player's death (colony defence) and a player leaving.
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

        // Colony defence: an enemy who kills a colony's owner has had their retribution.
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) ->
                ColonyDefence.onLivingDeath(event.getEntity(), event.getSource()));
        // The planner's in-memory selection does not outlive the session.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) ->
                ColonyPlanner.forget(event.getEntity().getUUID()));
    }
}
