package za.co.neroland.nerocolonies.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.ALLY;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.CHIEF;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.ENEMY;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.HOME;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.MOON;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.OWNER;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.STRANGER;
import static za.co.neroland.nerocolonies.map.ColonyMapFixture.THIRD;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.map.MapBounds;
import za.co.neroland.nerolandcore.map.MapLayerConformance;
import za.co.neroland.nerolandcore.map.MapLayerInfo;
import za.co.neroland.nerolandcore.map.MapLayerRegistry;
import za.co.neroland.nerolandcore.map.MapQueryResult;
import za.co.neroland.nerolandcore.map.MapScope;
import za.co.neroland.nerolandcore.map.MapViewer;

/**
 * NeroColonies' map layers against Core's {@link MapLayerConformance} fixture (two viewers, caps, absent dimension,
 * per-marker scope, identity), plus the role-specific rules the fixture cannot know: storage for the owner and
 * Chiefs only, nothing for an Enemy, nothing from the sandbox colony.
 */
class ColonyMapLayersTest {

    private static final MapBounds NEAR = MapBounds.around(0, 0, 512);

    private static MapViewer online(UUID id, int x, int z) {
        return MapViewer.forTest(id, null, HOME, new BlockPos(x, 64, z));
    }

    @Test
    void conformance() {
        ColonyMapFixture fixture = new ColonyMapFixture();
        MapLayerConformance.create(fixture.provider())
                .viewers(online(OWNER, 0, 0), online(STRANGER, 5, 0))
                .dimensions(HOME, MOON)
                .bounds(NEAR)
                .farBounds(MapBounds.around(20_000, 20_000, 256))
                .forbiddenText("Trudy", "Sam")
                .forbiddenIds(THIRD, ALLY, CHIEF, ENEMY)
                .expectEntitledSees(ColonyMapLayers.BORDER, 1)
                .expectEntitledSees(ColonyMapLayers.BUILDINGS, 3)
                .expectEntitledSees(ColonyMapLayers.OUTPOSTS, 2)
                .verify();
    }

    @Test
    void conformanceForAnAllyViewer() {
        ColonyMapFixture fixture = new ColonyMapFixture();
        MapLayerConformance.create(fixture.provider())
                .viewers(online(ALLY, 0, 0), online(STRANGER, 5, 0))
                .dimensions(HOME, MOON)
                .bounds(NEAR)
                .farBounds(MapBounds.around(20_000, 20_000, 256))
                .forbiddenIds(OWNER, THIRD, CHIEF, ENEMY)
                .expectEntitledSees(ColonyMapLayers.BORDER, 2)
                .expectEntitledSees(ColonyMapLayers.BUILDINGS, 2)
                .expectEntitledSees(ColonyMapLayers.OUTPOSTS, 2)
                .verify();
    }

    @Test
    void everyLayerIsMembersScoped() {
        for (MapLayerInfo info : new ColonyMapFixture().provider().layers()) {
            assertEquals(MapScope.MEMBERS, info.scope(), info.id().toString());
        }
    }

    private static List<String> ids(MapViewer viewer) {
        return ids(viewer, HOME);
    }

    private static List<String> ids(MapViewer viewer, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        MapLayerRegistry.register(new ColonyMapFixture().provider());
        MapQueryResult r = MapLayerRegistry.query("nerocolonies", viewer, dim, NEAR);
        assertFalse(r.failed());
        List<String> out = r.markers().stream().map(m -> m.id()).collect(Collectors.toList());
        r.areas().forEach(a -> out.add(a.id()));
        return out;
    }

    @Test
    void storageIsForOwnerAndChiefOnly() {
        assertTrue(ids(online(OWNER, 0, 0)).stream().anyMatch(i -> i.startsWith("storage/")));
        assertTrue(ids(online(CHIEF, 0, 0)).stream().anyMatch(i -> i.startsWith("storage/")));
        List<String> ally = ids(online(ALLY, 0, 0));
        assertTrue(ally.stream().anyMatch(i -> i.startsWith("building/")), "an Ally sees buildings");
        assertFalse(ally.stream().anyMatch(i -> i.startsWith("storage/")), "an Ally never sees storage");
    }

    @Test
    void enemyStrangerAndOtherDimensionSeeNothing() {
        assertTrue(ids(online(ENEMY, 0, 0)).isEmpty());
        assertTrue(ids(online(STRANGER, 0, 0)).isEmpty());
        assertTrue(ids(online(OWNER, 0, 0), MOON).isEmpty());
    }

    @Test
    void ownerNeverSeesTheSandboxOrAnotherPlayersColony() {
        List<String> own = ids(online(OWNER, 0, 0));
        assertTrue(own.contains("colony/" + ColonyMapFixture.OWN_COLONY));
        assertFalse(own.contains("colony/" + ColonyMapFixture.SANDBOX_COLONY));
        assertFalse(own.contains("colony/" + ColonyMapFixture.THIRD_COLONY));
        assertFalse(own.contains("outpost/" + ColonyMapFixture.THIRD_OUTPOST));
    }

    @Test
    void offlineMemberStillSeesTheirColony() {
        MapViewer offline = MapViewer.forTest(OWNER, null, null, null);
        assertTrue(ids(offline).contains("colony/" + ColonyMapFixture.OWN_COLONY));
    }

    @Test
    void oddBlueprintNamesFallBackToAGenericKey() {
        assertEquals("map.nerocolonies.building", ColonyMapLayers.nameKey("Not A Key!"));
        assertEquals("blueprint.nerocolonies.farm", ColonyMapLayers.nameKey("blueprint.nerocolonies.farm"));
    }
}
