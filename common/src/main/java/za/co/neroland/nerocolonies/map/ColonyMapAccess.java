package za.co.neroland.nerocolonies.map;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyPermissions.Role;

/**
 * The map's copy of the link module's visibility rule ({@code link/ColonyLinkAccess.coloniesOf}), re-implemented
 * here because that rule is package-private and the map package may not widen it. Pure: it takes the colonies and
 * the two role lists, not a server, so a plain JVM test can run it, and {@code ColonyMapParityTest} pins it to the
 * same owner / ally / chief / enemy / stranger / erased-owner / sandbox matrix the link rule answers.
 *
 * <p>The rule: a viewer sees the colonies they own or are an Ally or a Chief of, in the store's order, never the
 * sandbox colony, never anything for an Enemy or a stranger, and never widened for an operator.
 */
public final class ColonyMapAccess {

    /** The Chief and Enemy lists ({@code ColonyRoles}), as two questions. */
    public interface Roles {
        boolean isChief(UUID colonyId, UUID player);

        boolean isEnemy(UUID colonyId, UUID player);
    }

    private ColonyMapAccess() {
    }

    /** What {@code viewer} is to {@code colony}, from the stored lists only (mirrors {@code ColonyPermissions.roleOf}). */
    public static Role roleOf(Colony colony, Roles roles, UUID viewer) {
        if (colony == null || viewer == null) {
            return Role.STRANGER;
        }
        if (colony.isOwner(viewer)) {
            return Role.OWNER;
        }
        if (roles != null && roles.isEnemy(colony.colonyId(), viewer)) {
            return Role.ENEMY;
        }
        if (colony.accessList().contains(viewer)) {
            return roles != null && roles.isChief(colony.colonyId(), viewer) ? Role.CHIEF : Role.ALLY;
        }
        return Role.STRANGER;
    }

    /** The colonies among {@code candidates} that {@code viewer} may see, with nothing else. */
    public static List<Colony> visible(Iterable<Colony> candidates, Roles roles, Predicate<UUID> sandbox,
            UUID viewer) {
        if (candidates == null || viewer == null) {
            return List.of();
        }
        List<Colony> out = new ArrayList<>();
        for (Colony colony : candidates) {
            if (colony == null || (sandbox != null && sandbox.test(colony.colonyId()))) {
                continue;
            }
            if (colony.isMember(viewer) && roleOf(colony, roles, viewer).member()) {
                out.add(colony);
            }
        }
        return out;
    }

    /** Storage crates and depots are shown to the owner and Chiefs only (the Stage 0 review's rule). */
    public static boolean seesStorage(Role role) {
        return role == Role.OWNER || role == Role.CHIEF;
    }
}
