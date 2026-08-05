package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.data.SavedDataRecovery;

/**
 * What each colony has built for itself, and what it is building now.
 *
 * <h2>Why this is its own saved data</h2>
 *
 * <p>{@link Colony} is already at the sixteen-field {@code RecordCodecBuilder} ceiling, and it is a
 * small value copied on every morale tick — a build queue has no business riding along on that. So
 * construction state lives here, in its own {@link SavedData} keyed by colony id, exactly as the
 * colony's goods live in {@link ColonyStores}. The colony record links to a plan by nothing at all:
 * the id is the join.
 *
 * <p>The consequence, and it is the same one {@link ColonyStores} carries: <b>dissolving a colony
 * must call {@link #forget}</b>, or its plan outlives it. Every dissolve path does.
 *
 * <h2>What is persisted, and what is not</h2>
 *
 * <p>Persisted: how many of each blueprint the colony has finished, and — if a structure is part
 * built — which blueprint it is, where its corner is, how far down the build order the cursor has
 * got, how much fabrication credit is banked, and whether its materials were paid for. That is
 * everything needed to resume a half-built structure after a restart, which matters because a
 * structure takes minutes of colony ticks and a player will absolutely log out in the middle of one.
 *
 * <p>Not persisted: the site search cursor, which lives in the beacon's session state
 * ({@code Construction.State}). A restarted search costs one bounded sweep and nothing else.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing here is player-shaped. A plan is keyed by colony id — a place, not a person — and holds
 * blueprint ids, a block position and counters, so it is out of scope for an erasure request in the
 * same way a colony's goods are.
 */
public final class ColonyConstruction extends SavedData {

    /** Stable, non-identifying label used for the storage file and recovery logs. */
    public static final String NAME = NeroColoniesCommon.MOD_ID + ":construction";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "construction");

    public static final SavedDataType<ColonyConstruction> TYPE =
            new SavedDataType<>(ID, ColonyConstruction::new, codec(), null);

    private final Map<UUID, Plan> byColony = new LinkedHashMap<>();

    public ColonyConstruction() {
    }

    /** The one construction index, on the overworld so it is loaded whenever any colony is. */
    public static ColonyConstruction get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ColonyConstruction::new, NAME);
    }

    // --- access -------------------------------------------------------------

    /** This colony's plan, created empty on first use. */
    public Plan plan(UUID colonyId) {
        return this.byColony.computeIfAbsent(colonyId, key -> new Plan());
    }

    /** This colony's plan, or {@code null} if it has never built anything. */
    @Nullable
    public Plan peek(@Nullable UUID colonyId) {
        return colonyId == null ? null : this.byColony.get(colonyId);
    }

    /** Forgets a colony's construction record entirely. Called when the colony is dissolved. */
    public void forget(UUID colonyId) {
        if (this.byColony.remove(colonyId) != null) {
            this.setDirty();
        }
    }

    /** How many colonies currently hold a plan (a diagnostic count, never an identity). */
    public int size() {
        return this.byColony.size();
    }

    /**
     * Drops every plan whose colony no longer exists. Run from the retention sweep, so a colony that
     * disappeared without going through a dissolve path — an erasure under the {@code dissolve}
     * policy, a hand-edited save — cannot leave its build record behind forever.
     *
     * @return how many plans were dropped (a count, never an identity)
     */
    public int retainOnly(Set<UUID> liveColonies) {
        int before = this.byColony.size();
        this.byColony.keySet().removeIf(id -> !liveColonies.contains(id));
        int dropped = before - this.byColony.size();
        if (dropped > 0) {
            this.setDirty();
        }
        return dropped;
    }

    /** Marks the index dirty. Called by the planner after any write. */
    public void touch() {
        this.setDirty();
    }

    // --- one colony's plan --------------------------------------------------

    /** One colony's build record: what it has finished, and the structure it is part way through. */
    public static final class Plan {

        private final Map<Identifier, Integer> built = new LinkedHashMap<>();

        @Nullable
        private Identifier active;

        @Nullable
        private BlockPos origin;

        private int cursor;
        private int total;
        private double credit;
        private boolean supplied;

        /** How many of this blueprint the colony has completed. */
        public int builtCount(Identifier blueprint) {
            return this.built.getOrDefault(blueprint, 0);
        }

        /** How many structures the colony has completed in total, over every blueprint. */
        public int totalBuilt() {
            int total = 0;
            for (int count : this.built.values()) {
                total += count;
            }
            return total;
        }

        /** The blueprint currently under construction, or {@code null} when the colony is idle. */
        @Nullable
        public Identifier active() {
            return this.active;
        }

        /** The minimum corner of the structure under construction, or {@code null} when idle. */
        @Nullable
        public BlockPos origin() {
            return this.origin;
        }

        /** How far down the build order the cursor has reached. */
        public int cursor() {
            return this.cursor;
        }

        /** How many cells the current build order has in total. */
        public int total() {
            return this.total;
        }

        /** Banked fabrication credit, in blocks. */
        public double credit() {
            return this.credit;
        }

        /** Whether the current structure's materials have been paid out of colony storage. */
        public boolean supplied() {
            return this.supplied;
        }

        /** Progress through the current structure, 0..100. Zero when nothing is being built. */
        public int progressPercent() {
            if (this.active == null || this.total <= 0) {
                return 0;
            }
            return (int) Math.clamp(this.cursor * 100L / this.total, 0L, 100L);
        }

        // --- mutation (planner only) ----------------------------------------

        /** Begins a structure. The credit carries over so a finished build does not waste it. */
        void begin(Identifier blueprint, BlockPos corner, int cells) {
            this.active = blueprint;
            this.origin = corner.immutable();
            this.cursor = 0;
            this.total = Math.max(0, cells);
            this.supplied = false;
        }

        /** Records a completed structure and clears the site. */
        void complete() {
            if (this.active != null) {
                this.built.merge(this.active, 1, Integer::sum);
            }
            abandon();
        }

        /** Clears the site without counting it (the site became invalid, or building was disabled). */
        void abandon() {
            this.active = null;
            this.origin = null;
            this.cursor = 0;
            this.total = 0;
            this.supplied = false;
        }

        void advanceCursor(int cells) {
            this.cursor = Math.clamp(this.cursor + cells, 0, Math.max(0, this.total));
        }

        void addCredit(double blocks, double cap) {
            this.credit = Math.clamp(this.credit + Math.max(0.0D, blocks), 0.0D, Math.max(0.0D, cap));
        }

        void spendCredit(double blocks) {
            this.credit = Math.max(0.0D, this.credit - Math.max(0.0D, blocks));
        }

        void markSupplied() {
            this.supplied = true;
        }

        /** Whether this plan holds nothing worth writing to disk. */
        boolean isEmpty() {
            return this.active == null && this.built.isEmpty() && this.credit <= 0.0D;
        }
    }

    // --- persistence --------------------------------------------------------

    /** One completed-structure tally. A list of pairs, because a map key here is a resource id. */
    private record Tally(Identifier blueprint, int count) {

        static final Codec<Tally> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("blueprint").forGetter(Tally::blueprint),
                Codec.INT.optionalFieldOf("count", 0).forGetter(Tally::count)
        ).apply(instance, Tally::new));
    }

    /** One colony's row. Every field past the colony id is optional, so an old file still reads. */
    private record Row(UUID colony, List<Tally> built, Optional<Identifier> active,
            Optional<BlockPos> origin, int cursor, int total, double credit, boolean supplied) {

        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(Row::colony),
                Tally.CODEC.listOf().optionalFieldOf("built", List.of()).forGetter(Row::built),
                Identifier.CODEC.optionalFieldOf("active").forGetter(Row::active),
                BlockPos.CODEC.optionalFieldOf("origin").forGetter(Row::origin),
                Codec.INT.optionalFieldOf("cursor", 0).forGetter(Row::cursor),
                Codec.INT.optionalFieldOf("total", 0).forGetter(Row::total),
                Codec.DOUBLE.optionalFieldOf("credit", 0.0D).forGetter(Row::credit),
                Codec.BOOL.optionalFieldOf("supplied", false).forGetter(Row::supplied)
        ).apply(instance, Row::new));
    }

    private static Codec<ColonyConstruction> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Row.CODEC.listOf().optionalFieldOf("plans", List.of())
                        .forGetter(ColonyConstruction::rows)
        ).apply(instance, ColonyConstruction::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>(this.byColony.size());
        this.byColony.forEach((colony, plan) -> {
            if (plan.isEmpty()) {
                return;
            }
            List<Tally> tallies = new ArrayList<>(plan.built.size());
            plan.built.forEach((blueprint, count) -> tallies.add(new Tally(blueprint, count)));
            out.add(new Row(colony, tallies, Optional.ofNullable(plan.active),
                    Optional.ofNullable(plan.origin), plan.cursor, plan.total, plan.credit,
                    plan.supplied));
        });
        return out;
    }

    private static ColonyConstruction fromRows(List<Row> rows) {
        ColonyConstruction index = new ColonyConstruction();
        for (Row row : rows) {
            Plan plan = index.plan(row.colony());
            for (Tally tally : row.built()) {
                if (tally.count() > 0) {
                    plan.built.merge(tally.blueprint(), tally.count(), Integer::sum);
                }
            }
            // A row that names an active blueprint but no origin (a hand-edited file, or a partial
            // decode) is treated as idle rather than as a build with nowhere to put itself.
            if (row.active().isPresent() && row.origin().isPresent() && row.total() > 0) {
                plan.active = row.active().get();
                plan.origin = row.origin().get().immutable();
                plan.total = row.total();
                plan.cursor = Math.clamp(row.cursor(), 0, row.total());
                plan.supplied = row.supplied();
            }
            plan.credit = Math.max(0.0D, row.credit());
        }
        return index;
    }
}
