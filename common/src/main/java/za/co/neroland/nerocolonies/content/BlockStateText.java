package za.co.neroland.nerocolonies.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import org.jetbrains.annotations.Nullable;

/**
 * Block-state strings for blueprint palettes: {@code "minecraft:oak_stairs[facing=north,half=bottom]"}.
 *
 * <p>Parsed by hand against the block's own state definition rather than through the command
 * parser, so it needs no registry lookup object and behaves the same on every loader and Minecraft
 * version. An unknown property or value is ignored (the rest of the state still applies), because a
 * blueprint written for one version should degrade on another, not vanish. An unknown block resolves
 * to nothing and the cell is left empty, which is the long-standing palette rule.
 */
public final class BlockStateText {

    /** Parsed states, by their exact text. Cleared on every content reload. */
    private static final Map<String, Optional<BlockState>> CACHE = new ConcurrentHashMap<>();

    private BlockStateText() {
    }

    /** The parts of a block-state string, before any registry lookup. Pure; unit-tested. */
    public record Parts(String blockId, Map<String, String> properties) {
    }

    /**
     * Splits {@code id[key=value,...]} into its block id and property map. Whitespace around keys and
     * values is ignored; a missing closing bracket is tolerated.
     *
     * @return the parts, or {@code null} if the text has no block id
     */
    @Nullable
    public static Parts split(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        int open = trimmed.indexOf('[');
        String id = (open < 0 ? trimmed : trimmed.substring(0, open)).trim();
        if (id.isEmpty()) {
            return null;
        }
        Map<String, String> properties = new LinkedHashMap<>();
        if (open >= 0) {
            int close = trimmed.lastIndexOf(']');
            String body = trimmed.substring(open + 1, close > open ? close : trimmed.length());
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    properties.put(key, value);
                }
            }
        }
        return new Parts(id, properties);
    }

    /** The block a state string names, if registered. */
    @Nullable
    public static Block block(String text) {
        Parts parts = split(text);
        if (parts == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(parts.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    /** The block id a state string names (registered or not), for validation messages. */
    @Nullable
    public static Identifier blockId(String text) {
        Parts parts = split(text);
        return parts == null ? null : Identifier.tryParse(parts.blockId());
    }

    /** Resolves a state string to a block state, or empty if the block is not registered. */
    public static Optional<BlockState> resolve(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(text, BlockStateText::parse);
    }

    private static Optional<BlockState> parse(String text) {
        Block block = block(text);
        if (block == null) {
            return Optional.empty();
        }
        BlockState state = block.defaultBlockState();
        Parts parts = split(text);
        if (parts != null) {
            for (Map.Entry<String, String> entry : parts.properties().entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                if (property != null) {
                    state = withValue(state, property, entry.getValue());
                }
            }
        }
        return Optional.of(state);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property,
            String raw) {
        return property.getValue(raw).map(value -> state.setValue(property, value)).orElse(state);
    }

    /** Forgets parsed states (a reload may have changed what a string means). */
    public static void clearCache() {
        CACHE.clear();
    }
}
