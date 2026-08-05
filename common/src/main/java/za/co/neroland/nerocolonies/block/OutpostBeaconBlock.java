package za.co.neroland.nerocolonies.block;

import java.util.UUID;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
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

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.OutpostBeaconBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.menu.MenuOpener;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The outpost beacon. Placing it founds an outpost of the nearest colony you may act on; sneak-
 * breaking it (as the parent's owner or an operator) removes one.
 *
 * <p>The placement flow mirrors the colony beacon's exactly, including the refusal path: validation
 * happens <em>after</em> the block is in the world, because vanilla gives no pre-placement veto for a
 * block item, so a refused placement removes the block and hands the item back with a translated
 * reason rather than leaving an inert beacon standing.
 *
 * <p><b>There is no graduation to a full colony.</b> Break the outpost and place a colony beacon if
 * that is what you want. Graduation would have to decide what happens to the parent's claim, the
 * shared research and the split of the goods — three questions with no obviously right answer, none
 * of which need answering for outposts to be useful.
 */
public class OutpostBeaconBlock extends BaseEntityBlock {

    public static final MapCodec<OutpostBeaconBlock> CODEC = simpleCodec(OutpostBeaconBlock::new);

    public OutpostBeaconBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<OutpostBeaconBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OutpostBeaconBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.OUTPOST_BEACON.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }

    // --- interaction --------------------------------------------------------

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof OutpostBeaconBlockEntity beacon)) {
            return InteractionResult.SUCCESS;
        }
        Colony parent = beacon.parent();
        if (parent != null && !ColonyClaims.canAccess(serverPlayer, parent)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.nerocolonies.claim.no_access"));
            return InteractionResult.SUCCESS;
        }
        MenuOpener.open(serverPlayer, beacon);
        return InteractionResult.SUCCESS;
    }

    // --- founding -----------------------------------------------------------

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof OutpostBeaconBlockEntity beacon)) {
            return;
        }
        ColonyClaims.OutpostPlacement placement =
                ColonyClaims.validateOutpostPlacement(serverLevel, pos, player);
        if (placement.parent() == null) {
            refuse(serverLevel, pos, player, stack, placement.refusal());
            return;
        }
        Colony parent = placement.parent();
        Outpost outpost = new Outpost(UUID.randomUUID(), parent.colonyId(), serverLevel.dimension(), pos,
                ColonyClaims.effectiveOutpostRadius(0), serverLevel.getGameTime());
        ColonyState colonies = ColonyState.get(serverLevel.getServer());
        colonies.putOutpost(outpost);
        beacon.bind(outpost.outpostId());

        player.sendSystemMessage(
                Component.translatable("message.nerocolonies.outpost.founded", parent.name()));
        NeroColoniesCommon.LOGGER.info("[NeroColonies] An outpost was founded in {}.",
                serverLevel.dimension().identifier());
    }

    /** Undoes a refused placement: remove the block, hand the item back, explain why. */
    private static void refuse(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack stack,
            @Nullable Component reason) {
        level.removeBlock(pos, false);
        if (!player.isCreative()) {
            ItemStack refund = stack.copy();
            refund.setCount(1);
            if (!player.getInventory().add(refund)) {
                player.drop(refund, false);
            }
        }
        if (reason != null) {
            player.sendSystemMessage(reason);
        }
    }

    // --- removal ------------------------------------------------------------

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float base = super.getDestroyProgress(state, player, level, pos);
        if (!(level instanceof ServerLevel serverLevel)) {
            return base; // client: animate normally, the server is the authority
        }
        return ColonyClaims.mayRemoveOutpost(serverLevel, pos, player) ? base : 0.0F;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof OutpostBeaconBlockEntity beacon) {
            Containers.dropContents(level, pos, beacon.allContents());
            Outpost outpost = ColonyClaims.outpostAtBeacon(serverLevel, pos);
            if (outpost != null) {
                ColonyState.get(serverLevel.getServer()).removeOutpost(outpost.outpostId());
                player.sendSystemMessage(Component.translatable("message.nerocolonies.outpost.removed"));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
