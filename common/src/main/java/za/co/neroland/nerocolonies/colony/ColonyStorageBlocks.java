package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.data.SavedDataCompat;
import za.co.neroland.nerolandcore.data.SavedDataRecovery;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.ColonyDepotBlockEntity;
import za.co.neroland.nerocolonies.data.LenientCodecs;

/**
 * Where a colony keeps its goods <em>physically</em>: the Colony Depots and Storage Crates standing
 * in its claim (or in one of its outposts).
 *
 * <h2>Storage lives in boxes</h2>
 *
 * <p>The goods themselves are still one shared stock per colony in {@link ColonyStores}; every box is
 * a window onto it. What the boxes decide is <b>capacity</b>: a colony with no box has no storage at
 * all (and cannot be handed its Starter Works materials), a depot holds {@value #DEPOT_SLOTS} slots,
 * a crate {@value #CRATE_SLOTS}, and capacity modules in the beacon add to that only while at least
 * one box stands. See {@link ColonyStorage#usableSlots(int, int)}.
 *
 * <h2>Breaking a box</h2>
 *
 * <p>When a box goes, the colony's goods are packed into what the remaining boxes can hold and only
 * the overflow drops, at the broken box ({@link ColonyStorage#settle}). Breaking the last box drops
 * everything. A box removed without a player (an explosion, a command) is noticed by {@link #prune}
 * on the beacon's refresh and settled the same way.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Rows are a colony id and block positions: places, never people. Nothing here is player-shaped,
 * so it is out of scope for an erasure request, and it is forgotten with the colony.
 */
public final class ColonyStorageBlocks extends SavedData {

    /** Stable, non-identifying label used for the storage file and recovery logs. */
    public static final String NAME = NeroColoniesCommon.MOD_ID + ":storage_blocks";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "storage_blocks");

    public static final SavedDataType<ColonyStorageBlocks> TYPE =
            SavedDataCompat.type(ID, ColonyStorageBlocks::new, codec());

    /** Slots one Colony Depot holds. */
    public static final int DEPOT_SLOTS = 18;

    /** Slots one Storage Crate holds. */
    public static final int CRATE_SLOTS = 27;

    /** One box: where it stands, and whether it is a crate (or a depot). */
    public record Box(ResourceKey<Level> dimension, BlockPos pos, boolean crate) {

        static final Codec<Box> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Box::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Box::pos),
                Codec.BOOL.optionalFieldOf("crate", false).forGetter(Box::crate)
        ).apply(instance, Box::new));

        boolean at(ResourceKey<Level> dimension, BlockPos pos) {
            return this.dimension.equals(dimension) && this.pos.equals(pos);
        }

        /** Slots this box adds. */
        public int slots() {
            return this.crate ? CRATE_SLOTS : DEPOT_SLOTS;
        }
    }

    private final Map<UUID, List<Box>> byColony = new LinkedHashMap<>();

    public ColonyStorageBlocks() {
    }

    /** The one index, on the overworld so it is loaded whenever any colony is. */
    public static ColonyStorageBlocks get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ColonyStorageBlocks::new, NAME);
    }

    // --- reads ----------------------------------------------------------------

    /** This colony's boxes (a copy). */
    public List<Box> boxes(@Nullable UUID colonyId) {
        List<Box> boxes = colonyId == null ? null : this.byColony.get(colonyId);
        return boxes == null ? List.of() : List.copyOf(boxes);
    }

    /** Whether the colony has at least one depot or crate. */
    public boolean hasStorage(@Nullable UUID colonyId) {
        List<Box> boxes = colonyId == null ? null : this.byColony.get(colonyId);
        return boxes != null && !boxes.isEmpty();
    }

    /** The slots the colony's boxes hold between them. */
    public int boxSlots(@Nullable UUID colonyId) {
        return slotsOf(boxes(colonyId));
    }

    /** The slots a set of boxes holds. Pure; unit-tested. */
    public static int slotsOf(List<Box> boxes) {
        int slots = 0;
        for (Box box : boxes) {
            slots += box.slots();
        }
        return slots;
    }

    /** Whether the colony owning {@code colonyId} has somewhere to keep goods (convenience). */
    public static boolean hasStorage(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        return server != null && colonyId != null && get(server).hasStorage(colonyId);
    }

    // --- writes ---------------------------------------------------------------

    /**
     * Records a box for a colony. Idempotent; a box standing at a position another colony recorded
     * moves to this one (claims changed under it).
     */
    public void register(UUID colonyId, ResourceKey<Level> dimension, BlockPos pos, boolean crate) {
        BlockPos at = pos.immutable();
        boolean changed = false;
        for (Map.Entry<UUID, List<Box>> entry : this.byColony.entrySet()) {
            if (!entry.getKey().equals(colonyId)) {
                changed |= entry.getValue().removeIf(box -> box.at(dimension, at));
            }
        }
        List<Box> boxes = this.byColony.computeIfAbsent(colonyId, key -> new ArrayList<>());
        Box wanted = new Box(dimension, at, crate);
        if (!boxes.contains(wanted)) {
            boxes.removeIf(box -> box.at(dimension, at));
            boxes.add(wanted);
            changed = true;
        }
        if (changed) {
            this.setDirty();
        }
    }

    /**
     * Forgets the box at a position, whichever colony had it.
     *
     * @return the colony that had it, or {@code null}
     */
    @Nullable
    public UUID unregister(ResourceKey<Level> dimension, BlockPos pos) {
        for (Map.Entry<UUID, List<Box>> entry : this.byColony.entrySet()) {
            if (entry.getValue().removeIf(box -> box.at(dimension, pos))) {
                this.setDirty();
                return entry.getKey();
            }
        }
        return null;
    }

    /** Forgets every box of a dissolved colony. */
    public void forget(@Nullable UUID colonyId) {
        if (colonyId != null && this.byColony.remove(colonyId) != null) {
            this.setDirty();
        }
    }

    /**
     * Drops records whose box is no longer there (the chunk is loaded and holds no depot or crate),
     * then settles the colony's goods into what is left, dropping any overflow where the missing box
     * stood. Boxes in unloaded chunks are trusted. Called from the beacon's refresh.
     */
    public static void prune(MinecraftServer server, UUID colonyId, int capacityModules) {
        ColonyStorageBlocks index = get(server);
        List<Box> boxes = index.byColony.get(colonyId);
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        Box gone = null;
        ServerLevel goneLevel = null;
        for (Box box : List.copyOf(boxes)) {
            ServerLevel level = server.getLevel(box.dimension());
            if (level == null) {
                boxes.remove(box);
                index.setDirty();
                continue;
            }
            if (!level.isLoaded(box.pos())) {
                continue;
            }
            if (!(level.getBlockEntity(box.pos()) instanceof ColonyDepotBlockEntity)) {
                boxes.remove(box);
                index.setDirty();
                gone = box;
                goneLevel = level;
            }
        }
        if (gone != null) {
            ColonyStorage.settle(goneLevel, gone.pos(), colonyId,
                    ColonyStorage.usableSlots(server, colonyId, capacityModules));
        }
    }

    // --- persistence ------------------------------------------------------------

    private record Row(UUID colony, List<Box> boxes) {

        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(Row::colony),
                LenientCodecs.list(Box.CODEC, "storage box").optionalFieldOf("boxes", List.of())
                        .forGetter(Row::boxes)
        ).apply(instance, Row::new));
    }

    private static Codec<ColonyStorageBlocks> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                LenientCodecs.list(Row.CODEC, "colony storage boxes").optionalFieldOf("colonies", List.of())
                        .forGetter(ColonyStorageBlocks::rows)
        ).apply(instance, ColonyStorageBlocks::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>(this.byColony.size());
        this.byColony.forEach((colony, boxes) -> {
            if (!boxes.isEmpty()) {
                out.add(new Row(colony, List.copyOf(boxes)));
            }
        });
        return out;
    }

    private static ColonyStorageBlocks fromRows(List<Row> rows) {
        ColonyStorageBlocks index = new ColonyStorageBlocks();
        for (Row row : rows) {
            index.byColony.computeIfAbsent(row.colony(), key -> new ArrayList<>()).addAll(row.boxes());
        }
        return index;
    }
}
