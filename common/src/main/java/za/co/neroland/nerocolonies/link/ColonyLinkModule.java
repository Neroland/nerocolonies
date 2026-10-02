package za.co.neroland.nerocolonies.link;

import java.util.List;

import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * NeroColonies' plug into Neroland Core's link API — the seam a companion client reaches a player's
 * own colonies through, without NeroColonies knowing that any such client exists.
 *
 * <p>The whole module is plain server-side Java against Core's
 * {@link za.co.neroland.nerolandcore.link} package: no loader wiring, no networking of its own, no
 * HTTP. NeroColonies registers what it can show and what it can do; the separate NeroLink bridge mod
 * reads Core's registry and serves it. With no bridge installed this costs one registry entry.
 *
 * <p>Three surfaces, all registered from {@link NeroColoniesCommon#init()}:
 *
 * <ul>
 *   <li><b>Read</b> — {@link ColonyLinkSnapshots}, serving the {@code colonies}, {@code colonists},
 *       {@code jobs}, {@code research} and {@code exports} sections, and — since schema version 2 —
 *       {@code summary}, {@code needs}, {@code buildings}, {@code professions}, {@code roles} and
 *       {@code cache};</li>
 *   <li><b>Write</b> — {@link ColonyLinkActions}, accepting {@code toggle_export},
 *       {@code acknowledge_alert}, {@code prioritise_need} and {@code toggle_cache_sharing};</li>
 *   <li><b>Live</b> — {@link ColonyLinkEvents}, publishing the member-scoped colony events and one
 *       broadcast onto Core's shared event bus, and raising the handful of alerts this mod has any
 *       business raising.</li>
 * </ul>
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p><b>Own colonies only.</b> Every snapshot section is scoped to the colonies the requesting
 * {@code playerId} owns or is an Ally or Chief of, before anything leaves this mod. No other
 * player's colonies, and — crucially — <b>no membership at all</b>: a section reports how many
 * members a colony has, never who they are, for the same reason the beacon screen does not (a client
 * told who is on a colony's access list has been told where those people play). The only role ever
 * spelled out is the requester's own. The gallery's sandbox colony appears nowhere.
 *
 * <p><b>Coordinates.</b> A colony's beacon position is in the {@code colonies} section, because it is
 * the one thing a companion app needs to say "which of your bases is this" and it is the requesting
 * player's own base. Job stations, housing and generators are reported as <b>counts and indexes</b>,
 * never positions.
 *
 * <p><b>Broadcasts carry nothing player-shaped.</b> The one broadcast topic
 * ({@link #TOPIC_COLONY_STATE}) reaches every session, so it carries a colony id, a dimension and a
 * life-support state — the same rule Core's {@code ThresholdEvents} contract imposes on
 * {@code nerocolonies:oxygen}, applied to the same information.
 *
 * <p><b>Erasure needs no separate wiring.</b> Every read here goes to the live
 * {@code colony/ColonyState}, so a player erased through Core's {@code PlayerDataErasure} hook
 * immediately reads as belonging to nothing. See {@code PRIVACY.md} and {@code wiki/Link-Module.md}.
 *
 * <p><b>Schema version 2.</b> Bump {@link #SCHEMA_VERSION} whenever the shape of a snapshot section
 * changes, so a companion client can tell what it is parsing. Version 2 <em>added</em> six sections
 * and two actions and changed none of the version 1 sections, so a version 1 client keeps working.
 */
public final class ColonyLinkModule {

    /** The link module id — the same string as the mod id, as the ecosystem convention requires. */
    public static final String MODULE_ID = NeroColoniesCommon.MOD_ID;

    /** The snapshot schema revision. Bump on any change to a section's shape. */
    public static final int SCHEMA_VERSION = 2;

    /** Section: the colonies the requesting player owns or belongs to, with their state. */
    public static final String SECTION_COLONIES = "colonies";

    /** Section: population, housing and staffing counts per colony. Counts, never entities. */
    public static final String SECTION_COLONISTS = "colonists";

    /** Section: job slots and what each of the colony's stations is doing. */
    public static final String SECTION_JOBS = "jobs";

    /** Section: what each colony has unlocked, what it could unlock, and what it can pay for. */
    public static final String SECTION_RESEARCH = "research";

    /** Section: the export buffer's fill, its worth, and the colony's unlocked manifest. */
    public static final String SECTION_EXPORTS = "exports";

    /** Section (schema 2): stage, population, food, morale and needs at a glance, per colony. */
    public static final String SECTION_SUMMARY = "summary";

    /** Section (schema 2): what each colony is short of, why, and how long until it has it. */
    public static final String SECTION_NEEDS = "needs";

    /** Section (schema 2): finished, under-way and planned buildings. By blueprint, never position. */
    public static final String SECTION_BUILDINGS = "buildings";

    /** Section (schema 2): head-count against places, per trade. Counts, never Nerans. */
    public static final String SECTION_PROFESSIONS = "professions";

    /** Section (schema 2): your own role, and how many allies, chiefs and enemies. Counts only. */
    public static final String SECTION_ROLES = "roles";

    /** Section (schema 2): how full the Gratitude Cache is. Never what is in it. */
    public static final String SECTION_CACHE = "cache";

    /** Action: route a job's output to the export buffer, or back to colony storage. */
    public static final String ACTION_TOGGLE_EXPORT = "toggle_export";

    /** Action: acknowledge one of your own NeroColonies alerts in Core's alert store. */
    public static final String ACTION_ACKNOWLEDGE_ALERT = "acknowledge_alert";

    /** Action (schema 2): ask the colony to gather one need first, or clear that. Owner or Chief. */
    public static final String ACTION_PRIORITISE_NEED = "prioritise_need";

    /** Action (schema 2): share the Gratitude Cache with allies, or stop. Owner only. */
    public static final String ACTION_TOGGLE_CACHE_SHARING = "toggle_cache_sharing";

    /** Topic: one of your colonies' life support changed state. Member-scoped (owner and access list). */
    public static final String TOPIC_LIFE_SUPPORT = "life_support";

    /** Topic: one of your colonies crossed the work-stop morale threshold. Member-scoped (owner and access list). */
    public static final String TOPIC_MORALE = "morale";

    /** Topic: one of your colonies ran out of food, or started eating again. Member-scoped (owner and access list). */
    public static final String TOPIC_FOOD = "food";

    /** Topic: one of your colonies' export buffers filled up, or was drained. Member-scoped (owner and access list). */
    public static final String TOPIC_EXPORTS = "exports";

    /**
     * Topic: one of your colonies finished building a structure for itself. Member-scoped (owner and access list).
     *
     * <p>A topic and not a section: the snapshot sections describe standing state and adding a field
     * to one would need a {@link #SCHEMA_VERSION} bump on every client. A completion is an event, it
     * is what a companion app would actually want pushed, and a new topic costs an older client
     * nothing — it simply never sees it.
     */
    public static final String TOPIC_CONSTRUCTION = "construction";

    /**
     * Topic: a colony's life-support state changed. <b>Broadcast</b>, because a colony is a place,
     * not a person — the payload carries a colony id, a name, a dimension and a state, and nothing
     * else at all.
     */
    public static final String TOPIC_COLONY_STATE = "colony_state";

    /** Topic: a colony reached a new growth stage. Member-scoped. */
    public static final String TOPIC_STAGE = "stage";

    /** Topic: a Neran was born. Member-scoped; carries the new population, never a name. */
    public static final String TOPIC_BIRTH = "neran_born";

    /**
     * Topic: the needs list started, or stopped, holding something the colony cannot get without
     * help (a Starter Works material, a food shortfall). Member-scoped; carries counts only.
     */
    public static final String TOPIC_NEEDS = "needs";

    /** Topic: enemies are inside the claim. Member-scoped; carries a count only, never who. */
    public static final String TOPIC_ENEMY = "enemy";

    /** Topic: the Gratitude Cache was stocked, or is full. Member-scoped. */
    public static final String TOPIC_CACHE = "cache";

    /** Topic: the colony's guards engaged a target. Member-scoped; a count, never who. */
    public static final String TOPIC_GUARDS = "guards";

    private ColonyLinkModule() {
    }

    /**
     * Register the read, write and live surfaces with Core. Called <b>last</b> from
     * {@link NeroColoniesCommon#init()}, so a companion client is never told about something before
     * the mod itself has finished reacting to it.
     *
     * <p>A failure here must never take the mod down with it: colonies work perfectly well with no
     * link module, so any problem is logged and swallowed. The same is true of the config switch —
     * {@code linkModuleEnabled=false} simply means nothing is registered, and every publisher checks
     * the same flag before it speaks.
     */
    public static void init() {
        try {
            if (!NeroColoniesConfig.LINK_MODULE_ENABLED.get()) {
                NeroColoniesCommon.LOGGER.info(
                        "[NeroColonies] The NeroLink module is disabled by config; companion clients "
                                + "will not see NeroColonies data.");
                return;
            }
            LinkModuleInfo info = new LinkModuleInfo(MODULE_ID, modVersion(), SCHEMA_VERSION,
                    List.of(SECTION_COLONIES, SECTION_COLONISTS, SECTION_JOBS, SECTION_RESEARCH,
                            SECTION_EXPORTS, SECTION_SUMMARY, SECTION_NEEDS, SECTION_BUILDINGS,
                            SECTION_PROFESSIONS, SECTION_ROLES, SECTION_CACHE),
                    List.of(ACTION_TOGGLE_EXPORT, ACTION_ACKNOWLEDGE_ALERT, ACTION_PRIORITISE_NEED,
                            ACTION_TOGGLE_CACHE_SHARING));
            // One provider and one handler cover the whole module; Core keys both on the module id.
            NeroLinkRegistry.registerSnapshotProvider(new ColonyLinkSnapshots(), info);
            NeroLinkRegistry.registerActionHandler(new ColonyLinkActions(), info);
            ColonyLinkEvents.init();
        } catch (RuntimeException e) {
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] Could not register the NeroLink module; companion clients will "
                            + "not see NeroColonies data. Colonies themselves are unaffected.", e);
        }
    }

    /** This mod's public version string for discovery, or {@code "unknown"} if the seam is unhappy. */
    private static String modVersion() {
        try {
            String version = Services.PLATFORM.getModVersion();
            return version == null || version.isBlank() ? "unknown" : version;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
