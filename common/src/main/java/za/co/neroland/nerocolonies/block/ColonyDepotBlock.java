package za.co.neroland.nerocolonies.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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

import za.co.neroland.nerolandcore.registry.BlockCodecs;
import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;

import za.co.neroland.nerocolonies.block.entity.ColonyDepotBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStorageBlocks;
import za.co.neroland.nerocolonies.menu.MenuOpener;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The colony depot: the block players and pipes use to reach a colony's shared storage.
 *
 * <p>Opening it needs colony access, because its contents <em>are</em> the colony's contents — a
 * depot placed inside somebody else's claim is their storage, not a way into it. On unclaimed ground
 * it opens empty and does nothing, which is exactly what it is.
 *
 * <p>Breaking it takes its capacity away: the colony's goods are packed into the depots and crates
 * that remain, and only what no longer fits drops here — everything, if it was the colony's last
 * box. See {@code ColonyStorageBlocks}.
 */
public class ColonyDepotBlock extends BaseEntityBlock {

    public static final MapCodec<ColonyDepotBlock> CODEC = BlockCodecs.simple(ColonyDepotBlock::new);

    public ColonyDepotBlock(Properties properties) {
        super(properties);
    }

    protected MapCodec<? extends ColonyDepotBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ColonyDepotBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.COLONY_DEPOT.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof ColonyDepotBlockEntity depot)) {
            return InteractionResult.SUCCESS;
        }
        if (depot.colonyId() != null) {
            Colony colony = ColonyState.get(serverPlayer.level().getServer()).colony(depot.colonyId());
            if (colony != null
                    && !ColonyPermissions.check(serverPlayer, colony, ColonyPermissions.Action.INTERACT)) {
                return InteractionResult.SUCCESS;
            }
        }
        MenuOpener.open(serverPlayer, depot);
        return InteractionResult.SUCCESS;
    }

    /**
     * The box is going: forget it, pack the colony's goods into the boxes that remain, and drop here
     * only what no longer fits. A box removed some other way (an explosion) is caught by the beacon's
     * {@link ColonyStorageBlocks#prune} instead.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel) {
            java.util.UUID colonyId = ColonyStorageBlocks.get(serverLevel.getServer())
                    .unregister(serverLevel.dimension(), pos);
            if (colonyId != null) {
                Colony colony = ColonyState.get(serverLevel.getServer()).colony(colonyId);
                if (colony != null) {
                    ColonyStorage.settle(serverLevel, pos, colonyId, ColonyStorage.usableSlots(serverLevel, colony));
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** Comparator output tracks how full the colony's storage is, so redstone can react to a glut. */
    @Override
    //? if >=1.21.9 {
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
    //?} else {
    /*protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
    *///?}
        if (!(level.getBlockEntity(pos) instanceof ColonyDepotBlockEntity depot)) {
            return 0;
        }
        int usable = depot.usableSlots();
        if (usable <= 0) {
            return 0;
        }
        int filled = 0;
        for (int slot = 0; slot < usable; slot++) {
            if (!depot.getItem(slot).isEmpty()) {
                filled++;
            }
        }
        return filled == 0 ? 0 : 1 + (int) (filled / (double) usable * 14.0D);
    }
}
