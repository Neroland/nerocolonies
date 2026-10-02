package za.co.neroland.nerocolonies.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyPlanner;
import za.co.neroland.nerocolonies.colony.ColonyState;

/**
 * The Chief's Planning Table: where the owner and Chiefs see what the colony can build and what is
 * already planned. Use it to list the unlocked buildings and the queue; building itself is done with
 * the Colony Planner item.
 */
public class PlanningTableBlock extends Block {

    public PlanningTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        Colony colony = ColonyState.get(serverLevel.getServer()).colonyAt(serverLevel.dimension(), pos);
        if (colony == null) {
            serverPlayer.sendSystemMessage(Component.translatable("message.nerocolonies.needs.no_colony"));
            return InteractionResult.SUCCESS;
        }
        if (ColonyPermissions.check(serverPlayer, colony, ColonyPermissions.Action.PLAN)) {
            ColonyPlanner.describe(serverLevel, serverPlayer, colony);
        }
        return InteractionResult.SUCCESS;
    }
}
