package za.co.neroland.nerocolonies.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Everything NeroColonies is allowed to know about Nerospace, expressed in NeroColonies' own types.
 *
 * <p>Nerospace is a <b>soft dependency</b>. It is not on the compile classpath, it is not required
 * by any manifest, and exactly one class in this mod ever names a Nerospace type
 * ({@code compat.nerospace.NerospaceBridge}, reflectively). Everything else — the oxygen generator,
 * life support, the morale hazard term — talks to this façade, so removing Nerospace changes one
 * implementation and nothing else.
 *
 * <h2>Fallback semantics</h2>
 *
 * <p>{@link #NONE} is the implementation used when Nerospace is absent: <b>every dimension is
 * breathable and no dimension is hazardous</b>. That is the Earth-only colony experience — life
 * support machinery is still buildable and still runs, it simply has nothing to hold back. It is
 * deliberately not "assume airless": a mod that made vanilla Minecraft unbreathable because a
 * different mod was <em>not</em> installed would be indefensible.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Nerospace's {@code world.OxygenFieldManager} — per-block breathability — is <b>not</b> on its
 * supported API surface and is not consulted from anywhere in NeroColonies. Colonist life support is
 * our own colony-level system precisely because Nerospace's oxygen path is {@code Player}-typed and
 * has no NPC route. If a future per-block check is ever needed it goes behind this same façade with
 * an explicit unsupported-coupling note and a wiki entry, never as a direct call.
 */
public interface NerospaceCompat {

    /** Environmental hazard classes NeroColonies models. Mirrors Nerospace's own three by value. */
    enum Hazard {
        NONE,
        HEAT,
        COLD
    }

    /** Whether this dimension has no breathable atmosphere. */
    boolean airless(ResourceKey<Level> dimension);

    /** The environmental hazard of this dimension. */
    Hazard hazard(ResourceKey<Level> dimension);

    /** Whether this implementation is actually talking to Nerospace. */
    boolean active();

    /** The no-Nerospace implementation: everywhere breathable, nowhere hazardous. */
    NerospaceCompat NONE = new NerospaceCompat() {

        @Override
        public boolean airless(ResourceKey<Level> dimension) {
            return false;
        }

        @Override
        public Hazard hazard(ResourceKey<Level> dimension) {
            return Hazard.NONE;
        }

        @Override
        public boolean active() {
            return false;
        }
    };
}
