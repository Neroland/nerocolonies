package za.co.neroland.nerocolonies.colony;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A planetary outpost: a small remote work site that belongs to a parent {@link Colony}.
 *
 * <h2>What an outpost is, and what it deliberately is not</h2>
 *
 * <p>An outpost is a <b>lighter</b> thing than a colony, and the difference is structural rather than
 * a matter of tuning. It has:
 *
 * <ul>
 *   <li>a parent colony id, and it borrows that colony's claim and permission context wholesale —
 *       there is no separate owner and no separate access list, so there is no second place where
 *       player-shaped data can accumulate;</li>
 *   <li>its own small claim radius ({@code outpostClaimRadius});</li>
 *   <li>reduced caps ({@code outpostColonistCap}, {@code outpostJobSlots});</li>
 *   <li>no research, no morale, no housing tiers and no food store of its own — it runs on the
 *       parent's, and its production feeds the parent's storage on the parent's colony tick.</li>
 * </ul>
 *
 * <p><b>An outpost cannot become a colony in 0.1.0.</b> There is no graduation path: break it and
 * place a colony beacon instead. Graduation would mean deciding what happens to the parent's claim,
 * the shared research and the split of the goods — three design questions with no obviously right
 * answer, none of which need answering to make outposts useful.
 *
 * <h2>Lifetime</h2>
 *
 * <p>An outpost whose parent has been dissolved is <b>orphaned</b>: it goes inert immediately (the
 * parent lookup returns nothing, so nothing ticks) and the retention sweep removes the record. It is
 * never silently re-parented — an outpost that quietly attached itself to whichever colony happened
 * to be nearest would be a claim exploit.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing here is player-shaped. An outpost points at a colony, and the colony points at an owner;
 * a caller asking permission questions about an outpost is answered from the parent's record through
 * the same boolean-only surface as everything else.
 */
public record Outpost(
        UUID outpostId,
        UUID parentColonyId,
        ResourceKey<Level> dimension,
        BlockPos pos,
        int claimRadius,
        long createdAt) {

    public Outpost {
        pos = pos.immutable();
        claimRadius = Math.max(1, claimRadius);
    }

    public static final MapCodec<Outpost> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Colony.UUID_CODEC.fieldOf("id").forGetter(Outpost::outpostId),
            Colony.UUID_CODEC.fieldOf("parent").forGetter(Outpost::parentColonyId),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Outpost::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Outpost::pos),
            Codec.INT.optionalFieldOf("claim_radius", 16).forGetter(Outpost::claimRadius),
            Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(Outpost::createdAt)
    ).apply(instance, Outpost::new));

    public static final Codec<Outpost> CODEC = MAP_CODEC.codec();

    /** Whether a position falls inside this outpost's square claim (same dimension assumed). */
    public boolean contains(BlockPos other) {
        int dx = Math.abs(other.getX() - this.pos.getX());
        int dz = Math.abs(other.getZ() - this.pos.getZ());
        return dx <= this.claimRadius && dz <= this.claimRadius;
    }

    /** Horizontal distance from the outpost beacon, squared. */
    public double horizontalDistanceSqr(BlockPos other) {
        double dx = other.getX() - this.pos.getX();
        double dz = other.getZ() - this.pos.getZ();
        return dx * dx + dz * dz;
    }

    /** The same outpost under a new claim radius (an upgrade module, or a config change). */
    public Outpost withClaimRadius(int radius) {
        return new Outpost(outpostId, parentColonyId, dimension, pos, radius, createdAt);
    }
}
