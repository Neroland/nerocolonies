package za.co.neroland.nerocolonies.compat.nerospace;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerospace.api.NerospacePlanets;
import za.co.neroland.nerospace.api.PlanetId;
import za.co.neroland.nerospace.api.PlanetTraits;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.ColonyType;
import za.co.neroland.nerocolonies.compat.NerospaceCompat;

/**
 * The compile-time Nerospace bridge: with {@link NerospaceClaimPolicy}, the only code in NeroColonies that
 * imports {@code za.co.neroland.nerospace}. Nerospace is {@code compileOnly} and optional: this class is listed
 * in {@code META-INF/services/za.co.neroland.nerocolonies.compat.NerospaceCompat}, instantiated by
 * {@code CompatRegistry} only after the loader reports {@code nerospace} present, and left out of a build with
 * {@code nerospace_compat=false} (every colony is then {@link ColonyType#GENERIC}).
 *
 * <h2>The supported surface, and only it</h2>
 *
 * <p>{@code za.co.neroland.nerospace.api} only: {@code NerospacePlanets.byDimension} and {@code traits}, the
 * traits record's {@code airless()}, {@code hazard()} and (2.0+) {@code colonyProfile()}, and
 * {@code NerospaceTerraforming.setClaimPolicy}. Nothing outside the api package is touched.
 *
 * <h2>Failure</h2>
 *
 * <p>Every answer is cached per dimension (planet traits do not change within a launch). A failure of the
 * planet lookup collapses to the {@link NerospaceCompat#NONE} answers (breathable, no hazard, GENERIC) and
 * disables further lookups for the session. A Nerospace without colony profiles (1.x) throws a
 * {@link LinkageError} from {@code colonyProfile()} only: that is caught on its own, so the profile falls back
 * to GENERIC while breathability and hazard keep working. Each case logs one anonymous line. <b>No player data
 * is ever read, passed or logged here;</b> the bridge only handles dimension keys.
 */
public final class NerospaceBridge implements NerospaceCompat {

    /** Resolved per dimension key and cached: planet traits are immutable for a launch. */
    private final Map<ResourceKey<Level>, Traits> cache = new ConcurrentHashMap<>();

    private volatile boolean live = true;
    private volatile boolean profiles = true;

    /** For {@link java.util.ServiceLoader}. Touches nothing in Nerospace. */
    public NerospaceBridge() {
    }

    private record Traits(boolean airless, Hazard hazard, ColonyType type) {
        static final Traits BREATHABLE = new Traits(false, Hazard.NONE, ColonyType.GENERIC);
    }

    @Override
    public boolean airless(ResourceKey<Level> dimension) {
        return lookup(dimension).airless();
    }

    @Override
    public Hazard hazard(ResourceKey<Level> dimension) {
        return lookup(dimension).hazard();
    }

    @Override
    public ColonyType colonyProfile(ResourceKey<Level> dimension) {
        return lookup(dimension).type();
    }

    @Override
    public boolean active() {
        return this.live;
    }

    @Override
    public void installHooks() {
        try {
            NerospaceClaimPolicy.install();
        } catch (RuntimeException | LinkageError e) {
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] This Nerospace has no terraform claim hook ({}); colony claims do not "
                            + "govern overlay terraforming.", e.getClass().getSimpleName());
        }
    }

    private Traits lookup(@Nullable ResourceKey<Level> dimension) {
        if (dimension == null || !this.live) {
            return Traits.BREATHABLE;
        }
        return this.cache.computeIfAbsent(dimension, this::resolve);
    }

    private Traits resolve(ResourceKey<Level> dimension) {
        try {
            Optional<PlanetId> planet = NerospacePlanets.byDimension(dimension);
            if (planet == null || planet.isEmpty()) {
                return Traits.BREATHABLE; // Earth, or any dimension Nerospace does not own
            }
            PlanetTraits traits = NerospacePlanets.traits(planet.get());
            if (traits == null) {
                return Traits.BREATHABLE;
            }
            return new Traits(traits.airless(), toHazard(traits.hazard()), profileOf(traits));
        } catch (RuntimeException | LinkageError e) {
            this.live = false;
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] Nerospace planet-trait lookup failed ({}); every dimension is now "
                            + "treated as breathable and every new colony as generic for the rest of this "
                            + "session.", e.getClass().getSimpleName());
            return Traits.BREATHABLE;
        }
    }

    /** The planet's colony profile as a type; GENERIC for anything unknown or on a Nerospace without one. */
    private ColonyType profileOf(PlanetTraits traits) {
        if (!this.profiles) {
            return ColonyType.GENERIC;
        }
        try {
            Object profile = traits.colonyProfile();
            return ColonyType.fromNerospaceProfile(profile == null ? null : profile.toString());
        } catch (LinkageError e) {
            this.profiles = false;
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] This Nerospace has no colony profiles ({}); new colonies are generic, "
                            + "life support and hazards are unaffected.", e.getClass().getSimpleName());
            return ColonyType.GENERIC;
        } catch (RuntimeException e) {
            return ColonyType.GENERIC;
        }
    }

    /**
     * Maps Nerospace's hazard onto ours by name, so a constant this façade does not model (RADIATION, added
     * in Nerospace 2.0) is simply NONE here.
     */
    private static Hazard toHazard(@Nullable Object value) {
        if (!(value instanceof Enum<?> constant)) {
            return Hazard.NONE;
        }
        return switch (constant.name()) {
            case "HEAT" -> Hazard.HEAT;
            case "COLD" -> Hazard.COLD;
            default -> Hazard.NONE;
        };
    }
}
