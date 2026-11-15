package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyConstruction;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Builder: the colony sends every Builder to the active site's access spot (see
 * {@code Construction}), where each one within eight blocks speeds the build. At the site it works
 * the cell being laid next: it moves along the site's edge towards that cell, never further than
 * {@value #STAY} blocks from the access spot so it keeps counting, faces it and works it. The blocks
 * themselves are laid by the Nerans working the site, one per stroke from the build's banked credit
 * ({@link Construction#placeByHand}); with nobody on site the colony's construction cycle lays them.
 */
final class SiteWork {

    /** How far from the access spot a Builder may step to follow the work. */
    private static final int STAY = 5;

    /** How close a Neran must be to the cell to lay it. */
    private static final double HAND_REACH_SQR = 64.0D;

    private SiteWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        BlockPos cell = nextCell(level, colony, neran.workMemory());
        if (cell == null) {
            return WorkSupport.atPost(neran, anchor, profession.workRadius());
        }
        BlockPos stand = standNear(level, colony, anchor, cell);
        if (!WorkSupport.reach(neran, stand, 2.0D)) {
            return false;
        }
        WorkSupport.strike(neran, cell);
        if (neran.blockPosition().distSqr(cell) <= HAND_REACH_SQR) {
            Construction.placeByHand(level, colony);
        }
        return true;
    }

    /** The world position of the next cell the active build will lay, or null. */
    @Nullable
    private static BlockPos nextCell(ServerLevel level, Colony colony, WorkMemory memory) {
        MinecraftServer server = level.getServer();
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        if (plan == null || plan.active() == null || plan.origin() == null) {
            return null;
        }
        Blueprint blueprint = Construction.activeBlueprint(server, colony.colonyId());
        if (blueprint == null) {
            return null;
        }
        if (!plan.active().equals(memory.orderOf) || memory.order == null) {
            memory.order = List.copyOf(blueprint.buildOrder());
            memory.orderOf = plan.active();
        }
        List<BlockPos> order = memory.order;
        int cursor = plan.cursor();
        if (cursor < 0 || cursor >= order.size()) {
            return null;
        }
        return plan.origin().offset(blueprint.rotate(order.get(cursor), plan.rotation()));
    }

    /** A standable cell on the way from the access spot to the cell, at most {@value #STAY} out. */
    private static BlockPos standNear(ServerLevel level, Colony colony, BlockPos anchor, BlockPos cell) {
        double dx = cell.getX() - anchor.getX();
        double dz = cell.getZ() - anchor.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        for (int step = Math.min(STAY, (int) Math.floor(length)); step > 0; step--) {
            BlockPos candidate = BlockPos.containing(anchor.getX() + 0.5D + dx / length * step, anchor.getY(),
                    anchor.getZ() + 0.5D + dz / length * step);
            if (WorkSupport.inClaim(level, colony, candidate) && WorkSupport.standable(level, candidate)
                    && !Construction.insideActiveSite(level.getServer(), colony.colonyId(), candidate)) {
                return candidate;
            }
        }
        return anchor;
    }
}
