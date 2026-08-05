package za.co.neroland.nerocolonies.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** NeoForge entry point for NeroColonies. */
@Mod(NeroColoniesCommon.MOD_ID)
public final class NeroColoniesNeoForge {

    public NeroColoniesNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] NeoForge bootstrap");
        // Common init declares the payloads and creates the deferred registrations; the calls below
        // consume those declarations.
        NeroColoniesCommon.init();
        // Common init created NeroColonies' DeferredRegisters through Core's registration seam;
        // this attaches them to OUR mod event bus.
        RegistrationProvider.attach(modEventBus);
        NeoForgeColonyNetwork.register(modEventBus);
        NeoForgeColonyCapabilities.register(modEventBus);
        NeoForgeColonyEvents.register();
        // Client-only: screens. Gated so the client classes never load on a dedicated server.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            NeoForgeClientSetup.init(modEventBus);
        }
    }
}
