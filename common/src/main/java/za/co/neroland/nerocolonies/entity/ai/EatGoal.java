package za.co.neroland.nerocolonies.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyBuildings;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * The midday meal: walk to the colony's granary or canteen (any building with the {@code eat} role).
 * A colony with neither simply skips the meal walk.
 */
public class EatGoal extends WalkToGoal {

    private static final double ARRIVED_DISTANCE_SQR = 9.0D;

    public EatGoal(ColonistEntity colonist, double speed) {
        super(colonist, speed, ARRIVED_DISTANCE_SQR);
    }

    @Override
    @Nullable
    protected BlockPos target() {
        Colony colony = this.colonist.colony();
        if (colony == null || !(this.colonist.level() instanceof ServerLevel level)) {
            return null;
        }
        return ColonyBuildings.anchor(level, colony, ColonyBuildings.ROLE_EAT, this.colonist.blockPosition());
    }

    @Override
    protected boolean wanted() {
        return this.colonist.phase() == DayCycle.Phase.EAT;
    }

    @Override
    protected NeranStatus walkingStatus() {
        Colony colony = this.colonist.colony();
        return colony != null && FoodSupply.starving(colony) ? NeranStatus.HUNGRY : NeranStatus.NONE;
    }
}
