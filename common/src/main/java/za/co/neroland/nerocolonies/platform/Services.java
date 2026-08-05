package za.co.neroland.nerocolonies.platform;

import java.util.ServiceLoader;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * Loads loader-specific platform-seam implementations via {@link ServiceLoader}.
 *
 * <p>Common code calls {@code Services.PLATFORM.xxx()}; the correct Fabric / Forge / NeoForge
 * implementation is resolved at runtime from the {@code META-INF/services} entry each loader module
 * ships. Both seams are resolved during class initialisation, which happens the first time
 * {@code NeroColoniesCommon.init()} touches this class — <b>never lazily mid-tick</b>. A lazy
 * {@link ServiceLoader} read can throw {@code ServiceConfigurationError} out of gameplay code if
 * the jar has become unreadable (Nerospace crash precedent MC-NEROSPACE-F).
 */
public final class Services {

    public static final PlatformInfo PLATFORM = load(PlatformInfo.class);

    /** The loader's packet-send implementation (see {@link NetworkPlatform}). */
    public static final NetworkPlatform NETWORK = load(NetworkPlatform.class);

    private Services() {
    }

    /** Resolves every seam now, so nothing has to be resolved on a tick path later. */
    public static void init() {
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] platform seams resolved ({})",
                PLATFORM.getPlatformName());
    }

    public static <T> T load(Class<T> clazz) {
        final T loaded = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException(
                        "No implementation found for service " + clazz.getName()));
        NeroColoniesCommon.LOGGER.debug("Loaded service {} -> {}",
                clazz.getSimpleName(), loaded.getClass().getName());
        return loaded;
    }
}
