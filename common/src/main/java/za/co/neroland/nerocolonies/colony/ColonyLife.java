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

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.data.LenientCodecs;
import za.co.neroland.nerocolonies.data.SavedDataRecovery;

/**
 * Everything a living colony accumulates that is not on the {@link Colony} record (which is at the
 * sixteen-field codec ceiling): its growth stage, aggregate counters, the Gratitude Cache, the
 * need the owner has prioritised, and whether it is the gallery's sandbox. (Guardian animals are
 * not listed here: they are found by an entity tag.)
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing player-shaped. Counters are counts and the cache holds
 * items. Role lists, which do hold player
 * UUIDs, live in their own store ({@link ColonyRoles}) so erasure has one obvious place to look.
 */
public final class ColonyLife extends SavedData {

    public static final String NAME = NeroColoniesCommon.MOD_ID + ":life";

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "life");

    public static final SavedDataType<ColonyLife> TYPE =
            new SavedDataType<>(ID, ColonyLife::new, codec(), null);

    /** Gratitude Cache slots. Stocking pauses when full; nothing is ever voided. */
    public static final int CACHE_SLOTS = 27;

    private final Map<UUID, Life> byColony = new LinkedHashMap<>();

    public ColonyLife() {
    }

    public static ColonyLife get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ColonyLife::new, NAME);
    }

    /** The colony's record, created on first use. */
    public Life life(UUID colonyId) {
        return this.byColony.computeIfAbsent(colonyId, key -> new Life());
    }

    /** The colony's record if any, without creating one. */
    @Nullable
    public Life peek(@Nullable UUID colonyId) {
        return colonyId == null ? null : this.byColony.get(colonyId);
    }

    /** Whether {@code colonyId} is the gallery sandbox (excluded from link, telemetry and gates). */
    public static boolean isSandbox(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        if (server == null || colonyId == null) {
            return false;
        }
        Life life = get(server).peek(colonyId);
        return life != null && life.sandbox;
    }

    public void forget(UUID colonyId) {
        if (this.byColony.remove(colonyId) != null) {
            setDirty();
        }
    }

    public int retainOnly(Set<UUID> liveColonies) {
        int before = this.byColony.size();
        this.byColony.keySet().removeIf(id -> !liveColonies.contains(id));
        int dropped = before - this.byColony.size();
        if (dropped > 0) {
            setDirty();
        }
        return dropped;
    }

    public void touch() {
        setDirty();
    }

    // --- one colony ------------------------------------------------------------

    public static final class Life {

        private ColonyStage stage = ColonyStage.FOUNDING;
        private boolean stageKnown;
        private long stuckEvents;
        private int births;
        private long lastBirthTick;
        private final List<ItemStack> cache = new ArrayList<>();
        private long cacheLastStocked;
        private boolean cacheShared;
        @Nullable
        private Identifier priorityNeed;
        private int retributions;
        private boolean sandbox;

        public ColonyStage stage() {
            return this.stage;
        }

        /**
         * Whether a stage has ever been decided for this colony. False for a colony that predates
         * stages, which lets the migration decide FOUNDING versus SETTLED from what is built.
         */
        public boolean stageKnown() {
            return this.stageKnown;
        }

        public void setStage(ColonyStage newStage) {
            this.stage = newStage;
            this.stageKnown = true;
        }

        public long stuckEvents() {
            return this.stuckEvents;
        }

        public void countStuck() {
            this.stuckEvents++;
        }

        public int births() {
            return this.births;
        }

        public long lastBirthTick() {
            return this.lastBirthTick;
        }

        public void countBirth(long gameTime) {
            this.births++;
            this.lastBirthTick = gameTime;
        }

        /** The cache contents (live list; mutate through the cache logic only). */
        public List<ItemStack> cache() {
            return this.cache;
        }

        public long cacheLastStocked() {
            return this.cacheLastStocked;
        }

        public void markCacheStocked(long gameTime) {
            this.cacheLastStocked = gameTime;
        }

        public boolean cacheShared() {
            return this.cacheShared;
        }

        public void setCacheShared(boolean shared) {
            this.cacheShared = shared;
        }

        @Nullable
        public Identifier priorityNeed() {
            return this.priorityNeed;
        }

        public void setPriorityNeed(@Nullable Identifier item) {
            this.priorityNeed = item;
        }

        public int retributions() {
            return this.retributions;
        }

        public void countRetribution() {
            this.retributions++;
        }

        public boolean sandbox() {
            return this.sandbox;
        }

        public void setSandbox(boolean value) {
            this.sandbox = value;
        }

        boolean isEmpty() {
            return !this.stageKnown && this.stuckEvents == 0 && this.births == 0 && this.cache.isEmpty()
                    && this.priorityNeed == null && !this.sandbox
                    && !this.cacheShared && this.retributions == 0;
        }
    }

    // --- persistence -----------------------------------------------------------

    private record Row(UUID colony, int stage, boolean stageKnown, long stuck, int births,
            long lastBirth, List<ItemStack> cache, long cacheStocked, boolean cacheShared,
            Optional<Identifier> priority, int retributions, boolean sandbox) {

        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(Row::colony),
                Codec.INT.optionalFieldOf("stage", 0).forGetter(Row::stage),
                Codec.BOOL.optionalFieldOf("stage_known", false).forGetter(Row::stageKnown),
                Codec.LONG.optionalFieldOf("stuck", 0L).forGetter(Row::stuck),
                Codec.INT.optionalFieldOf("births", 0).forGetter(Row::births),
                Codec.LONG.optionalFieldOf("last_birth", 0L).forGetter(Row::lastBirth),
                LenientCodecs.list(ItemStack.OPTIONAL_CODEC, "cache item").optionalFieldOf("cache", List.of())
                        .forGetter(Row::cache),
                Codec.LONG.optionalFieldOf("cache_stocked", 0L).forGetter(Row::cacheStocked),
                Codec.BOOL.optionalFieldOf("cache_shared", false).forGetter(Row::cacheShared),
                Identifier.CODEC.optionalFieldOf("priority").forGetter(Row::priority),
                Codec.INT.optionalFieldOf("retributions", 0).forGetter(Row::retributions),
                Codec.BOOL.optionalFieldOf("sandbox", false).forGetter(Row::sandbox)
        ).apply(instance, Row::new));
    }

    private static Codec<ColonyLife> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                LenientCodecs.list(Row.CODEC, "colony life").optionalFieldOf("colonies", List.of())
                        .forGetter(ColonyLife::rows)
        ).apply(instance, ColonyLife::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>(this.byColony.size());
        this.byColony.forEach((id, life) -> {
            if (life.isEmpty()) {
                return;
            }
            List<ItemStack> cache = new ArrayList<>();
            for (ItemStack stack : life.cache) {
                if (!stack.isEmpty()) {
                    cache.add(stack.copy());
                }
            }
            out.add(new Row(id, life.stage.ordinal(), life.stageKnown, life.stuckEvents, life.births,
                    life.lastBirthTick, cache, life.cacheLastStocked, life.cacheShared,
                    Optional.ofNullable(life.priorityNeed),
                    life.retributions, life.sandbox));
        });
        return out;
    }

    private static ColonyLife fromRows(List<Row> rows) {
        ColonyLife store = new ColonyLife();
        for (Row row : rows) {
            Life life = store.life(row.colony());
            life.stage = ColonyStage.byOrdinal(row.stage());
            life.stageKnown = row.stageKnown();
            life.stuckEvents = Math.max(0L, row.stuck());
            life.births = Math.max(0, row.births());
            life.lastBirthTick = row.lastBirth();
            for (ItemStack stack : row.cache()) {
                if (!stack.isEmpty() && life.cache.size() < CACHE_SLOTS) {
                    life.cache.add(stack);
                }
            }
            life.cacheLastStocked = row.cacheStocked();
            life.cacheShared = row.cacheShared();
            life.priorityNeed = row.priority().orElse(null);
            life.retributions = Math.max(0, row.retributions());
            life.sandbox = row.sandbox();
        }
        return store;
    }
}
