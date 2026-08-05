package za.co.neroland.nerocolonies.link;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.nerolandcore.link.LinkAlerts;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.network.ColonySync;

/**
 * The write half of the link module, and a deliberately small one: two actions,
 * {@code toggle_export} and {@code acknowledge_alert}.
 *
 * <h2>Why these two and no others</h2>
 *
 * <p>Everything else a companion client might want to do to a colony — founding one, dissolving one,
 * researching a node, spending its stock, selling its goods, changing who may use it — either moves
 * items, spends resources or changes who has access to a place. Doing any of those from a phone would
 * let a player alter the world, and other people's standing in it, without being in it. Flipping
 * where a job's output goes changes no quantity of anything and is reversible with the same tap,
 * which is what makes it the one safe write; acknowledging your own alert touches nothing but your
 * own notification list.
 *
 * <p><b>{@code set_job_priority} is deliberately absent.</b> The 0.1.0 job board has no priority
 * model at all — slots are allocated first-fit in a stable position order — so an action by that name
 * would either do nothing or invent a mechanic through the back door. It belongs with the job board's
 * next revision, not here.
 *
 * <h2>Validation</h2>
 *
 * <p>Server-authoritative, and the incoming {@link UUID} is trusted for <em>nothing beyond scoping
 * the request to that player's own colonies</em>:
 *
 * <ol>
 *   <li>the {@code colony} parameter must name a colony this player owns or belongs to, or the call
 *       is refused with {@link LinkActionResult.Error#NOT_OWNER} — which is also the answer for a
 *       colony that belongs to somebody else, so the action cannot be used to probe for other
 *       players' bases;</li>
 *   <li>{@code toggle_export} additionally requires the player to be <b>online</b>
 *       ({@link LinkActionResult.Error#PLAYER_OFFLINE_REQUIRED}), because the permission this mod
 *       defines — {@link ColonyClaims#canAccess} — is asked of a live player and includes the
 *       operator override. Re-deriving it from a bare UUID would create a second permission path,
 *       and two permission paths are one too many;</li>
 *   <li>the named job must be one this colony actually has a station for, and the station's chunk
 *       must be loaded — a routing switch lives on the block, and no chunk is loaded to reach one
 *       ({@link LinkActionResult.Error#VALIDATION}).</li>
 * </ol>
 *
 * <p><b>Privacy (POPIA/GDPR).</b> No coordinates are read from or written to any store, and no result
 * names any player. A result carries a colony id, a job id and counts.
 *
 * <p>Server thread only.
 */
public final class ColonyLinkActions implements LinkActionHandler {

    private static final List<String> ACTIONS = List.of(
            ColonyLinkModule.ACTION_TOGGLE_EXPORT,
            ColonyLinkModule.ACTION_ACKNOWLEDGE_ALERT);

    @Override
    public String moduleId() {
        return ColonyLinkModule.MODULE_ID;
    }

    @Override
    public List<String> actionIds() {
        return ACTIONS;
    }

    /**
     * Honestly, per action. Acknowledging your own alert is a notification-list operation and works
     * perfectly well while you are away — that is rather the point of an alert. Flipping a job
     * station's routing is a change to the world and is refused while you are not in it; see the
     * class notes for why that is a permission argument rather than a taste one.
     */
    @Override
    public boolean allowOffline(String actionId) {
        return ColonyLinkModule.ACTION_ACKNOWLEDGE_ALERT.equals(actionId);
    }

    @Override
    public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        if (playerId == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "No player was supplied.");
        }
        if (!NeroColoniesConfig.LINK_MODULE_ENABLED.get()) {
            return LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED,
                    "The NeroColonies link module is disabled on this server.");
        }
        MinecraftServer server = ColonyLinkAccess.server();
        if (server == null) {
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL,
                    "The server is not running a world yet.");
        }
        try {
            if (ColonyLinkModule.ACTION_TOGGLE_EXPORT.equals(actionId)) {
                return toggleExport(server, playerId, params);
            }
            if (ColonyLinkModule.ACTION_ACKNOWLEDGE_ALERT.equals(actionId)) {
                return acknowledgeAlert(server, playerId, params);
            }
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "NeroColonies does not know the action '" + actionId + "'.");
        } catch (RuntimeException e) {
            // Action id only — never who asked (POPIA/GDPR).
            NeroColoniesCommon.LOGGER.warn("[NeroColonies] NeroLink action '{}' failed.", actionId, e);
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL,
                    "The action could not be processed.");
        }
    }

    // --- toggle_export ---------------------------------------------------------

    /**
     * Routes every loaded station running one job to the export buffer, or back to colony storage.
     *
     * <p>The action names a <b>job</b>, not a station, and that is the privacy-shaped choice as much
     * as the convenient one: naming a station would mean sending a companion client a set of block
     * positions to choose from. A colony rarely has two stations on the same job, and when it does,
     * "route my refining output to trade" is what the player meant for both.
     *
     * <p>{@code export} may be supplied to set the flag explicitly; omitted, it flips whatever the
     * first matching station currently has, so a repeated tap toggles rather than fighting itself.
     */
    private static LinkActionResult toggleExport(MinecraftServer server, UUID playerId,
            JsonObject params) {
        ServerPlayer player = ColonyLinkAccess.online(server, playerId);
        if (player == null) {
            return LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED,
                    "Export routing can only be changed while you are online.");
        }
        Colony colony = ColonyLinkAccess.colonyParam(server, playerId, params);
        if (colony == null) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "You do not have access to a colony with that id.");
        }
        if (!ColonyClaims.canAccess(player, colony)) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "You do not have access to that colony.");
        }
        String rawJob = ColonyLinkAccess.string(params, "job");
        Identifier job = rawJob == null ? null : Identifier.tryParse(rawJob);
        if (job == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "The 'job' parameter must be a job id, for example 'nerocolonies:refine'.");
        }
        ServerLevel level = server.getLevel(colony.dimension());
        if (level == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "That colony's dimension is not loaded.");
        }

        Boolean requested = ColonyLinkAccess.bool(params, "export");
        Boolean applied = null;
        int changed = 0;
        int matched = 0;
        int unreachable = 0;

        for (JobBoard.Station station : JobBoard.stationsOf(colony.colonyId())) {
            if (!job.equals(station.jobId())) {
                continue;
            }
            matched++;
            BlockPos pos = BlockPos.of(station.packedPos());
            if (!level.isLoaded(pos)
                    || !(level.getBlockEntity(pos) instanceof JobStationBlockEntity block)) {
                unreachable++;
                continue;
            }
            if (applied == null) {
                applied = requested != null ? requested : !block.exportOutput();
            }
            block.setExportOutput(applied);
            changed++;
        }

        if (matched == 0) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "That colony has no station running that job.");
        }
        if (applied == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "That colony's stations are not loaded right now.");
        }
        ColonySync.refresh(server, colony.colonyId());

        JsonObject result = new JsonObject();
        result.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
        result.addProperty("colony", colony.colonyId().toString());
        result.addProperty("job", job.toString());
        result.addProperty("export_routed", applied);
        result.addProperty("stations_changed", changed);
        result.addProperty("stations_unreachable", unreachable);
        return LinkActionResult.ok(result);
    }

    // --- acknowledge_alert -----------------------------------------------------

    /**
     * Marks one of the caller's own NeroColonies alerts as read in Core's shared alert store. The
     * store is per-player by construction, so this can only ever reach the caller's own row.
     */
    private static LinkActionResult acknowledgeAlert(MinecraftServer server, UUID playerId,
            JsonObject params) {
        String alertId = ColonyLinkAccess.string(params, "alert");
        if (alertId == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "The 'alert' parameter must be an alert id.");
        }
        boolean acked = LinkAlerts.get(server).ack(server, playerId, alertId);
        if (!acked) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "You have no unacknowledged alert with that id.");
        }
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
        result.addProperty("alert", alertId);
        result.addProperty("acked", true);
        return LinkActionResult.ok(result);
    }
}
