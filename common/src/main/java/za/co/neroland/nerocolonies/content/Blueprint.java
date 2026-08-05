package za.co.neroland.nerocolonies.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * One structure a colony can build for itself, loaded from
 * {@code data/<ns>/nerocolonies/blueprints/<path>.json}.
 *
 * <h2>The format, and why it is this format</h2>
 *
 * <pre>{@code
 * {
 *   "name": "blueprint.nerocolonies.habitat_pod",
 *   "category": "housing",
 *   "priority": 10,
 *   "max": 6,
 *   "research": "nerocolonies:habitation/shelter",
 *   "palette": { "#": "minecraft:iron_block", "H": "nerocolonies:habitat_pod" },
 *   "layers": [
 *     [ "###", "#H#", "###" ],
 *     [ "###", "#.#", "###" ]
 *   ],
 *   "materials": [
 *     { "item": "nerocolonies:habitat_pod", "count": 1 },
 *     { "tag": "c:ingots/iron", "count": 8 }
 *   ]
 * }
 * }</pre>
 *
 * <p>A character grid rather than a structure NBT, on purpose. A blueprint is meant to be written by
 * hand in a text editor by somebody who has never opened a structure block: {@code layers} is a list
 * of horizontal slices <b>bottom-up</b>, each slice a list of rows running north→south (+Z), each row
 * a string running west→east (+X). {@code palette} maps a character to a block id;
 * <b>any character not in the palette is a hole</b> — nothing is placed and whatever is there is left
 * alone — which is what {@code '.'} and {@code ' '} conventionally mean in the shipped content.
 *
 * <p>Every row is padded to the widest row in the blueprint, so a ragged grid is a shape rather than
 * an error. Blocks are placed in their {@link Block#defaultBlockState()}: a blueprint describes a
 * layout, not block states, which is the whole reason it stays hand-authorable.
 *
 * <h2>Materials</h2>
 *
 * <p>{@code materials} reuses {@link ItemTarget}, so a blueprint can ask for a tag
 * ({@code c:ingots/iron}) and be satisfied by any mod's iron. The list is what a player <em>may</em>
 * supply to build at full speed — it is never a hard requirement (see {@code colony/Construction}),
 * so a blueprint naming an item from an uninstalled mod is a slow build, not a broken colony.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing here is player-shaped. A blueprint is content.
 */
public record Blueprint(
        Identifier id,
        String name,
        Category category,
        int priority,
        int max,
        Optional<Identifier> research,
        Map<Character, Identifier> palette,
        List<String> layout,
        List<ItemTarget> materials,
        int width,
        int depth,
        int height) {

    /** Stands in until the loader stamps the file-derived id on. */
    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_blueprint");

    /** Hard cap on a blueprint's footprint in each horizontal direction. */
    public static final int MAX_EXTENT = 16;

    /** Hard cap on a blueprint's height. */
    public static final int MAX_HEIGHT = 12;

    /**
     * What a structure is for. Used for the housing-pressure rule (a colony only starts another
     * habitat when the one it has is nearly full) and for nothing else in 0.1.0 — an unknown value
     * decodes to {@link Category#OTHER} rather than failing, so a newer pack never bricks an older
     * jar.
     */
    public enum Category {

        /** Raises housing capacity. Gated on the colony actually needing room. */
        HOUSING,

        /** Food production. */
        FARM,

        /** Refining, fabrication, power. */
        INDUSTRY,

        /** Colony storage access. */
        STORAGE,

        /** Oxygen and atmosphere. */
        LIFE_SUPPORT,

        /** Anything else, and the fallback for an unrecognised value. */
        OTHER;

        static Category parse(String raw) {
            if (raw == null) {
                return OTHER;
            }
            for (Category category : values()) {
                if (category.name().equalsIgnoreCase(raw)) {
                    return category;
                }
            }
            return OTHER;
        }

        String serialised() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A single-character key. Longer strings are a decode error rather than a silent truncation. */
    private static final Codec<Character> CHARACTER_CODEC = Codec.STRING.comapFlatMap(
            text -> text.length() == 1
                    ? DataResult.success(text.charAt(0))
                    : DataResult.error(() -> "a palette key must be exactly one character: '" + text + "'"),
            String::valueOf);

    private static final Codec<Category> CATEGORY_CODEC = Codec.STRING
            .xmap(Category::parse, Category::serialised);

    /** The on-disk shape, before the layout is normalised. */
    private record Raw(String name, Category category, int priority, int max,
            Optional<Identifier> research, Map<Character, Identifier> palette,
            List<List<String>> layers, List<ItemTarget> materials) {
    }

    private static final Codec<Raw> RAW_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Raw::name),
            CATEGORY_CODEC.optionalFieldOf("category", Category.OTHER).forGetter(Raw::category),
            Codec.INT.optionalFieldOf("priority", 100).forGetter(Raw::priority),
            Codec.INT.optionalFieldOf("max", 4).forGetter(Raw::max),
            Identifier.CODEC.optionalFieldOf("research").forGetter(Raw::research),
            Codec.unboundedMap(CHARACTER_CODEC, Identifier.CODEC).fieldOf("palette")
                    .forGetter(Raw::palette),
            Codec.STRING.listOf().listOf().fieldOf("layers").forGetter(Raw::layers),
            ItemTarget.CODEC.listOf().optionalFieldOf("materials", List.of())
                    .forGetter(Raw::materials)
    ).apply(inst, Raw::new));

    public static final Codec<Blueprint> CODEC = RAW_CODEC.comapFlatMap(
            Blueprint::normalise, Blueprint::toRaw);

    /**
     * Turns the ragged authored grid into a rectangular one and works out the extents. A blueprint
     * bigger than {@link #MAX_EXTENT} / {@link #MAX_HEIGHT}, or with no layers at all, is rejected
     * here — the loader reports it and drops it, exactly like any other bad content.
     */
    private static DataResult<Blueprint> normalise(Raw raw) {
        if (raw.layers().isEmpty()) {
            return DataResult.error(() -> "a blueprint needs at least one layer");
        }
        int height = raw.layers().size();
        if (height > MAX_HEIGHT) {
            return DataResult.error(() -> "a blueprint may be at most " + MAX_HEIGHT + " layers tall");
        }
        int depth = 0;
        int width = 0;
        for (List<String> layer : raw.layers()) {
            depth = Math.max(depth, layer.size());
            for (String row : layer) {
                width = Math.max(width, row.length());
            }
        }
        if (depth == 0 || width == 0) {
            return DataResult.error(() -> "a blueprint needs at least one non-empty row");
        }
        if (depth > MAX_EXTENT || width > MAX_EXTENT) {
            return DataResult.error(
                    () -> "a blueprint may be at most " + MAX_EXTENT + " blocks in each direction");
        }

        // One flat list of height*depth padded rows, so a cell lookup is arithmetic rather than three
        // bounds checks. Missing rows become blanks: a ragged grid is a shape, not an error.
        List<String> flat = new ArrayList<>(height * depth);
        for (List<String> layer : raw.layers()) {
            for (int z = 0; z < depth; z++) {
                String row = z < layer.size() ? layer.get(z) : "";
                flat.add(row.length() >= width ? row.substring(0, width) : pad(row, width));
            }
        }
        return DataResult.success(new Blueprint(UNNAMED, raw.name(), raw.category(), raw.priority(),
                raw.max(), raw.research(), Map.copyOf(raw.palette()), List.copyOf(flat),
                List.copyOf(raw.materials()), width, depth, height));
    }

    private static String pad(String row, int width) {
        StringBuilder padded = new StringBuilder(width).append(row);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    private Raw toRaw() {
        List<List<String>> layers = new ArrayList<>(this.height);
        for (int y = 0; y < this.height; y++) {
            layers.add(this.layout.subList(y * this.depth, (y + 1) * this.depth));
        }
        return new Raw(this.name, this.category, this.priority, this.max, this.research,
                new LinkedHashMap<>(this.palette), layers, this.materials);
    }

    public Blueprint {
        priority = Math.clamp(priority, 0, 10_000);
        max = Math.clamp(max, 0, 64);
    }

    public Blueprint withId(Identifier newId) {
        return new Blueprint(newId, name, category, priority, max, research, palette, layout,
                materials, width, depth, height);
    }

    // --- the layout ---------------------------------------------------------

    /**
     * The block to place at a cell, or {@code null} for a hole (a character with no palette entry, or
     * a palette entry naming a block that is not registered in this launch).
     *
     * @param x 0..{@link #width}-1, west→east
     * @param y 0..{@link #height}-1, bottom-up
     * @param z 0..{@link #depth}-1, north→south
     */
    public Block blockAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.height || z >= this.depth) {
            return null;
        }
        char key = this.layout.get(y * this.depth + z).charAt(x);
        Identifier blockId = this.palette.get(key);
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(blockId);
    }

    /**
     * The cells that actually place a block, in build order: bottom layer first, then north→south,
     * then west→east. Deterministic, so a saved cursor into this list means the same cell after a
     * reload as it did before one.
     *
     * <p>Offsets are relative to the structure's minimum corner. Recomputed on demand rather than
     * cached on the record: it is a handful of characters, and a blueprint is only planned once per
     * structure.
     */
    public List<BlockPos> buildOrder() {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (blockAt(x, y, z) != null) {
                        cells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    /** How many blocks this blueprint places. Zero means it is inert and the loader drops it. */
    public int blockCount() {
        int count = 0;
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (blockAt(x, y, z) != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Every palette entry that names a block this launch does not have. Log-safe ids only. */
    public List<Identifier> missingBlocks() {
        List<Identifier> missing = new ArrayList<>();
        for (Identifier blockId : this.palette.values()) {
            if (!BuiltInRegistries.BLOCK.containsKey(blockId) && !missing.contains(blockId)) {
                missing.add(blockId);
            }
        }
        return missing;
    }

    /**
     * The translation key for this blueprint's display name: its own {@code name} field when it has
     * one, otherwise one derived from the id, so a pack that omits the field still gets something
     * translatable rather than a raw resource id on screen.
     */
    public String nameKey() {
        if (!this.name.isBlank()) {
            return this.name;
        }
        return "blueprint." + this.id.getNamespace() + "." + this.id.getPath().replace('/', '.');
    }
}
