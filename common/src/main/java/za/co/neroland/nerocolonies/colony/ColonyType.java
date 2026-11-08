package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;

import org.jetbrains.annotations.Nullable;

/**
 * What kind of colony this is: {@link #GENERIC} everywhere, or one of six planetary profiles when the
 * colony was founded on a Nerospace planet that names one.
 *
 * <h2>Derived once, at founding</h2>
 *
 * <p>The type is read from Nerospace's planet traits ({@code PlanetTraits.colonyProfile()}) through the
 * {@link za.co.neroland.nerocolonies.compat.NerospaceCompat} façade when the beacon founds the colony, and
 * never again. A colony that existed before the type did loads as {@link #GENERIC} and stays that way:
 * nothing re-derives a type on load. Without Nerospace every colony is {@link #GENERIC}.
 *
 * <h2>What it changes</h2>
 *
 * <p>Blueprints, jobs, research nodes and export entries may carry an optional {@code colony_types}
 * filter. Content with no filter is available to every colony; content with one is available only to
 * colonies of a listed type. An outpost has no type of its own: everything about it follows its parent
 * colony's type.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> a colony type is a property of a place, derived from a dimension. It is
 * not personal data.
 */
public enum ColonyType {
    GENERIC("generic"),
    LUNAR_OUTPOST("lunar_outpost"),
    MARTIAN_SETTLEMENT("martian_settlement"),
    EMBER_FORGE("ember_forge"),
    CRYO_STATION("cryo_station"),
    CANOPY_COMMUNE("canopy_commune"),
    TWILIGHT_ENCLAVE("twilight_enclave");

    /** The namespace Nerospace's colony profile identifiers use. */
    public static final String NEROSPACE_NAMESPACE = "nerospace";

    /**
     * Stored as its lower-case name. An unknown or blank string decodes as {@link #GENERIC}, never as a
     * failure, so a save written by a newer build with a type this one does not know still loads.
     */
    public static final Codec<ColonyType> CODEC = Codec.STRING.xmap(ColonyType::byNameOrGeneric,
            ColonyType::serializedName);

    private final String serializedName;

    ColonyType(String serializedName) {
        this.serializedName = serializedName;
    }

    /** The stored and JSON name, e.g. {@code lunar_outpost}. */
    public String serializedName() {
        return this.serializedName;
    }

    /** Whether this is a planetary profile rather than {@link #GENERIC}. */
    public boolean planetary() {
        return this != GENERIC;
    }

    /** The translation key for this type's display name. */
    public String translationKey() {
        return "colony_type.nerocolonies." + this.serializedName;
    }

    /** The type with this stored name (case-insensitive), or empty for an unknown or blank name. */
    public static Optional<ColonyType> byName(@Nullable String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (ColonyType type : values()) {
            if (type.serializedName.equals(wanted)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** The type with this stored name, or {@link #GENERIC}. */
    public static ColonyType byNameOrGeneric(@Nullable String name) {
        return byName(name).orElse(GENERIC);
    }

    /**
     * Maps a Nerospace colony profile identifier ({@code nerospace:<path>}) to a type. The <b>full</b>
     * identifier is compared: the six known Nerospace profiles map to their type, and anything else (another
     * namespace, {@code nerospace:generic}, a profile added after this build, a blank or malformed string) is
     * {@link #GENERIC}. Never throws. Pure, so it is unit-tested without Nerospace.
     */
    public static ColonyType fromNerospaceProfile(@Nullable String profile) {
        if (profile == null) {
            return GENERIC;
        }
        String trimmed = profile.trim();
        String prefix = NEROSPACE_NAMESPACE + ":";
        if (!trimmed.startsWith(prefix)) {
            return GENERIC;
        }
        String path = trimmed.substring(prefix.length());
        for (ColonyType type : values()) {
            if (type.planetary() && type.serializedName.equals(path)) {
                return type;
            }
        }
        return GENERIC;
    }

    /**
     * A {@code colony_types} filter split into the names this build knows (normalised to their stored form,
     * duplicates removed, order kept) and the ones it does not.
     *
     * @param known   the recognised type names
     * @param unknown the names that are not a type, as written
     */
    public record FilterCheck(List<String> known, List<String> unknown) {

        public FilterCheck {
            known = List.copyOf(known);
            unknown = List.copyOf(unknown);
        }

        /** A non-empty filter that named no known type at all: it can match no colony. */
        public boolean matchesNothing() {
            return this.known.isEmpty() && !this.unknown.isEmpty();
        }
    }

    /** Splits a declared filter into known and unknown names. Pure; unit-tested. */
    public static FilterCheck checkFilter(@Nullable Collection<String> declared) {
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        if (declared != null) {
            for (String name : declared) {
                Optional<ColonyType> type = byName(name);
                if (type.isPresent()) {
                    if (!known.contains(type.get().serializedName)) {
                        known.add(type.get().serializedName);
                    }
                } else {
                    unknown.add(String.valueOf(name));
                }
            }
        }
        return new FilterCheck(known, unknown);
    }

    /**
     * The shared {@code colony_types} rule: an empty filter admits every colony; otherwise the colony's type
     * must be listed by its stored name. Names that are not a known type never match (the loader reports
     * them).
     */
    public static boolean admits(@Nullable Collection<String> filter, @Nullable ColonyType type) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        ColonyType actual = type == null ? GENERIC : type;
        for (String name : filter) {
            if (name != null && actual.serializedName.equals(name.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
