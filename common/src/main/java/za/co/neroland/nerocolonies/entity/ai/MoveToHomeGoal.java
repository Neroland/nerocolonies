package za.co.neroland.nerocolonies.entity.ai;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * Walk home to sleep, or when there is nothing else to do during the working day.
 */
public class MoveToHomeGoal extends WalkToGoal {

    private static final double ARRIVED_DISTANCE_SQR = 4.0D;

    public MoveToHomeGoal(ColonistEntity colonist, double speed) {
        super(colonist, speed, ARRIVED_DISTANCE_SQR);
    }

    @Override
    @Nullable
    protected BlockPos target() {
        return this.colonist.homePos();
    }

    @Override
    protected boolean wanted() {
        DayCycle.Phase phase = this.colonist.phase();
        return phase == DayCycle.Phase.SLEEP
                || (phase == DayCycle.Phase.WORK && this.colonist.jobStationPos() == null
                        && !this.colonist.isChildNeran());
    }

    @Override
    protected NeranStatus walkingStatus() {
        return this.colonist.phase() == DayCycle.Phase.SLEEP ? NeranStatus.SLEEPING : NeranStatus.NONE;
    }
}
