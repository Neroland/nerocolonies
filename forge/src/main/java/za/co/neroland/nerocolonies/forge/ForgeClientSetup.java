package za.co.neroland.nerocolonies.forge;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import za.co.neroland.nerocolonies.client.ClientEntityRenderers;
import za.co.neroland.nerocolonies.client.ScreenBindings;
import za.co.neroland.nerocolonies.network.ColonyNetwork;

/**
 * Forge client-only setup: menu screens and entity renderers. Loaded only on the physical client
 * (the entry point gates on {@code Dist.CLIENT}), so the client classes never reach a dedicated
 * server.
 */
public final class ForgeClientSetup {

    private ForgeClientSetup() {
    }

    public static void init(BusGroup modBusGroup) {
        FMLClientSetupEvent.getBus(modBusGroup).addListener(event -> event.enqueueWork(() ->
                // One canonical menu->screen table in common; adapted onto vanilla
                // MenuScreens.register here.
                ScreenBindings.registerAll(new ScreenBindings.Registrar() {
                    @Override
                    public <M extends AbstractContainerMenu, U extends AbstractContainerScreen<M>> void register(
                            MenuType<M> type, ScreenBindings.ScreenFactory<M, U> factory) {
                        MenuScreens.register(type, factory::create);
                    }
                })));
        EntityRenderersEvent.RegisterRenderers.BUS.addListener(ForgeClientSetup::onRegisterEntityRenderers);
        // Drop the synced colony mirrors on leaving a world/server. Forge 26.x has no global event
        // bus — each event class owns a static BUS — and a statement-bodied lambda keeps the
        // Consumer/Predicate overloads unambiguous.
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            ColonyNetwork.clearClientCaches();
        });
    }

    private static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // One canonical entity->renderer table in common; adapted onto the Forge event here.
        ClientEntityRenderers.registerAll(new ClientEntityRenderers.Sink() {
            @Override
            public <E extends Entity> void register(EntityType<? extends E> type,
                    EntityRendererProvider<E> provider) {
                event.registerEntityRenderer(type, provider);
            }
        });
    }
}
