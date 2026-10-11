package za.co.neroland.nerocolonies.compat.neroeconomy;

import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.neroeconomy.api.CommodityApi;
import za.co.neroland.neroeconomy.api.EconomyCaller;
import za.co.neroland.neroeconomy.api.EconomyResult;
import za.co.neroland.neroeconomy.api.SiblingEconomy;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.compat.EconomyCompat;

/**
 * The one class in NeroColonies that names a NeroEconomy type. It is reached only from
 * {@link za.co.neroland.nerocolonies.compat.CompatRegistry#init()}, after the platform reported
 * {@code neroeconomy} loaded, and it claims the {@code nerocolonies} caller exactly once, at common
 * init (NeroEconomy refuses a claim after the first server has started).
 *
 * <p>{@code SiblingEconomy.API_VERSION} is read reflectively: the constant is inlined by javac, so a
 * plain read would report the version this jar was <em>compiled</em> against, not the one installed.
 * Below 1 (or with no sibling API at all) the bridge stays dormant and NeroColonies keeps Core's
 * {@code CurrencyApi} path.
 *
 * <p>Privacy (POPIA/GDPR): log lines here carry result names and class names only, never a player
 * id, a name or an amount next to either.
 */
public final class NeroEconomyBridge implements EconomyCompat {

    private final EconomyCaller caller;

    private NeroEconomyBridge(EconomyCaller caller) {
        this.caller = caller;
    }

    /**
     * Reads the installed API version and claims the caller.
     *
     * @return the bridge, or {@code null} to stay on Core's {@code CurrencyApi} path
     */
    @Nullable
    public static EconomyCompat probe() {
        int apiVersion;
        try {
            apiVersion = SiblingEconomy.class.getField("API_VERSION").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] NeroEconomy has no sibling API; export sales use Core's CurrencyApi.");
            return null;
        }
        if (apiVersion < 1) {
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] NeroEconomy sibling API {} is older than 1; export sales use Core's "
                            + "CurrencyApi.", apiVersion);
            return null;
        }
        try {
            EconomyCaller caller = SiblingEconomy.claim(NeroColoniesCommon.MOD_ID,
                    Set.of(REASON_EXPORT_SALE, REASON_STAGE_REWARD));
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] Export sales are paid through NeroEconomy's budgeted API (version {}).",
                    apiVersion);
            return new NeroEconomyBridge(caller);
        } catch (RuntimeException | LinkageError e) {
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] Could not claim a NeroEconomy caller ({}); export sales use Core's "
                            + "CurrencyApi.", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public OptionalLong bid(MinecraftServer server, String itemId) {
        try {
            return CommodityApi.bid(server, itemId);
        } catch (RuntimeException | LinkageError e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public Payout pay(MinecraftServer server, UUID player, long amount, String reason,
            @Nullable String requestKey) {
        EconomyResult result;
        try {
            result = this.caller.pay(server, player, amount, reason, requestKey);
        } catch (RuntimeException | LinkageError e) {
            NeroColoniesCommon.LOGGER.warn("[NeroColonies] A NeroEconomy payout failed ({}); nothing was sold.",
                    e.getClass().getSimpleName());
            return new Payout(false, false, EconomyResult.UNAVAILABLE.translationKey());
        }
        if (result.ok()) {
            return new Payout(true, false, null);
        }
        // Result name only — never who was to be paid, nor how much (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A NeroEconomy payout ({}) was refused: {}.", reason,
                result);
        return new Payout(false, result == EconomyResult.INVALID, result.translationKey());
    }
}
