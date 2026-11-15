package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.WorkAccounting;

/**
 * What one Neran remembers about its work for this session: where its field or quarry is, which tree
 * it is felling, what it is carrying and for whom, and when it last did productive work.
 *
 * <p><b>Never saved.</b> It holds positions the colony already owns and timings, nothing about any
 * player. After a restart a Neran simply finds its field, quarry or tree again (the searches are
 * deterministic); goods it was carrying are still in its saved nine-slot inventory and go back to
 * colony storage on its next working call.
 */
public final class WorkMemory {

    /** What a carrier is doing with what it holds. */
    public enum Carry {
        /** Nothing in hand for a job (anything held goes back to storage). */
        NONE,
        /** Food for the beacon's supply slots. */
        FOOD,
        /** A trade tool for {@link #recipient}. */
        TOOL
    }

    /** How long a walk target keeps the extended leash after it was last set. */
    private static final long TARGET_TTL = 60L;

    private long lastProductive = WorkAccounting.NEVER;
    private long lastAtWork = WorkAccounting.NEVER;

    @Nullable
    private BlockPos target;
    private long targetSetAt;

    /** Farmer: the centre of its field, and where the site search has got to. */
    @Nullable
    BlockPos field;
    int fieldSearchCursor;
    long fieldRetryAt;
    int fieldCellCursor;

    /** Miner: the corner of its quarry, the dig cursor, and the search cursor. */
    @Nullable
    BlockPos quarry;
    int quarryCursor;
    int quarrySearchCursor;
    long quarryRetryAt;
    final List<BlockPos> finishedQuarries = new ArrayList<>();

    /** Forester: the logs of the tree being felled, its trunk base cells, and the search cursor. */
    final List<BlockPos> tree = new ArrayList<>();
    final List<BlockPos> stumps = new ArrayList<>();
    @Nullable
    String treeSapling;
    int treeSearchCursor;
    long treeRetryAt;

    /** Carriers (Hauler, Toolsmith, Cook): what they carry and to whom. */
    Carry carry = Carry.NONE;
    @Nullable
    UUID recipient;
    long carryStartedAt;

    /** Calls spent walking to the current cell without reaching it (forester, miner). */
    int stall;

    /** Researcher: the station it studies at, and when to look again. */
    @Nullable
    BlockPos station;
    long stationRetryAt;

    /** Quartermaster: which store it walks to next. */
    int roundCursor;

    /** Builder: the cached build order of the blueprint being built. */
    @Nullable
    Identifier orderOf;
    @Nullable
    List<BlockPos> order;

    /** The trade these memories belong to; a change of trade forgets them. */
    @Nullable
    Identifier trade;

    /** Forgets everything work-specific when the Neran's trade changes. */
    public void resetFor(@Nullable Identifier newTrade) {
        if (newTrade == null ? this.trade == null : newTrade.equals(this.trade)) {
            return;
        }
        this.trade = newTrade;
        this.field = null;
        this.fieldSearchCursor = 0;
        this.fieldRetryAt = 0L;
        this.fieldCellCursor = 0;
        this.quarry = null;
        this.quarryCursor = 0;
        this.quarrySearchCursor = 0;
        this.quarryRetryAt = 0L;
        this.finishedQuarries.clear();
        this.tree.clear();
        this.stumps.clear();
        this.treeSapling = null;
        this.treeSearchCursor = 0;
        this.treeRetryAt = 0L;
        this.carry = Carry.NONE;
        this.recipient = null;
        this.station = null;
        this.stationRetryAt = 0L;
        this.roundCursor = 0;
        this.stall = 0;
        this.orderOf = null;
        this.order = null;
        this.target = null;
        this.lastProductive = WorkAccounting.NEVER;
    }

    /** Records productive work (goods into storage) at {@code gameTime}. */
    public void markProductive(long gameTime) {
        this.lastProductive = gameTime;
    }

    /** When this Neran last did productive work, or {@link WorkAccounting#NEVER}. */
    public long lastProductive() {
        return this.lastProductive;
    }

    /** Records that the Neran's trade behaviour ran at {@code gameTime}. */
    public void markAtWork(long gameTime) {
        this.lastAtWork = gameTime;
    }

    /** When this Neran's trade behaviour last ran, or {@link WorkAccounting#NEVER}. */
    public long lastAtWork() {
        return this.lastAtWork;
    }

    /** Sets the cell the Neran is walking to for its work. */
    public void setTarget(@Nullable BlockPos pos, long gameTime) {
        this.target = pos == null ? null : pos.immutable();
        this.targetSetAt = gameTime;
    }

    /** The work target set within the last few seconds, or null. */
    @Nullable
    public BlockPos activeTarget(long gameTime) {
        if (this.target == null || gameTime < this.targetSetAt || gameTime - this.targetSetAt > TARGET_TTL) {
            return null;
        }
        return this.target;
    }
}
