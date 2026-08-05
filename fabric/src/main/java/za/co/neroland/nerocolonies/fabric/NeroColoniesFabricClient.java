package za.co.neroland.nerocolonies.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.client.ClientEntityRenderers;
import za.co.neroland.nerocolonies.client.ScreenBindings;
import za.co.neroland.nerocolonies.network.ColonyNetwork;

/** Fabric client entry point for NeroColonies. */
public final class NeroColoniesFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Fabric client bootstrap");
        // Clientbound receivers (client-only API) — registered here, off the dedicated server.
        FabricColonyNetwork.registerClient();

        // One canonical menu->screen table in common; adapted onto vanilla MenuScreens.register here.
        ScreenBindings.registerAll(new ScreenBindings.Registrar() {
            @Override
            public <M extends AbstractContainerMenu, U extends AbstractContainerScreen<M>> void register(
                    MenuType<M> type, ScreenBindings.ScreenFactory<M, U> factory) {
                MenuScreens.register(type, factory::create);
            }
        });

        // One canonical entity->renderer table in common; adapted onto Fabric's registry here.
        ClientEntityRenderers.registerAll(new ClientEntityRenderers.Sink() {
            @Override
            public <E extends Entity> void register(EntityType<? extends E> type,
                    EntityRendererProvider<E> provider) {
                EntityRendererRegistry.register(type, provider);
            }
        });

        // Drop any synced mirror caches on leaving a world/server, so one session's colony state can
        // never be shown in the next (or on a server that does not run NeroColonies).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                ColonyNetwork.clearClientCaches());
    }
}
