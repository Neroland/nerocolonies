package za.co.neroland.nerocolonies.entity.ai;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Walk back inside the claim. Starts beyond the claim edge and stops within
 * {@value #RETURN_FRACTION} of the radius — the hysteresis that keeps it from trading places with
 * the stroll goal at the edge. An orphan Neran (its colony dissolved) is idle, never deleted.
 */
public class HoldClaimGoal extends WalkToGoal {

    private static final double RETURN_FRACTION = 0.75D;
    private static final double ARRIVED_DISTANCE_SQR = 4.0D;

    private boolean returning;

    public HoldClaimGoal(ColonistEntity colonist, double speed) {
        super(colonist, speed, ARRIVED_DISTANCE_SQR);
    }

    @Override
    @Nullable
    protected BlockPos target() {
        Colony colony = this.colonist.colony();
        return colony == null ? null : colony.beaconPos();
    }

    @Override
    protected boolean wanted() {
        this.returning = outside(this.returning ? RETURN_FRACTION : 1.0D);
        return this.returning;
    }

    private boolean outside(double fraction) {
        Colony colony = this.colonist.colony();
        if (colony == null || !colony.dimension().equals(this.colonist.level().dimension())) {
            return false;
        }
        BlockPos pos = this.colonist.blockPosition();
        double reach = colony.claimRadius() * fraction;
        return Math.abs(pos.getX() - colony.beaconPos().getX()) > reach
                || Math.abs(pos.getZ() - colony.beaconPos().getZ()) > reach;
    }
}
