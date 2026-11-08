package za.co.neroland.nerocolonies.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyType;

/**
 * The Nerospace bridge's profile mapping ({@link ColonyType#fromNerospaceProfile}, which the bridge calls with
 * {@code PlanetTraits.colonyProfile().toString()}), against the planet table in the Part B design, plus the
 * no-Nerospace façade.
 */
class NerospaceProfileMappingTest {

    @Test
    @DisplayName("Each planet's colony profile maps to its colony type")
    void planetTable() {
        Map<String, ColonyType> table = Map.of(
                "nerospace:lunar_outpost", ColonyType.LUNAR_OUTPOST,           // Luna
                "nerospace:martian_settlement", ColonyType.MARTIAN_SETTLEMENT, // Mars
                "nerospace:ember_forge", ColonyType.EMBER_FORGE,               // Cindara
                "nerospace:cryo_station", ColonyType.CRYO_STATION,             // Glacira
                "nerospace:canopy_commune", ColonyType.CANOPY_COMMUNE,         // Greenxertz
                "nerospace:twilight_enclave", ColonyType.TWILIGHT_ENCLAVE,     // Nyxara
                "nerospace:generic", ColonyType.GENERIC);                      // the orbital station
        table.forEach((profile, type) -> assertEquals(type, ColonyType.fromNerospaceProfile(profile), profile));
    }

    @Test
    @DisplayName("Anything unknown is GENERIC, and nothing throws")
    void unknownIsGeneric() {
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile("nerospace:dyson_ring"));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile("othermod:lunar_outpost"));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile("lunar_outpost"));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile("nerospace:"));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile(""));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile("   "));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile(null));
        assertEquals(ColonyType.GENERIC, ColonyType.fromNerospaceProfile("NEROSPACE:LUNAR_OUTPOST"));
    }

    @Test
    @DisplayName("Without Nerospace every dimension founds a GENERIC colony")
    void noneIsGeneric() {
        assertEquals(ColonyType.GENERIC, NerospaceCompat.NONE.colonyProfile(null));
    }

    @Test
    @DisplayName("Stored names round-trip; an unknown stored name reads as GENERIC")
    void storedNames() {
        for (ColonyType type : ColonyType.values()) {
            assertEquals(type, ColonyType.byNameOrGeneric(type.serializedName()));
        }
        assertEquals(ColonyType.GENERIC, ColonyType.byNameOrGeneric("plasma_city"));
    }
}
