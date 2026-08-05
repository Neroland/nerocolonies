package za.co.neroland.nerocolonies.data;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * Registers NeroColonies' player-keyed storage with Neroland Core's shared {@link PlayerDataErasure}
 * hook, so one erase request ({@code /neroland data eraseme} or an admin erase) purges a player's
 * NeroColonies data together with every other Nero mod's. Called once from
 * {@link NeroColoniesCommon#init()}.
 *
 * <p>The hook is registered <b>early</b>, ahead of the store it purges and before any colony can
 * exist, on purpose: registering late is the classic way an erasure request silently misses a store.
 *
 * <h2>What erasure actually does</h2>
 *
 * <p>One store is reachable from here — {@link ColonyState} — and a player can appear in it three
 * ways. All three are dealt with:
 *
 * <ol>
 *   <li><b>Access lists.</b> The UUID is stripped from every colony that carries it.</li>
 *   <li><b>Access-log rows.</b> Every row filed against the UUID is deleted, in every colony.</li>
 *   <li><b>Owned colonies.</b> Handled per {@code erasureOwnedColonyPolicy}. The default,
 *       {@code transfer_to_server}, hands the colony to the server: it keeps running, ownerless,
 *       and operators can administer or reassign it. {@code dissolve} deletes the record
 *       outright.</li>
 * </ol>
 *
 * <p><b>Why transfer is the default.</b> A colony is frequently shared. Deleting a settlement that
 * three other players live in because one of them exercised a data-protection right would turn an
 * erasure request into a griefing tool — and it is not required by either POPIA or the GDPR, which
 * ask that the <em>personal data</em> be erased, not that the world be rearranged. Removing the
 * owner UUID removes the personal data; the colony that remains identifies nobody.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing on this path logs the player's identity — the summary logs
 * counts only.
 */
public final class NeroColoniesData {

    private NeroColoniesData() {
    }

    /** Registers the eraser with Core. Idempotent per launch; called once from common init. */
    public static void init() {
        PlayerDataErasure.register(NeroColoniesData::erasePlayer);
    }

    /** Core's {@code PlayerDataEraser} body: purge one player from the colony store. */
    public static void erasePlayer(MinecraftServer server, UUID player) {
        if (server == null || player == null) {
            return;
        }
        int[] counts = ColonyState.get(server).forgetPlayer(player);
        int owned = counts[0];
        int access = counts[1];
        int rows = counts[2];
        if (owned == 0 && access == 0 && rows == 0) {
            return;
        }
        // Counts only — never who was erased, and never which colonies (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Erasure: {} owned colony record(s) {}, {} access-list membership(s) "
                        + "removed, {} access-log row(s) deleted.",
                owned,
                NeroColoniesConfig.erasureDissolves() ? "dissolved" : "transferred to the server",
                access, rows);
    }
}
