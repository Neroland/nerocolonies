package za.co.neroland.nerocolonies.link;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyBuildings;
import za.co.neroland.nerocolonies.colony.ColonyConstruction;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyNeeds;
import za.co.neroland.nerocolonies.colony.ColonyProgress;
import za.co.neroland.nerocolonies.colony.ColonyRoles;
import za.co.neroland.nerocolonies.colony.ColonyStage;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.colony.GratitudeCache;
import za.co.neroland.nerocolonies.colony.Growth;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.colony.Morale;
import za.co.neroland.nerocolonies.colony.Population;
import za.co.neroland.nerocolonies.colony.Professions;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The six sections schema version 2 added: {@code summary}, {@code needs}, {@code buildings},
 * {@code professions}, {@code roles} and {@code cache}. {@link ColonyLinkSnapshots} routes to them.
 *
 * <p>Every one of them answers {@code { schema_version, player_online, colonies: [rows] }}, and every
 * row starts with the same five fields: {@code id}, {@code name}, {@code dimension},
 * {@code is_owner} and {@code role} — the <em>requester's own</em> role in that colony
 * ({@code owner}, {@code chief} or {@code ally}).
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>The list of colonies handed in has already been through {@link ColonyLinkAccess#coloniesOf}, so
 * a row only ever exists for a colony the requester is a member of. Inside a row, people are
 * <b>counts</b>: how many allies, chiefs and enemies a colony has, how many Nerans hold a trade —
 * never a name, never a UUID. The one role that is spelled out is the requester's own. The Gratitude
 * Cache is reported as how full it is, never what is in it. Buildings are reported by blueprint,
 * never by position.
 *
 * <p><b>No chunk is loaded to answer a snapshot.</b> Anything that needs the Nerans themselves
 * (trade head-counts, children) is read only while the colony's beacon is loaded; an unloaded colony
 * keeps its row and reports those counts as zero.
 *
 * <p>Server thread only.
 */
final class ColonyLinkSections {

    private static final int TICKS_PER_MINUTE = 1_200;

    private ColonyLinkSections() {
    }

    // --- section: summary -------------------------------------------------------

    /**
     * One row per colony with the headline numbers, plus a {@code headline} and {@code sections}
     * rendering of the same facts for a client that draws generic label/value lists.
     */
    static JsonObject summary(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        JsonArray rows = new JsonArray();
        JsonArray sections = new JsonArray();
        int attention = 0;
        for (Colony colony : colonies) {
            ServerLevel level = ColonyLinkAccess.levelOf(server, colony);
            ColonyStage stage = ColonyProgress.stage(server, colony);
            List<ColonyNeeds.Need> needs = level == null ? List.of() : ColonyNeeds.derive(level, colony);
            ColonyNeeds.BuildEta eta = level == null
                    ? ColonyNeeds.BuildEta.NONE
                    : ColonyNeeds.buildEta(level, colony, needs);
            int helpNeeded = 0;
            for (ColonyNeeds.Need need : needs) {
                if (need.etaSoloMinutes() < 0) {
                    helpNeeded++;
                }
            }
            int children = 0;
            for (ColonistEntity neran : roster(level, colony)) {
                if (neran.isChildNeran()) {
                    children++;
                }
            }
            boolean starving = FoodSupply.starving(colony);
            boolean stopped = Morale.workStopped(colony);
            LifeSupport.State support = LifeSupport.stateOf(colony);
            long morale = Math.round(colony.morale());
            int[] milestone = Growth.nextMilestone(stage);

            JsonObject row = row(server, colony, playerId);
            row.addProperty("stage", stage.key());
            row.addProperty("stage_index", stage.ordinal());
            row.addProperty("tier", stage.ordinal() + 1);
            row.addProperty("population", colony.population());
            row.addProperty("children", children);
            row.addProperty("housing_capacity", colony.housingCapacity());
            row.addProperty("food_stock", colony.foodStock());
            row.addProperty("starving", starving);
            row.addProperty("life_support", support.name());
            row.addProperty("morale", morale);
            row.addProperty("work_stopped", stopped);
            row.addProperty("needs_count", needs.size());
            row.addProperty("needs_eta_solo_minutes", eta.soloMinutes());
            row.addProperty("needs_eta_help_minutes", eta.helpMinutes());
            if (milestone == null) {
                row.add("next_milestone", JsonNull.INSTANCE);
            } else {
                JsonObject next = new JsonObject();
                next.addProperty("population", milestone[0]);
                next.addProperty("structures", milestone[1]);
                row.add("next_milestone", next);
            }
            row.addProperty("structures_built", Construction.structuresBuilt(server, colony.colonyId()));
            rows.add(row);

            if (starving || stopped || support != LifeSupport.State.OK) {
                attention++;
            }

            // The same facts as label/value lines, one titled block per colony.
            JsonArray items = new JsonArray();
            String stageDetail = null;
            if (milestone != null) {
                stageDetail = "Next stage at " + milestone[0] + " population and " + milestone[1]
                        + " structures";
            } else if (stage == ColonyStage.FOUNDING) {
                stageDetail = "Building its Starter Works";
            }
            items.add(item("Stage", ColonyLinkAccess.readablePath(stage.key()), stageDetail, null, "info"));
            items.add(item("Population", colony.population() + " / " + colony.housingCapacity(),
                    children <= 0 ? null : children + (children == 1 ? " child" : " children"),
                    null, "info"));
            items.add(item("Food", String.valueOf(colony.foodStock()),
                    starving ? "Starving" : null, starving ? "starved" : null,
                    starving ? "critical" : "info"));
            items.add(item("Morale", String.valueOf(morale),
                    stopped ? "Work has stopped" : null, stopped ? "paused" : null,
                    stopped ? "warn" : "info"));
            if (support != LifeSupport.State.OK) {
                boolean failed = support == LifeSupport.State.FAILED;
                items.add(item("Life support", ColonyLinkAccess.readablePath(support.name()), null,
                        failed ? "failed" : null, failed ? "critical" : "warn"));
            }
            items.add(item("Needs", String.valueOf(needs.size()),
                    helpNeeded <= 0 ? null : helpNeeded + " cannot be met without help",
                    null, helpNeeded > 0 ? "warn" : "info"));
            JsonObject block = new JsonObject();
            block.addProperty("title", colony.name());
            block.add("items", items);
            sections.add(block);
        }

        JsonObject root = ColonyLinkSnapshots.envelope(server, playerId);
        root.addProperty("headline", headline(colonies.size(), attention));
        root.add("sections", sections);
        root.add("colonies", rows);
        return root;
    }

    /** {@code "2 colonies"}, with how many of them want attention appended when any do. */
    private static String headline(int colonies, int attention) {
        if (colonies <= 0) {
            return "No colonies";
        }
        String head = colonies + (colonies == 1 ? " colony" : " colonies");
        if (attention <= 0) {
            return head;
        }
        return head + " · " + attention + (attention == 1 ? " needs attention" : " need attention");
    }

    /** One label/value line. {@code detail} and {@code status} are left out when there is none. */
    private static JsonObject item(String label, String value, @Nullable String detail,
            @Nullable String status, String severity) {
        JsonObject item = new JsonObject();
        item.addProperty("label", label);
        item.addProperty("value", value);
        if (detail != null) {
            item.addProperty("detail", detail);
        }
        if (status != null) {
            item.addProperty("status", status);
        }
        item.addProperty("severity", severity);
        return item;
    }

    // --- section: needs ---------------------------------------------------------

    /**
     * What each colony is short of. {@code eta_solo_minutes} is {@code null} when nothing in the
     * colony gathers that item — it will not arrive without help. {@code eta_help_minutes} is always
     * zero: a need is met the moment the item is delivered.
     */
    static JsonObject needs(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            ServerLevel level = ColonyLinkAccess.levelOf(server, colony);
            JsonArray list = new JsonArray();
            if (level != null) {
                for (ColonyNeeds.Need need : ColonyNeeds.derive(level, colony)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("item", need.label());
                    entry.addProperty("name_key", need.nameKey());
                    entry.addProperty("needed", need.needed());
                    entry.addProperty("have", need.have());
                    entry.addProperty("reason", need.reason().key());
                    if (need.etaSoloMinutes() < 0) {
                        entry.add("eta_solo_minutes", JsonNull.INSTANCE);
                    } else {
                        entry.addProperty("eta_solo_minutes", need.etaSoloMinutes());
                    }
                    entry.addProperty("eta_help_minutes", 0);
                    entry.addProperty("priority", need.priority());
                    list.add(entry);
                }
            }
            JsonObject row = row(server, colony, playerId);
            row.add("needs", list);
            rows.add(row);
        }
        return wrap(server, playerId, rows);
    }

    // --- section: buildings -----------------------------------------------------

    /** Finished, under way and planned buildings — by blueprint, never by position. */
    static JsonObject buildings(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        Map<Identifier, Blueprint> blueprints = ColonyDefinitions.blueprintsForServer(server);
        ColonyConstruction index = ColonyConstruction.get(server);
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            ColonyConstruction.Plan plan = index.peek(colony.colonyId());
            JsonArray completed = new JsonArray();
            JsonArray planned = new JsonArray();
            JsonObject progress = null;
            if (plan != null) {
                for (ColonyConstruction.Structure structure : plan.structures()) {
                    Blueprint blueprint = blueprints.get(structure.blueprint());
                    JsonObject entry = new JsonObject();
                    entry.addProperty("blueprint", structure.blueprint().toString());
                    entry.addProperty("name_key", nameKey(structure.blueprint(), blueprint));
                    entry.addProperty("category", blueprint == null
                            ? Blueprint.Category.OTHER.serialised()
                            : blueprint.category().serialised());
                    entry.addProperty("level", structure.level());
                    entry.addProperty("stage", blueprint == null ? "" : blueprint.stage().key());
                    completed.add(entry);
                }
                Identifier active = plan.active();
                if (active != null) {
                    progress = new JsonObject();
                    progress.addProperty("blueprint", active.toString());
                    progress.addProperty("name_key", nameKey(active,
                            Construction.activeBlueprint(server, colony.colonyId())));
                    progress.addProperty("percent", plan.progressPercent());
                    progress.addProperty("supplied", plan.supplied());
                    progress.addProperty("upgrade", plan.isUpgrade());
                }
                for (ColonyConstruction.Planned waiting : plan.queue()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("blueprint", waiting.blueprint().toString());
                    entry.addProperty("name_key",
                            nameKey(waiting.blueprint(), blueprints.get(waiting.blueprint())));
                    planned.add(entry);
                }
            }
            JsonObject row = row(server, colony, playerId);
            row.add("completed", completed);
            row.add("in_progress", progress == null ? JsonNull.INSTANCE : progress);
            row.add("planned", planned);
            rows.add(row);
        }
        return wrap(server, playerId, rows);
    }

    /** A blueprint's translation key, or the conventional one for an id no longer in the content set. */
    private static String nameKey(Identifier id, @Nullable Blueprint blueprint) {
        return blueprint != null
                ? blueprint.nameKey()
                : "blueprint." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    // --- section: professions ---------------------------------------------------

    /**
     * Head-count against places, per trade. Counts, never Nerans: no entity id, no Neran name. Trades
     * the colony has neither a place nor a worker for are left out.
     */
    static JsonObject professions(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        Map<Identifier, ProfessionDefinition> trades = ColonyDefinitions.professionsForServer(server);
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            ServerLevel level = ColonyLinkAccess.levelOf(server, colony);
            Map<Identifier, Integer> places = level != null && level.isLoaded(colony.beaconPos())
                    ? Professions.places(level, colony)
                    : placesFromPlan(server, colony, trades);
            Map<Identifier, Integer> counts = new HashMap<>();
            int children = 0;
            int unassigned = 0;
            for (ColonistEntity neran : roster(level, colony)) {
                if (neran.isChildNeran()) {
                    children++;
                    continue;
                }
                Identifier trade = neran.professionId();
                if (trade == null || !trades.containsKey(trade)) {
                    unassigned++;
                } else {
                    counts.merge(trade, 1, Integer::sum);
                }
            }
            JsonArray list = new JsonArray();
            for (ProfessionDefinition trade : trades.values()) {
                int open = places.getOrDefault(trade.id(), 0);
                int count = counts.getOrDefault(trade.id(), 0);
                if (open <= 0 && count <= 0) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("id", trade.id().toString());
                entry.addProperty("name_key", trade.nameKey());
                entry.addProperty("count", count);
                entry.addProperty("places", open);
                list.add(entry);
            }
            JsonObject row = row(server, colony, playerId);
            row.add("professions", list);
            row.addProperty("children", children);
            row.addProperty("unassigned", unassigned);
            rows.add(row);
        }
        return wrap(server, playerId, rows);
    }

    /**
     * Places per trade for a colony that is not loaded, worked out from its building record alone so
     * that the world is not touched: each finished building opens {@code per_building} places for
     * every trade it unlocks, capped per colony.
     */
    private static Map<Identifier, Integer> placesFromPlan(MinecraftServer server, Colony colony,
            Map<Identifier, ProfessionDefinition> trades) {
        Map<Identifier, Integer> places = new HashMap<>();
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        if (plan == null) {
            return places;
        }
        Map<Identifier, Blueprint> blueprints = ColonyDefinitions.blueprintsForServer(server);
        for (ColonyConstruction.Structure structure : plan.structures()) {
            Blueprint blueprint = blueprints.get(structure.blueprint());
            if (blueprint == null) {
                continue;
            }
            for (Identifier unlock : blueprint.unlocks()) {
                ProfessionDefinition trade = trades.get(unlock);
                if (trade != null) {
                    places.merge(unlock, trade.perBuilding(), Integer::sum);
                }
            }
        }
        places.replaceAll((id, count) -> {
            ProfessionDefinition trade = trades.get(id);
            return trade == null ? count : Math.min(count, trade.maxPerColony());
        });
        return places;
    }

    // --- section: roles ---------------------------------------------------------

    /**
     * The requester's own role, and how many people hold each of the others. Counts only — there is
     * no roster field and there will not be one.
     */
    static JsonObject roles(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        ColonyRoles roles = ColonyRoles.get(server);
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            int[] counts = roles.counts(colony.colonyId());
            JsonObject row = row(server, colony, playerId);
            row.addProperty("your_role", ColonyLinkAccess.roleOf(server, colony, playerId).key());
            // Chiefs are on the access list too, so allies are whoever is left.
            row.addProperty("allies", Math.max(0, colony.accessList().size() - counts[0]));
            row.addProperty("chiefs", counts[0]);
            row.addProperty("enemies", counts[1]);
            rows.add(row);
        }
        return wrap(server, playerId, rows);
    }

    // --- section: cache ---------------------------------------------------------

    /**
     * How full each colony's Gratitude Cache is. Every member sees the row, whether or not they may
     * open the cache: it says how many slots hold something and never what is in them.
     */
    static JsonObject cache(MinecraftServer server, UUID playerId, List<Colony> colonies) {
        ColonyLife lives = ColonyLife.get(server);
        long now = server.overworld().getGameTime();
        JsonArray rows = new JsonArray();
        for (Colony colony : colonies) {
            ColonyLife.Life life = lives.peek(colony.colonyId());
            int filled = life == null ? 0 : GratitudeCache.filled(life);
            long stocked = life == null ? 0L : life.cacheLastStocked();
            long minutesAgo = stocked <= 0L ? -1L : Math.max(0L, (now - stocked) / TICKS_PER_MINUTE);
            JsonObject row = row(server, colony, playerId);
            row.addProperty("has_cache", hasCacheBuilding(server, colony));
            row.addProperty("filled", filled);
            row.addProperty("slots", ColonyLife.CACHE_SLOTS);
            row.addProperty("full", filled >= ColonyLife.CACHE_SLOTS);
            row.addProperty("last_stocked_minutes_ago", (int) Math.min(Integer.MAX_VALUE, minutesAgo));
            row.addProperty("shared_with_allies", life != null && life.cacheShared());
            rows.add(row);
        }
        return wrap(server, playerId, rows);
    }

    /** Whether the colony has finished a building that carries the Gratitude Cache role. */
    private static boolean hasCacheBuilding(MinecraftServer server, Colony colony) {
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        if (plan == null) {
            return false;
        }
        Map<Identifier, Blueprint> blueprints = ColonyDefinitions.blueprintsForServer(server);
        for (ColonyConstruction.Structure structure : plan.structures()) {
            Blueprint blueprint = blueprints.get(structure.blueprint());
            if (blueprint != null && blueprint.hasRole(ColonyBuildings.ROLE_CACHE)) {
                return true;
            }
        }
        return false;
    }

    // --- helpers ----------------------------------------------------------------

    /**
     * The five fields every schema 2 row starts with. {@code role} is about the <b>requester</b>, the
     * only person a row ever says anything about.
     */
    private static JsonObject row(MinecraftServer server, Colony colony, UUID playerId) {
        JsonObject row = ColonyLinkSnapshots.identity(colony, playerId);
        row.addProperty("role", ColonyLinkAccess.roleOf(server, colony, playerId).key());
        return row;
    }

    private static JsonObject wrap(MinecraftServer server, UUID playerId, JsonArray rows) {
        JsonObject root = ColonyLinkSnapshots.envelope(server, playerId);
        root.add("colonies", rows);
        return root;
    }

    /** The colony's Nerans, or nobody when its beacon is not loaded. Never loads a chunk. */
    private static List<ColonistEntity> roster(@Nullable ServerLevel level, Colony colony) {
        if (level == null || !level.isLoaded(colony.beaconPos())) {
            return List.of();
        }
        return Population.colonistsOf(level, colony);
    }
}
