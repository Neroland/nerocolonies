package za.co.neroland.nerocolonies.client;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyStage;
import za.co.neroland.nerocolonies.network.ColonySnapshotPayload;

/**
 * The client's mirror of the one colony the player currently has open.
 *
 * <p>Read by the beacon screen and by the research screen; written only by the server's snapshot
 * payload. Like {@link ClientColonyDefinitions} it is one immutable value in one {@code volatile}
 * field, replaced wholesale on the client thread, so readers never need a lock and never see half a
 * colony.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> what is in here is exactly what {@link ColonySnapshotPayload}
 * carries, for exactly as long as the server lets it stand. That is colony state, counts, and the
 * viewing player's own role — and, <em>only</em> for a viewer who may manage the colony's members,
 * the roster of names the beacon's Roles tab draws. There is never a UUID of any player. The mirror
 * lives in memory only: nothing here is written to disk or to a log, each snapshot replaces the last
 * one whole (so a roster the server stops sending is gone), and {@link #clear} drops it when the
 * client leaves the world.
 */
public final class ClientColonySnapshot {

    private static final ColonyPermissions.Role[] ROLES = ColonyPermissions.Role.values();

    private static volatile ColonySnapshotPayload current = ColonySnapshotPayload.EMPTY;
    private static volatile Set<String> unlocked = Set.of();
    private static volatile Set<String> affordable = Set.of();

    private ClientColonySnapshot() {
    }

    /** Replaces the mirror. Called on the client thread. */
    public static void accept(ColonySnapshotPayload payload) {
        current = payload;
        unlocked = Set.copyOf(new LinkedHashSet<>(payload.researchUnlocked()));
        affordable = Set.copyOf(new LinkedHashSet<>(payload.affordable()));
    }

    /** Drops everything. Called when the client leaves a world or server. */
    public static void clear() {
        current = ColonySnapshotPayload.EMPTY;
        unlocked = Set.of();
        affordable = Set.of();
    }

    /** The whole snapshot. Never null; {@link ColonySnapshotPayload#present()} says whether it counts. */
    public static ColonySnapshotPayload get() {
        return current;
    }

    /** Whether a colony snapshot has arrived at all. */
    public static boolean present() {
        return current.present();
    }

    /** Whether this colony has unlocked a research node. */
    public static boolean isUnlocked(Identifier node) {
        return unlocked.contains(node.toString());
    }

    /** Whether the colony could pay for a node right now, as of the last snapshot. */
    public static boolean isAffordable(Identifier node) {
        return affordable.contains(node.toString());
    }

    // --- the living-colony half -------------------------------------------------

    /** Stage, needs, role and counts. Never null; all zeroes when no colony is open. */
    public static ColonySnapshotPayload.Life life() {
        return current.life();
    }

    /** The colony's growth stage, as of the last snapshot. */
    public static ColonyStage stage() {
        return ColonyStage.byOrdinal(current.life().stage());
    }

    /** The viewing player's own role. An ordinal this jar does not know reads as a stranger. */
    public static ColonyPermissions.Role role() {
        return roleOf(current.present() ? current.life().role() : 0);
    }

    /** The role for an ordinal off the wire, bounded. */
    public static ColonyPermissions.Role roleOf(int ordinal) {
        return ordinal >= 0 && ordinal < ROLES.length ? ROLES[ordinal] : ColonyPermissions.Role.STRANGER;
    }

    /**
     * Whether the viewing player's role allows an action, by the same rule table the server uses.
     * This only decides what the screen <em>offers</em>: the server asks itself the same question
     * again before it does anything.
     */
    public static boolean may(ColonyPermissions.Action action) {
        return current.present()
                && ColonyPermissions.allows(role(), action, current.life().cacheShared());
    }
}
