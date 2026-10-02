package za.co.neroland.nerocolonies.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.Colony;

class LenientCodecsTest {

    @Test
    @DisplayName("A bad entry is skipped; the rest of the list survives")
    void skipsBadEntries() {
        JsonArray raw = new JsonArray();
        raw.add(new JsonPrimitive(1));
        raw.add(new JsonPrimitive("not a number"));
        raw.add(new JsonPrimitive(3));
        List<Integer> decoded = LenientCodecs.list(Codec.INT, "test")
                .parse(JsonOps.INSTANCE, raw).getOrThrow();
        assertEquals(List.of(1, 3), decoded);
    }

    @Test
    @DisplayName("A malformed UUID no longer fails the whole set")
    void uuidSetSurvivesGarbage() {
        UUID good = UUID.fromString("00000000-0000-0000-0000-000000000001");
        JsonArray raw = new JsonArray();
        raw.add(new JsonPrimitive(good.toString()));
        raw.add(new JsonPrimitive("garbage"));
        assertEquals(java.util.Set.of(good),
                Colony.UUID_SET_CODEC.parse(JsonOps.INSTANCE, raw).getOrThrow());
    }

    @Test
    @DisplayName("Encoding is identical to listOf()")
    void encodesLikeListOf() {
        List<Integer> values = List.of(4, 5, 6);
        JsonElement lenient = LenientCodecs.list(Codec.INT, "test")
                .encodeStart(JsonOps.INSTANCE, values).getOrThrow();
        JsonElement strict = Codec.INT.listOf().encodeStart(JsonOps.INSTANCE, values).getOrThrow();
        assertEquals(strict, lenient);
        assertTrue(lenient.isJsonArray());
    }
}
