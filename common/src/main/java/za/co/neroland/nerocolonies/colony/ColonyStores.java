package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.data.SavedDataRecovery;

/**
 * Where a colony's <b>goods</b> live: the shared colony storage every job station draws from and
 * pushes into, and the export buffer that automation drains.
 *
 * <h2>Why this is not on the colony record</h2>
 *
 * <p>{@link Colony} is a 16-field {@code RecordCodecBuilder} record — the ceiling for a flat group —
 * and it is deliberately a small, cheap, frequently-copied value: every morale tick produces a new
 * one. Two 54-slot item lists have no business being copied on every tick, and a colony's inventory
 * is a fundamentally different kind of data from its state. So the goods live here, in their own
 * {@link SavedData} keyed by colony id, and the colony record links to them by nothing at all — the
 * id is the join.
 *
 * <p>The consequence to remember: <b>dissolving a colony must call {@link #dropAndForget}</b>, or the
 * store outlives the colony that owned it. The beacon's break path does exactly that.
 *
 * <h2>Two regions, one file</h2>
 *
 * <ul>
 *   <li><b>storage</b> — the colony's working stock. Job inputs are pulled from it and non-export
 *       outputs pushed into it; players and pipes reach it through the beacon and through any colony
 *       depot in the claim.</li>
 *   <li><b>exports</b> — the sale buffer. Jobs flagged {@code export} route their output here and
 *       nothing may insert into it from outside, so what is in it is exactly what the colony
 *       produced for sale.</li>
 * </ul>
 *
 * <p>Both regions are allocated at their maximum size and gated down to the currently <em>usable</em>
 * slot count by {@link ColonyStorage} / {@link ExportBuffer}. Sizing the list by config would mean a
 * config change could strand items in slots that no longer exist; a fixed backing list with a moving
 * gate cannot lose anything.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing here is player-shaped. A store is keyed by colony id — a place, not a person — holds
 * item stacks only, and is therefore out of scope for an erasure request. Erasing a player never
 * touches a colony's goods; that is what the {@code transfer_to_server} ownership policy is for.
 */
public final class ColonyStores extends SavedData {

    /** Stable, non-identifying label used for the storage file and recovery logs. */
    public static final String NAME = NeroColoniesCommon.MOD_ID + ":stores";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "stores");

    public static final SavedDataType<ColonyStores> TYPE =
            new SavedDataType<>(ID, ColonyStores::new, codec(), null);

    /** Backing size of the storage region. The usable part is gated by {@link ColonyStorage}. */
    public static final int STORAGE_SLOTS = 54;

    /** Backing size of the export region. The usable part is gated by {@link ExportBuffer}. */
    public static final int EXPORT_SLOTS = 54;

    private final Map<UUID, Store> byColony = new LinkedHashMap<>();

    public ColonyStores() {
    }

    /** The one store index, on the overworld so it is loaded whenever any colony is. */
    public static ColonyStores get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ColonyStores::new, NAME);
    }

    // --- access -------------------------------------------------------------

    /** This colony's store, created empty on first use. */
    public Store store(UUID colonyId) {
        return this.byColony.computeIfAbsent(colonyId, key -> new Store());
    }

    /** This colony's store, or {@code null} if it has never held anything. */
    @Nullable
    public Store peek(UUID colonyId) {
        return colonyId == null ? null : this.byColony.get(colonyId);
    }

    /** Forgets a colony's goods entirely. Called when the colony is dissolved. */
    public void forget(UUID colonyId) {
        if (this.byColony.remove(colonyId) != null) {
            this.setDirty();
        }
    }

    /** How many colonies currently hold goods (a diagnostic count, never an identity). */
    public int size() {
        return this.byColony.size();
    }

    /**
     * Drops a dissolved colony's whole store into the world at {@code pos} and forgets it. Dropping
     * and forgetting are one operation on purpose: doing either without the other either duplicates
     * the goods or silently deletes them.
     */
    public static void dropAndForget(Level level, BlockPos pos, UUID colonyId) {
        if (colonyId == null || level.getServer() == null) {
            return;
        }
        ColonyStores stores = get(level.getServer());
        Store store = stores.peek(colonyId);
        if (store != null) {
            Containers.dropContents(level, pos, store.storage());
            Containers.dropContents(level, pos, store.exports());
        }
        stores.forget(colonyId);
    }

    /** Marks the index dirty. Called by the container views after any write. */
    public void touch() {
        this.setDirty();
    }

    // --- the store ----------------------------------------------------------

    /** One colony's goods: the working stock and the export buffer, both at their backing size. */
    public static final class Store {

        private final NonNullList<ItemStack> storage =
                NonNullList.withSize(STORAGE_SLOTS, ItemStack.EMPTY);

        private final NonNullList<ItemStack> exports =
                NonNullList.withSize(EXPORT_SLOTS, ItemStack.EMPTY);

        /** The working stock, live. Callers that mutate it must mark the index dirty. */
        public NonNullList<ItemStack> storage() {
            return this.storage;
        }

        /** The export buffer, live. Callers that mutate it must mark the index dirty. */
        public NonNullList<ItemStack> exports() {
            return this.exports;
        }

        /** Whether both regions are completely empty (used to skip persisting a dead store). */
        public boolean isEmpty() {
            for (ItemStack stack : this.storage) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            for (ItemStack stack : this.exports) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    // --- persistence --------------------------------------------------------

    /**
     * One colony's row. Stacks are stored as a plain list and re-seated by index on load, so a jar
     * that changes {@link #STORAGE_SLOTS} reads an old file without losing anything that still fits.
     */
    private record Row(UUID colony, List<ItemStack> storage, List<ItemStack> exports) {

        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(Row::colony),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("storage", List.of())
                        .forGetter(Row::storage),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("exports", List.of())
                        .forGetter(Row::exports)
        ).apply(instance, Row::new));
    }

    private static Codec<ColonyStores> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Row.CODEC.listOf().optionalFieldOf("stores", List.of()).forGetter(ColonyStores::rows)
        ).apply(instance, ColonyStores::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>(this.byColony.size());
        this.byColony.forEach((colony, store) -> {
            if (!store.isEmpty()) {
                out.add(new Row(colony, List.copyOf(store.storage()), List.copyOf(store.exports())));
            }
        });
        return out;
    }

    private static ColonyStores fromRows(List<Row> rows) {
        ColonyStores stores = new ColonyStores();
        for (Row row : rows) {
            Store store = stores.store(row.colony());
            seat(row.storage(), store.storage());
            seat(row.exports(), store.exports());
        }
        return stores;
    }

    private static void seat(List<ItemStack> source, NonNullList<ItemStack> target) {
        for (int slot = 0; slot < source.size() && slot < target.size(); slot++) {
            ItemStack stack = source.get(slot);
            target.set(slot, stack == null ? ItemStack.EMPTY : stack);
        }
    }
}
