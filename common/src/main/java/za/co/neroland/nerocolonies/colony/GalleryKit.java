package za.co.neroland.nerocolonies.colony;

import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.content.BlockStateText;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesEntityTypes;

/**
 * The gallery's small tools: placing a block quietly, floating a label, putting a Neran on the
 * ground, and the entity tags that let {@code gallery clear} find everything again.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing here is player-shaped. Tags mark what something is, never
 * who asked for it.
 */
final class GalleryKit {

    /** On every entity the gallery spawns, so clearing can find them all. */
    static final String TAG = "nerocolonies.gallery";

    /** On a demo mob that waits, motionless, for {@code gallery release}. */
    static final String TAG_HELD = "nerocolonies.gallery.held";

    /** On the guard demo's penned mobs. */
    static final String TAG_PEN = "nerocolonies.gallery.pen";

    /** On the Neran waiting outside the claim edge. */
    static final String TAG_RUNNER = "nerocolonies.gallery.runner";

    /**
     * Tell clients, and nothing else: no neighbour updates and no shape updates. This is what keeps a
     * hundred-thousand-block floor from starting a neighbour-update storm, and what lets
     * {@code clear} take a door or a crop away without its other half reacting.
     */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final int LABEL_LINE_WIDTH = 260;
    private static final float LABEL_VIEW_RANGE = 1.5F;

    private GalleryKit() {
    }

    /** Where the gallery is: its level, its sandbox colony and its centre (the beacon's position). */
    record Site(ServerLevel level, UUID colonyId, BlockPos centre) {

        /** A position relative to the centre; {@code dy} 0 is the layer standing on the floor. */
        BlockPos at(int dx, int dy, int dz) {
            return this.centre.offset(dx, dy, dz);
        }

        @Nullable
        Colony colony() {
            return ColonyState.get(this.level.getServer()).colony(this.colonyId);
        }
    }

    /** Running totals for the command's summary line. Counts only. */
    static final class Tally {
        int blocks;
        int buildings;
        int nerans;
        int labels;
    }

    // --- blocks -----------------------------------------------------------------

    /** A block state from its text form, or air if that block is not registered in this version. */
    static BlockState state(String text) {
        return BlockStateText.resolve(text).orElse(Blocks.AIR.defaultBlockState());
    }

    /** Places a dressing block quietly. An unregistered block is skipped. */
    static void put(Site site, Tally tally, int dx, int dy, int dz, String text) {
        put(site, tally, dx, dy, dz, state(text));
    }

    static void put(Site site, Tally tally, int dx, int dy, int dz, BlockState state) {
        if (state.isAir()) {
            return;
        }
        if (site.level().setBlock(site.at(dx, dy, dz), state, QUIET)) {
            tally.blocks++;
        }
    }

    /** Places a block the way a player would, neighbours told (for blocks with block entities). */
    static void place(Site site, Tally tally, int dx, int dy, int dz, BlockState state) {
        if (site.level().setBlock(site.at(dx, dy, dz), state, Block.UPDATE_ALL)) {
            tally.blocks++;
        }
    }

    /** Places connecting blocks (fences, gates) and then joins each one to its neighbours. */
    static void connected(Site site, Tally tally, List<BlockPos> offsets, String text) {
        BlockState state = state(text);
        if (state.isAir()) {
            return;
        }
        for (BlockPos offset : offsets) {
            put(site, tally, offset.getX(), offset.getY(), offset.getZ(), state);
        }
        for (BlockPos offset : offsets) {
            BlockPos pos = site.centre().offset(offset);
            BlockState joined = Block.updateFromNeighbourShapes(site.level().getBlockState(pos), site.level(), pos);
            site.level().setBlock(pos, joined, QUIET);
        }
    }

    /** An item stack by id, or empty if the item is not registered. */
    static ItemStack stack(String itemId, int count) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id), count);
    }

    // --- labels -----------------------------------------------------------------

    /**
     * Floats a text label that always faces the viewer. A text display keeps its settings private, so
     * they go in the one public way: replayed through {@code Entity#load}.
     */
    static void label(Site site, Tally tally, double x, double y, double z, Component text, float scale) {
        ServerLevel level = site.level();
        Display.TextDisplay display = textDisplayType().create(level, EntitySpawnReason.EVENT);
        if (display == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        ComponentSerialization.CODEC
                .encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), text)
                .result().ifPresent(encoded -> tag.put("text", encoded));
        tag.putString("billboard", "center");
        tag.putInt("line_width", LABEL_LINE_WIDTH);
        tag.putFloat("view_range", LABEL_VIEW_RANGE);
        if (scale != 1.0F) {
            tag.put("transformation", scaled(scale));
        }
        display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        display.setPos(x, y, z); // load() read an absent position as the origin
        display.addTag(TAG);
        if (level.addFreshEntity(display)) {
            tally.labels++;
        }
    }

    /** Several components as the lines of one label. */
    static Component lines(Component... parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append("\n");
            }
            out.append(parts[i]);
        }
        return out;
    }

    /** A translated heading in the gallery's label style. */
    static Component heading(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    /** A label centred over a block offset from the gallery centre. */
    static void label(Site site, Tally tally, int dx, double dy, int dz, Component text, float scale) {
        BlockPos centre = site.centre();
        label(site, tally, centre.getX() + dx + 0.5D, centre.getY() + dy, centre.getZ() + dz + 0.5D, text, scale);
    }

    private static CompoundTag scaled(float scale) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floats(0.0F, 0.0F, 0.0F));
        transformation.put("left_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        transformation.put("scale", floats(scale, scale, scale));
        transformation.put("right_rotation", floats(0.0F, 0.0F, 0.0F, 1.0F));
        return transformation;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    // --- entities ---------------------------------------------------------------

    /**
     * Puts one Neran on the ground at a block offset, bound to the sandbox colony, the way
     * {@code Population} creates one: bound, dated, named from the pool, never a founder. With a
     * trade it also carries that trade's tool, as {@code Professions} would have provisioned it.
     */
    @Nullable
    static ColonistEntity neran(Site site, Tally tally, int dx, int dz, @Nullable ProfessionDefinition trade) {
        ServerLevel level = site.level();
        ColonistEntity neran = NeroColoniesEntityTypes.COLONIST.get().create(level, EntitySpawnReason.EVENT);
        if (neran == null) {
            return null;
        }
        BlockPos spot = site.at(dx, 0, dz);
        neran.snapTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.0F, 0.0F);
        neran.bind(site.colonyId());
        neran.markArrival(level.getGameTime(), false);
        neran.assignGeneratedName();
        neran.addTag(TAG);
        if (trade != null) {
            neran.setProfession(trade.id());
            ItemStack tool = trade.toolStack();
            if (!tool.isEmpty()) {
                neran.inventory().addItem(tool);
            }
            neran.updateHeldTool();
        }
        if (!level.addFreshEntity(neran)) {
            return null;
        }
        Population.invalidate(site.colonyId());
        tally.nerans++;
        return neran;
    }

    /** Spawns a persistent mob at a block offset, tagged for clearing. */
    @Nullable
    static <T extends Mob> T mob(Site site, EntityType<T> type, int dx, int dz) {
        ServerLevel level = site.level();
        T mob = type.create(level, EntitySpawnReason.EVENT);
        if (mob == null) {
            return null;
        }
        BlockPos spot = site.at(dx, 0, dz);
        mob.snapTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.0F, 0.0F);
        mob.setPersistenceRequired();
        mob.addTag(TAG);
        return level.addFreshEntity(mob) ? mob : null;
    }

    // --- entity types (renamed holder class in 26.2) -------------------------------

    private static EntityType<Display.TextDisplay> textDisplayType() {
        //? if >=26.2 {
        return net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY;
        //?} else {
        /*return EntityType.TEXT_DISPLAY;
        *///?}
    }

    static EntityType<Zombie> zombieType() {
        //? if >=26.2 {
        return net.minecraft.world.entity.EntityTypes.ZOMBIE;
        //?} else {
        /*return EntityType.ZOMBIE;
        *///?}
    }

    static EntityType<Husk> huskType() {
        //? if >=26.2 {
        return net.minecraft.world.entity.EntityTypes.HUSK;
        //?} else {
        /*return EntityType.HUSK;
        *///?}
    }

    static EntityType<Wolf> wolfType() {
        //? if >=26.2 {
        return net.minecraft.world.entity.EntityTypes.WOLF;
        //?} else {
        /*return EntityType.WOLF;
        *///?}
    }
}
