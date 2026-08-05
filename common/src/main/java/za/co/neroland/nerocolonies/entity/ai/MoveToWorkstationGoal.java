package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Walk to the assigned job station and stand at it during the day.
 *
 * <p>The colonist does <b>not</b> perform the job — production is run centrally on the colony tick
 * so throughput is budgeted in one place (see {@code colony/ColonyTicker}). Standing at the station
 * is what makes the colony legible: you can see who is working where. Assignment itself arrives with
 * the job board in a later stage; this goal simply honours whatever {@code jobStationPos} says.
 *
 * <p>The goal stands down entirely when colony morale is below {@code moraleWorkStopThreshold}. That
 * is the visible half of the graceful failure curve — life support fails, morale decays, work stops,
 * colonists idle — and it is the <b>only</b> consequence. A colonist is never deleted.
 */
public class MoveToWorkstationGoal extends Goal {

    private static final double ARRIVED_DISTANCE_SQR = 4.0D;

    private static final int REPATH_INTERVAL = 40;

    /** How often morale is re-read, in ticks. It changes on the colony tick, not per game tick. */
    private static final int MORALE_CHECK_INTERVAL = 100;

    private final ColonistEntity colonist;
    private final double speed;

    private int repathCountdown;
    private int moraleCountdown;
    private boolean working = true;

    public MoveToWorkstationGoal(ColonistEntity colonist, double speed) {
        this.colonist = colonist;
        this.speed = speed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.colonist.aiActive() || this.colonist.jobStationPos() == null) {
            return false;
        }
        if (this.colonist.level().isDarkOutside()) {
            return false;
        }
        refreshMorale();
        if (!this.working) {
            return false;
        }
        return this.colonist.blockPosition().distSqr(this.colonist.jobStationPos())
                > ARRIVED_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos station = this.colonist.jobStationPos();
        if (station == null || !this.colonist.aiActive() || this.colonist.level().isDarkOutside()) {
            return false;
        }
        refreshMorale();
        return this.working && this.colonist.blockPosition().distSqr(station) > ARRIVED_DISTANCE_SQR;
    }

    /** Re-reads colony morale on a slow cadence — the record is only rewritten on the colony tick. */
    private void refreshMorale() {
        if (--this.moraleCountdown > 0) {
            return;
        }
        this.moraleCountdown = MORALE_CHECK_INTERVAL;
        Colony colony = this.colonist.colony();
        this.working = colony == null
                || colony.morale() >= NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get();
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
        BlockPos station = this.colonist.jobStationPos();
        if (station == null) {
            return;
        }
        if (--this.repathCountdown > 0) {
            return;
        }
        this.repathCountdown = REPATH_INTERVAL;
        this.colonist.getNavigation().moveTo(station.getX() + 0.5D, station.getY(),
                station.getZ() + 0.5D, this.speed);
    }
}
