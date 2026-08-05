package za.co.neroland.nerocolonies.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A concrete quantity of one item — a job's output, or one line of a research node's cost.
 *
 * <pre>{@code { "item": "minecraft:iron_ingot", "count": 8 }}</pre>
 *
 * <p>Stored as an {@link Identifier} rather than a resolved {@link Item} so that content naming an
 * item from a mod which is not installed can be <em>validated and reported</em> instead of silently
 * decoding to air — {@code BuiltInRegistries.ITEM} is a defaulted registry.
 */
public record ItemAmount(Identifier item, int count) {

    public ItemAmount {
        count = Math.max(1, count);
    }

    public static final MapCodec<ItemAmount> MAP_CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("item").forGetter(ItemAmount::item),
            Codec.INT.optionalFieldOf("count", 1).forGetter(ItemAmount::count)
    ).apply(inst, ItemAmount::new));

    public static final Codec<ItemAmount> CODEC = MAP_CODEC.codec();

    /** Whether the named item is registered in this launch. */
    public boolean present() {
        return BuiltInRegistries.ITEM.containsKey(this.item);
    }

    /** A stack of this amount, or {@link ItemStack#EMPTY} when the item is not registered. */
    public ItemStack toStack() {
        if (!present()) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(this.item), this.count);
    }
}
