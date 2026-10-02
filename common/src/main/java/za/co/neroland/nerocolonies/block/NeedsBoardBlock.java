package za.co.neroland.nerocolonies.block;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyNeeds;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyState;

/**
 * The Needs Board: where a colony posts what it is short of, and where members hand it over.
 *
 * <p>Use it empty-handed to read the list (what, how much, and how long the colony would take alone).
 * Use it with an item the list wants and that much of the stack goes straight into colony storage,
 * which is where every need is paid from. The board stores nothing itself: the list is worked out
 * fresh each time, and the goods go to the colony's one shared store.
 */
public class NeedsBoardBlock extends Block {

    /** Lines shown per read, so a long list does not flood the chat. */
    private static final int MAX_LINES = 8;

    public NeedsBoardBlock(Properties properties) {
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
        if (!ColonyPermissions.check(serverPlayer, colony, ColonyPermissions.Action.CONTRIBUTE)) {
            return InteractionResult.SUCCESS;
        }
        List<ColonyNeeds.Need> needs = ColonyNeeds.derive(serverLevel, colony);
        if (needs.isEmpty()) {
            serverPlayer.sendSystemMessage(Component.translatable("message.nerocolonies.needs.none", colony.name()));
            return InteractionResult.SUCCESS;
        }
        serverPlayer.sendSystemMessage(Component.translatable("message.nerocolonies.needs.header", colony.name())
                .withStyle(ChatFormatting.GOLD));
        int shown = 0;
        for (ColonyNeeds.Need need : needs) {
            if (shown++ >= MAX_LINES) {
                serverPlayer.sendSystemMessage(Component.translatable("message.nerocolonies.needs.more",
                        needs.size() - MAX_LINES).withStyle(ChatFormatting.GRAY));
                break;
            }
            MutableComponent eta = need.etaSoloMinutes() < 0
                    ? Component.translatable("message.nerocolonies.needs.eta_never")
                    : Component.translatable("message.nerocolonies.needs.eta", need.etaSoloMinutes());
            serverPlayer.sendSystemMessage(Component.translatable("message.nerocolonies.needs.line",
                    Component.translatable(need.nameKey()), need.have(), need.needed(), eta)
                    .withStyle(need.priority() ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        Colony colony = ColonyState.get(serverLevel.getServer()).colonyAt(serverLevel.dimension(), pos);
        if (colony == null || !ColonyPermissions.check(serverPlayer, colony, ColonyPermissions.Action.CONTRIBUTE)) {
            return InteractionResult.SUCCESS;
        }
        Component name = stack.getHoverName();
        int accepted = ColonyNeeds.contribute(serverLevel, colony, stack);
        serverPlayer.sendOverlayMessage(accepted > 0
                ? Component.translatable("message.nerocolonies.needs.thanks", accepted, name)
                : Component.translatable("message.nerocolonies.needs.not_needed", name));
        return InteractionResult.SUCCESS;
    }
}
