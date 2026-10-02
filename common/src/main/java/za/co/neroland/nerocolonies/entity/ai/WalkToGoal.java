package za.co.neroland.nerocolonies.entity.ai;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;
import za.co.neroland.nerocolonies.entity.ai.nav.StuckDetector;
import za.co.neroland.nerocolonies.entity.ai.nav.TargetResolver;

/**
 * Shared shape of every "walk to a place" goal a Neran has.
 *
 * <ul>
 *   <li><b>The AI budget gates starting, never finishing.</b> Only {@link #canUse()} consults
 *       {@link ColonistEntity#aiActive()}; a walk under way is never abandoned for budget reasons.</li>
 *   <li><b>Walk to a standable spot.</b> The target block is resolved to a standable neighbour by a
 *       {@link TargetResolver}.</li>
 *   <li><b>Notice when it is going nowhere.</b> Every {@value #CHECK_INTERVAL} goal ticks a
 *       {@link StuckDetector} is told whether there is a path and how far the spot is, and its
 *       recovery ladder decides: repath, try another side, hop, or give up for a while (counting a
 *       stuck event for the colony, and — only when boxed in by colony-built blocks — rescuing the
 *       Neran to a safe spot).</li>
 * </ul>
 */
public abstract class WalkToGoal extends Goal {

    /** Re-plan cadence, in goal ticks (goals tick every other game tick). */
    private static final int REPATH_INTERVAL = 40;

    /** Progress check cadence, in goal ticks. */
    private static final int CHECK_INTERVAL = 20;

    /** How close to the resolved spot counts as there. */
    private static final double SPOT_ARRIVED_SQR = 2.25D;

    protected final ColonistEntity colonist;
    private final double speed;
    private final double arrivedDistanceSqr;
    private final TargetResolver resolver = new TargetResolver();
    private final StuckDetector detector = new StuckDetector();

    private int repathCountdown;
    private int checkCountdown;

    protected WalkToGoal(ColonistEntity colonist, double speed, double arrivedDistanceSqr) {
        this.colonist = colonist;
        this.speed = speed;
        this.arrivedDistanceSqr = arrivedDistanceSqr;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** Where this goal wants the Neran to be right now, or {@code null}. */
    @Nullable
    protected abstract BlockPos target();

    /** Whether the goal's own conditions (time of day, morale, ...) want it to run. */
    protected abstract boolean wanted();

    /** The status shown while walking. */
    protected NeranStatus walkingStatus() {
        return NeranStatus.NONE;
    }

    @Override
    public boolean canUse() {
        if (!this.colonist.aiActive() || !wanted()) {
            return false;
        }
        return stillWalking();
    }

    @Override
    public boolean canContinueToUse() {
        return wanted() && stillWalking();
    }

    private boolean stillWalking() {
        BlockPos target = target();
        if (target == null || this.detector.coolingDown(target, now())) {
            return false;
        }
        if (arrived(target)) {
            this.detector.arrived();
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        this.repathCountdown = 0;
        this.checkCountdown = CHECK_INTERVAL;
        this.colonist.setStatus(walkingStatus());
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
        BlockPos target = target();
        if (target == null) {
            return;
        }
        BlockPos spot = this.resolver.resolve(this.colonist.level(), target);
        if (--this.repathCountdown <= 0 || this.colonist.getNavigation().isDone()) {
            this.repathCountdown = REPATH_INTERVAL;
            moveTo(spot);
        }
        if (--this.checkCountdown > 0) {
            return;
        }
        this.checkCountdown = CHECK_INTERVAL;
        boolean hasPath = this.colonist.getNavigation().getPath() != null
                && !this.colonist.getNavigation().isDone();
        double distance = this.colonist.blockPosition().distSqr(spot);
        switch (this.detector.check(hasPath, distance, now())) {
            case REPATH -> moveTo(spot);
            case ALTERNATE -> {
                if (this.resolver.nextAlternate(this.colonist.level())) {
                    moveTo(this.resolver.resolve(this.colonist.level(), target));
                } else {
                    moveTo(spot);
                }
            }
            case HOP -> {
                Vec3 hop = DefaultRandomPos.getPos(this.colonist, 4, 2);
                if (hop != null) {
                    this.colonist.getNavigation().moveTo(hop.x, hop.y, hop.z, this.speed);
                }
            }
            case GIVE_UP -> {
                this.colonist.getNavigation().stop();
                this.colonist.setStatus(NeranStatus.NO_PATH);
                this.colonist.reportStuck();
                this.resolver.invalidate();
                Colony colony = this.colonist.colony();
                if (colony != null && this.colonist.level() instanceof ServerLevel serverLevel) {
                    Construction.rescue(serverLevel, colony, this.colonist);
                }
            }
            default -> {
                // making progress
            }
        }
    }

    private void moveTo(BlockPos spot) {
        this.colonist.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, this.speed);
    }

    private boolean arrived(BlockPos target) {
        BlockPos here = this.colonist.blockPosition();
        if (here.distSqr(target) <= this.arrivedDistanceSqr) {
            return true;
        }
        BlockPos spot = this.resolver.resolve(this.colonist.level(), target);
        return here.distSqr(spot) <= SPOT_ARRIVED_SQR;
    }

    protected long now() {
        return this.colonist.level().getGameTime();
    }
}
