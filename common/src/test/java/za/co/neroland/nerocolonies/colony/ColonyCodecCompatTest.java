package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [COMPAT] for the 0.7 colony type: a colony saved by 0.6.1 (no {@code colony_type} key) loads as GENERIC with
 * every other field identical, round-trips to the same flat object, and an unknown or malformed type never
 * drops a colony.
 */
class ColonyCodecCompatTest {

    private static final String ID = "00000000-0000-0000-0000-00000000c0de";
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String ALLY = "00000000-0000-0000-0000-000000000002";
    private static final String OUTPOST = "00000000-0000-0000-0000-000000000003";

    /** A colony exactly as 0.6.1 wrote it: all sixteen keys, every value off its default, no colony_type. */
    private static JsonObject saved061() {
        return JsonParser.parseString("""
                {
                  "id": "%s",
                  "name": "Tranquility",
                  "dimension": "nerospace:luna",
                  "beacon": [10, 64, -20],
                  "claim_radius": 56,
                  "owner": "%s",
                  "access": ["%s"],
                  "created_at": 1200,
                  "last_tick": 4800,
                  "morale": 72.5,
                  "population": 9,
                  "housing_capacity": 12,
                  "research": ["nerocolonies:habitation/shelter"],
                  "life_support_ok": false,
                  "food_stock": 31,
                  "outposts": ["%s"]
                }
                """.formatted(ID, OWNER, ALLY, OUTPOST)).getAsJsonObject();
    }

    private static Colony decode(JsonElement json) {
        return Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    @Test
    @DisplayName("A 0.6.1 colony with no colony_type decodes as GENERIC, every other field identical")
    void oldColonyIsGeneric() {
        Colony colony = decode(saved061());
        assertEquals(ColonyType.GENERIC, colony.colonyType());
        assertEquals(UUID.fromString(ID), colony.colonyId());
        assertEquals("Tranquility", colony.name());
        assertEquals("nerospace:luna", colony.dimension().identifier().toString());
        assertEquals(new BlockPos(10, 64, -20), colony.beaconPos());
        assertEquals(56, colony.claimRadius());
        assertEquals(UUID.fromString(OWNER), colony.ownerId());
        assertEquals(Set.of(UUID.fromString(ALLY)), colony.accessList());
        assertEquals(1200L, colony.createdAt());
        assertEquals(4800L, colony.lastTick());
        assertEquals(72.5D, colony.morale());
        assertEquals(9, colony.population());
        assertEquals(12, colony.housingCapacity());
        assertEquals(Set.of("nerocolonies:habitation/shelter"), colony.researchUnlocked());
        assertFalse(colony.lifeSupportOk());
        assertEquals(31, colony.foodStock());
        assertEquals(Set.of(UUID.fromString(OUTPOST)), colony.outpostIds());
    }

    @Test
    @DisplayName("A GENERIC colony round-trips to the identical 0.6.1 object (no new key written)")
    void genericRoundTripIsByteIdentical() {
        JsonObject original = saved061();
        JsonElement encoded = Colony.CODEC.encodeStart(JsonOps.INSTANCE, decode(original)).getOrThrow();
        assertEquals(original, encoded);
        assertEquals(decode(original), decode(encoded));
    }

    @Test
    @DisplayName("A typed colony stores colony_type as one more flat key and round-trips")
    void typedRoundTrip() {
        Colony typed = decode(saved061()).withColonyType(ColonyType.MARTIAN_SETTLEMENT);
        JsonObject encoded = Colony.CODEC.encodeStart(JsonOps.INSTANCE, typed).getOrThrow().getAsJsonObject();
        assertEquals(new JsonPrimitive("martian_settlement"), encoded.get("colony_type"));
        JsonObject withoutType = encoded.deepCopy();
        withoutType.remove("colony_type");
        assertEquals(saved061(), withoutType, "every 0.6 key keeps its name, place and value");
        assertEquals(typed, decode(encoded));
    }

    @Test
    @DisplayName("An unknown or malformed colony_type decodes as GENERIC and keeps the colony")
    void lenientType() {
        JsonObject unknown = saved061();
        unknown.addProperty("colony_type", "plasma_city");
        assertEquals(ColonyType.GENERIC, decode(unknown).colonyType());

        JsonObject malformed = saved061();
        malformed.add("colony_type", new JsonArray());
        Colony kept = decode(malformed);
        assertEquals(ColonyType.GENERIC, kept.colonyType());
        assertEquals("Tranquility", kept.name());

        JsonObject numeric = saved061();
        numeric.addProperty("colony_type", 5);
        assertEquals(ColonyType.GENERIC, decode(numeric).colonyType());
    }

    @Test
    @DisplayName("Copy-with helpers keep the colony type")
    void helpersKeepType() {
        Colony typed = decode(saved061()).withColonyType(ColonyType.CRYO_STATION);
        assertEquals(ColonyType.CRYO_STATION, typed.withMorale(10.0D).colonyType());
        assertEquals(ColonyType.CRYO_STATION, typed.withOwner(null).colonyType());
        assertEquals(ColonyType.CRYO_STATION, typed.withOutposts(Set.of()).colonyType());
        assertEquals(ColonyType.CRYO_STATION, typed.withResearch(Set.of()).colonyType());
    }

    @Test
    @DisplayName("A terraform disc counts as inside a claim only when all of it is")
    void containsDisc() {
        Colony colony = decode(saved061()); // beacon (10, -20), claim radius 56
        assertTrue(colony.containsDisc(new BlockPos(10, 70, -20), 56));
        assertTrue(colony.containsDisc(new BlockPos(30, 70, -20), 36));
        assertFalse(colony.containsDisc(new BlockPos(30, 70, -20), 37), "crosses the east edge");
        assertFalse(colony.containsDisc(new BlockPos(10, 70, 40), 1), "centre outside the claim");
        assertFalse(colony.containsDisc(new BlockPos(10, 70, -20), 57), "larger than the claim");
        assertFalse(colony.containsDisc(null, 1));
    }
}
