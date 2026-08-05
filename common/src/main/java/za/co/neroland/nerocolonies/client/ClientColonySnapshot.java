package za.co.neroland.nerocolonies.client;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.network.ColonySnapshotPayload;

/**
 * The client's mirror of the one colony the player currently has open.
 *
 * <p>Read by the beacon's Trade tab and by the research screen; written only by the server's
 * snapshot payload. Like {@link ClientColonyDefinitions} it is one immutable value in one
 * {@code volatile} field, replaced wholesale on the client thread, so readers never need a lock and
 * never see half a colony.
 *
 * <p><b>Privacy:</b> what is in here is exactly what {@link ColonySnapshotPayload} carries — colony
 * state, counts, and one boolean about the viewing player. There is no owner UUID, no access list and
 * no other player's name to leak, because none was ever sent.
 */
public final class ClientColonySnapshot {

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
}
