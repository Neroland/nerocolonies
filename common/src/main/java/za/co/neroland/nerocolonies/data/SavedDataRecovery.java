package za.co.neroland.nerocolonies.data;

import java.util.function.Supplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.telemetry.NeroColoniesTelemetry;

/**
 * Crash-proof loading for NeroColonies {@link SavedData}. <b>Every</b> NeroColonies saved-data
 * accessor routes through {@link #get}; nothing calls {@code getDataStorage().computeIfAbsent(...)}
 * directly — a direct call is a review failure.
 *
 * <p>Vanilla's {@code SavedDataStorage.computeIfAbsent} reads {@code data/<id>.dat} on first access
 * and lets any failure (corrupt, truncated or unreadable file) propagate unchecked. NeroColonies
 * fetches the colony index from block-placement, interaction and tick paths, so one bad file would
 * otherwise crash the server on every attempt to touch a claim. Instead this helper:
 *
 * <ol>
 *   <li>tries vanilla storage;</li>
 *   <li>on failure <b>or a null return</b>, substitutes a fresh empty instance, installs it into the
 *       storage cache (so later calls hit the cache instead of re-reading the bad file) and marks it
 *       dirty (so a clean file is written at the next level save).</li>
 * </ol>
 *
 * <p>The result is degraded-but-playable rather than a hard crash: a recovered colony index starts
 * empty, so claims are unenforced and beacons re-register themselves on the next interaction —
 * annoying, and far better than an unloadable world.
 *
 * <p><b>Pattern credit.</b> This is the Nerospace {@code SavedDataRecovery} guard (added there after
 * a live corruption incident, MC-NEROSPACE-H) by way of NeroQuests' and NeroCreatures' copies; the
 * ecosystem rule is that every {@code SavedData} in every Nero mod goes through a guard of this
 * shape.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> the failure is logged with the saved-data resource name and the
 * dimension key only — never a player name, UUID or any stored value — and reported through the
 * existing scrubbed, opt-out telemetry pipeline as a handled (non-fatal) event.
 */
public final class SavedDataRecovery {

    private SavedDataRecovery() {
    }

    /**
     * Fetches {@code type} from {@code level}'s data storage, recovering to a fresh instance if the
     * stored file cannot be read.
     *
     * @param level    the level whose data storage holds the file (NeroColonies uses the overworld)
     * @param type     the saved-data type (id + factory + codec)
     * @param fallback supplies the fresh empty instance used when recovery is needed
     * @param name     stable non-identifying label for logs/telemetry, e.g.
     *                 {@code "nerocolonies:colonies"}
     * @return the loaded instance, or a fresh one if loading failed
     */
    public static <T extends SavedData> T get(ServerLevel level, SavedDataType<T> type,
            Supplier<T> fallback, String name) {
        try {
            T instance = level.getDataStorage().computeIfAbsent(type);
            if (instance != null) {
                return instance;
            }
        } catch (Exception e) {
            return recover(level, type, fallback, name, e);
        }
        // computeIfAbsent's generic return is unannotated; a null here means the same thing as a
        // read failure, so recover the same way.
        return recover(level, type, fallback, name, null);
    }

    private static <T extends SavedData> T recover(ServerLevel level, SavedDataType<T> type,
            Supplier<T> fallback, String name, Exception failure) {
        T fresh = fallback.get();
        if (fresh == null) {
            throw new IllegalStateException("SavedData fallback supplier returned null for " + name);
        }
        try {
            level.getDataStorage().set(type, fresh);
            fresh.setDirty();
        } catch (Exception inner) {
            if (failure != null) {
                failure.addSuppressed(inner);
            }
        }
        NeroColoniesCommon.LOGGER.warn(
                "[NeroColonies] Could not read saved data '{}' in {} (missing, corrupt or unreadable); "
                        + "starting with fresh data. A clean file is written at the next save.",
                name, level.dimension(), failure);
        if (failure != null) {
            NeroColoniesTelemetry.captureHandledException(failure, "saved_data_recovery", name);
        }
        return fresh;
    }
}
