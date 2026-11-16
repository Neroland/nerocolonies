package za.co.neroland.nerocolonies.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyPermissions.Role;

/**
 * G6 parity: the map's re-implemented rule ({@link ColonyMapAccess}) against the link module's contract
 * ({@code link/ColonyLinkAccess.coloniesOf}: owned plus Ally or Chief, never an Enemy or a stranger, never the
 * sandbox, never widened) for owner, member, stranger and erased-owner fixtures. The link class needs a running
 * server for its stores, so both sides are pinned to the same matrix here, and the role check is the link's own
 * public {@link Role#member()} predicate.
 */
class ColonyMapParityTest {

    /** The colonies each viewer must see, as the link module's rule defines them. */
    private static Map<UUID, Set<UUID>> linkContract() {
        Map<UUID, Set<UUID>> m = new LinkedHashMap<>();
        m.put(ColonyMapFixture.OWNER, Set.of(ColonyMapFixture.OWN_COLONY));
        m.put(ColonyMapFixture.ALLY, Set.of(ColonyMapFixture.OWN_COLONY, ColonyMapFixture.ERASED_COLONY));
        m.put(ColonyMapFixture.CHIEF, Set.of(ColonyMapFixture.OWN_COLONY));
        m.put(ColonyMapFixture.ENEMY, Set.of());
        m.put(ColonyMapFixture.STRANGER, Set.of());
        m.put(ColonyMapFixture.THIRD, Set.of(ColonyMapFixture.THIRD_COLONY));
        m.put(ColonyMapFixture.ERASED, Set.of());
        m.put(Colony.SERVER_OWNER, Set.of());
        return m;
    }

    @Test
    void mapRuleMatchesTheLinkContract() {
        ColonyMapFixture f = new ColonyMapFixture();
        for (Map.Entry<UUID, Set<UUID>> e : linkContract().entrySet()) {
            Set<UUID> seen = ColonyMapAccess.visible(f.colonies.values(), f, f::sandbox, e.getKey()).stream()
                    .map(Colony::colonyId).collect(Collectors.toSet());
            assertEquals(e.getValue(), seen, "viewer " + e.getKey());
        }
    }

    @Test
    void rolesMatchTheLinkRoleTable() {
        ColonyMapFixture f = new ColonyMapFixture();
        Colony own = f.colonies.get(ColonyMapFixture.OWN_COLONY);
        assertEquals(Role.OWNER, ColonyMapAccess.roleOf(own, f, ColonyMapFixture.OWNER));
        assertEquals(Role.CHIEF, ColonyMapAccess.roleOf(own, f, ColonyMapFixture.CHIEF));
        assertEquals(Role.ALLY, ColonyMapAccess.roleOf(own, f, ColonyMapFixture.ALLY));
        assertEquals(Role.ENEMY, ColonyMapAccess.roleOf(own, f, ColonyMapFixture.ENEMY));
        assertEquals(Role.STRANGER, ColonyMapAccess.roleOf(own, f, ColonyMapFixture.STRANGER));
        Colony erased = f.colonies.get(ColonyMapFixture.ERASED_COLONY);
        assertEquals(Role.STRANGER, ColonyMapAccess.roleOf(erased, f, Colony.SERVER_OWNER),
                "the erased-owner slot is nobody");
        for (Role role : Role.values()) {
            boolean storage = ColonyMapAccess.seesStorage(role);
            assertEquals(ColonyPermissions.allows(role, ColonyPermissions.Action.PLAN, false), storage,
                    "storage follows the owner/Chief tier: " + role);
        }
    }

    @Test
    void providerBordersMatchTheRule() {
        ColonyMapFixture f = new ColonyMapFixture();
        for (UUID viewer : List.of(ColonyMapFixture.OWNER, ColonyMapFixture.ALLY, ColonyMapFixture.ENEMY,
                ColonyMapFixture.ERASED)) {
            za.co.neroland.nerolandcore.map.MapLayerRegistry.register(f.provider());
            var r = za.co.neroland.nerolandcore.map.MapLayerRegistry.query("nerocolonies",
                    za.co.neroland.nerolandcore.map.MapViewer.forTest(viewer, null, null, null), ColonyMapFixture.HOME,
                    za.co.neroland.nerolandcore.map.MapBounds.around(0, 0, 1024));
            Set<String> borders = r.areas().stream().map(a -> a.id()).filter(i -> i.startsWith("colony/"))
                    .collect(Collectors.toSet());
            Set<String> expected = linkContract().get(viewer).stream().map(c -> "colony/" + c)
                    .collect(Collectors.toSet());
            assertEquals(expected, borders, "viewer " + viewer);
        }
    }

    /**
     * The same matrix over the real {@code ColonyState} / {@code ColonyRoles} stores, read the way the live source
     * reads them ({@code memberOf}, {@code isChief}, {@code isEnemy}). Older Minecraft lines cannot index a
     * colony's chunks without a bootstrapped game, so there this test is skipped rather than failed.
     */
    @Test
    void mapRuleMatchesTheLinkContractOverTheRealStores() {
        za.co.neroland.nerocolonies.colony.ColonyState state;
        za.co.neroland.nerocolonies.colony.ColonyRoles roles;
        try {
            state = new za.co.neroland.nerocolonies.colony.ColonyState();
            roles = new za.co.neroland.nerocolonies.colony.ColonyRoles();
            new ColonyMapFixture().colonies.values().forEach(state::put);
        } catch (Throwable notBootstrapped) {
            org.junit.jupiter.api.Assumptions.abort("SavedData needs a bootstrapped game on this Minecraft line");
            return;
        }
        ColonyMapFixture f = new ColonyMapFixture();
        roles.addChief(ColonyMapFixture.OWN_COLONY, ColonyMapFixture.CHIEF);
        roles.addEnemy(ColonyMapFixture.OWN_COLONY, ColonyMapFixture.ENEMY);
        ColonyMapAccess.Roles real = new ColonyMapAccess.Roles() {
            @Override
            public boolean isChief(UUID colonyId, UUID player) {
                return roles.isChief(colonyId, player);
            }

            @Override
            public boolean isEnemy(UUID colonyId, UUID player) {
                return roles.isEnemy(colonyId, player);
            }
        };
        for (Map.Entry<UUID, Set<UUID>> e : linkContract().entrySet()) {
            List<Colony> candidates = new java.util.ArrayList<>();
            for (UUID id : state.memberOf(e.getKey())) {
                candidates.add(state.colony(id));
            }
            Set<UUID> seen = ColonyMapAccess.visible(candidates, real, f::sandbox, e.getKey()).stream()
                    .map(Colony::colonyId).collect(Collectors.toSet());
            assertEquals(e.getValue(), seen, "viewer " + e.getKey());
        }
    }
}
