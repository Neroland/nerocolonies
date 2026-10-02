package za.co.neroland.nerocolonies.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import za.co.neroland.nerocolonies.colony.ColonyPlanner;

/**
 * The Colony Planner: how the owner and Chiefs place a building by hand.
 *
 * <ul>
 *   <li>Use in the air: pick the next building the colony has unlocked. Sneak-use: turn it.</li>
 *   <li>Use on the ground: preview the footprint there (green if it fits, red if not).</li>
 *   <li>Use on the same spot again: confirm. The plan jumps the colony's own queue.</li>
 * </ul>
 *
 * <p>Everything is decided on the server; the preview is an outline of particles around a footprint
 * the server has already checked.
 */
public class ColonyPlannerItem extends Item {

    public ColonyPlannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable("item.nerocolonies.colony_planner.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            ColonyPlanner.cycle(serverLevel, serverPlayer, player.isShiftKeyDown());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel() instanceof ServerLevel serverLevel
                && context.getPlayer() instanceof ServerPlayer serverPlayer) {
            ColonyPlanner.previewOrConfirm(serverLevel, serverPlayer, context.getClickedPos().above());
        }
        return InteractionResult.SUCCESS;
    }
}
