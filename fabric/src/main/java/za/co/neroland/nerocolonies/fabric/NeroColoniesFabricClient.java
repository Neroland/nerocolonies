package za.co.neroland.nerocolonies.fabric;

import net.fabricmc.api.ClientModInitializer;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** Fabric client entry point for NeroColonies. */
public final class NeroColoniesFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Fabric client bootstrap");
    }
}
