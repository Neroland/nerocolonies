package za.co.neroland.nerocolonies.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import za.co.neroland.nerolandcore.worldgen.SpaceTags;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * Sibling-mod detection, done exactly once during common init (step 9) and cached in final fields.
 *
 * <p>Every bridge in NeroColonies sits behind a check made here, never behind a per-interaction
 * {@code isModLoaded} call: asking the loader "is that mod present?" on a gameplay path is both
 * wasteful and a way to get a different answer at different times.
 *
 * <p>Detection is presence-only and one-way. NeroColonies never calls into a sibling that has not
 * been detected, and no sibling is required for anything — remove them all and the mod runs.
 */
public final class CompatRegistry {

    private static volatile NerospaceCompat nerospace = NerospaceCompat.NONE;

    private static volatile boolean neroAgriculture;
    private static volatile boolean neroLogistics;
    private static volatile boolean neroEconomy;

    private static volatile boolean detected;

    private CompatRegistry() {
    }

    /** Detects the optional siblings. Idempotent; called once from common init. */
    public static void init() {
        if (detected) {
            return;
        }
        detected = true;

        if (Services.PLATFORM.isModLoaded("nerospace")) {
            NerospaceCompat bridge = za.co.neroland.nerocolonies.compat.nerospace.NerospaceBridge.probe();
            if (bridge != null) {
                nerospace = bridge;
            }
        }
        neroAgriculture = Services.PLATFORM.isModLoaded("neroagriculture");
        neroLogistics = Services.PLATFORM.isModLoaded("nerologistics");
        neroEconomy = Services.PLATFORM.isModLoaded("neroeconomy");

        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Sibling compat: nerospace={}, neroagriculture={}, nerologistics={}, "
                        + "neroeconomy={}.",
                nerospace.active(), neroAgriculture, neroLogistics, neroEconomy);
    }

    /**
     * The planet-traits façade. Never {@code null}: with Nerospace absent this is
     * {@link NerospaceCompat#NONE}, which reports every dimension breathable and no hazard.
     */
    public static NerospaceCompat nerospace() {
        return nerospace;
    }

    /**
     * Whether a colony in this dimension needs life support at all.
     *
     * <p>Two signals, in strict priority order:
     * <ol>
     *   <li>Nerospace's {@code airless()} for the dimension, which is authoritative when Nerospace
     *       is installed;</li>
     *   <li>with Nerospace absent, Core's {@code SpaceTags.isSpace(level)} as an <b>advisory
     *       hint</b> — another mod's planet dimension may carry the shared
     *       {@code neroland:space/dimensions} tag, and honouring it costs nothing and makes
     *       NeroColonies work with a planet mod it has never heard of.</li>
     * </ol>
     *
     * <p>The hint never overrides the adapter: with Nerospace installed, a dimension it says is
     * breathable is breathable, tag or no tag. That ordering is what keeps a single authority for
     * the answer whenever there is one.
     */
    public static boolean requiresLifeSupport(ServerLevel level) {
        if (level == null) {
            return false;
        }
        if (nerospace.active()) {
            return nerospace.airless(level.dimension());
        }
        try {
            return SpaceTags.isSpace(level);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The hazard class of a dimension — {@code NONE} whenever Nerospace is absent. */
    public static NerospaceCompat.Hazard hazard(ResourceKey<Level> dimension) {
        return nerospace.hazard(dimension);
    }

    public static boolean hasNeroAgriculture() {
        return neroAgriculture;
    }

    public static boolean hasNeroLogistics() {
        return neroLogistics;
    }

    public static boolean hasNeroEconomy() {
        return neroEconomy;
    }
}
