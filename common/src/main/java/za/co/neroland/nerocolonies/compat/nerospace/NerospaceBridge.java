package za.co.neroland.nerocolonies.compat.nerospace;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.compat.NerospaceCompat;

/**
 * The one and only class in NeroColonies that names a Nerospace type — and it names it as a
 * <b>string</b>, resolved by reflection at runtime.
 *
 * <h2>Why reflection rather than a {@code compileOnly} dependency</h2>
 *
 * <p>A {@code compileOnly} artifact would be the tidier option and it is what the plan called for
 * first, but it only works if the artifact resolves. Nerospace declares {@code maven-publish} in all
 * three loader builds ({@code za.co.neroland.nerospace:nerospace-<loader>-<mc>}, GitHub Packages),
 * yet its release workflow grants only {@code packages: read} and never invokes a {@code :publish}
 * task — so nothing has ever been pushed, and a fresh clone or a CI runner cannot resolve it. Adding
 * a hard {@code compileOnly} on an unresolvable coordinate would break all six build cells for
 * everyone except a developer who had run Nerospace's {@code publishToMavenLocal} by hand. Reflection
 * costs one probe per launch and always resolves, so that is what this uses. If Nerospace's CI ever
 * starts publishing, this class can be swapped for a compile-time bridge with no change anywhere
 * else — that is the entire point of {@link NerospaceCompat} being a façade.
 *
 * <h2>The supported surface, and only it</h2>
 *
 * <p>{@code za.co.neroland.nerospace.api.NerospacePlanets} is Nerospace's declared semver-stable
 * facade. Three of its members are used:
 * {@code byDimension(ResourceKey) -> Optional<PlanetId>}, {@code traits(PlanetId) -> PlanetTraits},
 * and on the traits record {@code airless()} and {@code hazard()}. Nothing outside
 * {@code za.co.neroland.nerospace.api} is touched — in particular <b>not</b>
 * {@code world.OxygenFieldManager}, which is internal.
 *
 * <p>Every failure path — mod absent, class missing, method shape changed, invocation error —
 * collapses to the {@link NerospaceCompat#NONE} answers (breathable, no hazard) and permanently
 * disables further attempts. One anonymous info line records the outcome. <b>No player data is ever
 * read, passed or logged on this path;</b> the bridge only ever handles dimension keys.
 */
public final class NerospaceBridge implements NerospaceCompat {

    private static final String API_CLASS = "za.co.neroland.nerospace.api.NerospacePlanets";
    private static final String BY_DIMENSION = "byDimension";
    private static final String TRAITS = "traits";
    private static final String AIRLESS = "airless";
    private static final String HAZARD = "hazard";

    /** Resolved per dimension key and cached: planet traits are immutable for a launch. */
    private final Map<ResourceKey<Level>, Traits> cache = new ConcurrentHashMap<>();

    private final Method byDimension;
    private final Method traits;

    private volatile boolean live = true;

    private NerospaceBridge(Method byDimension, Method traits) {
        this.byDimension = byDimension;
        this.traits = traits;
    }

    private record Traits(boolean airless, Hazard hazard) {
        static final Traits BREATHABLE = new Traits(false, Hazard.NONE);
    }

    /**
     * Probes Nerospace's public facade once. Called only when the platform reports {@code nerospace}
     * loaded; returns {@code null} when the facade is missing or has a shape this jar does not
     * recognise, in which case the caller keeps {@link NerospaceCompat#NONE}.
     */
    @Nullable
    public static NerospaceCompat probe() {
        try {
            Class<?> api = Class.forName(API_CLASS);
            Method byDimension = null;
            Method traits = null;
            for (Method method : api.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (byDimension == null && BY_DIMENSION.equals(method.getName())
                        && parameters.length == 1 && parameters[0] == ResourceKey.class
                        && Optional.class.isAssignableFrom(method.getReturnType())) {
                    byDimension = method;
                } else if (traits == null && TRAITS.equals(method.getName())
                        && parameters.length == 1) {
                    traits = method;
                }
            }
            if (byDimension == null || traits == null) {
                NeroColoniesCommon.LOGGER.info(
                        "[NeroColonies] Nerospace is installed but its planet API has a shape this "
                                + "build does not recognise; all dimensions are treated as breathable.");
                return null;
            }
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] Nerospace planet traits connected via {}.", API_CLASS);
            return new NerospaceBridge(byDimension, traits);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] Nerospace exposes no usable planet API ({}); all dimensions are "
                            + "treated as breathable.", e.getClass().getSimpleName());
            return null;
        }
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
    public boolean active() {
        return this.live;
    }

    private Traits lookup(@Nullable ResourceKey<Level> dimension) {
        if (dimension == null || !this.live) {
            return Traits.BREATHABLE;
        }
        return this.cache.computeIfAbsent(dimension, this::resolve);
    }

    private Traits resolve(ResourceKey<Level> dimension) {
        try {
            Object planet = this.byDimension.invoke(null, dimension);
            if (!(planet instanceof Optional<?> maybe) || maybe.isEmpty()) {
                return Traits.BREATHABLE; // Earth, or any dimension Nerospace does not own
            }
            Object planetTraits = this.traits.invoke(null, maybe.get());
            if (planetTraits == null) {
                return Traits.BREATHABLE;
            }
            boolean airless = Boolean.TRUE.equals(invoke(planetTraits, AIRLESS));
            Hazard hazard = toHazard(invoke(planetTraits, HAZARD));
            return new Traits(airless, hazard);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.live = false;
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] Nerospace planet-trait lookup failed ({}); every dimension is now "
                            + "treated as breathable for the rest of this session.",
                    e.getClass().getSimpleName());
            return Traits.BREATHABLE;
        }
    }

    @Nullable
    private static Object invoke(Object target, String accessor) {
        try {
            return target.getClass().getMethod(accessor).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Maps Nerospace's hazard enum onto ours by name, so a new value there is simply NONE here. */
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
