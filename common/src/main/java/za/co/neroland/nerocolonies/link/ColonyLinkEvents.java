package za.co.neroland.nerocolonies.link;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.nerolandcore.link.LinkAlerts;
import za.co.neroland.nerolandcore.link.LinkEvent;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyNeeds;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * The live half of the link module: the member-scoped things worth waking a companion client for,
 * one broadcast, plus the few things worth interrupting a player for.
 *
 * <h2>Events</h2>
 *
 * <ul>
 *   <li><b>{@code life_support}</b> — one of <em>your</em> colonies moved between {@code OK},
 *       {@code DEGRADED} and {@code FAILED}. Published with {@link LinkEvent#forPlayer} to each of
 *       the colony's members (owner and access list), so the bridge routes it to those players'
 *       sessions and nobody else's.</li>
 *   <li><b>{@code morale}</b> — one of your colonies crossed the work-stop threshold, in either
 *       direction.</li>
 *   <li><b>{@code food}</b> — one of your colonies ran out of rations, or started eating again.</li>
 *   <li><b>{@code exports}</b> — one of your colonies' export buffers filled up (which stops export
 *       production) or was drained.</li>
 *   <li><b>{@code construction}</b> — one of your colonies finished building a structure.</li>
 *   <li><b>{@code stage}</b> — one of your colonies reached a new growth stage.</li>
 *   <li><b>{@code neran_born}</b> — a Neran was born; carries the new population.</li>
 *   <li><b>{@code needs}</b> — the needs list started, or stopped, holding something the colony
 *       cannot get without help; carries how many, never what a player holds.</li>
 *   <li><b>{@code enemy}</b> — enemies are inside the claim; carries a count, never who.</li>
 *   <li><b>{@code cache}</b> — the Gratitude Cache was stocked, or is full.</li>
 *   <li><b>{@code guards}</b> — the colony's guards engaged a target.</li>
 *   <li><b>{@code colony_state}</b> — a colony's life support changed. <b>Broadcast</b>, because a
 *       colony is a place, not a person.</li>
 * </ul>
 *
 * <p>The last six member-scoped topics arrived with schema version 2 and are published by the code
 * that detects them, through {@link #colonyEvent}; {@code needs} is detected here, by
 * {@link #needsCheck}.
 *
 * <h2>Alerts</h2>
 *
 * <p>Few, and deliberately few — an alert survives in Core's store until it is acknowledged, so it
 * is reserved for things a player would genuinely want to be told about while the game is closed:
 *
 * <ul>
 *   <li><b>life support has failed</b> — raised for each member of the colony;</li>
 *   <li><b>morale collapsed and work has stopped</b> — likewise;</li>
 *   <li><b>enemies are inside the claim</b>;</li>
 *   <li><b>the Gratitude Cache is full</b>;</li>
 *   <li><b>the colony needs help</b> — its needs list holds something it cannot get alone.</li>
 * </ul>
 *
 * <p>All are <b>rate-limited per colony per kind</b> ({@value #ALERT_COOLDOWN_MS} ms), so a
 * generator flapping between powered and unpowered cannot turn a companion client into an alarm
 * clock. The alert id is one per colony per kind, so a re-raise replaces rather than stacks.
 *
 * <h2>Scope and privacy (POPIA/GDPR)</h2>
 *
 * <p>A broadcast reaches every session, so the broadcast payload here carries a colony id, a
 * dimension id and a life-support state — <b>not even the colony's name</b>, and certainly no owner,
 * no member count and no position. That is the same rule Core's {@code ThresholdEvents} contract
 * imposes on {@code nerocolonies:oxygen}, applied to the same information.
 *
 * <p>A member-scoped event, by contrast, goes only to the people who can already see the colony in
 * their snapshots, so it may name the colony. Default names identify nobody ({@code "Colony N"}),
 * and erasure replaces the name of any colony the erased player owned. An alert's {@code text} is a
 * plain string by Core's contract and therefore must never contain <em>another</em> player's data —
 * none of these ever mentions a player at all: a colony name, a condition and at most a count.
 *
 * <p>The gallery's <b>sandbox colony</b> publishes nothing and raises nothing.
 *
 * <p>A colony with no owner (post-erasure, under the {@code transfer_to_server} policy) still tells
 * its remaining access-list members, and nobody else. With no members it publishes nothing.
 *
 * <p><b>Nothing here may throw at its caller.</b> Every publisher is wrapped: a link failure must
 * never disturb a colony tick.
 *
 * <p>Server thread only.
 */
public final class ColonyLinkEvents {

    /** Minimum gap between two alerts of the same kind for the same colony, in milliseconds. */
    private static final long ALERT_COOLDOWN_MS = 300_000L;

    /** {@code colonyId + kind → when it was last raised}. Session state; cleared on server stop. */
    private static final Map<String, Long> LAST_ALERT = new ConcurrentHashMap<>();

    /**
     * {@code colonyId → how many of its needs wanted help last cycle}. Session state; cleared on
     * server stop. A count about a colony — nothing player-shaped.
     */
    private static final Map<UUID, Integer> LAST_NEEDS = new ConcurrentHashMap<>();

    private ColonyLinkEvents() {
    }

    /**
     * Nothing to subscribe to. NeroColonies publishes from the colony tick itself rather than from an
     * in-mod bus, because the tick is already the one place a colony's state transitions are
     * detected — {@code ColonyTicker} compares this cycle against the last one to decide what to
     * publish, and does it once. The method exists so {@link ColonyLinkModule#init()} has the same
     * three-surface shape as every other Nero mod's, and so a future in-mod listener has an obvious
     * home.
     */
    static void init() {
        // Intentionally empty — see the javadoc.
    }

    /** Drops the rate-limiter and needs-watch state. Called from the server-stopped hook. */
    public static void reset() {
        LAST_ALERT.clear();
        LAST_NEEDS.clear();
    }

    // --- life_support ----------------------------------------------------------

    /**
     * Publishes a life-support transition: member-scoped detail, plus one broadcast that says only
     * that a colony somewhere changed state.
     *
     * @param state the state the colony has just moved <em>to</em>
     */
    public static void lifeSupportChanged(ServerLevel level, Colony colony, LifeSupport.State state) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            JsonObject payload = colonyPayload(colony);
            payload.addProperty("state", state.name());
            payload.addProperty("life_support_ok", colony.lifeSupportOk());
            payload.addProperty("oxygen_generators", LifeSupport.generatorCount(colony.colonyId()));
            payload.addProperty("population", colony.population());
            forMembers(colony, ColonyLinkModule.TOPIC_LIFE_SUPPORT, payload);
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_LIFE_SUPPORT, e);
        }
        try {
            // Broadcast: a colony id, a place and a state. Nothing player-shaped, not even a name.
            JsonObject broadcast = new JsonObject();
            broadcast.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
            broadcast.addProperty("colony", colony.colonyId().toString());
            broadcast.addProperty("dimension", colony.dimension().identifier().toString());
            broadcast.addProperty("state", state.name());
            broadcast.addProperty("timestamp", System.currentTimeMillis());
            publish(LinkEvent.broadcast(ColonyLinkModule.MODULE_ID,
                    ColonyLinkModule.TOPIC_COLONY_STATE, broadcast));
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_COLONY_STATE, e);
        }
        if (state == LifeSupport.State.FAILED) {
            raise(level.getServer(), colony, "life_support",
                    LinkAlert.Severity.CRITICAL,
                    "Life support has failed at " + colony.name() + ".");
        }
    }

    // --- morale ----------------------------------------------------------------

    /** Publishes a work-stop crossing, and raises the alert when work has just stopped. */
    public static void workStopChanged(ServerLevel level, Colony colony, boolean stopped) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            JsonObject payload = colonyPayload(colony);
            payload.addProperty("morale", Math.round(colony.morale()));
            payload.addProperty("work_stopped", stopped);
            payload.addProperty("threshold", NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get());
            forMembers(colony, ColonyLinkModule.TOPIC_MORALE, payload);
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_MORALE, e);
        }
        if (stopped) {
            raise(level.getServer(), colony, "morale", LinkAlert.Severity.WARN,
                    "Morale has collapsed at " + colony.name() + " and work has stopped.");
        }
    }

    // --- food ------------------------------------------------------------------

    /** Publishes a starvation crossing. No alert: an empty larder is a slow problem, not an urgent one. */
    public static void foodChanged(ServerLevel level, Colony colony, boolean starving) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            JsonObject payload = colonyPayload(colony);
            payload.addProperty("starving", starving);
            payload.addProperty("food_stock", colony.foodStock());
            payload.addProperty("population", colony.population());
            forMembers(colony, ColonyLinkModule.TOPIC_FOOD, payload);
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_FOOD, e);
        }
    }

    // --- exports ---------------------------------------------------------------

    /** Publishes an export-buffer fill crossing. No alert: nothing is lost, production simply pauses. */
    public static void exportBufferChanged(ServerLevel level, Colony colony, boolean full) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            JsonObject payload = colonyPayload(colony);
            payload.addProperty("buffer_full", full);
            forMembers(colony, ColonyLinkModule.TOPIC_EXPORTS, payload);
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_EXPORTS, e);
        }
    }

    // --- construction ------------------------------------------------------------

    /**
     * Publishes a finished structure. Member-scoped and no alert: a colony building itself a habitat
     * is good news, and good news does not survive in an alert store until somebody dismisses it.
     *
     * <p>Deliberately <b>not</b> broadcast. The equivalent broadcast would have to carry a colony id
     * and a blueprint id and nothing else to be safe, which is information nobody has a use for; the
     * colony-scoped Core threshold channel {@code nerocolonies:structures} already covers the
     * cross-mod case (a NeroQuests objective, say) without any per-player routing at all.
     *
     * @param blueprint the blueprint that was completed — content, never player data
     * @param built     how many structures the colony has now built for itself
     */
    public static void structureCompleted(Colony colony, Identifier blueprint, int built) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            JsonObject payload = colonyPayload(colony);
            payload.addProperty("blueprint", blueprint.toString());
            payload.addProperty("structures_built", built);
            payload.addProperty("population", colony.population());
            payload.addProperty("housing_capacity", colony.housingCapacity());
            forMembers(colony, ColonyLinkModule.TOPIC_CONSTRUCTION, payload);
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_CONSTRUCTION, e);
        }
    }

    // --- living-colony events (schema 2) ------------------------------------------

    /**
     * Publishes a member-scoped event: the standard colony fields plus {@code extra}. Never carries a
     * player name or id — callers put counts and states in {@code extra}, nothing else. Sandbox
     * (gallery) colonies publish nothing.
     */
    public static void colonyEvent(Colony colony, String topic, JsonObject extra) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            JsonObject payload = colonyPayload(colony);
            if (extra != null) {
                for (String key : extra.keySet()) {
                    payload.add(key, extra.get(key));
                }
            }
            forMembers(colony, topic, payload);
        } catch (RuntimeException e) {
            warn(topic, e);
        }
    }

    /**
     * Raises a rate-limited alert for every member of a colony. {@code text} must name no player.
     */
    public static void colonyAlert(MinecraftServer server, Colony colony, String kind, boolean critical,
            String text) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        raise(server, colony, kind, critical ? LinkAlert.Severity.CRITICAL : LinkAlert.Severity.WARN, text);
    }

    private static boolean sandbox(Colony colony) {
        try {
            return ColonyLife.isSandbox(ServerStateReset.currentServer(), colony.colonyId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- needs -------------------------------------------------------------------

    /**
     * Watches a colony's needs list for the moment it starts wanting help. Called once per colony
     * cycle.
     *
     * <p>A need "wants help" when nothing in the colony gathers it — a Starter Works material, a food
     * shortfall, a tool nobody makes. The count of those is remembered per colony; when it goes from
     * none to some, a {@code needs} event is published and one warning alert is raised, and when it
     * returns to none the event is published again with {@code critical: 0} and no alert. Changes
     * between two non-zero counts publish nothing: this is a crossing, not a ticker.
     *
     * <p>The payload is two counts, and the alert text is a colony name and a count. Neither says
     * what any player holds or who could help.
     */
    public static void needsCheck(ServerLevel level, Colony colony) {
        if (!enabled() || sandbox(colony)) {
            return;
        }
        try {
            List<ColonyNeeds.Need> needs = ColonyNeeds.derive(level, colony);
            int critical = 0;
            for (ColonyNeeds.Need need : needs) {
                if (need.etaSoloMinutes() < 0) {
                    critical++;
                }
            }
            Integer previous = LAST_NEEDS.put(colony.colonyId(), critical);
            boolean was = previous != null && previous > 0;
            boolean is = critical > 0;
            if (was == is) {
                return;
            }
            JsonObject extra = new JsonObject();
            extra.addProperty("critical", critical);
            extra.addProperty("total", needs.size());
            colonyEvent(colony, ColonyLinkModule.TOPIC_NEEDS, extra);
            if (is) {
                raise(level.getServer(), colony, "needs", LinkAlert.Severity.WARN,
                        colony.name() + " needs your help: " + critical
                                + (critical == 1 ? " need" : " needs")
                                + " cannot be met without you.");
            }
        } catch (RuntimeException e) {
            warn(ColonyLinkModule.TOPIC_NEEDS, e);
        }
    }

    // --- plumbing ---------------------------------------------------------------

    private static boolean enabled() {
        try {
            return NeroColoniesConfig.LINK_MODULE_ENABLED.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The fields every member-scoped colony payload starts with. */
    private static JsonObject colonyPayload(Colony colony) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema_version", ColonyLinkModule.SCHEMA_VERSION);
        payload.addProperty("colony", colony.colonyId().toString());
        payload.addProperty("name", colony.name());
        payload.addProperty("dimension", colony.dimension().identifier().toString());
        payload.addProperty("timestamp", System.currentTimeMillis());
        return payload;
    }

    /**
     * Publishes to every member of the colony — its owner and its access list — exactly the people
     * whose link snapshots already show it. Members used to see the colony in snapshots but never
     * hear about it changing.
     */
    private static void forMembers(Colony colony, String topic, JsonObject payload) {
        for (UUID member : members(colony)) {
            publish(LinkEvent.forPlayer(ColonyLinkModule.MODULE_ID, topic, member, payload));
        }
    }

    /** Owner (if any) then access-list members, without duplicates. */
    private static java.util.Set<UUID> members(Colony colony) {
        java.util.Set<UUID> out = new java.util.LinkedHashSet<>();
        if (colony.hasOwner()) {
            out.add(colony.ownerId());
        }
        out.addAll(colony.accessList());
        return out;
    }

    /**
     * Raises one alert for a colony's owner through Core's per-player store, at most once per
     * {@value #ALERT_COOLDOWN_MS} ms per colony per kind. Never logs who it was raised for.
     */
    private static void raise(MinecraftServer server, Colony colony, String kind,
            LinkAlert.Severity severity, String text) {
        if (server == null) {
            return;
        }
        java.util.Set<UUID> recipients = members(colony);
        if (recipients.isEmpty()) {
            return;
        }
        String key = colony.colonyId() + "/" + kind;
        long now = System.currentTimeMillis();
        Long last = LAST_ALERT.get(key);
        if (last != null && now - last < ALERT_COOLDOWN_MS) {
            return;
        }
        LAST_ALERT.put(key, now);
        for (UUID recipient : recipients) {
            try {
                LinkAlerts.get(server).raise(server, recipient,
                        LinkAlert.raise(kind + "." + colony.colonyId(), ColonyLinkModule.MODULE_ID,
                                severity, text));
            } catch (RuntimeException e) {
                warn("alerts", e);
            }
        }
    }

    /** Publish to Core's shared bus; a failure there is logged, never thrown at the caller. */
    private static void publish(LinkEvent event) {
        try {
            NeroLinkRegistry.eventBus().publish(event);
        } catch (RuntimeException e) {
            warn(event.topic(), e);
        }
    }

    /** Topic only — never who the event was for (POPIA/GDPR). */
    private static void warn(String topic, RuntimeException e) {
        NeroColoniesCommon.LOGGER.warn(
                "[NeroColonies] Publishing the NeroLink '{}' event failed.", topic, e);
    }
}
