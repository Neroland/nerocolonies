package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;
import za.co.neroland.nerocolonies.entity.ai.profession.ProfessionBehaviours;

/**
 * Doing the job: once a Neran is at its workplace during working hours, its trade's behaviour runs
 * every {@value #WORK_INTERVAL} goal ticks — harvest a crop, fell a log, carry a load, tend a wolf.
 * Each action that does something earns one point of trade experience.
 *
 * <p>This is the <em>visible</em> half of a trade. The colony's share of the trade's goods is
 * gathered on the colony tick whether or not anybody is watching (see {@code colony/Professions}),
 * so a quiet colony still produces; what stops while nobody is near is only the walking around.
 */
public class WorkGoal extends Goal {

    private static final int WORK_INTERVAL = 20;

    /** How far past the trade's work radius a Neran may wander before walking back. */
    private static final int LEASH = 3;

    private final ColonistEntity colonist;
    private int countdown;

    public WorkGoal(ColonistEntity colonist) {
        this.colonist = colonist;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.colonist.aiActive() && atWork();
    }

    @Override
    public boolean canContinueToUse() {
        return atWork();
    }

    private boolean atWork() {
        if (this.colonist.isChildNeran() || this.colonist.phase() != DayCycle.Phase.WORK) {
            return false;
        }
        ProfessionDefinition profession = this.colonist.profession();
        BlockPos anchor = this.colonist.jobStationPos();
        if (profession == null || anchor == null) {
            return false;
        }
        Colony colony = this.colonist.colony();
        if (colony != null && colony.morale() < NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get()) {
            return false;
        }
        int reach = profession.workRadius() + LEASH;
        return this.colonist.blockPosition().distSqr(anchor) <= (double) reach * reach;
    }

    @Override
    public void start() {
        this.countdown = 0;
        this.colonist.setStatus(this.colonist.hasTool() ? NeranStatus.WORKING : NeranStatus.NO_TOOL);
    }

    @Override
    public void stop() {
        this.colonist.getNavigation().stop();
        if (this.colonist.status() == NeranStatus.WORKING || this.colonist.status() == NeranStatus.HAULING) {
            this.colonist.setStatus(NeranStatus.NONE);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return false;
    }

    @Override
    public void tick() {
        if (--this.countdown > 0) {
            return;
        }
        this.countdown = WORK_INTERVAL;
        ProfessionDefinition profession = this.colonist.profession();
        BlockPos anchor = this.colonist.jobStationPos();
        Colony colony = this.colonist.colony();
        if (profession == null || anchor == null || colony == null
                || !(this.colonist.level() instanceof ServerLevel level)) {
            return;
        }
        if (ProfessionBehaviours.get(profession.behaviour()).work(level, this.colonist, colony, profession, anchor)) {
            this.colonist.addProfessionXp(1);
        }
    }
}
