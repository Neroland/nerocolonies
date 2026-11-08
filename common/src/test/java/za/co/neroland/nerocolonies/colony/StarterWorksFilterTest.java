package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.content.Blueprint;

/**
 * A founding-stage blueprint filtered to another colony type is not one of this colony's Starter Works, so it
 * can never hold the colony in Founding; an unfiltered one is everybody's.
 */
class StarterWorksFilterTest {

    private static Blueprint starter(String path, String typesJson) {
        String json = """
                { "stage": "founding", "palette": { "#": "minecraft:stone" }, "layers": [ [ "#" ] ]%s }
                """.formatted(typesJson.isEmpty() ? "" : ", \"colony_types\": " + typesJson);
        return Blueprint.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()
                .withId(Identifier.fromNamespaceAndPath("nerocolonies", path));
    }

    private static List<String> ids(List<Blueprint> blueprints) {
        return blueprints.stream().map(b -> b.id().getPath()).toList();
    }

    @Test
    @DisplayName("Starter Works filtered to another type are left out; unfiltered ones always count")
    void filteredStarterWorks() {
        Blueprint lodge = starter("lodge", "");
        Blueprint dome = starter("lunar_dome", "[ \"lunar_outpost\" ]");
        Blueprint settled = Blueprint.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{ \"stage\": \"settled\", \"palette\": { \"#\": \"minecraft:stone\" }, \"layers\": [ [ \"#\" ] ] }"))
                .getOrThrow().withId(Identifier.fromNamespaceAndPath("nerocolonies", "hall"));
        List<Blueprint> all = List.of(lodge, dome, settled);

        assertEquals(List.of("lodge"), ids(ColonyProgress.starterWorksFor(all, ColonyType.GENERIC)));
        assertEquals(List.of("lodge"), ids(ColonyProgress.starterWorksFor(all, ColonyType.CRYO_STATION)));
        assertEquals(List.of("lodge", "lunar_dome"),
                ids(ColonyProgress.starterWorksFor(all, ColonyType.LUNAR_OUTPOST)));
    }
}
