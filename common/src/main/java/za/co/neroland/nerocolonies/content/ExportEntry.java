package za.co.neroland.nerocolonies.content;

import java.util.List;
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
 *
 * <p>{@code colony_types} (optional) limits the entry to colonies of the listed
 * {@link za.co.neroland.nerocolonies.colony.ColonyType}s, by stored name. Absent or empty means every
 * colony.
 */
public record ExportEntry(
        Identifier id,
        ItemTarget target,
        double baseValue,
        int stackSize,
        Optional<Identifier> research,
        List<String> colonyTypes) {

    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_export");

    public ExportEntry {
        baseValue = Math.clamp(baseValue, 0.0D, 1_000_000.0D);
        stackSize = Math.clamp(stackSize, 1, 64);
        colonyTypes = colonyTypes == null ? List.of() : List.copyOf(colonyTypes);
    }

    /** An entry with no {@code colony_types} filter (available to every colony). */
    public ExportEntry(Identifier id, ItemTarget target, double baseValue, int stackSize,
            Optional<Identifier> research) {
        this(id, target, baseValue, stackSize, research, List.of());
    }

    public static final Codec<ExportEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("id", UNNAMED).forGetter(ExportEntry::id),
            ItemTarget.CODEC.fieldOf("target").forGetter(ExportEntry::target),
            Codec.DOUBLE.optionalFieldOf("base_value", 1.0D).forGetter(ExportEntry::baseValue),
            Codec.INT.optionalFieldOf("stack_size", 64).forGetter(ExportEntry::stackSize),
            Identifier.CODEC.optionalFieldOf("research").forGetter(ExportEntry::research),
            Codec.STRING.listOf().optionalFieldOf("colony_types", List.of())
                    .forGetter(ExportEntry::colonyTypes)
    ).apply(inst, ExportEntry::new));

    public ExportEntry withId(Identifier newId) {
        return new ExportEntry(newId, target, baseValue, stackSize, research, colonyTypes);
    }

    /** The same entry with a cleaned {@code colony_types} filter. */
    public ExportEntry withColonyTypes(List<String> types) {
        return new ExportEntry(id, target, baseValue, stackSize, research, types);
    }

    /** Whether a colony of this type may sell this entry (the {@code colony_types} filter). */
    public boolean availableTo(za.co.neroland.nerocolonies.colony.ColonyType type) {
        return za.co.neroland.nerocolonies.colony.ColonyType.admits(this.colonyTypes, type);
    }
}
