package za.co.neroland.nerocolonies.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyType;

/**
 * The six shipped planetary profiles: every file parses with its codec, each profile has at least two
 * blueprints, two jobs and one research node, and every one of them is filtered to its own type only.
 */
class PlanetaryProfilesTest {

    private static final String ROOT = "data/nerocolonies/nerocolonies/";

    private record Profile(ColonyType type, List<String> blueprints, List<String> jobs, List<String> research,
            List<String> exports) {
    }

    private static final List<Profile> PROFILES = List.of(
            new Profile(ColonyType.LUNAR_OUTPOST,
                    List.of("lunar_regolith_dome", "lunar_sinter_kiln"),
                    List.of("lunar_regolith_sintering", "lunar_helium3_extraction"),
                    List.of("lunar_outpost"), List.of("lunar_helium3")),
            new Profile(ColonyType.MARTIAN_SETTLEMENT,
                    List.of("martian_storm_shelter", "martian_water_works"),
                    List.of("martian_water_extraction", "martian_hematite_smelting"),
                    List.of("martian_settlement"), List.of()),
            new Profile(ColonyType.EMBER_FORGE,
                    List.of("ember_sulfur_vats", "ember_forge_hall"),
                    List.of("ember_sulfur_refining", "ember_smelting"),
                    List.of("ember_forge"), List.of()),
            new Profile(ColonyType.CRYO_STATION,
                    List.of("cryo_methane_plant", "cryo_habitat"),
                    List.of("cryo_methane_cracking", "cryo_methane_power"),
                    List.of("cryo_station"), List.of()),
            new Profile(ColonyType.CANOPY_COMMUNE,
                    List.of("canopy_sap_platform", "canopy_biolab"),
                    List.of("canopy_sap_tapping", "canopy_amber_biotech"),
                    List.of("canopy_commune"), List.of()),
            new Profile(ColonyType.TWILIGHT_ENCLAVE,
                    List.of("twilight_observatory", "twilight_lens_workshop"),
                    List.of("twilight_voidglass_grinding", "twilight_lens_crafting"),
                    List.of("twilight_enclave"), List.of("twilight_voidglass_lenses")));

    private static JsonElement json(String path) throws Exception {
        try (InputStream in = PlanetaryProfilesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        }
    }

    private static <T> T decode(Codec<T> codec, String path) throws Exception {
        return codec.parse(JsonOps.INSTANCE, json(path)).getOrThrow();
    }

    private static void onlyFor(ColonyType type, java.util.function.Predicate<ColonyType> available, String what) {
        for (ColonyType other : ColonyType.values()) {
            assertEquals(other == type, available.test(other), what + " for " + other);
        }
    }

    @Test
    @DisplayName("Six profiles, each with two blueprints, two jobs and a research node of its own")
    void everyProfileIsComplete() throws Exception {
        assertEquals(6, PROFILES.size());
        for (Profile profile : PROFILES) {
            assertTrue(profile.blueprints().size() >= 2, profile.type() + " blueprints");
            assertTrue(profile.jobs().size() >= 2, profile.type() + " jobs");
            assertTrue(profile.research().size() >= 1, profile.type() + " research");
            for (String name : profile.blueprints()) {
                Blueprint blueprint = decode(Blueprint.CODEC, ROOT + "blueprints/planetary/" + name + ".json");
                assertTrue(blueprint.hasGrid(), name + " has a grid");
                assertEquals(1, blueprint.level(), name);
                onlyFor(profile.type(), blueprint::availableTo, name);
            }
            for (String name : profile.jobs()) {
                JobDefinition job = decode(JobDefinition.CODEC, ROOT + "jobs/planetary/" + name + ".json");
                assertFalse(job.outputs().isEmpty(), name + " produces something");
                onlyFor(profile.type(), job::availableTo, name);
            }
            for (String name : profile.research()) {
                // Research effects need their registry; the filter field is checked on the raw JSON.
                JsonElement node = json(ROOT + "research/planetary/" + name + ".json");
                assertEquals(List.of(profile.type().serializedName()),
                        Codec.STRING.listOf().parse(JsonOps.INSTANCE,
                                node.getAsJsonObject().get("colony_types")).getOrThrow(), name);
            }
            for (String name : profile.exports()) {
                ExportEntry entry = decode(ExportEntry.CODEC, ROOT + "exports/planetary/" + name + ".json");
                onlyFor(profile.type(), entry::availableTo, name);
            }
        }
    }

    @Test
    @DisplayName("The English lang file parses and names every type, blueprint and research node")
    void langKeys() throws Exception {
        com.google.gson.JsonObject lang = json("assets/nerocolonies/lang/en_us.json").getAsJsonObject();
        for (ColonyType type : ColonyType.values()) {
            assertTrue(lang.has(type.translationKey()), type.translationKey());
        }
        assertTrue(lang.has("message.nerocolonies.claim.founded_type"));
        assertTrue(lang.has("research.nerocolonies.branch.planetary"));
        for (Profile profile : PROFILES) {
            for (String name : profile.blueprints()) {
                assertTrue(lang.has("blueprint.nerocolonies.planetary." + name), name);
            }
            for (String name : profile.research()) {
                assertTrue(lang.has("research.nerocolonies.planetary." + name), name);
            }
        }
    }

    @Test
    @DisplayName("Planetary input tags parse and keep a plain Minecraft stand-in")
    void tagsHaveFallbacks() throws Exception {
        Map<String, String> fallbacks = Map.of(
                "regolith", "minecraft:gravel",
                "martian_ice", "minecraft:ice",
                "hematite", "minecraft:raw_iron",
                "sulfur", "minecraft:netherrack",
                "methane", "minecraft:packed_ice",
                "amber", "minecraft:honeycomb",
                "voidglass", "minecraft:obsidian");
        for (Map.Entry<String, String> entry : fallbacks.entrySet()) {
            JsonElement tag = json("data/nerocolonies/tags/item/planetary/" + entry.getKey() + ".json");
            boolean found = false;
            for (JsonElement value : tag.getAsJsonObject().getAsJsonArray("values")) {
                if (value.isJsonPrimitive() && entry.getValue().equals(value.getAsString())) {
                    found = true;
                }
            }
            assertTrue(found, entry.getKey() + " keeps " + entry.getValue());
        }
        // The two Nerospace-only tags parse too (they are empty without Nerospace, on purpose).
        json("data/nerocolonies/tags/item/planetary/helium3.json");
        json("data/nerocolonies/tags/item/planetary/voidglass_lens.json");
    }
}
