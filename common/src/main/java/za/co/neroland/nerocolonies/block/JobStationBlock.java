package za.co.neroland.nerocolonies.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.menu.MenuOpener;
import za.co.neroland.nerocolonies.network.ColonySync;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * A colony job station. One class serves every station block in the mod, and any a datapack cares to
 * name: what a station <em>does</em> comes from the {@code JobDefinition}s that reference its block
 * id, never from Java.
 *
 * <p>Opening one is gated by {@link ColonyClaims#canAccess}: a station inside a claim belongs to that
 * colony, and a station on unclaimed ground is open to anyone (it is not doing anything either way,
 * because production needs a colony). Both cases go through {@link MenuOpener}, like every other GUI
 * in this mod.
 */
public class JobStationBlock extends BaseEntityBlock {

    public static final MapCodec<JobStationBlock> CODEC = simpleCodec(JobStationBlock::new);

    public JobStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<JobStationBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new JobStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.JOB_STATION.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof JobStationBlockEntity station)) {
            return InteractionResult.SUCCESS;
        }
        Colony colony = station.colonyId() == null
                ? null
                : ColonyState.get(serverPlayer.level().getServer()).colony(station.colonyId());
        if (colony != null && !ColonyClaims.canAccess(serverPlayer, colony)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.nerocolonies.claim.no_access"));
            return InteractionResult.SUCCESS;
        }
        // The station's output-routing button needs to name this block in its intent, and the anchor
        // travels in the snapshot — a menu's 16-bit data slots cannot carry a block position.
        ColonySync.open(serverPlayer, colony, pos);
        MenuOpener.open(serverPlayer, station);
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof JobStationBlockEntity station) {
            // Only the modules are here; the goods live in colony storage and stay there.
            Containers.dropContents(level, pos, station.upgrades().items());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
