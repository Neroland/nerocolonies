package za.co.neroland.nerocolonies.content;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One sellable colony export, loaded from {@code data/<ns>/nerocolonies/exports/<path>.json}.
 *
 * <pre>{@code
 * {
 *   "target": { "tag": "c:crops", "count": 1 },
 *   "base_value": 2.5,
 *   "stack_size": 64,
 *   "research": "nerocolonies:trade/manifest"
 * }
 * }</pre>
 *
 * <p>Export tables stay pure datapack. The <b>only</b> code-level hook anywhere in the export system
 * is {@link #baseValue()} feeding Core's currency API when a colony sells — there is no pricing
 * engine in NeroColonies, and there will not be one: NeroEconomy owns pricing when it exists.
 */
public record ExportEntry(
        Identifier id,
        ItemTarget target,
        double baseValue,
        int stackSize,
        Optional<Identifier> research) {

    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_export");

    public ExportEntry {
        baseValue = Math.clamp(baseValue, 0.0D, 1_000_000.0D);
        stackSize = Math.clamp(stackSize, 1, 64);
    }

    public static final Codec<ExportEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("id", UNNAMED).forGetter(ExportEntry::id),
            ItemTarget.CODEC.fieldOf("target").forGetter(ExportEntry::target),
            Codec.DOUBLE.optionalFieldOf("base_value", 1.0D).forGetter(ExportEntry::baseValue),
            Codec.INT.optionalFieldOf("stack_size", 64).forGetter(ExportEntry::stackSize),
            Identifier.CODEC.optionalFieldOf("research").forGetter(ExportEntry::research)
    ).apply(inst, ExportEntry::new));

    public ExportEntry withId(Identifier newId) {
        return new ExportEntry(newId, target, baseValue, stackSize, research);
    }
}
