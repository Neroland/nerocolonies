package za.co.neroland.nerocolonies.fabric;

import net.fabricmc.api.ModInitializer;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** Fabric entry point for NeroColonies. */
public final class NeroColoniesFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Fabric bootstrap");
        // Common init declares the payloads and (on Fabric) registers content eagerly; the calls
        // below consume those declarations. Core's RegistrationProvider needs no attach on Fabric —
        // its registrations apply immediately at class-load.
        NeroColoniesCommon.init();
        FabricColonyNetwork.registerCommon();
        FabricColonyCapabilities.register();
        FabricColonyEvents.register();
    }
}
