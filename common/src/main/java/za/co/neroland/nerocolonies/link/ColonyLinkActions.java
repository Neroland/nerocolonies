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
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.network.ColonySync;

/**
 * The write half of the link module, and a deliberately small one: four actions,
 * {@code toggle_export}, {@code acknowledge_alert}, {@code prioritise_need} and
 * {@code toggle_cache_sharing}.
 *
 * <h2>Why these and no others</h2>
 *
 * <p>Everything else a companion client might want to do to a colony — founding one, dissolving one,
 * researching a node, spending its stock, selling its goods, changing who may use it — either moves
 * items, spends resources or changes who has access to a place. Doing any of those from a phone would
 * let a player alter the world, and other people's standing in it, without being in it. Flipping
 * where a job's output goes changes no quantity of anything and is reversible with the same tap,
 * which is what makes it a safe write; acknowledging your own alert touches nothing but your own
 * notification list.
 *
 * <p>The two schema 2 actions are settings of the same kind. {@code prioritise_need} tells the
 * colony's own gatherers which shortfall to work on first — it moves nothing and spends nothing, and
 * the same tap clears it. {@code toggle_cache_sharing} is the owner's own switch for whether allies
 * may open the Gratitude Cache; it adds nobody to the colony and removes nobody from it.
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
 *       ({@link LinkActionResult.Error#VALIDATION});</li>
 *   <li>{@code prioritise_need} and {@code toggle_cache_sharing} re-derive the caller's <b>role</b>
 *       on every call from the colony's own stored role lists
 *       ({@link ColonyPermissions#roleOf}) and ask the one rule table
 *       ({@link ColonyPermissions#allows}) whether that role may do it: owner or Chief for the first,
 *       owner only for the second. That rule is a function of a UUID and stored state — there is no
 *       operator elevation in it — so these two work while the player is offline.</li>
 * </ol>
 *
 * <p>The gallery's sandbox colony is invisible to {@link ColonyLinkAccess}, so every action answers
 * for it exactly as it does for a colony that does not exist.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> No coordinates are read from or written to any store, and no result
 * names any player. A result carries a colony id, a job id and counts.
 *
 * <p>Server thread only.
 */
public final class ColonyLinkActions implements LinkActionHandler {

    private static final List<String> ACTIONS = List.of(
            ColonyLinkModule.ACTION_TOGGLE_EXPORT,
            ColonyLinkModule.ACTION_ACKNOWLEDGE_ALERT,
            ColonyLinkModule.ACTION_PRIORITISE_NEED,
            ColonyLinkModule.ACTION_TOGGLE_CACHE_SHARING);

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
     * class notes for why that is a permission argument rather than a taste one. Prioritising a need
     * and sharing the cache are colony settings checked against stored roles, so they work offline.
     */
    @Override
    public boolean allowOffline(String actionId) {
        return ColonyLinkModule.ACTION_ACKNOWLEDGE_ALERT.equals(actionId)
                || ColonyLinkModule.ACTION_PRIORITISE_NEED.equals(actionId)
                || ColonyLinkModule.ACTION_TOGGLE_CACHE_SHARING.equals(actionId);
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
            if (ColonyLinkModule.ACTION_PRIORITISE_NEED.equals(actionId)) {
                return prioritiseNeed(server, playerId, params);
            }
            if (ColonyLinkModule.ACTION_TOGGLE_CACHE_SHARING.equals(actionId)) {
                return toggleCacheSharing(server, playerId, params);
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

    // --- prioritise_need -------------------------------------------------------

    /**
     * Sets, or clears, the one need a colony's gatherers work on first.
     *
     * <p>{@code item} is an item id as the {@code needs} section reports it; an empty string clears
     * the priority. A {@code #tag} need cannot be prioritised — the colony prioritises one item, and
     * a tag is not one — so it is refused as a validation error rather than quietly ignored.
     *
     * <p>Owner or Chief. The role is read from the colony's stored role lists on every call; nothing
     * a client says about its own rank is believed.
     */
    private static LinkActionResult prioritiseNeed(MinecraftServer server, UUID playerId,
            JsonObject params) {
        Colony colony = ColonyLinkAccess.colonyParam(server, playerId, params);
        if (colony == null) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "You do not have access to a colony with that id.");
        }
        ColonyPermissions.Role role = ColonyLinkAccess.roleOf(server, colony, playerId);
        if (!ColonyPermissions.allows(role, ColonyPermissions.Action.PLAN, false)) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "Only the colony's owner or a Chief can prioritise a need.");
        }
        if (params == null || !params.has("item")) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "The 'item' parameter must be an item id, or empty to clear the priority.");
        }
        String rawItem = ColonyLinkAccess.string(params, "item");
        Identifier item = null;
        if (rawItem != null && rawItem.startsWith("#")) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "A tag need cannot be prioritised; pick a need for a single item.");
        }
        if (rawItem != null) {
            item = Identifier.tryParse(rawItem);
            if (item == null) {
                return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                        "The 'item' parameter must be an item id, for example 'minecraft:oak_log'.");
            }
        }
        ColonyLife store = ColonyLife.get(server);
        store.life(colony.colonyId()).setPriorityNeed(item);
        store.touch();
        ColonySync.refresh(server, colony.colonyId());

        JsonObject result = new JsonObject();
        result.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
        result.addProperty("colony", colony.colonyId().toString());
        result.addProperty("item", item == null ? "" : item.toString());
        result.addProperty("prioritised", item != null);
        return LinkActionResult.ok(result);
    }

    // --- toggle_cache_sharing --------------------------------------------------

    /**
     * Shares the Gratitude Cache with the colony's allies, or stops sharing it.
     *
     * <p>{@code shared} may be supplied to set the switch explicitly; omitted, it flips, so a
     * repeated tap toggles. Owner only — the cache is the colony's thank-you to its owner, and who
     * else may open it is the owner's call alone.
     */
    private static LinkActionResult toggleCacheSharing(MinecraftServer server, UUID playerId,
            JsonObject params) {
        Colony colony = ColonyLinkAccess.colonyParam(server, playerId, params);
        if (colony == null) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "You do not have access to a colony with that id.");
        }
        ColonyPermissions.Role role = ColonyLinkAccess.roleOf(server, colony, playerId);
        if (!ColonyPermissions.allows(role, ColonyPermissions.Action.OWNER_SETTINGS, false)) {
            return LinkActionResult.error(LinkActionResult.Error.NOT_OWNER,
                    "Only the colony's owner can change who the Gratitude Cache is shared with.");
        }
        Boolean requested = ColonyLinkAccess.bool(params, "shared");
        if (requested == null && params != null && params.has("shared")) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "The 'shared' parameter must be true or false.");
        }
        ColonyLife store = ColonyLife.get(server);
        ColonyLife.Life life = store.life(colony.colonyId());
        boolean shared = requested != null ? requested : !life.cacheShared();
        life.setCacheShared(shared);
        store.touch();
        ColonySync.refresh(server, colony.colonyId());

        JsonObject result = new JsonObject();
        result.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
        result.addProperty("colony", colony.colonyId().toString());
        result.addProperty("shared_with_allies", shared);
        return LinkActionResult.ok(result);
    }
}
