package za.co.neroland.nerocolonies.platform;

import java.nio.file.Path;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * Fabric implementation of {@link PlatformInfo}. Registered via
 * {@code META-INF/services/za.co.neroland.nerocolonies.platform.PlatformInfo}. The class keeps the
 * common package name so every loader's services file resolves the same way.
 */
public final class FabricPlatformInfo implements PlatformInfo {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public String getModVersion() {
        return FabricLoader.getInstance().getModContainer(NeroColoniesCommon.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public List<String> getLoadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString())
                .sorted()
                .toList();
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
