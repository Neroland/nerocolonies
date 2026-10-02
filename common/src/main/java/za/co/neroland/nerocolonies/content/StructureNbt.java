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
        ListTag size = root.getListOrEmpty("size");
        int width = size.getIntOr(0, 0);
        int height = size.getIntOr(1, 0);
        int depth = size.getIntOr(2, 0);
        if (width <= 0 || height <= 0 || depth <= 0) {
            return DataResult.error(() -> "structure has no size");
        }
        if (width > Blueprint.MAX_EXTENT || depth > Blueprint.MAX_EXTENT || height > Blueprint.MAX_HEIGHT) {
            return DataResult.error(() -> "structure is " + width + "x" + height + "x" + depth
                    + ", larger than the blueprint ceiling");
        }

        ListTag paletteTag = root.getListOrEmpty("palette");
        if (paletteTag.isEmpty()) {
            paletteTag = root.getListOrEmpty("palettes").getListOrEmpty(0);
        }
        List<String> stateTexts = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            stateTexts.add(stateText(paletteTag.getCompoundOrEmpty(i)));
        }

        char[][][] cells = new char[height][depth][width];
        for (char[][] layer : cells) {
            for (char[] row : layer) {
                java.util.Arrays.fill(row, HOLE);
            }
        }
        Map<String, Character> keys = new HashMap<>();
        Map<Character, String> palette = new LinkedHashMap<>();
        ListTag blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            ListTag pos = block.getListOrEmpty("pos");
            int x = pos.getIntOr(0, -1);
            int y = pos.getIntOr(1, -1);
            int z = pos.getIntOr(2, -1);
            int state = block.getIntOr("state", -1);
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
        String name = entry.getStringOr("Name", "minecraft:air");
        CompoundTag properties = entry.getCompoundOrEmpty("Properties");
        if (properties.isEmpty()) {
            return name;
        }
        List<String> keys = new ArrayList<>(properties.keySet());
        java.util.Collections.sort(keys);
        StringBuilder text = new StringBuilder(name).append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(keys.get(i)).append('=').append(properties.getStringOr(keys.get(i), ""));
        }
        return text.append(']').toString();
    }
}
