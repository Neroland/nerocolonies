package za.co.neroland.nerocolonies.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
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

import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.menu.MenuOpener;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The colony oxygen generator block. Right-click opens its gauges; a comparator reads its tank.
 *
 * <p>Interaction is claim-gated: a generator inside somebody else's claim will not open for a
 * stranger, because life support is the one machine whose being switched off ruins a colony. Outside
 * any claim it opens for anyone, like any other machine.
 */
public class OxygenGeneratorBlock extends BaseEntityBlock {

    public static final MapCodec<OxygenGeneratorBlock> CODEC = simpleCodec(OxygenGeneratorBlock::new);

    public OxygenGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<OxygenGeneratorBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OxygenGeneratorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.OXYGEN_GENERATOR.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof OxygenGeneratorBlockEntity generator)) {
            return InteractionResult.SUCCESS;
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            Colony colony = ColonyState.get(serverLevel.getServer())
                    .colonyAt(serverLevel.dimension(), pos);
            if (colony != null && !ColonyClaims.canAccess(serverPlayer, colony)) {
                serverPlayer.sendSystemMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "message.nerocolonies.claim.no_access"));
                return InteractionResult.SUCCESS;
            }
        }
        MenuOpener.open(serverPlayer, generator);
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level.getBlockEntity(pos) instanceof OxygenGeneratorBlockEntity generator) {
            Containers.dropContents(level, pos, generator);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof OxygenGeneratorBlockEntity generator
                ? generator.comparatorSignal() : 0;
    }
}
