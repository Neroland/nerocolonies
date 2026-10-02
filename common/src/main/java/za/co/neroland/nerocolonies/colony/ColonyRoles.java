package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.data.LenientCodecs;
import za.co.neroland.nerocolonies.data.SavedDataRecovery;

/**
 * Chiefs and Enemies, per colony.
 *
 * <h2>How the three lists fit together</h2>
 *
 * <ul>
 *   <li><b>Allies</b> are the colony's existing access list on the {@link Colony} record. Nothing was
 *       migrated because nothing needed to move: every player who had access before 0.3 is an Ally
 *       now, with exactly the rights they had.</li>
 *   <li><b>Chiefs</b> are Allies who may also plan buildings and manage Allies and Enemies. A Chief
 *       is always on the access list as well, so every "is this a member?" check keeps working; this
 *       store only records the extra rank.</li>
 *   <li><b>Enemies</b> are never members. Marking an Ally or Chief as an Enemy removes them from the
 *       access list and the Chief list first.</li>
 * </ul>
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Player UUIDs, stored because the feature cannot work without them; nothing else about the
 * player. Each list is capped at {@link Colony#MAX_ACCESS_LIST}. They live as long as the colony,
 * are removed by every dissolve path, and a data-erasure request removes the player from every list
 * of every colony ({@link #forgetPlayer}) — including an Enemy list, because the law outranks the
 * gameplay rule that only retribution or a Chief can lift an enmity. Names are never stored: the
 * role GUI resolves them from the server's profile cache at render time.
 */
public final class ColonyRoles extends SavedData {

    public static final String NAME = NeroColoniesCommon.MOD_ID + ":roles";

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "roles");

    public static final SavedDataType<ColonyRoles> TYPE =
            new SavedDataType<>(ID, ColonyRoles::new, codec(), null);

    private final Map<UUID, Lists> byColony = new LinkedHashMap<>();

    public ColonyRoles() {
    }

    public static ColonyRoles get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ColonyRoles::new, NAME);
    }

    /** A colony's two extra lists. */
    public static final class Lists {
        private final Set<UUID> chiefs = new LinkedHashSet<>();
        private final Set<UUID> enemies = new LinkedHashSet<>();

        public Set<UUID> chiefs() {
            return Set.copyOf(this.chiefs);
        }

        public Set<UUID> enemies() {
            return Set.copyOf(this.enemies);
        }
    }

    @Nullable
    private Lists peek(UUID colonyId) {
        return this.byColony.get(colonyId);
    }

    private Lists lists(UUID colonyId) {
        return this.byColony.computeIfAbsent(colonyId, key -> new Lists());
    }

    public boolean isChief(UUID colonyId, UUID player) {
        Lists lists = peek(colonyId);
        return lists != null && lists.chiefs.contains(player);
    }

    public boolean isEnemy(UUID colonyId, UUID player) {
        Lists lists = peek(colonyId);
        return lists != null && lists.enemies.contains(player);
    }

    public Set<UUID> chiefs(UUID colonyId) {
        Lists lists = peek(colonyId);
        return lists == null ? Set.of() : lists.chiefs();
    }

    public Set<UUID> enemies(UUID colonyId) {
        Lists lists = peek(colonyId);
        return lists == null ? Set.of() : lists.enemies();
    }

    /** Adds a Chief. Returns false when the list is full or they already are one. */
    public boolean addChief(UUID colonyId, UUID player) {
        Lists lists = lists(colonyId);
        if (lists.chiefs.contains(player) || lists.chiefs.size() >= Colony.MAX_ACCESS_LIST) {
            return false;
        }
        lists.enemies.remove(player);
        lists.chiefs.add(player);
        setDirty();
        return true;
    }

    public boolean removeChief(UUID colonyId, UUID player) {
        Lists lists = peek(colonyId);
        if (lists == null || !lists.chiefs.remove(player)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Adds an Enemy (also dropping any Chief rank). Returns false when full or already listed. */
    public boolean addEnemy(UUID colonyId, UUID player) {
        Lists lists = lists(colonyId);
        if (lists.enemies.contains(player) || lists.enemies.size() >= Colony.MAX_ACCESS_LIST) {
            return false;
        }
        lists.chiefs.remove(player);
        lists.enemies.add(player);
        setDirty();
        return true;
    }

    public boolean removeEnemy(UUID colonyId, UUID player) {
        Lists lists = peek(colonyId);
        if (lists == null || !lists.enemies.remove(player)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Every colony that lists {@code player} as an Enemy. */
    public List<UUID> coloniesWithEnemy(UUID player) {
        List<UUID> out = new ArrayList<>();
        this.byColony.forEach((colony, lists) -> {
            if (lists.enemies.contains(player)) {
                out.add(colony);
            }
        });
        return out;
    }

    /**
     * Adds one player's own role rows to their data export: the ids of colonies where they are a
     * Chief and of colonies that list them as an Enemy. No other player's id appears.
     */
    public void exportInto(com.google.gson.JsonObject root, UUID player) {
        com.google.gson.JsonArray chiefOf = new com.google.gson.JsonArray();
        com.google.gson.JsonArray enemyOf = new com.google.gson.JsonArray();
        this.byColony.forEach((colony, lists) -> {
            if (lists.chiefs.contains(player)) {
                chiefOf.add(colony.toString());
            }
            if (lists.enemies.contains(player)) {
                enemyOf.add(colony.toString());
            }
        });
        root.add("chief_of_colonies", chiefOf);
        root.add("enemy_of_colonies", enemyOf);
    }

    /** Forgets a colony's lists. Called from every dissolve path. */
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

    /**
     * POPIA/GDPR erasure: removes {@code player} from every Chief and Enemy list.
     *
     * @return how many list entries were removed (a count, for the erasure log)
     */
    public int forgetPlayer(UUID player) {
        int removed = 0;
        for (Lists lists : this.byColony.values()) {
            if (lists.chiefs.remove(player)) {
                removed++;
            }
            if (lists.enemies.remove(player)) {
                removed++;
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** Counts for one colony: {chiefs, enemies}. For snapshots and GUIs — never the ids. */
    public int[] counts(UUID colonyId) {
        Lists lists = peek(colonyId);
        return lists == null ? new int[] {0, 0} : new int[] {lists.chiefs.size(), lists.enemies.size()};
    }

    // --- persistence -----------------------------------------------------------

    private record Row(UUID colony, List<UUID> chiefs, List<UUID> enemies) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("colony").forGetter(Row::colony),
                LenientCodecs.list(Colony.UUID_CODEC, "chief").optionalFieldOf("chiefs", List.of())
                        .forGetter(Row::chiefs),
                LenientCodecs.list(Colony.UUID_CODEC, "enemy").optionalFieldOf("enemies", List.of())
                        .forGetter(Row::enemies)
        ).apply(instance, Row::new));
    }

    private static Codec<ColonyRoles> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                LenientCodecs.list(Row.CODEC, "role list").optionalFieldOf("colonies", List.of())
                        .forGetter(ColonyRoles::rows)
        ).apply(instance, ColonyRoles::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        this.byColony.forEach((colony, lists) -> {
            if (!lists.chiefs.isEmpty() || !lists.enemies.isEmpty()) {
                out.add(new Row(colony, List.copyOf(lists.chiefs), List.copyOf(lists.enemies)));
            }
        });
        return out;
    }

    private static ColonyRoles fromRows(List<Row> rows) {
        ColonyRoles store = new ColonyRoles();
        for (Row row : rows) {
            Lists lists = store.lists(row.colony());
            for (UUID chief : row.chiefs()) {
                if (lists.chiefs.size() < Colony.MAX_ACCESS_LIST) {
                    lists.chiefs.add(chief);
                }
            }
            for (UUID enemy : row.enemies()) {
                if (lists.enemies.size() < Colony.MAX_ACCESS_LIST && !lists.chiefs.contains(enemy)) {
                    lists.enemies.add(enemy);
                }
            }
        }
        return store;
    }
}
