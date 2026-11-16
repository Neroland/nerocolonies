package za.co.neroland.nerocolonies.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.Outpost;

/**
 * Seeded stores for the map tests: the viewer's own colony (with an Ally, a Chief and an Enemy), a third player's
 * colony, a colony whose owner was erased, and a sandbox colony. No player names anywhere.
 */
final class ColonyMapFixture implements ColonyMapSource {

    static final ResourceKey<Level> HOME = dim("home_test");
    static final ResourceKey<Level> MOON = dim("moon_test");

    static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID THIRD = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final UUID ALLY = UUID.fromString("00000000-0000-4000-8000-000000000004");
    static final UUID CHIEF = UUID.fromString("00000000-0000-4000-8000-000000000005");
    static final UUID ENEMY = UUID.fromString("00000000-0000-4000-8000-000000000006");
    static final UUID ERASED = UUID.fromString("00000000-0000-4000-8000-000000000007");

    static final UUID OWN_COLONY = UUID.nameUUIDFromBytes("own_colony".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final UUID THIRD_COLONY = UUID.nameUUIDFromBytes("third_colony".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final UUID ERASED_COLONY = UUID.nameUUIDFromBytes("erased_colony".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final UUID SANDBOX_COLONY = UUID.nameUUIDFromBytes("sandbox_colony".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final UUID OWN_OUTPOST = UUID.nameUUIDFromBytes("own_outpost".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final UUID THIRD_OUTPOST = UUID.nameUUIDFromBytes("third_outpost".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    final Map<UUID, Colony> colonies = new LinkedHashMap<>();
    final Set<String> chiefs = new HashSet<>();
    final Set<String> enemies = new HashSet<>();
    final Set<UUID> sandboxes = Set.of(SANDBOX_COLONY);
    final List<Outpost> outposts = new ArrayList<>();
    final Map<UUID, List<Building>> buildings = new HashMap<>();
    final Map<UUID, List<Storage>> storage = new HashMap<>();

    ColonyMapFixture() {
        put(Colony.found(OWN_COLONY, "Harbour", HOME, new BlockPos(0, 64, 0), 32, OWNER, 0L)
                .withAccessList(Set.of(ALLY, CHIEF, ENEMY)));
        chiefs.add(OWN_COLONY + "/" + CHIEF);
        enemies.add(OWN_COLONY + "/" + ENEMY);
        put(Colony.found(THIRD_COLONY, "Ridge", HOME, new BlockPos(100, 64, 100), 24, THIRD, 0L));
        put(Colony.found(ERASED_COLONY, "Old Mill", HOME, new BlockPos(-200, 64, 0), 16, Colony.SERVER_OWNER, 0L)
                .withAccessList(Set.of(ALLY)));
        put(Colony.found(SANDBOX_COLONY, "Gallery", HOME, new BlockPos(0, 64, 300), 16, OWNER, 0L));
        outposts.add(new Outpost(OWN_OUTPOST, OWN_COLONY, HOME, new BlockPos(40, 64, 0), 8, 0L));
        outposts.add(new Outpost(THIRD_OUTPOST, THIRD_COLONY, HOME, new BlockPos(120, 64, 100), 8, 0L));
        buildings.put(OWN_COLONY, List.of(new Building(new BlockPos(4, 64, 4), "blueprint.nerocolonies.neran_cottage"),
                new Building(new BlockPos(-6, 64, 3), "Not A Key!")));
        buildings.put(THIRD_COLONY, List.of(new Building(new BlockPos(104, 64, 104), "blueprint.nerocolonies.farm")));
        storage.put(OWN_COLONY, List.of(new Storage(HOME, new BlockPos(2, 64, -3), true)));
        storage.put(THIRD_COLONY, List.of(new Storage(HOME, new BlockPos(101, 64, 99), false)));
    }

    private void put(Colony colony) {
        colonies.put(colony.colonyId(), colony);
    }

    static ResourceKey<Level> dim(String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("nerocolonies_test", path));
    }

    @Override
    public List<Colony> memberColonies(UUID player) {
        List<Colony> out = new ArrayList<>();
        for (Colony c : colonies.values()) {
            if (c.isMember(player)) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public boolean isChief(UUID colonyId, UUID player) {
        return chiefs.contains(colonyId + "/" + player);
    }

    @Override
    public boolean isEnemy(UUID colonyId, UUID player) {
        return enemies.contains(colonyId + "/" + player);
    }

    @Override
    public List<Outpost> outposts() {
        return outposts;
    }

    @Override
    public boolean sandbox(UUID colonyId) {
        return sandboxes.contains(colonyId);
    }

    @Override
    public List<Building> buildings(Colony colony) {
        return new ArrayList<>(buildings.getOrDefault(colony.colonyId(), List.of()));
    }

    @Override
    public List<Storage> storage(UUID colonyId) {
        return storage.getOrDefault(colonyId, List.of());
    }

    ColonyMapLayers provider() {
        return new ColonyMapLayers(server -> this);
    }
}
