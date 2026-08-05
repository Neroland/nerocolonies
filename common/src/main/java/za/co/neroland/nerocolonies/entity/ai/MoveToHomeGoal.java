package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Walk home. Runs at night, or whenever the colonist has no workstation to be at.
 *
 * <p>This is deliberately not a schedule: there is no bed-claiming, no sleeping, no "off duty"
 * state. It is one goal that walks toward a position, because a colony where everyone stands in the
 * open all night looks broken and a colony where everyone has a daily routine is a different mod.
 */
public class MoveToHomeGoal extends Goal {

    /** Close enough — pathing all the way onto the block just makes them jostle. */
    private static final double ARRIVED_DISTANCE_SQR = 4.0D;

    /** Re-path cadence while walking, in ticks. */
    private static final int REPATH_INTERVAL = 40;

    private final ColonistEntity colonist;
    private final double speed;

    private int repathCountdown;

    public MoveToHomeGoal(ColonistEntity colonist, double speed) {
        this.colonist = colonist;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.colonist.aiActive()) {
            return false;
        }
        BlockPos home = this.colonist.homePos();
        if (home == null) {
            return false;
        }
        if (!nightOrIdle()) {
            return false;
        }
        return this.colonist.blockPosition().distSqr(home) > ARRIVED_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos home = this.colonist.homePos();
        return home != null && this.colonist.aiActive() && nightOrIdle()
                && this.colonist.blockPosition().distSqr(home) > ARRIVED_DISTANCE_SQR;
    }

    /**
     * Night, or nothing to do. {@code isDarkOutside()} is the de-obfuscated 26.x day/night helper —
     * there is no {@code isDay()} on {@code Level} — and it is dimension-aware, so a colonist on a
     * permanently dark planet stays home unless it has a job, which is exactly right.
     */
    private boolean nightOrIdle() {
        return this.colonist.jobStationPos() == null || this.colonist.level().isDarkOutside();
    }

    @Override
    public void start() {
        this.repathCountdown = 0;
    }

    @Override
    public void stop() {
        this.colonist.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return false;
    }

    @Override
    public void tick() {
        BlockPos home = this.colonist.homePos();
        if (home == null) {
            return;
        }
        if (--this.repathCountdown > 0) {
            return;
        }
        this.repathCountdown = REPATH_INTERVAL;
        this.colonist.getNavigation().moveTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D,
                this.speed);
    }
}
