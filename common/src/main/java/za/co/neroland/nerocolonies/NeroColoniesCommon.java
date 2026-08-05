package za.co.neroland.nerocolonies;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.nerocolonies.compat.CompatRegistry;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.effect.ResearchEffectTypes;
import za.co.neroland.nerocolonies.data.NeroColoniesData;
import za.co.neroland.nerocolonies.link.ColonyLinkModule;
import za.co.neroland.nerocolonies.network.ColonyNetwork;
import za.co.neroland.nerocolonies.platform.Services;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlocks;
import za.co.neroland.nerocolonies.registry.NeroColoniesEntityTypes;
import za.co.neroland.nerocolonies.registry.NeroColoniesItems;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;
import za.co.neroland.nerocolonies.telemetry.NeroColoniesTelemetry;

/**
 * Loader-agnostic entry point for NeroColonies. Each loader entry point (Fabric / Forge / NeoForge)
 * calls {@link #init()} once during mod construction.
 *
 * <p>The ordering below is not cosmetic. Fabric registers <em>eagerly</em> — the moment a registry
 * class is touched — so anything that must exist before something else has to be listed before it,
 * on every loader, whether or not that loader would have cared. The numbered steps are the
 * ecosystem convention; later stages fill the slots that are currently placeholders rather than
 * inserting themselves wherever is convenient.
 */
public final class NeroColoniesCommon {

    public static final String MOD_ID = "nerocolonies";
    public static final Logger LOGGER = LoggerFactory.getLogger("NeroColonies");

    private NeroColoniesCommon() {
    }

    /** Called once per loader during mod construction. */
    public static void init() {
        LOGGER.info("[NeroColonies] common init");

        // 0. Platform seams, resolved here during construction and never lazily on a tick path — a
        //    late ServiceLoader read can throw ServiceConfigurationError out of gameplay code
        //    (Nerospace crash precedent MC-NEROSPACE-F).
        Services.init();

        // 1. Config first: everything below reads it, including telemetry's opt-out flag.
        NeroColoniesConfig.init();

        // 2. Anonymous, NeroColonies-only crash reporting. Must follow the config registration and
        //    precede the rest of init so early failures are still reported. Inert until a real
        //    Sentry DSN is configured (see NeroColoniesTelemetry's PLACEHOLDER_DSN guard).
        NeroColoniesTelemetry.init();

        // 3. Blocks, then block entities (the BE types name the blocks, and on Fabric "before" is
        //    literal). NeroColoniesBlocks.init() also installs the beacon check the colony
        //    retention sweep uses.
        NeroColoniesBlocks.init();
        NeroColoniesBlockEntities.init();

        // 4. Entity registry — the colonist. The type only; its attributes are declared in step 7,
        //    because on Fabric a declaration applies immediately and must not name a type that does
        //    not exist yet.
        NeroColoniesEntityTypes.init();

        // 5. Items and menus. Items reference the blocks registered in step 3.
        NeroColoniesItems.init();
        NeroColoniesMenus.init();

        // 6. Everything joins Neroland Core's shared creative tab — NeroColonies has no tab of its
        //    own. Core reads the tab lazily when displayed, so contributing after Core built it is
        //    fine.
        NeroColoniesItems.addToCreativeTab();

        // 7. Entity attributes through Core's entity seam. No spawn placement is registered: a
        //    colonist has no natural spawn, it is grown by a colony that can house and feed it.
        NeroColoniesEntityTypes.registerEntitySupport();

        // 8. Player-data erasure registration. Registered before any colony can exist on purpose:
        //    registering late is how an erasure request silently misses a store (POPIA/GDPR).
        NeroColoniesData.init();

        // 9. Compat bridge detection — resolved once here through Services.PLATFORM.isModLoaded,
        //    never per interaction. This is also where the Nerospace planet adapter is probed; with
        //    Nerospace absent the façade stays on its "everywhere breathable" fallback.
        CompatRegistry.init();

        // 10. Declare the payloads before any loader registers them: every loader entry point runs
        //     this method first, then wires its own networking. The datapack content types are
        //     registered here too — they must exist before any pack is read, and a research effect
        //     whose type is unregistered at load time would decode to Unknown for the whole session.
        ColonyNetwork.init();
        ResearchEffectTypes.init();

        // 11. The NeroLink module goes LAST, so a companion client is never told about something
        //     before the mod itself has finished reacting to it, and its own init swallows any
        //     failure — a broken link module must never take the colony layer down with it.
        ColonyLinkModule.init();
    }
}
