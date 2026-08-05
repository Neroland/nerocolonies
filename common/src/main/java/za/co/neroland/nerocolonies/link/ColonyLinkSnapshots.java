package za.co.neroland.nerocolonies.link;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ExportBuffer;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.colony.Morale;
import za.co.neroland.nerocolonies.colony.Research;
import za.co.neroland.nerocolonies.colony.ResearchEffects;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ExportEntry;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ResearchNode;

/**
 * The read half of the link module: the state of the colonies the requesting player has a stake in.
 *
 * <h2>Sections</h2>
 *
 * <ul>
 *   <li>{@code colonies} — one row per colony this player owns or belongs to: name, place, morale,
 *       population, food, life support, and how many members it has;</li>
 *   <li>{@code colonists} — the population and staffing <em>counts</em> per colony;</li>
 *   <li>{@code jobs} — job slots, and what each station is doing, by index;</li>
 *   <li>{@code research} — what each colony has unlocked, could unlock and can pay for, plus the
 *       loaded node catalogue;</li>
 *   <li>{@code exports} — buffer fill, its worth, and each colony's unlocked manifest.</li>
 * </ul>
 *
 * <p>Any other section name yields an empty object, as Core's contract prescribes. Every section
 * accepts an optional {@code colony} parameter to narrow to one of them.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>{@link ColonyLinkAccess#coloniesOf} is the whole visibility rule and it runs first in every
 * section: a request sees its own UUID's colonies and nothing else. Within those, membership is
 * reported as a <b>count</b> — no UUID, no name, no roster — because a client told who is on a
 * colony's access list has been told where those people play.
 *
 * <p>Positions: a colony's own beacon is reported (it is the requesting player's own base, and it is
 * what lets an app tell two colonies apart on a map). Job stations, housing and generators are
 * reported as counts and stable indexes, never as coordinates.
 *
 * <p><b>Read-only and bounded.</b> Nothing here mutates anything, no section loads a chunk, and the
 * per-colony work is proportional to that colony's own station count. The bridge governs how often it
 * is called and caches the result.
 *
 * <p>Server thread only.
 */
public final class ColonyLinkSnapshots implements LinkSnapshotProvider {

    private static final List<String> SECTIONS = List.of(
            ColonyLinkModule.SECTION_COLONIES,
            ColonyLinkModule.SECTION_COLONISTS,
            ColonyLinkModule.SECTION_JOBS,
            ColonyLinkModule.SECTION_RESEARCH,
            ColonyLinkModule.SECTION_EXPORTS);

    @Override
    public String moduleId() {
        return ColonyLinkModule.MODULE_ID;
    }

    @Override
    public int schemaVersion() {
        return ColonyLinkModule.SCHEMA_VERSION;
    }

    @Override
    public List<String> sections() {
        return SECTIONS;
    }

    @Override
    public JsonObject snapshot(UUID playerId, String section, Map<String, String> params) {
        if (playerId == null || section == null) {
            return new JsonObject();
        }
        MinecraftServer server = ColonyLinkAccess.server();
        if (server == null || !NeroColoniesConfig.LINK_MODULE_ENABLED.get()) {
            return new JsonObject();
        }
        try {
            List<Colony> colonies = ColonyLinkAccess.requested(server, playerId, params);
            return switch (section) {
                case ColonyLinkModule.SECTION_COLONIES -> colonies(server, playerId, colonies);
                case ColonyLinkModule.SECTION_COLONISTS -> colonists(server, playerId, colonies);
                case ColonyLinkModule.SECTION_JOBS -> jobs(server, playerId, colonies);
                case ColonyLinkModule.SECTION_RESEARCH -> research(server, playerId, colonies);
                case ColonyLinkModule.SECTION_EXPORTS -> exports(server, playerId, colonies);
                // Unknown section: nothing to say.
                default -> new JsonObject();
            };
        } catch (RuntimeException e) {
            // Section name only — never who asked (POPIA/GDPR). A failed snapshot must not propagate
            // into the bridge.
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] NeroLink snapshot section '{}' failed; returning nothing for it.",
                    section, e);
            return new JsonObject();
        }
    }

    // --- section: colonies ----------------------------------------------------

    private static JsonObject colonies(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            JsonObject row = identity(colony, playerId);
            BlockPos beacon = colony.beaconPos();
            JsonObject at = new JsonObject();
            at.addProperty("x", beacon.getX());
            at.addProperty("y", beacon.getY());
            at.addProperty("z", beacon.getZ());
            row.add("beacon", at);
            row.addProperty("claim_radius", colony.claimRadius());
            row.addProperty("morale", Math.round(colony.morale()));
            row.addProperty("work_stopped", Morale.workStopped(colony));
            row.addProperty("output_multiplier", Morale.outputMultiplier(colony));
            row.addProperty("population", colony.population());
            row.addProperty("housing_capacity", colony.housingCapacity());
            row.addProperty("food_stock", colony.foodStock());
            row.addProperty("starving", FoodSupply.starving(colony));
            row.addProperty("life_support", LifeSupport.stateOf(colony).name());
            row.addProperty("life_support_ok", colony.lifeSupportOk());
            row.addProperty("oxygen_generators", LifeSupport.generatorCount(colony.colonyId()));
            row.addProperty("research_unlocked", colony.researchUnlocked().size());
            row.addProperty("outposts", colony.outpostIds().size());
            // A count, never a roster (POPIA/GDPR).
            row.addProperty("members", colony.accessList().size());
            row.addProperty("has_owner", colony.hasOwner());
            row.addProperty("created_at", colony.createdAt());
            rows.add(row);
        }
        JsonObject root = envelope(server, playerId);
        root.add("colonies", rows);
        return root;
    }

    // --- section: colonists ---------------------------------------------------

    /**
     * Counts, not entities. A colonist has no name, no identity and nothing player-shaped about it,
     * and the numbers here are read from the colony record and the job board rather than by walking
     * an entity index — so an unloaded colony reports its last known roster instead of zero.
     */
    private static JsonObject colonists(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        int cap = NeroColoniesConfig.COLONISTS_PER_COLONY.get();
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            int assigned = 0;
            for (JobBoard.Station station : JobBoard.stationsOf(colony.colonyId())) {
                assigned += station.assigned();
            }
            JsonObject row = identity(colony, playerId);
            row.addProperty("population", colony.population());
            row.addProperty("housing_capacity", colony.housingCapacity());
            row.addProperty("population_cap", cap);
            row.addProperty("assigned", assigned);
            row.addProperty("idle", Math.max(0, colony.population() - assigned));
            row.addProperty("work_stopped", Morale.workStopped(colony));
            rows.add(row);
        }
        JsonObject root = envelope(server, playerId);
        root.add("colonies", rows);
        return root;
    }

    // --- section: jobs --------------------------------------------------------

    /**
     * Each colony's job slots and its stations, by <b>index</b> into the board's own stable order —
     * the same handle {@code toggle_export} does not need, and the reason no coordinates appear.
     */
    private static JsonObject jobs(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            ServerLevel level = server.getLevel(colony.dimension());
            List<JobBoard.Station> stations = JobBoard.stationsOf(colony.colonyId());
            JsonArray list = new JsonArray();
            int index = 0;
            for (JobBoard.Station station : stations) {
                JsonObject entry = new JsonObject();
                entry.addProperty("index", index++);
                Identifier job = station.jobId();
                entry.addProperty("job", job == null ? "" : job.toString());
                entry.addProperty("name", job == null ? "" : ColonyLinkAccess.readablePath(job.getPath()));
                entry.addProperty("active", station.active());
                entry.addProperty("blocked", station.blocked());
                entry.addProperty("assigned", station.assigned());
                entry.addProperty("required", station.required());
                entry.addProperty("progress", station.progressFraction());
                entry.addProperty("outpost", station.isOutpost());
                Boolean routed = exportRouting(level, station);
                if (routed != null) {
                    entry.addProperty("export_routed", routed);
                }
                list.add(entry);
            }
            JsonObject row = identity(colony, playerId);
            row.addProperty("job_slots", ResearchEffects.jobSlots(colony));
            row.addProperty("job_slots_used", JobBoard.activeCount(colony.colonyId()));
            row.addProperty("work_stopped", Morale.workStopped(colony));
            row.add("stations", list);
            rows.add(row);
        }
        JsonObject root = envelope(server, playerId);
        root.add("colonies", rows);
        return root;
    }

    /**
     * Whether a station is routing its output to the export buffer, or {@code null} when the block is
     * not loaded. Reading it needs the block entity — the routing switch is the block's, not the
     * board's — and an unloaded chunk is never loaded to answer a snapshot.
     */
    private static Boolean exportRouting(ServerLevel level, JobBoard.Station station) {
        if (level == null) {
            return null;
        }
        BlockPos pos = BlockPos.of(station.packedPos());
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof JobStationBlockEntity block
                ? block.exportOutput()
                : null;
    }

    // --- section: research ----------------------------------------------------

    private static JsonObject research(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        Map<Identifier, ResearchNode> nodes = ColonyDefinitions.researchForServer(server);

        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            JsonArray unlocked = new JsonArray();
            JsonArray available = new JsonArray();
            for (ResearchNode node : nodes.values()) {
                if (Research.isUnlocked(colony, node.id())) {
                    unlocked.add(node.id().toString());
                } else if (Research.isAvailable(colony, node)) {
                    available.add(node.id().toString());
                }
            }
            JsonArray affordable = new JsonArray();
            for (Identifier id : Research.affordable(server, colony)) {
                affordable.add(id.toString());
            }
            JsonObject row = identity(colony, playerId);
            row.addProperty("job_slots", ResearchEffects.jobSlots(colony));
            row.add("unlocked", unlocked);
            row.add("available", available);
            row.add("affordable", affordable);
            rows.add(row);
        }

        // The catalogue is world content, identical for every player, and is what lets an app draw a
        // tree rather than a list of opaque ids.
        JsonArray catalogue = new JsonArray();
        for (ResearchNode node : nodes.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", node.id().toString());
            entry.addProperty("branch", node.branch());
            entry.addProperty("name", ColonyLinkAccess.readablePath(node.id().getPath()));
            entry.addProperty("translation_key", node.titleKey());
            JsonArray requires = new JsonArray();
            for (Identifier id : node.requires()) {
                requires.add(id.toString());
            }
            entry.add("requires", requires);
            entry.add("cost", amounts(node.cost()));
            catalogue.add(entry);
        }

        JsonObject root = envelope(server, playerId);
        root.add("colonies", rows);
        root.add("nodes", catalogue);
        return root;
    }

    private static JsonArray amounts(List<ItemAmount> cost) {
        JsonArray out = new JsonArray();
        for (ItemAmount amount : cost) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", amount.item().toString());
            entry.addProperty("count", amount.count());
            out.add(entry);
        }
        return out;
    }

    // --- section: exports -----------------------------------------------------

    private static JsonObject exports(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        Map<Identifier, ExportEntry> entries = ColonyDefinitions.exportsForServer(server);
        int slots = ExportBuffer.usableSlots();

        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            JsonArray manifest = new JsonArray();
            for (ExportEntry entry : entries.values()) {
                JsonObject line = new JsonObject();
                line.addProperty("id", entry.id().toString());
                line.addProperty("name", ColonyLinkAccess.readablePath(entry.id().getPath()));
                line.addProperty("target", entry.target().label());
                line.addProperty("base_value", entry.baseValue());
                line.addProperty("unlocked", ResearchEffects.exportUnlocked(colony, entry.id()));
                manifest.add(line);
            }
            JsonObject row = identity(colony, playerId);
            row.addProperty("buffer_filled", ExportBuffer.filledSlots(server, colony.colonyId()));
            row.addProperty("buffer_slots", slots);
            row.addProperty("buffer_full", ExportBuffer.isFull(server, colony.colonyId()));
            row.addProperty("value", ExportBuffer.previewValue(server, colony));
            row.addProperty("sellable", colony.hasOwner() && CurrencyApi.hasRealProvider());
            row.add("manifest", manifest);
            rows.add(row);
        }

        JsonObject root = envelope(server, playerId);
        root.addProperty("market_available", CurrencyApi.hasRealProvider());
        root.add("colonies", rows);
        return root;
    }

    // --- helpers --------------------------------------------------------------

    /** The three fields every colony row starts with: which colony, what it is called, whose it is. */
    private static JsonObject identity(Colony colony, UUID playerId) {
        JsonObject row = new JsonObject();
        row.addProperty("id", colony.colonyId().toString());
        row.addProperty("name", colony.name());
        row.addProperty("dimension", colony.dimension().identifier().toString());
        // A boolean about the REQUESTER, which is the only membership question this mod answers.
        row.addProperty("is_owner", colony.isOwner(playerId));
        return row;
    }

    private static JsonObject envelope(MinecraftServer server, UUID playerId) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
        root.addProperty("player_online", ColonyLinkAccess.isOnline(server, playerId));
        return root;
    }
}
