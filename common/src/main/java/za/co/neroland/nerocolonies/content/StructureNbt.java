package za.co.neroland.nerocolonies.content;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.DataResult;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Turns a vanilla structure file into a blueprint {@link Blueprint.Grid}.
 *
 * <p>The file is read directly (the documented structure format: {@code size}, {@code palette},
 * {@code blocks}) rather than through the structure manager. That keeps loading on the content
 * reload path, needs no level, and gives every cell individually so the building can still go up a
 * few blocks per cycle. Each distinct block state becomes one palette character, starting above
 * the ASCII range so it can never collide with a hand-written key. Air in the structure becomes a
 * clear cell; structure-void (cells the structure does not mention) stays a hole.
 *
 * <p>Block-entity contents in the file are ignored: a colony builds the shell, not the furniture's
 * inventory, and copying chest contents out of a datapack would be an item duplication path.
 */
public final class StructureNbt {

    /** First character used for generated palette keys (Latin Extended-A onwards). */
    private static final char FIRST_KEY = 'Ā';

    /** Hole character in the generated layout. */
    private static final char HOLE = ' ';

    private StructureNbt() {
    }

    /** Reads {@code data/<ns>/structure/<path>.nbt} and converts it. */
    public static DataResult<Blueprint.Grid> load(ResourceManager resources, Identifier structure) {
        Identifier file = Identifier.fromNamespaceAndPath(structure.getNamespace(),
                "structure/" + structure.getPath() + ".nbt");
        Optional<Resource> resource = resources.getResource(file);
        if (resource.isEmpty()) {
            return DataResult.error(() -> "structure file " + file + " not found");
        }
        try (InputStream in = resource.get().open()) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.create(64L * 1024L * 1024L));
            return convert(root);
        } catch (Exception e) {
            return DataResult.error(() -> "structure file " + file + " could not be read ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** Converts a parsed structure compound into a grid. Pure apart from NBT types; unit-tested. */
    public static DataResult<Blueprint.Grid> convert(CompoundTag root) {
        ListTag size = listOrEmpty(root, "size");
        int width = intOr(size, 0, 0);
        int height = intOr(size, 1, 0);
        int depth = intOr(size, 2, 0);
        if (width <= 0 || height <= 0 || depth <= 0) {
            return DataResult.error(() -> "structure has no size");
        }
        if (width > Blueprint.MAX_EXTENT || depth > Blueprint.MAX_EXTENT || height > Blueprint.MAX_HEIGHT) {
            return DataResult.error(() -> "structure is " + width + "x" + height + "x" + depth
                    + ", larger than the blueprint ceiling");
        }

        ListTag paletteTag = listOrEmpty(root, "palette");
        if (paletteTag.isEmpty()) {
            paletteTag = listOrEmpty(listOrEmpty(root, "palettes"), 0);
        }
        List<String> stateTexts = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            stateTexts.add(stateText(compoundOrEmpty(paletteTag, i)));
        }

        char[][][] cells = new char[height][depth][width];
        for (char[][] layer : cells) {
            for (char[] row : layer) {
                java.util.Arrays.fill(row, HOLE);
            }
        }
        Map<String, Character> keys = new HashMap<>();
        Map<Character, String> palette = new LinkedHashMap<>();
        ListTag blocks = listOrEmpty(root, "blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = compoundOrEmpty(blocks, i);
            ListTag pos = listOrEmpty(block, "pos");
            int x = intOr(pos, 0, -1);
            int y = intOr(pos, 1, -1);
            int z = intOr(pos, 2, -1);
            int state = intOr(block, "state", -1);
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth
                    || state < 0 || state >= stateTexts.size()) {
                continue;
            }
            String text = stateTexts.get(state);
            if (text.startsWith("minecraft:structure_void")) {
                continue; // a hole, by the structure format's own definition
            }
            Character key = keys.get(text);
            if (key == null) {
                key = (char) (FIRST_KEY + keys.size());
                keys.put(text, key);
                palette.put(key, text);
            }
            cells[y][z][x] = key;
        }

        List<List<String>> layers = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            List<String> rows = new ArrayList<>(depth);
            for (int z = 0; z < depth; z++) {
                rows.add(new String(cells[y][z]));
            }
            layers.add(rows);
        }
        return Blueprint.grid(palette, layers);
    }

    /** {@code {Name, Properties}} as a block-state string. */
    static String stateText(CompoundTag entry) {
        String name = stringOr(entry, "Name", "minecraft:air");
        CompoundTag properties = compoundOrEmpty(entry, "Properties");
        if (properties.isEmpty()) {
            return name;
        }
        List<String> keys = new ArrayList<>(keys(properties));
        java.util.Collections.sort(keys);
        StringBuilder text = new StringBuilder(name).append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(keys.get(i)).append('=').append(stringOr(properties, keys.get(i), ""));
        }
        return text.append(']').toString();
    }

    // --- NBT reads, one copy across Minecraft lines -------------------------------------------
    // 1.21.5 gave CompoundTag/ListTag their getXOr / getXOrEmpty readers; before it the same lookups
    // return 0 / empty on a missing or mistyped entry, so the defaults are applied by hand here.

    private static ListTag listOrEmpty(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getListOrEmpty(key);
        //?} else {
        /*return tag.get(key) instanceof ListTag list ? list : new ListTag();
        *///?}
    }

    private static ListTag listOrEmpty(ListTag list, int index) {
        //? if >=1.21.5 {
        return list.getListOrEmpty(index);
        //?} else {
        /*return index < list.size() && list.get(index) instanceof ListTag inner ? inner : new ListTag();
        *///?}
    }

    private static CompoundTag compoundOrEmpty(CompoundTag tag, String key) {
        //? if >=1.21.5 {
        return tag.getCompoundOrEmpty(key);
        //?} else {
        /*return tag.get(key) instanceof CompoundTag inner ? inner : new CompoundTag();
        *///?}
    }

    private static CompoundTag compoundOrEmpty(ListTag list, int index) {
        //? if >=1.21.5 {
        return list.getCompoundOrEmpty(index);
        //?} else {
        /*return index < list.size() && list.get(index) instanceof CompoundTag inner ? inner : new CompoundTag();
        *///?}
    }

    private static int intOr(CompoundTag tag, String key, int fallback) {
        //? if >=1.21.5 {
        return tag.getIntOr(key, fallback);
        //?} else {
        /*return tag.get(key) instanceof net.minecraft.nbt.NumericTag number ? number.getAsInt() : fallback;
        *///?}
    }

    private static int intOr(ListTag list, int index, int fallback) {
        //? if >=1.21.5 {
        return list.getIntOr(index, fallback);
        //?} else {
        /*return index < list.size() && list.get(index) instanceof net.minecraft.nbt.NumericTag number
                ? number.getAsInt() : fallback;
        *///?}
    }

    private static String stringOr(CompoundTag tag, String key, String fallback) {
        //? if >=1.21.5 {
        return tag.getStringOr(key, fallback);
        //?} else {
        /*return tag.get(key) instanceof net.minecraft.nbt.StringTag text ? text.getAsString() : fallback;
        *///?}
    }

    private static java.util.Set<String> keys(CompoundTag tag) {
        //? if >=1.21.5 {
        return tag.keySet();
        //?} else {
        /*return tag.getAllKeys();
        *///?}
    }
}
