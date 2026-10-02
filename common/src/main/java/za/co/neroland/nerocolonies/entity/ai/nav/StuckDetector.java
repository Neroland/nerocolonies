package za.co.neroland.nerocolonies.entity.ai.nav;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * One shared answer to "is this walk getting anywhere, and if not, what next?".
 *
 * <p>Every movement goal reports to a detector at each progress check: whether the navigator holds a
 * path and how far the target still is. When several checks in a row show no progress, the detector
 * climbs a <b>recovery ladder</b>, one rung per stalled check:
 *
 * <ol>
 *   <li>{@link Step#REPATH} — plan the path again;</li>
 *   <li>{@link Step#ALTERNATE} — try a different standable side of the target;</li>
 *   <li>{@link Step#HOP} — a short random hop to shake loose from a corner;</li>
 *   <li>{@link Step#GIVE_UP} — mark the target unreachable for a cooldown, count one stuck event
 *       for the colony, and leave the walk alone until the cooldown ends. If the Neran is boxed
 *       in by colony-built blocks the goal then moves it to the nearest safe spot.</li>
 * </ol>
 *
 * <p>Progress resets the ladder. Pure state; unit-tested.
 */
public final class StuckDetector {

    /** Stalled checks before the first rung. */
    static final int GRACE_CHECKS = 1;

    /** Ticks a target is left alone after giving up on it. */
    public static final long COOLDOWN_TICKS = 600L;

    /** Squared-distance improvement that counts as progress between two checks. */
    static final double PROGRESS_EPSILON_SQR = 0.5D;

    /** What the goal should do after a check. */
    public enum Step {
        CONTINUE, REPATH, ALTERNATE, HOP, GIVE_UP
    }

    @Nullable
    private BlockPos target;
    private double bestDistanceSqr = Double.MAX_VALUE;
    private int stalled;
    private long cooldownUntil = Long.MIN_VALUE;

    /** Whether {@code candidate} is on cooldown at {@code now}. Switching target clears the state. */
    public boolean coolingDown(BlockPos candidate, long now) {
        if (!candidate.equals(this.target)) {
            this.target = candidate.immutable();
            reset();
            this.cooldownUntil = Long.MIN_VALUE;
            return false;
        }
        return now < this.cooldownUntil;
    }

    /**
     * Records one progress check and says what to do next.
     *
     * @param hasPath     whether the navigator currently holds a usable path
     * @param distanceSqr squared distance to the target now
     */
    public Step check(boolean hasPath, double distanceSqr, long now) {
        if (hasPath && distanceSqr < this.bestDistanceSqr - PROGRESS_EPSILON_SQR) {
            this.bestDistanceSqr = distanceSqr;
            this.stalled = 0;
            return Step.CONTINUE;
        }
        this.stalled++;
        int rung = this.stalled - GRACE_CHECKS;
        if (rung <= 0) {
            return hasPath ? Step.CONTINUE : Step.REPATH;
        }
        return switch (rung) {
            case 1 -> Step.REPATH;
            case 2 -> Step.ALTERNATE;
            case 3 -> Step.HOP;
            default -> {
                this.cooldownUntil = now + COOLDOWN_TICKS;
                reset();
                yield Step.GIVE_UP;
            }
        };
    }

    /** Called on arrival: the next walk starts clean. */
    public void arrived() {
        reset();
        this.cooldownUntil = Long.MIN_VALUE;
    }

    private void reset() {
        this.bestDistanceSqr = Double.MAX_VALUE;
        this.stalled = 0;
    }
}
