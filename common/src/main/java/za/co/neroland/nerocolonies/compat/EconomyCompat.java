package za.co.neroland.nerocolonies.compat;

import java.util.OptionalLong;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

/**
 * Everything NeroColonies is allowed to know about NeroEconomy's budgeted sibling API, expressed in
 * NeroColonies' own types.
 *
 * <p>NeroEconomy is a <b>soft dependency</b>: it is compiled against {@code compileOnly}, never
 * required by any manifest, and exactly one class in this mod names a NeroEconomy type
 * ({@code compat.neroeconomy.NeroEconomyBridge}). That class is loaded only after
 * {@code isModLoaded("neroeconomy")} said yes, during common init.
 *
 * <p>{@link #NONE} is used when NeroEconomy is absent or too old to have the sibling API
 * ({@code API_VERSION < 1}): {@link #active()} is false, no market price is known, and every payout
 * is refused, so callers keep the export table and Core's {@code CurrencyApi} path.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>The only player id that passes through here is the colony owner's, which the caller already
 * holds. Request keys are built from the colony id and a counter, never from a player id or name, and
 * nothing here logs a player id next to an amount.
 */
public interface EconomyCompat {

    /** Reason code for selling the export buffer. */
    String REASON_EXPORT_SALE = "export_sale";

    /** Reason code for the optional one-off colony stage reward. */
    String REASON_STAGE_REWARD = "stage_reward";

    /**
     * What a payout did.
     *
     * @param ok             the payout stands as applied (applied, or a duplicate of one that was)
     * @param invalid        refused as an out-of-range argument (for example above the single-payout cap)
     * @param translationKey a lang key explaining a refusal, or {@code null} when {@code ok}
     */
    record Payout(boolean ok, boolean invalid, @Nullable String translationKey) {
    }

    /** Whether this implementation is actually talking to NeroEconomy's budgeted API. */
    boolean active();

    /**
     * What a Merchant House pays for one unmodified unit of {@code itemId} right now; empty when the
     * market does not buy it (or NeroEconomy is absent).
     */
    OptionalLong bid(MinecraftServer server, String itemId);

    /** A budgeted Reserve-to-player payout under one of the claimed reason codes. */
    Payout pay(MinecraftServer server, UUID player, long amount, String reason, @Nullable String requestKey);

    /** The no-NeroEconomy implementation: no market price, every payout refused. */
    EconomyCompat NONE = new EconomyCompat() {

        @Override
        public boolean active() {
            return false;
        }

        @Override
        public OptionalLong bid(MinecraftServer server, String itemId) {
            return OptionalLong.empty();
        }

        @Override
        public Payout pay(MinecraftServer server, UUID player, long amount, String reason,
                @Nullable String requestKey) {
            return new Payout(false, false, "message.nerocolonies.export.no_market");
        }
    };
}
