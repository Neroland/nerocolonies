package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.data.SavedDataRecovery;

/**
 * The one server-wide colony index: {@code colony id → }{@link Colony}, plus the optional
 * {@link AccessLog} rows filed under the same ids.
 *
 * <p>Persisted as vanilla {@link SavedData} on the <b>overworld</b> (so it is always loaded, even
 * while a colony's own dimension is not) under the name {@code nerocolonies:colonies}. Every
 * accessor goes through {@link SavedDataRecovery}, so a corrupt file degrades to an empty index
 * instead of crashing the server.
 *
 * <h2>Indexes</h2>
 *
 * <p>Three, all derived and rebuilt from the colony map:
 * <ul>
 *   <li><b>by id</b> — the store itself;</li>
 *   <li><b>by dimension</b> — for "list the colonies here" and for the colony ticker's batches;</li>
 *   <li><b>by chunk</b> — a {@code ChunkPos.pack() → colony id} map so "which colony owns this
 *       block?" is O(1) on the hot path (every block placement inside a claim asks).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>Server thread only — placement, interaction, commands, the tick and the erasure hook all run
 * there. Nothing here is synchronised.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Owner UUIDs, access lists and access-log rows live here and only here. {@link ColonyApi} is
 * the boolean-only public surface over them; nothing in this class is called from a client path.
 * Erasure ({@link #forgetPlayer}) and retention ({@link #sweep}) both reach every row, and both log
 * counts only — never who.
 */
public final class ColonyState extends SavedData {

    /** Stable, non-identifying label used for the storage file and recovery logs. */
    public static final String NAME = NeroColoniesCommon.MOD_ID + ":colonies";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colonies");

    public static final SavedDataType<ColonyState> TYPE =
            new SavedDataType<>(ID, ColonyState::new, codec(), null);

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * The server whose retention sweep has already run, so the lazy check in {@link #get} fires at
     * most once per server instance rather than on every call. Written on the server thread;
     * {@code volatile} only so an integrated-server restart in the same JVM is seen promptly.
     */
    private static volatile MinecraftServer prunedFor;

    /**
     * "Is a colony beacon still standing here?" — supplied by the block registry at init so this
     * package never has to import the block package (and so the sweep is inert in any context where
     * blocks were never registered). Defaults to "assume yes", i.e. never orphan anything.
     */
    private static volatile BiPredicate<ServerLevel, BlockPos> beaconCheck = (level, pos) -> true;

    /** The same test for an outpost beacon. Same defaults, same reasoning as {@link #beaconCheck}. */
    private static volatile BiPredicate<ServerLevel, BlockPos> outpostCheck = (level, pos) -> true;

    private final Map<UUID, Colony> byId = new LinkedHashMap<>();
    private final Map<UUID, List<AccessLog.Entry>> accessLog = new LinkedHashMap<>();
    private final Map<UUID, Outpost> outposts = new LinkedHashMap<>();

    // Derived indexes — never serialised, always rebuilt from byId.
    private final Map<ResourceKey<Level>, Set<UUID>> byDimension = new LinkedHashMap<>();
    private final Map<Long, UUID> byChunk = new LinkedHashMap<>();
    private final Map<Long, UUID> outpostByChunk = new LinkedHashMap<>();

    public ColonyState() {
    }

    /** Installs the "beacon still there?" test. Called once from the block registry's init. */
    public static void setBeaconCheck(BiPredicate<ServerLevel, BlockPos> check) {
        if (check != null) {
            beaconCheck = check;
        }
    }

    /** Installs the "outpost beacon still there?" test. Called once from the block registry's init. */
    public static void setOutpostCheck(BiPredicate<ServerLevel, BlockPos> check) {
        if (check != null) {
            outpostCheck = check;
        }
    }

    /**
     * The one store, on the overworld so it is always loaded. Runs the retention sweep on the first
     * call for a given server instance — once per server, never per call.
     */
    public static ColonyState get(MinecraftServer server) {
        ColonyState state = SavedDataRecovery.get(server.overworld(), TYPE, ColonyState::new, NAME);
        if (prunedFor != server) {
            prunedFor = server; // set first: the sweep must never re-enter itself
            state.sweep(server);
        }
        return state;
    }

    // --- queries ------------------------------------------------------------

    @Nullable
    public Colony colony(UUID colonyId) {
        return colonyId == null ? null : this.byId.get(colonyId);
    }

    /** Every colony, in insertion order. */
    public Collection<Colony> colonies() {
        return List.copyOf(this.byId.values());
    }

    public int size() {
        return this.byId.size();
    }

    /** The colonies in one dimension. */
    public List<Colony> coloniesIn(ResourceKey<Level> dimension) {
        Set<UUID> ids = this.byDimension.get(dimension);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Colony> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Colony colony = this.byId.get(id);
            if (colony != null) {
                out.add(colony);
            }
        }
        return out;
    }

    /**
     * The colony whose claim contains {@code pos}, or {@code null}. O(1): the chunk index answers
     * first and the exact square test only runs for the one candidate.
     */
    @Nullable
    public Colony colonyAt(ResourceKey<Level> dimension, BlockPos pos) {
        UUID id = this.byChunk.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (id == null) {
            return null;
        }
        Colony colony = this.byId.get(id);
        if (colony == null || !colony.dimension().equals(dimension) || !colony.contains(pos)) {
            return null;
        }
        return colony;
    }

    /** Server-side only: how many colonies this player owns. */
    public int ownedCount(UUID player) {
        if (player == null || Colony.SERVER_OWNER.equals(player)) {
            return 0;
        }
        int total = 0;
        for (Colony colony : this.byId.values()) {
            if (colony.isOwner(player)) {
                total++;
            }
        }
        return total;
    }

    /** Server-side only: the ids of the colonies this player owns or is a member of. */
    public List<UUID> memberOf(UUID player) {
        if (player == null) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (Colony colony : this.byId.values()) {
            if (colony.isMember(player)) {
                out.add(colony.colonyId());
            }
        }
        return out;
    }

    // --- outposts -----------------------------------------------------------

    /** One outpost by id, or {@code null}. */
    @Nullable
    public Outpost outpost(UUID outpostId) {
        return outpostId == null ? null : this.outposts.get(outpostId);
    }

    /** Every outpost, in insertion order. */
    public Collection<Outpost> allOutposts() {
        return List.copyOf(this.outposts.values());
    }

    /** The outposts parented to one colony, skipping ids whose record has gone. */
    public List<Outpost> outpostsOf(UUID colonyId) {
        Colony colony = colony(colonyId);
        if (colony == null || colony.outpostIds().isEmpty()) {
            return List.of();
        }
        List<Outpost> out = new ArrayList<>(colony.outpostIds().size());
        for (UUID id : colony.outpostIds()) {
            Outpost outpost = this.outposts.get(id);
            if (outpost != null) {
                out.add(outpost);
            }
        }
        return out;
    }

    /**
     * The outpost whose claim contains {@code pos}, or {@code null}. Same O(1) chunk-index shape as
     * {@link #colonyAt}; outposts have their own index because their claims are separate ground.
     */
    @Nullable
    public Outpost outpostAt(ResourceKey<Level> dimension, BlockPos pos) {
        UUID id = this.outpostByChunk.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (id == null) {
            return null;
        }
        Outpost outpost = this.outposts.get(id);
        if (outpost == null || !outpost.dimension().equals(dimension) || !outpost.contains(pos)) {
            return null;
        }
        return outpost;
    }

    /**
     * Inserts or replaces an outpost and links it to its parent colony's id set, so the link is
     * navigable in both directions and neither side can be updated without the other.
     */
    public void putOutpost(Outpost outpost) {
        if (outpost == null) {
            return;
        }
        Outpost previous = this.outposts.put(outpost.outpostId(), outpost);
        if (previous != null) {
            forEachClaimedChunk(previous.pos(), previous.claimRadius(),
                    key -> this.outpostByChunk.remove(key, previous.outpostId()));
        }
        forEachClaimedChunk(outpost.pos(), outpost.claimRadius(),
                key -> this.outpostByChunk.put(key, outpost.outpostId()));
        Colony parent = this.byId.get(outpost.parentColonyId());
        if (parent != null && !parent.outpostIds().contains(outpost.outpostId())) {
            Set<UUID> ids = new LinkedHashSet<>(parent.outpostIds());
            ids.add(outpost.outpostId());
            put(parent.withOutposts(ids));
        }
        this.setDirty();
    }

    /**
     * Removes an outpost and unlinks it from its parent.
     *
     * @return the removed outpost, or {@code null} if there was none
     */
    @Nullable
    public Outpost removeOutpost(UUID outpostId) {
        Outpost removed = this.outposts.remove(outpostId);
        if (removed == null) {
            return null;
        }
        forEachClaimedChunk(removed.pos(), removed.claimRadius(),
                key -> this.outpostByChunk.remove(key, removed.outpostId()));
        Colony parent = this.byId.get(removed.parentColonyId());
        if (parent != null && parent.outpostIds().contains(outpostId)) {
            Set<UUID> ids = new LinkedHashSet<>(parent.outpostIds());
            ids.remove(outpostId);
            put(parent.withOutposts(ids));
        }
        this.setDirty();
        return removed;
    }

    // --- edits --------------------------------------------------------------

    /** Inserts or replaces a colony and refreshes the derived indexes. */
    public void put(Colony colony) {
        if (colony == null) {
            return;
        }
        Colony previous = this.byId.put(colony.colonyId(), colony);
        if (previous != null) {
            unindex(previous);
        }
        index(colony);
        this.setDirty();
    }

    /**
     * Removes a colony (dissolve). Its access-log rows go with it — the rows only exist to explain
     * what happened to a colony that still exists.
     *
     * @return the removed colony, or {@code null} if there was none
     */
    @Nullable
    public Colony remove(UUID colonyId) {
        Colony removed = this.byId.remove(colonyId);
        if (removed == null) {
            return null;
        }
        unindex(removed);
        this.accessLog.remove(colonyId);
        // Outposts have no independent existence: their parent is gone, so they go with it.
        for (UUID outpostId : removed.outpostIds()) {
            Outpost outpost = this.outposts.remove(outpostId);
            if (outpost != null) {
                forEachClaimedChunk(outpost.pos(), outpost.claimRadius(),
                        key -> this.outpostByChunk.remove(key, outpostId));
            }
        }
        this.setDirty();
        return removed;
    }

    /**
     * Records one action against a colony, if {@code accessLogEnabled} is on. A no-op otherwise —
     * the check lives here so no call site can accidentally log while the feature is off.
     */
    public void log(UUID colonyId, UUID player, AccessLog.Action action) {
        if (colonyId == null || player == null || !NeroColoniesConfig.ACCESS_LOG_ENABLED.get()) {
            return;
        }
        List<AccessLog.Entry> rows = this.accessLog.computeIfAbsent(colonyId, key -> new ArrayList<>());
        if (rows.size() >= AccessLog.MAX_ROWS_PER_COLONY) {
            rows.removeFirst();
        }
        rows.add(AccessLog.Entry.now(player, action));
        this.setDirty();
    }

    /** The rows recorded for one colony, oldest first (empty when logging is off). */
    public List<AccessLog.Entry> logFor(UUID colonyId) {
        List<AccessLog.Entry> rows = this.accessLog.get(colonyId);
        return rows == null || rows.isEmpty() ? List.of() : List.copyOf(rows);
    }

    // --- privacy: erasure, retention, export --------------------------------

    /**
     * POPIA/GDPR erasure for one player: strips the UUID from every access list, purges its
     * access-log rows, and deals with the colonies it owns per {@code erasureOwnedColonyPolicy} —
     * either transferring them to the server (default: the colony keeps running, ownerless, so a
     * co-op server is not griefed by an erasure request) or dissolving them.
     *
     * <p>Returns counts only; callers log counts only. Never the identity.
     *
     * @return {@code [ownedHandled, accessRemoved, logRowsRemoved]}
     */
    public int[] forgetPlayer(UUID player) {
        if (player == null || Colony.SERVER_OWNER.equals(player)) {
            return new int[] {0, 0, 0};
        }
        boolean dissolve = NeroColoniesConfig.erasureDissolves();
        int owned = 0;
        int access = 0;
        List<UUID> toDissolve = new ArrayList<>();
        for (Colony colony : List.copyOf(this.byId.values())) {
            Colony updated = colony;
            if (colony.isOwner(player)) {
                owned++;
                if (dissolve) {
                    toDissolve.add(colony.colonyId());
                    continue;
                }
                updated = updated.withOwner(Colony.SERVER_OWNER);
            }
            if (updated.accessList().contains(player)) {
                access++;
                updated = updated.revokeAccess(player);
            }
            if (updated != colony) {
                put(updated);
            }
        }
        for (UUID id : toDissolve) {
            remove(id);
        }
        int rows = purgeLogRows(entry -> player.equals(entry.player()));
        if (owned > 0 || access > 0 || rows > 0) {
            this.setDirty();
        }
        return new int[] {owned, access, rows};
    }

    /**
     * The retention pass, run once per server from {@link #get}: drops expired access-log rows and
     * colonies whose beacon block is gone. Both are bounded, both are cheap, and neither touches an
     * unloaded chunk — an unloaded colony is simply left for the next session.
     *
     * @return {@code [expiredAccessLogRows, orphanedColonies, orphanedOutposts]} — counts only, so an
     *         operator command can report what a sweep did without the caller ever seeing a record
     */
    public int[] sweep(MinecraftServer server) {
        int days = NeroColoniesConfig.ACCESS_LOG_RETENTION_DAYS.get();
        long nowSeconds = System.currentTimeMillis() / 1000L;
        int expiredRows = purgeLogRows(entry -> entry.expired(nowSeconds, days));

        int orphans = 0;
        for (Colony colony : List.copyOf(this.byId.values())) {
            ServerLevel level = server.getLevel(colony.dimension());
            if (level == null || !level.isLoaded(colony.beaconPos())) {
                continue; // unloaded: not evidence of anything
            }
            if (!beaconCheck.test(level, colony.beaconPos())) {
                remove(colony.colonyId());
                // The goods go with the colony. They cannot be dropped — a beacon that vanished
                // without a break event (an explosion, a world edit) left no place to drop them —
                // but leaving the store behind would leak it forever.
                ColonyStores.get(server).forget(colony.colonyId());
                orphans++;
            }
        }

        int orphanedOutposts = 0;
        for (Outpost outpost : List.copyOf(this.outposts.values())) {
            if (!this.byId.containsKey(outpost.parentColonyId())) {
                removeOutpost(outpost.outpostId());
                orphanedOutposts++;
                continue;
            }
            ServerLevel level = server.getLevel(outpost.dimension());
            if (level == null || !level.isLoaded(outpost.pos())) {
                continue;
            }
            if (!outpostCheck.test(level, outpost.pos())) {
                removeOutpost(outpost.outpostId());
                orphanedOutposts++;
            }
        }

        if (expiredRows > 0 || orphans > 0 || orphanedOutposts > 0) {
            // Counts only — never which colonies or which players (POPIA/GDPR).
            NeroColoniesCommon.LOGGER.info(
                    "[NeroColonies] Retention: dropped {} expired access-log row(s), {} orphaned "
                            + "colony record(s) and {} orphaned outpost record(s).",
                    expiredRows, orphans, orphanedOutposts);
        }
        return new int[] {expiredRows, orphans, orphanedOutposts};
    }

    /**
     * A data-access export of exactly one player's own colony-related records and nothing else:
     * the ids of colonies they own or are a member of, and their own access-log rows. No other
     * player's UUID appears anywhere in the result.
     */
    public JsonObject export(UUID player) {
        JsonObject root = new JsonObject();
        JsonArray ownedIds = new JsonArray();
        JsonArray memberIds = new JsonArray();
        for (Colony colony : this.byId.values()) {
            if (colony.isOwner(player)) {
                ownedIds.add(colony.colonyId().toString());
            } else if (colony.accessList().contains(player)) {
                memberIds.add(colony.colonyId().toString());
            }
        }
        root.add("owned_colonies", ownedIds);
        root.add("member_of_colonies", memberIds);
        JsonArray rows = new JsonArray();
        this.accessLog.forEach((colonyId, entries) -> {
            for (AccessLog.Entry entry : entries) {
                if (player.equals(entry.player())) {
                    JsonObject row = new JsonObject();
                    row.addProperty("colony", colonyId.toString());
                    row.addProperty("action", entry.action().key());
                    row.addProperty("at", entry.epochSeconds());
                    rows.add(row);
                }
            }
        });
        root.add("access_log", rows);
        return root;
    }

    /** Milliseconds-per-day, exposed so later stages share one definition of "a day". */
    public static long millisPerDay() {
        return MILLIS_PER_DAY;
    }

    // --- internals ----------------------------------------------------------

    private int purgeLogRows(java.util.function.Predicate<AccessLog.Entry> doomed) {
        int removed = 0;
        for (Map.Entry<UUID, List<AccessLog.Entry>> entry : List.copyOf(this.accessLog.entrySet())) {
            List<AccessLog.Entry> rows = entry.getValue();
            int before = rows.size();
            rows.removeIf(doomed);
            removed += before - rows.size();
            if (rows.isEmpty()) {
                this.accessLog.remove(entry.getKey());
            }
        }
        if (removed > 0) {
            this.setDirty();
        }
        return removed;
    }

    private void index(Colony colony) {
        this.byDimension.computeIfAbsent(colony.dimension(), key -> new LinkedHashSet<>())
                .add(colony.colonyId());
        forEachClaimedChunk(colony, key -> this.byChunk.put(key, colony.colonyId()));
    }

    private void unindex(Colony colony) {
        Set<UUID> ids = this.byDimension.get(colony.dimension());
        if (ids != null) {
            ids.remove(colony.colonyId());
            if (ids.isEmpty()) {
                this.byDimension.remove(colony.dimension());
            }
        }
        forEachClaimedChunk(colony, key -> this.byChunk.remove(key, colony.colonyId()));
    }

    private static void forEachClaimedChunk(Colony colony, java.util.function.LongConsumer action) {
        forEachClaimedChunk(colony.beaconPos(), colony.claimRadius(), action);
    }

    private static void forEachClaimedChunk(BlockPos pos, int radius,
            java.util.function.LongConsumer action) {
        int minX = (pos.getX() - radius) >> 4;
        int maxX = (pos.getX() + radius) >> 4;
        int minZ = (pos.getZ() - radius) >> 4;
        int maxZ = (pos.getZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                action.accept(ChunkPos.pack(x, z));
            }
        }
    }

    private void rebuildIndexes() {
        this.byDimension.clear();
        this.byChunk.clear();
        this.outpostByChunk.clear();
        for (Colony colony : this.byId.values()) {
            index(colony);
        }
        for (Outpost outpost : this.outposts.values()) {
            forEachClaimedChunk(outpost.pos(), outpost.claimRadius(),
                    key -> this.outpostByChunk.put(key, outpost.outpostId()));
        }
    }

    // --- persistence (same SavedDataType + Codec pattern as Core) -----------

    private record LogRows(UUID colony, List<AccessLog.Entry> rows) {
        static final Codec<LogRows> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(LogRows::colony),
                AccessLog.Entry.CODEC.listOf().optionalFieldOf("rows", List.of()).forGetter(LogRows::rows)
        ).apply(instance, LogRows::new));
    }

    private static Codec<ColonyState> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Colony.CODEC.listOf().optionalFieldOf("colonies", List.of())
                        .forGetter(ColonyState::colonyList),
                LogRows.CODEC.listOf().optionalFieldOf("access_log", List.of())
                        .forGetter(ColonyState::logRows),
                Outpost.CODEC.listOf().optionalFieldOf("outposts", List.of())
                        .forGetter(ColonyState::outpostList)
        ).apply(instance, ColonyState::fromParts));
    }

    private List<Outpost> outpostList() {
        return List.copyOf(this.outposts.values());
    }

    private List<Colony> colonyList() {
        return List.copyOf(this.byId.values());
    }

    private List<LogRows> logRows() {
        List<LogRows> out = new ArrayList<>(this.accessLog.size());
        this.accessLog.forEach((colony, rows) -> out.add(new LogRows(colony, List.copyOf(rows))));
        return out;
    }

    private static ColonyState fromParts(List<Colony> colonies, List<LogRows> log,
            List<Outpost> outposts) {
        ColonyState state = new ColonyState();
        for (Colony colony : colonies) {
            state.byId.put(colony.colonyId(), colony);
        }
        for (LogRows rows : log) {
            if (state.byId.containsKey(rows.colony()) && !rows.rows().isEmpty()) {
                state.accessLog.put(rows.colony(), new ArrayList<>(rows.rows()));
            }
        }
        for (Outpost outpost : outposts) {
            // An outpost whose parent did not survive the load is dropped here rather than being
            // re-parented: an outpost that silently attached itself to a neighbour would be a claim
            // exploit. The retention sweep reports the same case for records that orphan later.
            if (state.byId.containsKey(outpost.parentColonyId())) {
                state.outposts.put(outpost.outpostId(), outpost);
            }
        }
        state.rebuildIndexes();
        return state;
    }
}
