package za.co.neroland.nerocolonies.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.upgrade.UpgradeContainer;
import za.co.neroland.nerolandcore.upgrade.UpgradeType;

/**
 * A colony upgrade module. Core owns the upgrade <em>framework</em> ({@link UpgradeContainer},
 * {@link UpgradeType}, {@code UpgradeModifiers}) but ships no module items, so each mod supplies its
 * own items plus the {@link UpgradeContainer.Classifier} that maps a stack to a type — which is
 * exactly what {@link #CLASSIFIER} is.
 *
 * <p>What each type does in a colony:
 * <ul>
 *   <li>{@link UpgradeType#RANGE} — widens the claim radius;</li>
 *   <li>{@link UpgradeType#EFFICIENCY} — reduces life-support oxygen burn;</li>
 *   <li>{@link UpgradeType#CAPACITY} — enlarges colony storage;</li>
 *   <li>{@link UpgradeType#SPEED} — quickens job stations (Stage 7).</li>
 * </ul>
 */
public class ColonyUpgradeItem extends Item {

    /** Maps any stack to its upgrade type — the one place module items are recognised. */
    public static final UpgradeContainer.Classifier CLASSIFIER = ColonyUpgradeItem::typeOf;

    private final UpgradeType type;

    public ColonyUpgradeItem(Properties properties, UpgradeType type) {
        super(properties);
        this.type = type;
    }

    public UpgradeType upgradeType() {
        return this.type;
    }

    /** The upgrade type a stack provides, or {@code null} when it is not a module at all. */
    @Nullable
    public static UpgradeType typeOf(ItemStack stack) {
        return stack.getItem() instanceof ColonyUpgradeItem module ? module.type : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable(
                        "item.nerocolonies.upgrade." + this.type.name().toLowerCase(java.util.Locale.ROOT)
                                + ".tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
