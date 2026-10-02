package za.co.neroland.nerocolonies.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyBuildings;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * The evening: gather at the plaza (any building with the {@code social} role), or near the beacon
 * if the colony has none yet. Children play here during the working day too.
 */
public class SocialiseGoal extends WalkToGoal {

    private static final double ARRIVED_DISTANCE_SQR = 16.0D;

    public SocialiseGoal(ColonistEntity colonist, double speed) {
        super(colonist, speed, ARRIVED_DISTANCE_SQR);
    }

    @Override
    @Nullable
    protected BlockPos target() {
        Colony colony = this.colonist.colony();
        if (colony == null || !(this.colonist.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos plaza = ColonyBuildings.anchor(level, colony, ColonyBuildings.ROLE_SOCIAL,
                this.colonist.blockPosition());
        return plaza != null ? plaza : colony.beaconPos();
    }

    @Override
    protected boolean wanted() {
        DayCycle.Phase phase = this.colonist.phase();
        return phase == DayCycle.Phase.SOCIAL
                || (this.colonist.isChildNeran() && phase == DayCycle.Phase.WORK);
    }

    @Override
    protected NeranStatus walkingStatus() {
        return NeranStatus.SOCIAL;
    }
}
