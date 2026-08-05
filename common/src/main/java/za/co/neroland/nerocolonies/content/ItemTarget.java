package za.co.neroland.nerocolonies.content;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/**
 * What a job input or an export entry is looking for: <b>exactly one</b> of a single item id or an
 * item tag, plus a count.
 *
 * <pre>{@code
 * { "item": "minecraft:wheat", "count": 4 }
 * { "tag":  "c:crops",         "count": 4 }
 * }</pre>
 *
 * <p>Declaring both, or neither, is a decode error and drops the owning definition with a warning.
 *
 * <p>Tags are the preferred form throughout the shipped content: a tag lets NeroAgriculture,
 * Nerospace or any third-party mod satisfy a colony job with its own produce and needs no compat
 * code on either side. Hard item ids are only used where the item is unmistakably vanilla.
 */
public record ItemTarget(Optional<Identifier> item, Optional<Identifier> tag, int count) {

    public ItemTarget {
        count = Math.max(1, count);
    }

    /** Contributes {@code item} / {@code tag} / {@code count} straight into the owner's map. */
    public static final MapCodec<ItemTarget> MAP_CODEC = RecordCodecBuilder
            .<ItemTarget>mapCodec(inst -> inst.group(
                    Identifier.CODEC.optionalFieldOf("item").forGetter(ItemTarget::item),
                    Identifier.CODEC.optionalFieldOf("tag").forGetter(ItemTarget::tag),
                    Codec.INT.optionalFieldOf("count", 1).forGetter(ItemTarget::count)
            ).apply(inst, ItemTarget::new))
            .validate(target -> target.item().isPresent() == target.tag().isPresent()
                    ? DataResult.error(() -> "an item target needs exactly one of 'item' or 'tag'")
                    : DataResult.success(target));

    public static final Codec<ItemTarget> CODEC = MAP_CODEC.codec();

    /** Whether {@code stack} is one of the items this target selects. Empty stacks never match. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (this.item.isPresent()) {
            return stack.typeHolder().is(this.item.get());
        }
        return this.tag.isPresent() && stack.typeHolder().is(tagKey());
    }

    /** This target's tag key, or {@code null} when it selects a single item id. */
    public TagKey<net.minecraft.world.item.Item> tagKey() {
        return this.tag.map(id -> TagKey.create(Registries.ITEM, id)).orElse(null);
    }

    /**
     * Whether the selected content exists in the running game: the item id is registered, or the tag
     * resolves to at least one item. {@code BuiltInRegistries.ITEM} is a <em>defaulted</em> registry
     * (an unknown id silently yields {@code air}), so this asks {@code containsKey} rather than
     * looking the value up.
     */
    public boolean present() {
        if (this.item.isPresent()) {
            return BuiltInRegistries.ITEM.containsKey(this.item.get());
        }
        return this.tag.isPresent()
                && BuiltInRegistries.ITEM.getTagOrEmpty(tagKey()).iterator().hasNext();
    }

    /** A log-safe label (a resource id, never player data). */
    public String label() {
        return this.item.map(Identifier::toString)
                .orElseGet(() -> this.tag.map(id -> "#" + id).orElse("<no item or tag>"));
    }
}
