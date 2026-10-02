package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyPermissions.Action;
import za.co.neroland.nerocolonies.colony.ColonyPermissions.Role;

/**
 * Covers the pure rule table {@link ColonyPermissions#allows}: the full role x action matrix with
 * the cache both private and shared, that strangers and enemies get nothing, that the owner gets
 * everything, that rank only ever adds rights (Owner over Chief over Ally), and that sharing the
 * cache changes nothing but the cache. Role lookup for a real player needs a server and is not
 * covered here.
 */
class ColonyPermissionsTest {

    private static final Set<Role> NOBODY = EnumSet.noneOf(Role.class);
    private static final Set<Role> MEMBERS = EnumSet.of(Role.ALLY, Role.CHIEF, Role.OWNER);
    private static final Set<Role> LEADERS = EnumSet.of(Role.CHIEF, Role.OWNER);
    private static final Set<Role> OWNER_ONLY = EnumSet.of(Role.OWNER);

    /** Who may do what while the Gratitude Cache is private. */
    private static Map<Action, Set<Role>> expectedPrivate() {
        Map<Action, Set<Role>> table = new EnumMap<>(Action.class);
        table.put(Action.INTERACT, MEMBERS);
        table.put(Action.CONTRIBUTE, MEMBERS);
        table.put(Action.NERAN, MEMBERS);
        table.put(Action.OPEN_CACHE, OWNER_ONLY);
        table.put(Action.PLAN, LEADERS);
        table.put(Action.MANAGE_MEMBERS, LEADERS);
        table.put(Action.MANAGE_CHIEFS, OWNER_ONLY);
        table.put(Action.OWNER_SETTINGS, OWNER_ONLY);
        return table;
    }

    /** The same table once the owner has shared the cache: only OPEN_CACHE widens. */
    private static Map<Action, Set<Role>> expectedShared() {
        Map<Action, Set<Role>> table = expectedPrivate();
        table.put(Action.OPEN_CACHE, MEMBERS);
        return table;
    }

    private static void assertMatrix(Map<Action, Set<Role>> expected, boolean cacheShared) {
        for (Action action : Action.values()) {
            Set<Role> allowed = expected.getOrDefault(action, NOBODY);
            for (Role role : Role.values()) {
                assertEquals(allowed.contains(role), ColonyPermissions.allows(role, action, cacheShared),
                        role + " / " + action + " / cacheShared=" + cacheShared);
            }
        }
    }

    @Test
    @DisplayName("The expectation table names every action")
    void tableCoversEveryAction() {
        assertEquals(EnumSet.allOf(Action.class), expectedPrivate().keySet());
        assertEquals(EnumSet.allOf(Action.class), expectedShared().keySet());
    }

    @Test
    @DisplayName("Role x action matrix with the cache private")
    void matrixWithPrivateCache() {
        assertMatrix(expectedPrivate(), false);
    }

    @Test
    @DisplayName("Role x action matrix with the cache shared")
    void matrixWithSharedCache() {
        assertMatrix(expectedShared(), true);
    }

    @Test
    @DisplayName("Strangers and enemies may do nothing, shared cache or not")
    void outsidersGetNothing() {
        for (Role role : new Role[] {Role.STRANGER, Role.ENEMY}) {
            for (Action action : Action.values()) {
                assertFalse(ColonyPermissions.allows(role, action, false), role + " / " + action);
                assertFalse(ColonyPermissions.allows(role, action, true), role + " / " + action + " (shared)");
            }
        }
    }

    @Test
    @DisplayName("The owner may do everything")
    void ownerGetsEverything() {
        for (Action action : Action.values()) {
            assertTrue(ColonyPermissions.allows(Role.OWNER, action, false), action.toString());
            assertTrue(ColonyPermissions.allows(Role.OWNER, action, true), action + " (shared)");
        }
    }

    @Test
    @DisplayName("Rank only adds rights: Owner over Chief over Ally")
    void hierarchyIsMonotonic() {
        for (boolean shared : new boolean[] {false, true}) {
            for (Action action : Action.values()) {
                if (ColonyPermissions.allows(Role.ALLY, action, shared)) {
                    assertTrue(ColonyPermissions.allows(Role.CHIEF, action, shared), "chief / " + action);
                }
                if (ColonyPermissions.allows(Role.CHIEF, action, shared)) {
                    assertTrue(ColonyPermissions.allows(Role.OWNER, action, shared), "owner / " + action);
                }
            }
        }
    }

    @Test
    @DisplayName("Allies and chiefs open the cache only once it is shared; the owner always can")
    void cacheSharing() {
        assertFalse(ColonyPermissions.allows(Role.ALLY, Action.OPEN_CACHE, false));
        assertFalse(ColonyPermissions.allows(Role.CHIEF, Action.OPEN_CACHE, false));
        assertTrue(ColonyPermissions.allows(Role.OWNER, Action.OPEN_CACHE, false));
        assertTrue(ColonyPermissions.allows(Role.ALLY, Action.OPEN_CACHE, true));
        assertTrue(ColonyPermissions.allows(Role.CHIEF, Action.OPEN_CACHE, true));
        assertTrue(ColonyPermissions.allows(Role.OWNER, Action.OPEN_CACHE, true));
    }

    @Test
    @DisplayName("Sharing the cache changes nothing except opening the cache")
    void cacheFlagOnlyAffectsCache() {
        for (Action action : Action.values()) {
            if (action == Action.OPEN_CACHE) {
                continue;
            }
            for (Role role : Role.values()) {
                assertEquals(ColonyPermissions.allows(role, action, false),
                        ColonyPermissions.allows(role, action, true), role + " / " + action);
            }
        }
    }

    @Test
    @DisplayName("An ally may take part but not plan or manage; a chief may plan and manage members only")
    void allyAndChiefLimits() {
        assertTrue(ColonyPermissions.allows(Role.ALLY, Action.INTERACT, false));
        assertTrue(ColonyPermissions.allows(Role.ALLY, Action.CONTRIBUTE, false));
        assertTrue(ColonyPermissions.allows(Role.ALLY, Action.NERAN, false));
        assertFalse(ColonyPermissions.allows(Role.ALLY, Action.PLAN, false));
        assertFalse(ColonyPermissions.allows(Role.ALLY, Action.MANAGE_MEMBERS, false));
        assertTrue(ColonyPermissions.allows(Role.CHIEF, Action.PLAN, false));
        assertTrue(ColonyPermissions.allows(Role.CHIEF, Action.MANAGE_MEMBERS, false));
        assertFalse(ColonyPermissions.allows(Role.CHIEF, Action.MANAGE_CHIEFS, false));
        assertFalse(ColonyPermissions.allows(Role.CHIEF, Action.OWNER_SETTINGS, false));
    }

    @Test
    @DisplayName("Members are allies, chiefs and the owner; keys are lower-case names")
    void roleMembershipAndKeys() {
        for (Role role : Role.values()) {
            assertEquals(MEMBERS.contains(role), role.member(), role.toString());
        }
        assertEquals("stranger", Role.STRANGER.key());
        assertEquals("enemy", Role.ENEMY.key());
        assertEquals("ally", Role.ALLY.key());
        assertEquals("chief", Role.CHIEF.key());
        assertEquals("owner", Role.OWNER.key());
    }
}
