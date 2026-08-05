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
 *       {@code jobs}, {@code research} and {@code exports} sections;</li>
 *   <li><b>Write</b> — {@link ColonyLinkActions}, accepting {@code toggle_export} and
 *       {@code acknowledge_alert};</li>
 *   <li><b>Live</b> — {@link ColonyLinkEvents}, publishing the four owner-scoped colony events and
 *       one broadcast onto Core's shared event bus, and raising the two alerts this mod has any
 *       business raising.</li>
 * </ul>
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p><b>Own colonies only.</b> Every snapshot section is scoped to the colonies the requesting
 * {@code playerId} owns or is on the access list of, before anything leaves this mod. No other
 * player's colonies, and — crucially — <b>no membership at all</b>: a section reports how many
 * members a colony has, never who they are, for the same reason the beacon screen does not (a client
 * told who is on a colony's access list has been told where those people play).
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
 * <p><b>Schema version 1.</b> Bump {@link #SCHEMA_VERSION} whenever the shape of a snapshot section
 * changes, so a companion client can tell what it is parsing.
 */
public final class ColonyLinkModule {

    /** The link module id — the same string as the mod id, as the ecosystem convention requires. */
    public static final String MODULE_ID = NeroColoniesCommon.MOD_ID;

    /** The snapshot schema revision. Bump on any change to a section's shape. */
    public static final int SCHEMA_VERSION = 1;

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

    /** Action: route a job's output to the export buffer, or back to colony storage. */
    public static final String ACTION_TOGGLE_EXPORT = "toggle_export";

    /** Action: acknowledge one of your own NeroColonies alerts in Core's alert store. */
    public static final String ACTION_ACKNOWLEDGE_ALERT = "acknowledge_alert";

    /** Topic: one of your colonies' life support changed state. Owner-scoped. */
    public static final String TOPIC_LIFE_SUPPORT = "life_support";

    /** Topic: one of your colonies crossed the work-stop morale threshold. Owner-scoped. */
    public static final String TOPIC_MORALE = "morale";

    /** Topic: one of your colonies ran out of food, or started eating again. Owner-scoped. */
    public static final String TOPIC_FOOD = "food";

    /** Topic: one of your colonies' export buffers filled up, or was drained. Owner-scoped. */
    public static final String TOPIC_EXPORTS = "exports";

    /**
     * Topic: a colony's life-support state changed. <b>Broadcast</b>, because a colony is a place,
     * not a person — the payload carries a colony id, a name, a dimension and a state, and nothing
     * else at all.
     */
    public static final String TOPIC_COLONY_STATE = "colony_state";

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
                            SECTION_EXPORTS),
                    List.of(ACTION_TOGGLE_EXPORT, ACTION_ACKNOWLEDGE_ALERT));
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
