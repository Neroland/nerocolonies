package za.co.neroland.nerocolonies.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;

import za.co.neroland.nerocolonies.block.entity.ResearchStationBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.menu.MenuOpener;
import za.co.neroland.nerocolonies.network.ColonySync;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The research station block.
 *
 * <p>Opening it pushes the player a fresh copy of the research graph and of their colony's snapshot
 * <b>before</b> the menu opens, so the screen has something to draw the moment it appears. That is
 * the whole of the sync trigger: content and snapshots are sent when a player looks at a colony, not
 * on join and not on a timer, because that is when they are needed and it costs nothing when they
 * are not.
 */
public class ResearchStationBlock extends BaseEntityBlock {

    public static final MapCodec<ResearchStationBlock> CODEC = simpleCodec(ResearchStationBlock::new);

    public ResearchStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ResearchStationBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ResearchStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.RESEARCH_STATION.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof ResearchStationBlockEntity station)) {
            return InteractionResult.SUCCESS;
        }
        Colony colony = station.colony();
        if (colony != null && !ColonyClaims.canAccess(serverPlayer, colony)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.nerocolonies.claim.no_access"));
            return InteractionResult.SUCCESS;
        }
        ColonySync.open(serverPlayer, colony, pos);
        MenuOpener.open(serverPlayer, station);
        return InteractionResult.SUCCESS;
    }
}
