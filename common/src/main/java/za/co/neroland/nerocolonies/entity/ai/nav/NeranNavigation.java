package za.co.neroland.nerocolonies.entity.ai.nav;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;

/**
 * Neran ground navigation: opens and passes wooden doors, floats in water rather than drowning on a
 * path, and searches a little harder than a vanilla mob so a path across a busy colony is found
 * instead of truncated. Everything else is vanilla.
 */
public class NeranNavigation extends GroundPathNavigation {

    /** Node budget multiplier: 1.5× vanilla, enough for a cluttered claim without being a TPS risk. */
    private static final float VISITED_NODES_MULTIPLIER = 1.5F;

    @SuppressWarnings("this-escape") // configuring our own evaluator in the constructor is the vanilla pattern
    public NeranNavigation(Mob mob, Level level) {
        super(mob, level);
        this.setCanOpenDoors(true);
        this.setCanFloat(true);
        this.setMaxVisitedNodesMultiplier(VISITED_NODES_MULTIPLIER);
        this.getNodeEvaluator().setCanPassDoors(true);
    }
}
