package za.co.neroland.nerocolonies.content;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * One kind of colonist housing, loaded from {@code data/<ns>/nerocolonies/housing/<path>.json}.
 *
 * <pre>{@code
 * {
 *   "block": "nerocolonies:habitat_pod",
 *   "tier": 1,
 *   "capacity": 2,
 *   "comfort": 0.4,
 *   "research": "nerocolonies:habitation/pressurised_pods"
 * }
 * }</pre>
 *
 * <p>Housing is matched by <b>block</b>, not by block entity: it costs one block-state comparison
 * during the housing sweep, and it lets a datapack declare any block in the game — vanilla beds,
 * another mod's habitat module — as colony housing with no code on either side.
 *
 * <p>{@code capacity} is how many colonists the block houses; {@code comfort} (0..1) is its weight
 * in the morale housing term, so a cramped pod can house the same number of people as a proper
 * module and still feel worse to live in.
 */
public record HousingTier(
        Identifier id,
        Identifier block,
        int tier,
        int capacity,
        double comfort,
        Optional<Identifier> research) {

    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_housing");

    public HousingTier {
        tier = Math.clamp(tier, 1, 16);
        capacity = Math.clamp(capacity, 0, 256);
        comfort = Math.clamp(comfort, 0.0D, 1.0D);
    }

    public static final Codec<HousingTier> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("id", UNNAMED).forGetter(HousingTier::id),
            Identifier.CODEC.fieldOf("block").forGetter(HousingTier::block),
            Codec.INT.optionalFieldOf("tier", 1).forGetter(HousingTier::tier),
            Codec.INT.optionalFieldOf("capacity", 1).forGetter(HousingTier::capacity),
            Codec.DOUBLE.optionalFieldOf("comfort", 0.5D).forGetter(HousingTier::comfort),
            Identifier.CODEC.optionalFieldOf("research").forGetter(HousingTier::research)
    ).apply(inst, HousingTier::new));

    public HousingTier withId(Identifier newId) {
        return new HousingTier(newId, block, tier, capacity, comfort, research);
    }

    /** Whether the housing block is registered in this launch. */
    public boolean blockPresent() {
        return BuiltInRegistries.BLOCK.containsKey(this.block);
    }

    /** The resolved block, or {@code null} when it is not registered. */
    public Block resolvedBlock() {
        return blockPresent() ? BuiltInRegistries.BLOCK.getValue(this.block) : null;
    }
}
