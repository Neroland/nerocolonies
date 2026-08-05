package za.co.neroland.nerocolonies.content.effect;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * The static registry of research-effect kinds.
 *
 * <p>Deliberately a plain {@link ConcurrentHashMap} rather than a Minecraft registry (the pattern
 * Neroland Core uses for its link registry and NeroQuests for objective types): effect types are
 * pure code contracts, must be resolvable before any datapack load, and must behave identically on
 * all three loaders without registry-freeze timing games.
 *
 * <p>Populated by {@link #init()} during common init, before {@code ColonyDefinitions} reads a
 * single file. An add-on may register its own types the same way; ids are namespaced, so a collision
 * is the registering mod's own doing.
 */
public final class ResearchEffectTypes {

    public static final Identifier HOUSING_TIER = id("housing_tier");
    public static final Identifier JOB_UNLOCK = id("job_unlock");
    public static final Identifier JOB_SLOTS = id("job_slots");
    public static final Identifier OXYGEN_EFFICIENCY = id("oxygen_efficiency");
    public static final Identifier EXPORT_UNLOCK = id("export_unlock");
    public static final Identifier MORALE_BONUS = id("morale_bonus");

    private static final Map<Identifier, MapCodec<? extends ResearchEffect>> TYPES =
            new ConcurrentHashMap<>();

    /** Unknown ids already complained about, so a big pack logs each once rather than per node. */
    private static final Set<Identifier> WARNED = ConcurrentHashMap.newKeySet();

    private static volatile boolean initialised;

    private ResearchEffectTypes() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, path);
    }

    /** Registers the built-in effect types. Idempotent; called once from common init. */
    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        register(HOUSING_TIER, ResearchEffect.HousingTierUnlock.MAP_CODEC);
        register(JOB_UNLOCK, ResearchEffect.JobUnlock.MAP_CODEC);
        register(JOB_SLOTS, ResearchEffect.JobSlots.MAP_CODEC);
        register(OXYGEN_EFFICIENCY, ResearchEffect.OxygenEfficiency.MAP_CODEC);
        register(EXPORT_UNLOCK, ResearchEffect.ExportUnlock.MAP_CODEC);
        register(MORALE_BONUS, ResearchEffect.MoraleBonus.MAP_CODEC);
    }

    /**
     * Registers an effect kind. A second registration for the same id replaces the first and logs a
     * warning, matching Core's registry conventions.
     */
    public static void register(Identifier typeId, MapCodec<? extends ResearchEffect> codec) {
        if (TYPES.put(typeId, codec) != null) {
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] Research effect type '{}' was replaced by a later registration.",
                    typeId);
        }
    }

    /** Whether {@code typeId} names a registered effect type. */
    public static boolean isRegistered(Identifier typeId) {
        return TYPES.containsKey(typeId);
    }

    /** Every registered effect type id (a snapshot). */
    public static Set<Identifier> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(TYPES.keySet()));
    }

    /**
     * The dispatch target for {@link ResearchEffect#CODEC}. An unregistered id yields a codec that
     * decodes to an inert {@link ResearchEffect.Unknown} instead of failing, so the owning node
     * still loads and the pack still works on an older jar.
     */
    public static MapCodec<? extends ResearchEffect> codecFor(Identifier typeId) {
        MapCodec<? extends ResearchEffect> codec = TYPES.get(typeId);
        if (codec != null) {
            return codec;
        }
        if (WARNED.add(typeId)) {
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] Unknown research effect type '{}' - it is ignored. This usually "
                            + "means the datapack was written for a newer NeroColonies.", typeId);
        }
        return MapCodec.unit(new ResearchEffect.Unknown(typeId));
    }
}
