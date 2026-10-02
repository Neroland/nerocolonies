package za.co.neroland.nerocolonies.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the registry-free half of {@link BlockStateText}: {@code split} on a bare id, an id with
 * properties, stray whitespace and malformed input, and {@code blockId}, which only parses the id.
 * {@code block} and {@code resolve} look blocks up in the game's registries and are not covered here.
 */
class BlockStateTextTest {

    private static BlockStateText.Parts split(String text) {
        BlockStateText.Parts parts = BlockStateText.split(text);
        assertNotNull(parts, "no parts for: " + text);
        return parts;
    }

    // --- split ----------------------------------------------------------------------

    @Test
    @DisplayName("A bare id has no properties")
    void bareId() {
        BlockStateText.Parts parts = split("minecraft:oak_planks");
        assertEquals("minecraft:oak_planks", parts.blockId());
        assertTrue(parts.properties().isEmpty());
        // The id is kept as written: no namespace is added.
        assertEquals("stone", split("stone").blockId());
    }

    @Test
    @DisplayName("An id with properties splits into the id and a key/value map, in written order")
    void idWithProperties() {
        BlockStateText.Parts parts = split("minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]");
        assertEquals("minecraft:oak_stairs", parts.blockId());
        assertEquals(Map.of("facing", "north", "half", "bottom", "waterlogged", "false"), parts.properties());
        assertEquals(List.of("facing", "half", "waterlogged"), List.copyOf(parts.properties().keySet()));
    }

    @Test
    @DisplayName("Whitespace around the text, the id, keys and values is ignored")
    void whitespaceIsIgnored() {
        BlockStateText.Parts parts = split("  minecraft:oak_stairs [ facing = north ,  half=bottom ]  ");
        assertEquals("minecraft:oak_stairs", parts.blockId());
        assertEquals(Map.of("facing", "north", "half", "bottom"), parts.properties());
        assertEquals(split("minecraft:oak_stairs[facing=north,half=bottom]"), parts);
    }

    @Test
    @DisplayName("Empty brackets mean no properties")
    void emptyBrackets() {
        BlockStateText.Parts parts = split("minecraft:stone[]");
        assertEquals("minecraft:stone", parts.blockId());
        assertTrue(parts.properties().isEmpty());
    }

    @Test
    @DisplayName("A missing closing bracket is tolerated")
    void missingClosingBracket() {
        BlockStateText.Parts parts = split("minecraft:oak_stairs[facing=north,half=bottom");
        assertEquals("minecraft:oak_stairs", parts.blockId());
        assertEquals(Map.of("facing", "north", "half", "bottom"), parts.properties());
    }

    @Test
    @DisplayName("Malformed pairs are skipped and the well-formed ones survive")
    void malformedPairsAreSkipped() {
        // No '=', no key, a blank key, no value, an empty pair, then one good pair.
        BlockStateText.Parts parts = split("minecraft:oak_stairs[facing,=north, =south,half=,,waterlogged=true]");
        assertEquals("minecraft:oak_stairs", parts.blockId());
        assertEquals(Map.of("waterlogged", "true"), parts.properties());
    }

    @Test
    @DisplayName("A repeated key keeps its last value; only the first '=' splits a pair")
    void repeatedKeysAndExtraEquals() {
        assertEquals(Map.of("facing", "south"), split("minecraft:oak_stairs[facing=north,facing=south]").properties());
        assertEquals(Map.of("a", "b=c"), split("x[a=b=c]").properties());
    }

    @Test
    @DisplayName("Text after the closing bracket is ignored")
    void trailingTextIsIgnored() {
        BlockStateText.Parts parts = split("minecraft:stone[a=b] trailing");
        assertEquals("minecraft:stone", parts.blockId());
        assertEquals(Map.of("a", "b"), parts.properties());
    }

    @Test
    @DisplayName("No block id means no parts")
    void noBlockId() {
        assertNull(BlockStateText.split(null));
        assertNull(BlockStateText.split(""));
        assertNull(BlockStateText.split("   "));
        assertNull(BlockStateText.split("[facing=north]"));
        assertNull(BlockStateText.split("  [facing=north]"));
    }

    // --- blockId --------------------------------------------------------------------

    @Test
    @DisplayName("blockId parses the id part, with the default namespace when none is written")
    void blockIdParses() {
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "oak_stairs"),
                BlockStateText.blockId("minecraft:oak_stairs[facing=north]"));
        assertEquals(Identifier.fromNamespaceAndPath("nerocolonies", "farm_station"),
                BlockStateText.blockId(" nerocolonies:farm_station "));
        assertEquals(Identifier.withDefaultNamespace("stone"), BlockStateText.blockId("stone"));
    }

    @Test
    @DisplayName("blockId is null for missing text or an id that is not a valid identifier")
    void blockIdRejectsGarbage() {
        assertNull(BlockStateText.blockId(null));
        assertNull(BlockStateText.blockId(""));
        assertNull(BlockStateText.blockId("[facing=north]"));
        assertNull(BlockStateText.blockId("Not A Block!"));
    }
}
