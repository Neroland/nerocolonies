package za.co.neroland.nerocolonies.platform;

import java.nio.file.Path;
import java.util.List;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * Forge implementation of {@link PlatformInfo}. Registered via
 * {@code META-INF/services/za.co.neroland.nerocolonies.platform.PlatformInfo}.
 *
 * <p>Forge diverges from NeoForge here: {@code FMLEnvironment.production} / {@code .dist} are
 * fields (not methods), and {@code ModList}'s lookups are static rather than reached through
 * {@code ModList.get()}.
 */
public final class ForgePlatformInfo implements PlatformInfo {

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    @Override
    public boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public String getModVersion() {
        return ModList.getModContainerById(NeroColoniesCommon.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.isLoaded(modId);
    }

    @Override
    public List<String> getLoadedModIds() {
        return ModList.getMods().stream()
                .map(m -> m.getModId() + " " + m.getVersion())
                .sorted()
                .toList();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
