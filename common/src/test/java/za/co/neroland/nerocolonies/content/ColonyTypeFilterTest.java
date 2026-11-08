package za.co.neroland.nerocolonies.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyType;

/** The optional {@code colony_types} filter on blueprints, jobs, research nodes and export entries. */
class ColonyTypeFilterTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("nerocolonies", "test");

    @Test
    @DisplayName("An empty or absent filter admits every colony type")
    void emptyAdmitsAll() {
        for (ColonyType type : ColonyType.values()) {
            assertTrue(ColonyType.admits(List.of(), type));
            assertTrue(ColonyType.admits(null, type));
        }
    }

    @Test
    @DisplayName("A filter admits exactly the listed types, case-insensitively")
    void filterAdmitsListed() {
        List<String> filter = List.of("lunar_outpost", "CRYO_STATION");
        assertTrue(ColonyType.admits(filter, ColonyType.LUNAR_OUTPOST));
        assertTrue(ColonyType.admits(filter, ColonyType.CRYO_STATION));
        assertFalse(ColonyType.admits(filter, ColonyType.GENERIC));
        assertFalse(ColonyType.admits(filter, ColonyType.MARTIAN_SETTLEMENT));
        assertFalse(ColonyType.admits(filter, null), "a missing type is GENERIC");
    }

    @Test
    @DisplayName("Unknown names are split out for the loader to report; only-unknown matches nothing")
    void checkFilter() {
        ColonyType.FilterCheck mixed = ColonyType.checkFilter(List.of("ember_forge", "plasma_city", "EMBER_FORGE"));
        assertEquals(List.of("ember_forge"), mixed.known());
        assertEquals(List.of("plasma_city"), mixed.unknown());
        assertFalse(mixed.matchesNothing());

        ColonyType.FilterCheck bad = ColonyType.checkFilter(List.of("plasma_city"));
        assertTrue(bad.matchesNothing());

        assertFalse(ColonyType.checkFilter(List.of()).matchesNothing());
    }

    @Test
    @DisplayName("A job's colony_types decodes, filters, and an old job round-trips without the key")
    void jobFilter() {
        JsonElement typed = JsonParser.parseString("""
                { "station": "nerocolonies:refinery_station",
                  "outputs": [ { "item": "minecraft:iron_ingot", "count": 1 } ],
                  "colony_types": [ "martian_settlement" ] }
                """);
        JobDefinition job = JobDefinition.CODEC.parse(JsonOps.INSTANCE, typed).getOrThrow();
        assertEquals(List.of("martian_settlement"), job.colonyTypes());
        assertTrue(job.availableTo(ColonyType.MARTIAN_SETTLEMENT));
        assertFalse(job.availableTo(ColonyType.GENERIC));

        JsonElement old = JsonParser.parseString("""
                { "station": "nerocolonies:farm_station",
                  "outputs": [ { "item": "minecraft:wheat", "count": 2 } ] }
                """);
        JobDefinition plain = JobDefinition.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertTrue(plain.colonyTypes().isEmpty());
        assertTrue(plain.availableTo(ColonyType.GENERIC));
        assertTrue(plain.availableTo(ColonyType.TWILIGHT_ENCLAVE));
        JsonObject encoded = JobDefinition.CODEC.encodeStart(JsonOps.INSTANCE, plain).getOrThrow()
                .getAsJsonObject();
        assertFalse(encoded.has("colony_types"), "an unfiltered job syncs exactly as in 0.6");
    }

    @Test
    @DisplayName("An export entry's colony_types decodes and filters")
    void exportFilter() {
        JsonElement json = JsonParser.parseString("""
                { "target": { "tag": "nerocolonies:planetary/helium3", "count": 1 },
                  "base_value": 9.0,
                  "colony_types": [ "lunar_outpost" ] }
                """);
        ExportEntry entry = ExportEntry.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertTrue(entry.availableTo(ColonyType.LUNAR_OUTPOST));
        assertFalse(entry.availableTo(ColonyType.CANOPY_COMMUNE));
        assertTrue(entry.withColonyTypes(List.of()).availableTo(ColonyType.CANOPY_COMMUNE));
    }

    @Test
    @DisplayName("A blueprint reads colony_types beside its sixteen 0.6 fields and round-trips")
    void blueprintFilter() {
        JsonElement json = JsonParser.parseString("""
                { "name": "blueprint.nerocolonies.test",
                  "category": "industry",
                  "stage": "settled",
                  "priority": 50,
                  "max": 2,
                  "palette": { "#": "minecraft:stone" },
                  "layers": [ [ "##", "##" ] ],
                  "colony_types": [ "canopy_commune" ] }
                """);
        Blueprint blueprint = Blueprint.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().withId(ID);
        assertEquals(List.of("canopy_commune"), blueprint.colonyTypes());
        assertEquals(50, blueprint.priority());
        assertEquals(2, blueprint.width());
        assertTrue(blueprint.availableTo(ColonyType.CANOPY_COMMUNE));
        assertFalse(blueprint.availableTo(ColonyType.GENERIC));

        JsonObject encoded = Blueprint.CODEC.encodeStart(JsonOps.INSTANCE, blueprint).getOrThrow()
                .getAsJsonObject();
        assertEquals(1, encoded.getAsJsonArray("colony_types").size(), "flat key, not nested");
        assertEquals(blueprint, Blueprint.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().withId(ID));
    }

    @Test
    @DisplayName("A research node filters by colony type; the unfiltered constructor admits all")
    void researchFilter() {
        ResearchNode typed = new ResearchNode(ID, "planetary", "", List.of(), List.of(), List.of(),
                List.of("twilight_enclave"));
        assertTrue(typed.availableTo(ColonyType.TWILIGHT_ENCLAVE));
        assertFalse(typed.availableTo(ColonyType.LUNAR_OUTPOST));
        ResearchNode plain = new ResearchNode(ID, "general", "", List.of(), List.of(), List.of());
        assertTrue(plain.availableTo(ColonyType.GENERIC));
    }
}
