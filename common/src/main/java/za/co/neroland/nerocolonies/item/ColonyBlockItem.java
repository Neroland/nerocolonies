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
 * A {@link BlockItem} that carries one explanatory tooltip line.
 *
 * <p>26.x blocks expose no hover-text hook — {@code Block} has no {@code appendHoverText} — so a
 * block's tooltip has to live on its item. This is the generic form of the workaround (the colony
 * beacon has its own subclass because it needs two lines); a block whose behaviour is not obvious
 * from its name gets one of these rather than a bespoke class.
 */
public class ColonyBlockItem extends BlockItem {

    private final String tooltipKey;

    public ColonyBlockItem(Block block, Properties properties, String tooltipKey) {
        super(block, properties);
        this.tooltipKey = tooltipKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable(this.tooltipKey).withStyle(ChatFormatting.GRAY));
    }
}
