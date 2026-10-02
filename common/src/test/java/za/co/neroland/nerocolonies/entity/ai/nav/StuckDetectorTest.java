package za.co.neroland.nerocolonies.entity.ai.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.entity.ai.nav.StuckDetector.Step;

/**
 * Covers {@link StuckDetector}, which is pure state: what counts as progress, the grace checks
 * before the recovery ladder, the ladder itself (repath, alternate, hop, give up), that progress
 * resets it, and the give-up cooldown (when it starts, when it ends, and what clears it).
 */
class StuckDetectorTest {

    private static final BlockPos TARGET = new BlockPos(10, 64, -3);
    private static final BlockPos OTHER = new BlockPos(-20, 70, 8);

    /** A distance that is clearly progress over {@code from}. */
    private static double closer(double from) {
        return from - StuckDetector.PROGRESS_EPSILON_SQR - 1.0D;
    }

    /** Makes one check that counts as progress, then stalls until the detector gives up. */
    private static void giveUp(StuckDetector detector, long now) {
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, now));
        Step last = Step.CONTINUE;
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 4; i++) {
            last = detector.check(true, 100.0D, now);
        }
        assertEquals(Step.GIVE_UP, last);
    }

    // --- progress -------------------------------------------------------------------

    @Test
    @DisplayName("A walk that keeps closing the distance just continues")
    void steadyProgressContinues() {
        StuckDetector detector = new StuckDetector();
        double distance = 400.0D;
        for (int i = 0; i < 50; i++) {
            assertEquals(Step.CONTINUE, detector.check(true, distance, i));
            distance = closer(distance);
        }
    }

    @Test
    @DisplayName("The first check with a path is progress, however far the target is")
    void firstCheckIsProgress() {
        assertEquals(Step.CONTINUE, new StuckDetector().check(true, 1.0e12D, 0L));
    }

    @Test
    @DisplayName("Closing by no more than the epsilon is not progress")
    void tinyImprovementIsNotProgress() {
        StuckDetector detector = new StuckDetector();
        detector.check(true, 100.0D, 0L);
        // Shave off exactly the epsilon each time: never strictly better than best - epsilon.
        Step last = Step.CONTINUE;
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 4; i++) {
            last = detector.check(true, 100.0D - StuckDetector.PROGRESS_EPSILON_SQR, 0L);
        }
        assertEquals(Step.GIVE_UP, last);
    }

    // --- the ladder -----------------------------------------------------------------

    @Test
    @DisplayName("With a path, stalled checks get a grace period and then climb the ladder one rung each")
    void ladderWithPath() {
        StuckDetector detector = new StuckDetector();
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L), "grace check " + i);
        }
        assertEquals(Step.REPATH, detector.check(true, 100.0D, 0L));
        assertEquals(Step.ALTERNATE, detector.check(true, 100.0D, 0L));
        assertEquals(Step.HOP, detector.check(true, 100.0D, 0L));
        assertEquals(Step.GIVE_UP, detector.check(true, 100.0D, 0L));
    }

    @Test
    @DisplayName("Without a path the grace checks ask for a repath at once, then the same ladder follows")
    void ladderWithoutPath() {
        StuckDetector detector = new StuckDetector();
        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            assertEquals(Step.REPATH, detector.check(false, 100.0D, 0L), "grace check " + i);
        }
        assertEquals(Step.REPATH, detector.check(false, 100.0D, 0L));
        assertEquals(Step.ALTERNATE, detector.check(false, 100.0D, 0L));
        assertEquals(Step.HOP, detector.check(false, 100.0D, 0L));
        assertEquals(Step.GIVE_UP, detector.check(false, 100.0D, 0L));
    }

    @Test
    @DisplayName("Getting closer without a path is not progress")
    void closerWithoutPathIsNotProgress() {
        StuckDetector detector = new StuckDetector();
        double distance = 400.0D;
        Step last = Step.CONTINUE;
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 4; i++) {
            last = detector.check(false, distance, 0L);
            assertTrue(last != Step.CONTINUE, "check " + i + " continued without a path");
            distance = closer(distance);
        }
        assertEquals(Step.GIVE_UP, last);
    }

    @Test
    @DisplayName("Progress resets the ladder: the next stall starts from the grace period again")
    void progressResetsTheLadder() {
        StuckDetector detector = new StuckDetector();
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            detector.check(true, 100.0D, 0L);
        }
        assertEquals(Step.REPATH, detector.check(true, 100.0D, 0L));
        assertEquals(Step.ALTERNATE, detector.check(true, 100.0D, 0L));

        // The alternate side worked.
        assertEquals(Step.CONTINUE, detector.check(true, closer(100.0D), 0L));

        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            assertEquals(Step.CONTINUE, detector.check(true, closer(100.0D), 0L), "grace check " + i);
        }
        assertEquals(Step.REPATH, detector.check(true, closer(100.0D), 0L));
        assertEquals(Step.ALTERNATE, detector.check(true, closer(100.0D), 0L));
        assertEquals(Step.HOP, detector.check(true, closer(100.0D), 0L));
        assertEquals(Step.GIVE_UP, detector.check(true, closer(100.0D), 0L));
    }

    @Test
    @DisplayName("Progress is measured against the best distance so far, not the last one")
    void progressIsAgainstTheBestDistance() {
        StuckDetector detector = new StuckDetector();
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        // Drifting away and coming back to where it was is still a stall.
        Step last = Step.CONTINUE;
        double[] wander = {150.0D, 120.0D, 100.0D, 130.0D, 100.0D};
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 4; i++) {
            last = detector.check(true, wander[i % wander.length], 0L);
        }
        assertEquals(Step.GIVE_UP, last);
    }

    @Test
    @DisplayName("Giving up clears the ladder: the next walk starts clean")
    void giveUpStartsClean() {
        StuckDetector detector = new StuckDetector();
        giveUp(detector, 0L);
        // The same distance counts as progress again because the best distance was forgotten.
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        }
        assertEquals(Step.REPATH, detector.check(true, 100.0D, 0L));
    }

    // --- cooldown -------------------------------------------------------------------

    @Test
    @DisplayName("A target is not on cooldown until the detector has given up on it")
    void noCooldownBeforeGivingUp() {
        StuckDetector detector = new StuckDetector();
        assertFalse(detector.coolingDown(TARGET, 0L));
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 3; i++) {
            detector.check(true, 100.0D, 0L);
        }
        assertFalse(detector.coolingDown(TARGET, 1L));
        assertFalse(detector.coolingDown(TARGET, Long.MAX_VALUE));
    }

    @Test
    @DisplayName("Giving up puts the target on cooldown for exactly the cooldown period")
    void cooldownAfterGivingUp() {
        StuckDetector detector = new StuckDetector();
        long now = 5_000L;
        assertFalse(detector.coolingDown(TARGET, now));
        giveUp(detector, now);
        assertTrue(detector.coolingDown(TARGET, now));
        assertTrue(detector.coolingDown(TARGET, now + StuckDetector.COOLDOWN_TICKS - 1));
        assertFalse(detector.coolingDown(TARGET, now + StuckDetector.COOLDOWN_TICKS));
        assertFalse(detector.coolingDown(TARGET, now + StuckDetector.COOLDOWN_TICKS + 1_000));
    }

    @Test
    @DisplayName("The cooldown belongs to an equal position, not to one particular object")
    void cooldownMatchesByValue() {
        StuckDetector detector = new StuckDetector();
        assertFalse(detector.coolingDown(TARGET, 0L));
        giveUp(detector, 0L);
        assertTrue(detector.coolingDown(new BlockPos(TARGET.getX(), TARGET.getY(), TARGET.getZ()), 1L));
    }

    @Test
    @DisplayName("Switching target clears the cooldown and the ladder")
    void switchingTargetClearsState() {
        StuckDetector detector = new StuckDetector();
        assertFalse(detector.coolingDown(TARGET, 0L));
        giveUp(detector, 0L);
        assertTrue(detector.coolingDown(TARGET, 1L));

        assertFalse(detector.coolingDown(OTHER, 1L));
        // Coming back to the first target is a fresh start: the old cooldown is gone.
        assertFalse(detector.coolingDown(TARGET, 2L));
    }

    @Test
    @DisplayName("Switching target part-way up the ladder starts the ladder again")
    void switchingTargetResetsTheLadder() {
        StuckDetector detector = new StuckDetector();
        assertFalse(detector.coolingDown(TARGET, 0L));
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 2; i++) {
            detector.check(true, 100.0D, 0L);
        }

        assertFalse(detector.coolingDown(OTHER, 0L));
        // A longer distance than the old best still counts as progress on the new target.
        assertEquals(Step.CONTINUE, detector.check(true, 900.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            assertEquals(Step.CONTINUE, detector.check(true, 900.0D, 0L));
        }
        assertEquals(Step.REPATH, detector.check(true, 900.0D, 0L));
    }

    @Test
    @DisplayName("Arriving clears the cooldown and the ladder")
    void arrivedClearsState() {
        StuckDetector detector = new StuckDetector();
        assertFalse(detector.coolingDown(TARGET, 0L));
        giveUp(detector, 0L);
        assertTrue(detector.coolingDown(TARGET, 1L));
        detector.arrived();
        assertFalse(detector.coolingDown(TARGET, 1L));

        // Part-way up the ladder, arriving starts the next walk from scratch.
        assertEquals(Step.CONTINUE, detector.check(true, 100.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS + 2; i++) {
            detector.check(true, 100.0D, 0L);
        }
        detector.arrived();
        assertEquals(Step.CONTINUE, detector.check(true, 900.0D, 0L));
        for (int i = 0; i < StuckDetector.GRACE_CHECKS; i++) {
            assertEquals(Step.CONTINUE, detector.check(true, 900.0D, 0L));
        }
        assertEquals(Step.REPATH, detector.check(true, 900.0D, 0L));
    }
}
