package za.co.neroland.nerocolonies.fabric;

import net.fabricmc.api.ModInitializer;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** Fabric entry point for NeroColonies. */
public final class NeroColoniesFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Fabric bootstrap");
        NeroColoniesCommon.init();
    }
}
