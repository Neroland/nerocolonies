package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;
import za.co.neroland.nerocolonies.entity.ai.profession.ProfessionBehaviours;

/**
 * Doing the job: once a Neran is at its workplace during working hours, its trade's behaviour runs
 * every {@value #WORK_INTERVAL} goal ticks — harvest a crop, fell a log, dig a block, carry a load.
 * Each action that does something earns one point of trade experience.
 *
 * <p>The work is real: drops go to colony storage, and a Neran whose trade put goods in storage
 * recently is skipped by the colony tick's rolled outputs (see {@code colony/WorkAccounting}). A
 * Neran that is not working (unloaded, idle, nobody near) is still covered by the colony tick, so a
 * quiet colony keeps producing.
 *
 * <p><b>Labourers.</b> An adult with no trade of its own (the founders, before a colony has any
 * workplaces) still works: sent to a building site it lays the building by hand as a Builder does,
 * and otherwise it gathers wood as a Forester does, anywhere in the claim.
 *
 * <p><b>The leash.</b> A Neran stays at work within its trade's work radius of the workplace plus
 * {@value #LEASH}. While a behaviour is walking it to a real target (a tree, the quarry face, a Neran
 * that needs a tool), the leash stretches to that target plus {@value #LEASH}, up to
 * {@value #MAX_TARGET_LEASH} blocks, as long as both the target and the Neran are inside the claim.
 */
public class WorkGoal extends Goal {

    private static final int WORK_INTERVAL = 20;

    /** The trade a labourer works as when there is no building site. */
    private static final Identifier LABOURER_TRADE =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "forester");

    /** How far past the trade's work radius a Neran may wander before walking back. */
    private static final int LEASH = 3;

    /** The furthest a work target may take a Neran from its workplace. */
    private static final int MAX_TARGET_LEASH = 96;

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

    /** The trade this Neran works as: its own, or a labourer's (builder at a site, else forester). */
    private ProfessionDefinition trade() {
        ProfessionDefinition own = this.colonist.profession();
        if (own != null || this.colonist.isChildNeran()) {
            return own;
        }
        Identifier fallback = Construction.BUILDER_JOB.equals(this.colonist.jobId())
                ? Construction.BUILDER_PROFESSION : LABOURER_TRADE;
        return ColonyDefinitions.profession(fallback).orElse(null);
    }

    /** The workplace: the assigned one, or for a labourer with none, the colony beacon. */
    private BlockPos anchor(Colony colony) {
        BlockPos assigned = this.colonist.jobStationPos();
        return assigned != null ? assigned : colony == null ? null : colony.beaconPos();
    }

    private boolean atWork() {
        if (this.colonist.isChildNeran() || this.colonist.phase() != DayCycle.Phase.WORK) {
            return false;
        }
        Colony colony = this.colonist.colony();
        ProfessionDefinition profession = trade();
        BlockPos anchor = anchor(colony);
        if (profession == null || anchor == null || colony == null) {
            return false;
        }
        if (colony.morale() < NeroColoniesConfig.MORALE_WORK_STOP_THRESHOLD.get()) {
            return false;
        }
        if (this.colonist.professionId() == null && this.colonist.jobStationPos() == null) {
            // A labourer gathering wood works anywhere in the claim.
            return colony.contains(this.colonist.blockPosition());
        }
        int reach = profession.workRadius() + LEASH;
        double distance = this.colonist.blockPosition().distSqr(anchor);
        if (distance <= (double) reach * reach) {
            return true;
        }
        BlockPos target = this.colonist.workMemory().activeTarget(this.colonist.level().getGameTime());
        if (target == null || colony == null || !colony.contains(target)
                || !colony.contains(this.colonist.blockPosition())) {
            return false;
        }
        double leash = Math.min(MAX_TARGET_LEASH, Math.sqrt(target.distSqr(anchor)) + LEASH);
        return distance <= leash * leash;
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
        // So the stroke counter below is in real ticks: one piece of work a second.
        return true;
    }

    @Override
    public void tick() {
        if (--this.countdown > 0) {
            return;
        }
        this.countdown = WORK_INTERVAL;
        Colony colony = this.colonist.colony();
        ProfessionDefinition profession = trade();
        BlockPos anchor = anchor(colony);
        if (profession == null || anchor == null || colony == null
                || !(this.colonist.level() instanceof ServerLevel level)) {
            return;
        }
        this.colonist.workMemory().resetFor(profession.id());
        this.colonist.workMemory().markAtWork(level.getGameTime());
        if (ProfessionBehaviours.get(profession.behaviour()).work(level, this.colonist, colony, profession, anchor)
                && this.colonist.professionId() != null) {
            this.colonist.addProfessionXp(1);
        }
    }
}
