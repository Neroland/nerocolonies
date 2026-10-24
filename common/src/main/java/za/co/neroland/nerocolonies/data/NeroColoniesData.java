package za.co.neroland.nerocolonies.data;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.data.SavedDataRecovery;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.ColonyConstruction;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyRoles;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStores;
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
 * <h2>Last-known-good backups</h2>
 *
 * <p>Every store goes through Neroland Core's {@link SavedDataRecovery}, which keeps a
 * last-known-good copy of each store beside the primary file. That copy holds the same player-keyed
 * rows, so once a player has been purged this hook calls {@link SavedDataRecovery#backupNow} for
 * every store it touched — the colony index and the Chief/Enemy lists always, and the per-colony
 * side stores when a colony was dissolved — so the erasure reaches the backup in the same request
 * rather than at the next periodic backup pass.
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
        ColonyState state = ColonyState.get(server);
        java.util.List<UUID> ownedBefore = new java.util.ArrayList<>();
        for (za.co.neroland.nerocolonies.colony.Colony colony : state.colonies()) {
            if (colony.isOwner(player)) {
                ownedBefore.add(colony.colonyId());
            }
        }
        int[] counts = state.forgetPlayer(player);
        // Under the dissolve policy the colony record is gone, so everything filed under its id goes
        // now too: its stores, build records, and its Chief and Enemy lists (other players' ids),
        // rather than waiting for the next retention sweep.
        for (UUID id : ownedBefore) {
            if (state.colony(id) == null) {
                za.co.neroland.nerocolonies.colony.ColonyStores.get(server).forget(id);
                za.co.neroland.nerocolonies.colony.Construction.forget(server, id);
            }
        }
        int owned = counts[0];
        int access = counts[1];
        int rows = counts[2];
        // Chief and Enemy lists too — an Enemy listing is removed like any other, because the law
        // outranks the rule that only retribution or a Chief can lift an enmity.
        int roles = za.co.neroland.nerocolonies.colony.ColonyRoles.get(server).forgetPlayer(player);
        if (owned == 0 && access == 0 && rows == 0 && roles == 0) {
            return;
        }
        refreshBackups(server, !ownedBefore.isEmpty() && NeroColoniesConfig.erasureDissolves());
        // Counts only — never who was erased, and never which colonies (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Erasure: {} owned colony record(s) {}, {} ally membership(s) removed, "
                        + "{} chief/enemy listing(s) removed, {} access-log row(s) deleted.",
                owned,
                NeroColoniesConfig.erasureDissolves() ? "dissolved" : "transferred to the server",
                access, roles, rows);
    }

    /**
     * Rewrites Core's last-known-good backup of every store the erasure touched, so the purged rows
     * do not survive in the backup file. {@code dissolved} adds the per-colony side stores that a
     * dissolved colony's records were dropped from.
     */
    private static void refreshBackups(MinecraftServer server, boolean dissolved) {
        ServerLevel overworld = server.overworld();
        SavedDataRecovery.backupNow(overworld, ColonyState.TYPE, ColonyState.get(server),
                ColonyState.NAME);
        SavedDataRecovery.backupNow(overworld, ColonyRoles.TYPE, ColonyRoles.get(server),
                ColonyRoles.NAME);
        if (dissolved) {
            SavedDataRecovery.backupNow(overworld, ColonyStores.TYPE, ColonyStores.get(server),
                    ColonyStores.NAME);
            SavedDataRecovery.backupNow(overworld, ColonyConstruction.TYPE,
                    ColonyConstruction.get(server), ColonyConstruction.NAME);
            SavedDataRecovery.backupNow(overworld, ColonyLife.TYPE, ColonyLife.get(server),
                    ColonyLife.NAME);
        }
    }
}
