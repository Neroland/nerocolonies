package za.co.neroland.nerocolonies.neoforge;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

import za.co.neroland.nerocolonies.client.ClientEntityRenderers;
import za.co.neroland.nerocolonies.client.ScreenBindings;
import za.co.neroland.nerocolonies.network.ColonyNetwork;

/**
 * NeoForge client-only setup: menu screens and entity renderers. Loaded only on the physical client
 * (the entry point gates on {@code Dist.CLIENT}), so the client classes never reach a dedicated
 * server.
 */
public final class NeoForgeClientSetup {

    private NeoForgeClientSetup() {
    }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeClientSetup::onRegisterScreens);
        modEventBus.addListener(NeoForgeClientSetup::onRegisterEntityRenderers);
        // Drop the synced colony mirrors on leaving a world/server, so one session's colony state can
        // never be shown in the next (or on a server that does not run NeroColonies). This is on the
        // GAME bus, not the mod bus.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                ColonyNetwork.clearClientCaches());
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        // One canonical menu->screen table in common; adapted onto the NeoForge event here.
        ScreenBindings.registerAll(new ScreenBindings.Registrar() {
            @Override
            public <M extends AbstractContainerMenu, U extends AbstractContainerScreen<M>> void register(
                    MenuType<M> type, ScreenBindings.ScreenFactory<M, U> factory) {
                event.register(type, factory::create);
            }
        });
    }

    private static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // One canonical entity->renderer table in common; adapted onto the NeoForge event here.
        ClientEntityRenderers.registerAll(new ClientEntityRenderers.Sink() {
            @Override
            public <E extends Entity> void register(EntityType<? extends E> type,
                    EntityRendererProvider<E> provider) {
                event.registerEntityRenderer(type, provider);
            }
        });
    }
}
