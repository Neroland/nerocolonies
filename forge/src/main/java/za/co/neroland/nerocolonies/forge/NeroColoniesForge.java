package za.co.neroland.nerocolonies.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** MinecraftForge entry point for NeroColonies. */
@Mod(NeroColoniesCommon.MOD_ID)
public final class NeroColoniesForge {

    public NeroColoniesForge(FMLJavaModLoadingContext context) {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Forge bootstrap");
        // Common init declares the payloads; the channel below is sealed the moment it is built,
        // so that ordering is mandatory on Forge.
        NeroColoniesCommon.init();
        // Common init created NeroColonies' DeferredRegisters through Core's registration seam;
        // this attaches them to OUR mod bus group.
        RegistrationProvider.attach(context.getModBusGroup());
        ForgeColonyNetwork.register();
        ForgeColonyCapabilities.register();
        ForgeColonyEvents.register();
        // Client-only: screens. Gated so the client classes never load on a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientSetup.init(context.getModBusGroup());
        }
    }
}
