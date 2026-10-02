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
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.ColonyStage;

/**
 * One structure a colony can build, loaded from {@code data/<ns>/nerocolonies/blueprints/<path>.json}.
 *
 * <h2>The format</h2>
 *
 * <pre>{@code
 * {
 *   "name": "blueprint.nerocolonies.neran_cottage",
 *   "category": "housing",
 *   "stage": "settled",
 *   "priority": 10,
 *   "max": 6,
 *   "level": 1,
 *   "upgrade_to": "nerocolonies:neran_cottage_2",
 *   "unlocks": [ "nerocolonies:farmer" ],
 *   "roles": [ "sleep" ],
 *   "capacity": { "housing": 2, "storage": 0, "jobs": 0 },
 *   "research": "nerocolonies:habitation/shelter",
 *   "palette": {
 *     "#": "minecraft:oak_planks",
 *     "S": "minecraft:oak_stairs[facing=north,half=bottom]",
 *     "_": "minecraft:air"
 *   },
 *   "layers": [ [ "###", "#_#", "###" ], [ "S_S", "___", "###" ] ],
 *   "materials": [ { "tag": "minecraft:planks", "count": 24 } ],
 *   "rotate": true
 * }
 * }</pre>
 *
 * <p>{@code layers} is a list of horizontal slices <b>bottom-up</b>, each a list of rows running
 * north→south (+Z), each row a string running west→east (+X). {@code palette} maps one character to a
 * <b>block-state string</b> (a bare id still works). Any character not in the palette is a hole:
 * nothing is placed and whatever is there is left alone. A character mapped to
 * {@code minecraft:air} is a <b>clear</b> cell: natural blocks there (see the
 * {@code nerocolonies:clearable} tag) are cleared before building, so an interior is actually open.
 *
 * <p>Instead of {@code palette}/{@code layers}, a blueprint may name a vanilla structure file with
 * {@code "structure": "<ns>:<path>"} ({@code data/<ns>/structure/<path>.nbt}). The structure's
 * blocks become the grid at load time, air included as clear cells, so large builds can be made
 * with a structure block and still go up a few blocks at a time.
 *
 * <p>{@code rotate} lets the planner turn the building so its front (the +Z, south face, where
 * doors conventionally go) faces the beacon. Block states turn with it.
 *
 * <p>{@code stage} is the colony stage the blueprint needs. Blueprints at {@code founding} are the
 * <b>Starter Works</b>: while the colony is founding they build only from player-supplied materials.
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
        Map<Character, String> palette,
        List<String> layout,
        List<ItemTarget> materials,
        int width,
        int depth,
        int height,
        ColonyStage stage,
        Optional<Identifier> upgradeTo,
        int level,
        List<Identifier> unlocks,
        Capacity capacity,
        List<String> roles,
        Optional<Identifier> structure,
        boolean rotate) {

    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_blueprint");

    /** Format ceiling on footprint, either axis. Servers cap autonomous builds lower by config. */
    public static final int MAX_EXTENT = 48;

    /** Format ceiling on height. */
    public static final int MAX_HEIGHT = 64;

    /** The palette value that marks a clear cell. */
    public static final String CLEAR = "minecraft:air";

    /** Broad kinds of building, used by the planner, the GUI and the link module. */
    public enum Category {
        HOUSING, FARM, INDUSTRY, STORAGE, LIFE_SUPPORT, CIVIC, DEFENCE, LANDMARK, OTHER;

        static Category parse(String raw) {
            if (raw == null) {
                return OTHER;
            }
            for (Category category : values()) {
                if (category.name().equalsIgnoreCase(raw.trim())) {
                    return category;
                }
            }
            return OTHER;
        }

        public String serialised() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a finished building offers, for display. Housing is still measured by the sweep. */
    public record Capacity(int housing, int storage, int jobs) {

        public static final Capacity NONE = new Capacity(0, 0, 0);

        public static final Codec<Capacity> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.INT.optionalFieldOf("housing", 0).forGetter(Capacity::housing),
                Codec.INT.optionalFieldOf("storage", 0).forGetter(Capacity::storage),
                Codec.INT.optionalFieldOf("jobs", 0).forGetter(Capacity::jobs)
        ).apply(inst, Capacity::new));

        public Capacity {
            housing = Math.max(0, housing);
            storage = Math.max(0, storage);
            jobs = Math.max(0, jobs);
        }
    }

    private static final Codec<Character> CHARACTER_CODEC = Codec.STRING.comapFlatMap(
            text -> text.length() == 1
                    ? DataResult.success(text.charAt(0))
                    : DataResult.error(() -> "a palette key must be exactly one character: '" + text + "'"),
            String::valueOf);

    private static final Codec<Category> CATEGORY_CODEC = Codec.STRING
            .xmap(Category::parse, Category::serialised);

    private static final Codec<ColonyStage> STAGE_CODEC = Codec.STRING
            .xmap(ColonyStage::parse, ColonyStage::key);

    private record Raw(String name, Category category, int priority, int max,
            Optional<Identifier> research, Map<Character, String> palette,
            List<List<String>> layers, List<ItemTarget> materials, ColonyStage stage,
            Optional<Identifier> upgradeTo, int level, List<Identifier> unlocks, Capacity capacity,
            List<String> roles, Optional<Identifier> structure, boolean rotate) {
    }

    private static final Codec<Raw> RAW_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(Raw::name),
            CATEGORY_CODEC.optionalFieldOf("category", Category.OTHER).forGetter(Raw::category),
            Codec.INT.optionalFieldOf("priority", 100).forGetter(Raw::priority),
            Codec.INT.optionalFieldOf("max", 4).forGetter(Raw::max),
            Identifier.CODEC.optionalFieldOf("research").forGetter(Raw::research),
            Codec.unboundedMap(CHARACTER_CODEC, Codec.STRING).optionalFieldOf("palette", Map.of())
                    .forGetter(Raw::palette),
            Codec.STRING.listOf().listOf().optionalFieldOf("layers", List.of()).forGetter(Raw::layers),
            ItemTarget.CODEC.listOf().optionalFieldOf("materials", List.of())
                    .forGetter(Raw::materials),
            STAGE_CODEC.optionalFieldOf("stage", ColonyStage.SETTLED).forGetter(Raw::stage),
            Identifier.CODEC.optionalFieldOf("upgrade_to").forGetter(Raw::upgradeTo),
            Codec.INT.optionalFieldOf("level", 1).forGetter(Raw::level),
            Identifier.CODEC.listOf().optionalFieldOf("unlocks", List.of()).forGetter(Raw::unlocks),
            Capacity.CODEC.optionalFieldOf("capacity", Capacity.NONE).forGetter(Raw::capacity),
            Codec.STRING.listOf().optionalFieldOf("roles", List.of()).forGetter(Raw::roles),
            Identifier.CODEC.optionalFieldOf("structure").forGetter(Raw::structure),
            Codec.BOOL.optionalFieldOf("rotate", true).forGetter(Raw::rotate)
    ).apply(inst, Raw::new));

    public static final Codec<Blueprint> CODEC = RAW_CODEC.comapFlatMap(
            Blueprint::normalise, Blueprint::toRaw);

    private static DataResult<Blueprint> normalise(Raw raw) {
        if (raw.layers().isEmpty()) {
            if (raw.structure().isEmpty()) {
                return DataResult.error(() -> "a blueprint needs layers or a structure");
            }
            // The grid arrives from the structure file once the resource manager is in reach.
            return DataResult.success(fromRaw(raw, Map.of(), List.of(), 0, 0, 0));
        }
        return grid(raw.palette(), raw.layers()).map(g -> fromRaw(raw, g.palette(), g.layout(),
                g.width(), g.depth(), g.height()));
    }

    /** A validated, padded character grid. */
    public record Grid(Map<Character, String> palette, List<String> layout, int width, int depth,
            int height) {
    }

    /**
     * Pads and validates a palette + layer list into a {@link Grid}. Shared by JSON blueprints and the
     * structure-file converter, so both obey the same ceilings.
     */
    public static DataResult<Grid> grid(Map<Character, String> palette, List<List<String>> layers) {
        if (layers.isEmpty()) {
            return DataResult.error(() -> "a blueprint needs at least one layer");
        }
        int height = layers.size();
        if (height > MAX_HEIGHT) {
            return DataResult.error(() -> "a blueprint may be at most " + MAX_HEIGHT + " layers tall");
        }
        int depth = 0;
        int width = 0;
        for (List<String> layer : layers) {
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
        List<String> flat = new ArrayList<>(height * depth);
        for (List<String> layer : layers) {
            for (int z = 0; z < depth; z++) {
                String row = z < layer.size() ? layer.get(z) : "";
                flat.add(row.length() >= width ? row.substring(0, width) : pad(row, width));
            }
        }
        return DataResult.success(new Grid(Map.copyOf(palette), List.copyOf(flat), width, depth, height));
    }

    private static Blueprint fromRaw(Raw raw, Map<Character, String> palette, List<String> layout,
            int width, int depth, int height) {
        return new Blueprint(UNNAMED, raw.name(), raw.category(), raw.priority(), raw.max(),
                raw.research(), palette, layout, List.copyOf(raw.materials()), width, depth, height,
                raw.stage(), raw.upgradeTo(), raw.level(), raw.unlocks(), raw.capacity(), raw.roles(),
                raw.structure(), raw.rotate());
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
        if (this.structure.isEmpty()) {
            for (int y = 0; y < this.height; y++) {
                layers.add(this.layout.subList(y * this.depth, (y + 1) * this.depth));
            }
        }
        return new Raw(this.name, this.category, this.priority, this.max, this.research,
                this.structure.isEmpty() ? new LinkedHashMap<>(this.palette) : Map.of(), layers,
                this.materials, this.stage, this.upgradeTo, this.level, this.unlocks, this.capacity,
                this.roles, this.structure, this.rotate);
    }

    public Blueprint {
        priority = Math.clamp(priority, 0, 10_000);
        max = Math.clamp(max, 0, 64);
        level = Math.clamp(level, 1, 9);
        palette = palette == null ? Map.of() : Map.copyOf(palette);
        layout = layout == null ? List.of() : List.copyOf(layout);
        materials = materials == null ? List.of() : List.copyOf(materials);
        unlocks = unlocks == null ? List.of() : List.copyOf(unlocks);
        roles = roles == null ? List.of() : List.copyOf(roles);
        capacity = capacity == null ? Capacity.NONE : capacity;
        stage = stage == null ? ColonyStage.SETTLED : stage;
    }

    public Blueprint withId(Identifier newId) {
        return new Blueprint(newId, name, category, priority, max, research, palette, layout,
                materials, width, depth, height, stage, upgradeTo, level, unlocks, capacity, roles,
                structure, rotate);
    }

    /** A copy with its grid replaced, used when a structure file supplies the cells. */
    public Blueprint withGrid(Grid grid) {
        return new Blueprint(id, name, category, priority, max, research, grid.palette(),
                grid.layout(), materials, grid.width(), grid.depth(), grid.height(), stage, upgradeTo,
                level, unlocks, capacity, roles, structure, rotate);
    }

    // --- facts ----------------------------------------------------------------

    /** Whether this is one of the Starter Works (builds only from supplied materials while founding). */
    public boolean starter() {
        return this.stage == ColonyStage.FOUNDING;
    }

    /** Whether the blueprint carries a role tag such as {@code "sleep"}, {@code "eat"} or {@code "social"}. */
    public boolean hasRole(String role) {
        return this.roles.contains(role);
    }

    /** Whether the grid has been filled (a structure-backed blueprint before load has none). */
    public boolean hasGrid() {
        return this.width > 0 && this.depth > 0 && this.height > 0;
    }

    // --- the layout -----------------------------------------------------------

    private String paletteText(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.height || z >= this.depth) {
            return null;
        }
        char key = this.layout.get(y * this.depth + z).charAt(x);
        return this.palette.get(key);
    }

    /**
     * The block at a cell (unrotated), or {@code null} for a hole, a clear cell or an unregistered
     * block.
     */
    @Nullable
    public Block blockAt(int x, int y, int z) {
        BlockState state = stateAt(x, y, z);
        return state == null ? null : state.getBlock();
    }

    /** The block state at a cell (unrotated), or {@code null} for a hole, clear cell or missing block. */
    @Nullable
    public BlockState stateAt(int x, int y, int z) {
        String text = paletteText(x, y, z);
        if (text == null || isClearText(text)) {
            return null;
        }
        return BlockStateText.resolve(text).orElse(null);
    }

    /** The block state at a cell, turned by {@code rotation}. */
    @Nullable
    public BlockState stateAt(int x, int y, int z, Rotation rotation) {
        BlockState state = stateAt(x, y, z);
        return state == null || rotation == Rotation.NONE ? state : state.rotate(rotation);
    }

    /** Whether a cell is a clear cell (natural blocks there are removed before building). */
    public boolean isClear(int x, int y, int z) {
        String text = paletteText(x, y, z);
        return text != null && isClearText(text);
    }

    private static boolean isClearText(String text) {
        BlockStateText.Parts parts = BlockStateText.split(text);
        if (parts == null) {
            return false;
        }
        String id = parts.blockId();
        return CLEAR.equals(id) || "air".equals(id);
    }

    /** Every cell that receives a block, bottom layer first, in unrotated blueprint coordinates. */
    public List<BlockPos> buildOrder() {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (stateAt(x, y, z) != null) {
                        cells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    /** Every clear cell, in unrotated blueprint coordinates. */
    public List<BlockPos> clearCells() {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (isClear(x, y, z)) {
                        cells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    public int blockCount() {
        int count = 0;
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (stateAt(x, y, z) != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Block ids the palette names that are not registered in this launch. */
    public List<Identifier> missingBlocks() {
        List<Identifier> missing = new ArrayList<>();
        for (String text : this.palette.values()) {
            if (isClearText(text)) {
                continue;
            }
            Identifier blockId = BlockStateText.blockId(text);
            if (blockId != null && BlockStateText.block(text) == null && !missing.contains(blockId)) {
                missing.add(blockId);
            }
        }
        return missing;
    }

    public String nameKey() {
        if (!this.name.isBlank()) {
            return this.name;
        }
        return "blueprint." + this.id.getNamespace() + "." + this.id.getPath().replace('/', '.');
    }

    // --- rotation ---------------------------------------------------------------

    /** Footprint width (X) once turned. */
    public int width(Rotation rotation) {
        return quarterTurn(rotation) ? this.depth : this.width;
    }

    /** Footprint depth (Z) once turned. */
    public int depth(Rotation rotation) {
        return quarterTurn(rotation) ? this.width : this.depth;
    }

    private static boolean quarterTurn(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    /**
     * Where an unrotated cell lands inside the turned footprint, as an offset from the footprint's
     * minimum corner. Y is unchanged. Pure; unit-tested.
     */
    public static BlockPos rotate(BlockPos cell, int width, int depth, Rotation rotation) {
        int x = cell.getX();
        int z = cell.getZ();
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(depth - 1 - z, cell.getY(), x);
            case CLOCKWISE_180 -> new BlockPos(width - 1 - x, cell.getY(), depth - 1 - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, cell.getY(), width - 1 - x);
            default -> cell;
        };
    }

    /** {@link #rotate(BlockPos, int, int, Rotation)} for this blueprint's own size. */
    public BlockPos rotate(BlockPos cell, Rotation rotation) {
        return rotate(cell, this.width, this.depth, rotation);
    }

    /** Whether a state is the plain air this format uses for clear cells. */
    public static boolean isAir(BlockState state) {
        return state.is(Blocks.AIR);
    }
}
