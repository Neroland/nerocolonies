package za.co.neroland.nerocolonies.platform;

import java.nio.file.Path;
import java.util.List;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * NeoForge implementation of {@link PlatformInfo}. Registered via
 * {@code META-INF/services/za.co.neroland.nerocolonies.platform.PlatformInfo}.
 */
public final class NeoForgePlatformInfo implements PlatformInfo {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        // 26.x exposes these as methods (the old `FMLEnvironment.production` / `.dist` fields are gone).
        return !FMLEnvironment.isProduction();
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.getDist() == Dist.CLIENT;
    }

    @Override
    public String getModVersion() {
        return ModList.get().getModContainerById(NeroColoniesCommon.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public List<String> getLoadedModIds() {
        return ModList.get().getMods().stream()
                .map(m -> m.getModId() + " " + m.getVersion())
                .sorted()
                .toList();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
