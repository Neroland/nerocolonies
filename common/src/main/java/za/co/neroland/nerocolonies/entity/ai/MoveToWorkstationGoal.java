package za.co.neroland.nerocolonies.entity.ai;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Walk to the workplace during working hours. What happens there is {@link WorkGoal}'s business.
 *
 * <p>Stands down when colony morale is below {@code moraleWorkStopThreshold}: life support fails,
 * morale decays, work stops, Nerans idle. A Neran is never deleted for it.
 */
public class MoveToWorkstationGoal extends WalkToGoal {

    private static final double ARRIVED_DISTANCE_SQR = 4.0D;
    private static final int MORALE_CHECK_INTERVAL = 50;

    private int moraleCountdown;
    private boolean working = true;

    public MoveToWorkstationGoal(ColonistEntity colonist, double speed) {
        super(colonist, speed, ARRIVED_DISTANCE_SQR);
    }

    @Override
    @Nullable
    protected BlockPos target() {
        return this.colonist.jobStationPos();
    }

    @Override
    protected boolean wanted() {
        if (this.colonist.jobStationPos() == null || this.colonist.isChildNeran()
                || this.colonist.phase() != DayCycle.Phase.WORK) {
            return false;
        }
        if (--this.moraleCountdown <= 0) {
            this.moraleCountdown = MORALE_CHECK_INTERVAL;
            Colony colony = this.colonist.colony();
            this.working = colony == null
                    || colony.morale() >= NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get();
        }
        return this.working;
    }
}
