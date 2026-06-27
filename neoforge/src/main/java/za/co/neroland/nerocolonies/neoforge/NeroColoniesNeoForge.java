package za.co.neroland.nerocolonies.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** NeoForge entry point for NeroColonies. */
@Mod(NeroColoniesCommon.MOD_ID)
public final class NeroColoniesNeoForge {

    public NeroColoniesNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] NeoForge bootstrap");
        NeroColoniesCommon.init();
    }
}
