package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyDefence;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Who a guard may fight: hostile mobs, and players on the colony's Enemy list, inside the claim plus
 * {@code guardPursuitMargin}. Nothing else — never a non-enemy player, never another colony's
 * people, never an animal. The rule itself lives in {@link ColonyDefence#isValidTarget} so guardian
 * wolves and golems obey exactly the same one.
 */
public class GuardTargetGoal extends TargetGoal {

    private static final int SCAN_INTERVAL = 10;
    private static final double SCAN_RADIUS = 16.0D;

    private final ColonistEntity guard;
    private int scanCountdown;
    @Nullable
    private LivingEntity candidate;

    public GuardTargetGoal(ColonistEntity guard) {
        super(guard, true);
        this.guard = guard;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!this.guard.isGuard() || --this.scanCountdown > 0) {
            return false;
        }
        this.scanCountdown = SCAN_INTERVAL;
        Colony colony = this.guard.colony();
        if (colony == null || !(this.guard.level() instanceof ServerLevel level)) {
            return false;
        }
        AABB box = this.guard.getBoundingBox().inflate(SCAN_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> ColonyDefence.isValidTarget(level, colony, e))) {
            double distance = entity.distanceToSqr(this.guard);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        this.candidate = best;
        return best != null;
    }

    @Override
    public void start() {
        this.mob.setTarget(this.candidate);
        ColonyDefence.guardsEngaged(this.guard);
        super.start();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.mob.getTarget();
        Colony colony = this.guard.colony();
        if (!this.guard.isGuard() || target == null || colony == null
                || !(this.guard.level() instanceof ServerLevel level)) {
            return false;
        }
        return ColonyDefence.isValidTarget(level, colony, target) && super.canContinueToUse();
    }
}
