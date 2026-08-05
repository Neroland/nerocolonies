package za.co.neroland.nerocolonies.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * The colony beacon's item form, carrying the two lines a player has to know before they place it:
 * placing founds a colony, and sneak-breaking dissolves it.
 *
 * <p>26.x blocks expose no hover-text hook — {@code Block} has no {@code appendHoverText} — so a
 * block's tooltip lives on its {@link BlockItem}. This is the ecosystem's standard workaround
 * (Nerotech's {@code TooltipBlockItem}); putting it on the block instead simply will not compile.
 */
public class ColonyBeaconItem extends BlockItem {

    public ColonyBeaconItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable("block.nerocolonies.colony_beacon.tooltip")
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("block.nerocolonies.colony_beacon.tooltip.dissolve")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
