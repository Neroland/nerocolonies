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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.data.LenientCodecs;
import za.co.neroland.nerocolonies.data.SavedDataRecovery;

/**
 * The durable half of colony construction: what each colony is building, where, how far along, what
 * it has finished, and what its owner or Chiefs have asked for.
 *
 * <p>A side store because {@link Colony} is at the sixteen-field codec ceiling. Keyed by colony id
 * and nothing else.
 *
 * <ul>
 *   <li><b>The active build</b> — blueprint, minimum corner, rotation, cursor, credit, supplied,
 *       and whether it is an upgrade of a standing structure.</li>
 *   <li><b>Structures</b> — every finished building: blueprint, corner, rotation, level. This is
 *       what makes upgrades, land-clearing protection, building anchors (where Nerans eat, meet and
 *       work) and the link module's building list possible.</li>
 *   <li><b>Plans</b> — buildings placed by hand with the Colony Planner. They jump the autonomous
 *       queue, oldest first.</li>
 * </ul>
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing player-shaped. A plan does not record who placed it.
 */
public final class ColonyConstruction extends SavedData {

    public static final String NAME = NeroColoniesCommon.MOD_ID + ":construction";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "construction");

    public static final SavedDataType<ColonyConstruction> TYPE =
            new SavedDataType<>(ID, ColonyConstruction::new, codec(), null);

    /** Hard cap on finished structures remembered per colony (a bound on stored data). */
    public static final int MAX_STRUCTURES = 512;

    /** Hard cap on queued hand-placed plans per colony. */
    public static final int MAX_PLANS = 16;

    private final Map<UUID, Plan> byColony = new LinkedHashMap<>();

    public ColonyConstruction() {
    }

    public static ColonyConstruction get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ColonyConstruction::new, NAME);
    }

    // --- access -------------------------------------------------------------

    /** The colony's plan, created on first use. */
    public Plan plan(UUID colonyId) {
        return this.byColony.computeIfAbsent(colonyId, key -> new Plan());
    }

    /** The colony's plan if it has one, without creating it. */
    @Nullable
    public Plan peek(@Nullable UUID colonyId) {
        return colonyId == null ? null : this.byColony.get(colonyId);
    }

    public void forget(UUID colonyId) {
        if (this.byColony.remove(colonyId) != null) {
            this.setDirty();
        }
    }

    public int size() {
        return this.byColony.size();
    }

    /** Drops plans for colonies not in {@code liveColonies}. */
    public int retainOnly(Set<UUID> liveColonies) {
        int before = this.byColony.size();
        this.byColony.keySet().removeIf(id -> !liveColonies.contains(id));
        int dropped = before - this.byColony.size();
        if (dropped > 0) {
            this.setDirty();
        }
        return dropped;
    }

    public void touch() {
        this.setDirty();
    }

    // --- records ------------------------------------------------------------

    /** A finished building. */
    public record Structure(Identifier blueprint, BlockPos origin, Rotation rotation, int level) {

        static final Codec<Structure> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("blueprint").forGetter(Structure::blueprint),
                BlockPos.CODEC.fieldOf("origin").forGetter(Structure::origin),
                Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(Structure::rotation),
                Codec.INT.optionalFieldOf("level", 1).forGetter(Structure::level)
        ).apply(instance, Structure::new));

        public Structure {
            origin = origin.immutable();
            rotation = rotation == null ? Rotation.NONE : rotation;
            level = Math.max(1, level);
        }
    }

    /** A building placed by hand, waiting its turn. */
    public record Planned(Identifier blueprint, BlockPos origin, Rotation rotation) {

        static final Codec<Planned> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("blueprint").forGetter(Planned::blueprint),
                BlockPos.CODEC.fieldOf("origin").forGetter(Planned::origin),
                Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(Planned::rotation)
        ).apply(instance, Planned::new));

        public Planned {
            origin = origin.immutable();
            rotation = rotation == null ? Rotation.NONE : rotation;
        }
    }

    // --- one colony's plan --------------------------------------------------

    public static final class Plan {

        private final Map<Identifier, Integer> built = new LinkedHashMap<>();
        private final List<Structure> structures = new ArrayList<>();
        private final List<Planned> queue = new ArrayList<>();

        @Nullable
        private Identifier active;

        @Nullable
        private BlockPos origin;

        private Rotation rotation = Rotation.NONE;
        private int cursor;
        private int total;
        private double credit;
        private boolean supplied;

        /** Index into {@link #structures} of the building being upgraded, or -1. */
        private int upgrading = -1;

        public int builtCount(Identifier blueprint) {
            return this.built.getOrDefault(blueprint, 0);
        }

        public int totalBuilt() {
            int sum = 0;
            for (int count : this.built.values()) {
                sum += count;
            }
            return sum;
        }

        @Nullable
        public Identifier active() {
            return this.active;
        }

        @Nullable
        public BlockPos origin() {
            return this.origin;
        }

        public Rotation rotation() {
            return this.rotation;
        }

        public int cursor() {
            return this.cursor;
        }

        public int total() {
            return this.total;
        }

        public double credit() {
            return this.credit;
        }

        public boolean supplied() {
            return this.supplied;
        }

        /** Whether the active build replaces a standing structure (an upgrade). */
        public boolean isUpgrade() {
            return this.upgrading >= 0;
        }

        /** The structure being upgraded, or null. */
        @Nullable
        public Structure upgradeTarget() {
            return this.upgrading >= 0 && this.upgrading < this.structures.size()
                    ? this.structures.get(this.upgrading) : null;
        }

        public int progressPercent() {
            if (this.active == null || this.total <= 0) {
                return 0;
            }
            return (int) Math.clamp(this.cursor * 100L / this.total, 0L, 100L);
        }

        /** Every finished structure, oldest first. */
        public List<Structure> structures() {
            return List.copyOf(this.structures);
        }

        /** Hand-placed plans waiting their turn, oldest first. */
        public List<Planned> queue() {
            return List.copyOf(this.queue);
        }

        // --- mutation (construction and planner only) -----------------------

        void begin(Identifier blueprint, BlockPos corner, Rotation turn, int cells) {
            this.active = blueprint;
            this.origin = corner.immutable();
            this.rotation = turn == null ? Rotation.NONE : turn;
            this.cursor = 0;
            this.total = Math.max(0, cells);
            this.supplied = false;
            this.upgrading = -1;
        }

        void beginUpgrade(int structureIndex, Identifier blueprint, int cells) {
            Structure target = this.structures.get(structureIndex);
            begin(blueprint, target.origin(), target.rotation(), cells);
            this.upgrading = structureIndex;
        }

        /**
         * Records the active build as finished. An upgrade replaces its structure's entry (same
         * corner, next level); a new build appends one.
         */
        void complete(int level) {
            if (this.active != null && this.origin != null) {
                this.built.merge(this.active, 1, Integer::sum);
                Structure done = new Structure(this.active, this.origin, this.rotation, level);
                if (this.upgrading >= 0 && this.upgrading < this.structures.size()) {
                    Structure old = this.structures.get(this.upgrading);
                    this.built.computeIfPresent(old.blueprint(), (k, v) -> v > 1 ? v - 1 : null);
                    this.structures.set(this.upgrading, done);
                } else if (this.structures.size() < MAX_STRUCTURES) {
                    this.structures.add(done);
                }
            }
            abandon();
        }

        void abandon() {
            this.active = null;
            this.origin = null;
            this.rotation = Rotation.NONE;
            this.cursor = 0;
            this.total = 0;
            this.supplied = false;
            this.upgrading = -1;
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

        /** Adds a hand-placed plan. Returns false when the queue is full. */
        public boolean enqueue(Planned planned) {
            if (this.queue.size() >= MAX_PLANS) {
                return false;
            }
            this.queue.add(planned);
            return true;
        }

        /** Removes and returns the oldest plan, or null. */
        @Nullable
        Planned pollPlanned() {
            return this.queue.isEmpty() ? null : this.queue.remove(0);
        }

        /** Removes a plan by index; returns whether one was removed. */
        public boolean cancelPlanned(int index) {
            if (index < 0 || index >= this.queue.size()) {
                return false;
            }
            this.queue.remove(index);
            return true;
        }

        /** Registers a structure built outside the build loop (the gallery, a migration). */
        public void recordStructure(Structure structure) {
            if (this.structures.size() < MAX_STRUCTURES) {
                this.structures.add(structure);
                this.built.merge(structure.blueprint(), 1, Integer::sum);
            }
        }

        boolean isEmpty() {
            return this.active == null && this.built.isEmpty() && this.credit <= 0.0D
                    && this.structures.isEmpty() && this.queue.isEmpty();
        }
    }

    // --- persistence --------------------------------------------------------

    private record Tally(Identifier blueprint, int count) {

        static final Codec<Tally> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("blueprint").forGetter(Tally::blueprint),
                Codec.INT.optionalFieldOf("count", 0).forGetter(Tally::count)
        ).apply(instance, Tally::new));
    }

    private record Row(UUID colony, List<Tally> built, Optional<Identifier> active,
            Optional<BlockPos> origin, int cursor, int total, double credit, boolean supplied,
            Rotation rotation, int upgrading, List<Structure> structures, List<Planned> queue) {

        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(Row::colony),
                LenientCodecs.list(Tally.CODEC, "build tally").optionalFieldOf("built", List.of())
                        .forGetter(Row::built),
                Identifier.CODEC.optionalFieldOf("active").forGetter(Row::active),
                BlockPos.CODEC.optionalFieldOf("origin").forGetter(Row::origin),
                Codec.INT.optionalFieldOf("cursor", 0).forGetter(Row::cursor),
                Codec.INT.optionalFieldOf("total", 0).forGetter(Row::total),
                Codec.DOUBLE.optionalFieldOf("credit", 0.0D).forGetter(Row::credit),
                Codec.BOOL.optionalFieldOf("supplied", false).forGetter(Row::supplied),
                Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(Row::rotation),
                Codec.INT.optionalFieldOf("upgrading", -1).forGetter(Row::upgrading),
                LenientCodecs.list(Structure.CODEC, "structure").optionalFieldOf("structures", List.of())
                        .forGetter(Row::structures),
                LenientCodecs.list(Planned.CODEC, "planned build").optionalFieldOf("queue", List.of())
                        .forGetter(Row::queue)
        ).apply(instance, Row::new));
    }

    private static Codec<ColonyConstruction> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                LenientCodecs.list(Row.CODEC, "build plan").optionalFieldOf("plans", List.of())
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
                    plan.supplied, plan.rotation, plan.upgrading, List.copyOf(plan.structures),
                    List.copyOf(plan.queue)));
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
            for (Structure structure : row.structures()) {
                if (plan.structures.size() < MAX_STRUCTURES) {
                    plan.structures.add(structure);
                }
            }
            for (Planned planned : row.queue()) {
                if (plan.queue.size() < MAX_PLANS) {
                    plan.queue.add(planned);
                }
            }
            // A row that names an active blueprint but no origin (a hand-edited file, or a partial
            // decode) is treated as idle rather than as a build with nowhere to put itself.
            if (row.active().isPresent() && row.origin().isPresent() && row.total() > 0) {
                plan.active = row.active().get();
                plan.origin = row.origin().get().immutable();
                plan.rotation = row.rotation();
                plan.total = row.total();
                plan.cursor = Math.clamp(row.cursor(), 0, row.total());
                plan.supplied = row.supplied();
                plan.upgrading = row.upgrading() < plan.structures.size() ? row.upgrading() : -1;
            }
            plan.credit = Math.max(0.0D, row.credit());
        }
        return index;
    }
}
