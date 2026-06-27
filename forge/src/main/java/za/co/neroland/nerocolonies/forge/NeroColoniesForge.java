package za.co.neroland.nerocolonies.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/** MinecraftForge entry point for NeroColonies. */
@Mod(NeroColoniesCommon.MOD_ID)
public final class NeroColoniesForge {

    public NeroColoniesForge(FMLJavaModLoadingContext context) {
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Forge bootstrap");
        NeroColoniesCommon.init();
    }
}
