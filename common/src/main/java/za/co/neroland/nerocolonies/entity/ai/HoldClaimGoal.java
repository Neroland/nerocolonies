package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Walk back inside the claim.
 *
 * <p>This is how the stroll goal is bounded without subclassing it: the colonist wanders freely, and
 * the moment wandering takes it outside its colony's claim square, this higher-priority goal takes
 * over and walks it toward the beacon until it is back inside. It is one distance comparison per
 * evaluation, and it costs nothing at all for a colonist that never leaves.
 *
 * <p>A colonist with no colony record (its colony was dissolved out from under it) is left alone —
 * an orphan colonist is idle, not deleted.
 */
public class HoldClaimGoal extends Goal {

    /** Re-path cadence while walking back, in ticks. */
    private static final int REPATH_INTERVAL = 40;

    /** Walk back to within this fraction of the claim radius, not merely to the edge. */
    private static final double RETURN_FRACTION = 0.75D;

    private final ColonistEntity colonist;
    private final double speed;

    private int repathCountdown;

    public HoldClaimGoal(ColonistEntity colonist, double speed) {
        this.colonist = colonist;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.colonist.aiActive() && outside(1.0D);
    }

    @Override
    public boolean canContinueToUse() {
        return this.colonist.aiActive() && outside(RETURN_FRACTION);
    }

    /** Whether the colonist is beyond {@code fraction} of its claim radius from the beacon. */
    private boolean outside(double fraction) {
        Colony colony = this.colonist.colony();
        if (colony == null) {
            return false;
        }
        if (!colony.dimension().equals(this.colonist.level().dimension())) {
            return false; // wrong dimension entirely: not something a walk goal can fix
        }
        BlockPos pos = this.colonist.blockPosition();
        double reach = colony.claimRadius() * fraction;
        return Math.abs(pos.getX() - colony.beaconPos().getX()) > reach
                || Math.abs(pos.getZ() - colony.beaconPos().getZ()) > reach;
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
        Colony colony = this.colonist.colony();
        if (colony == null) {
            return;
        }
        if (--this.repathCountdown > 0) {
            return;
        }
        this.repathCountdown = REPATH_INTERVAL;
        BlockPos beacon = colony.beaconPos();
        this.colonist.getNavigation().moveTo(beacon.getX() + 0.5D, beacon.getY(),
                beacon.getZ() + 0.5D, this.speed);
    }
}
