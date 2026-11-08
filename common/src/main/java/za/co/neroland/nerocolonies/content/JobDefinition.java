package za.co.neroland.nerocolonies.content;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * One automated colony job, loaded from {@code data/<ns>/nerocolonies/jobs/<path>.json}.
 *
 * <pre>{@code
 * {
 *   "station": "nerocolonies:farm_station",
 *   "inputs":  [ { "tag": "c:seeds", "count": 1 } ],
 *   "outputs": [ { "item": "minecraft:wheat", "count": 2 } ],
 *   "ticks": 200,
 *   "colonists": 1,
 *   "morale_floor": 20,
 *   "research": "nerocolonies:habitation/allotments",
 *   "export": false
 * }
 * }</pre>
 *
 * <p>Every magnitude here is scaled at runtime by {@code jobBaseRateMultiplier} and by the colony's
 * morale multiplier, so the JSON expresses <b>shape</b> (what turns into what, and roughly how
 * fast), not balance.
 *
 * <p>The {@code id} field is optional in JSON and ignored: the id is the file's namespace + path
 * without the extension, so a datapack overrides a job simply by shipping the same path.
 *
 * <p>{@code colony_types} (optional) limits the job to colonies of the listed
 * {@link za.co.neroland.nerocolonies.colony.ColonyType}s, by stored name. Absent or empty means every
 * colony. An unknown name is reported by the loader.
 */
public record JobDefinition(
        Identifier id,
        Identifier station,
        List<ItemTarget> inputs,
        List<ItemAmount> outputs,
        int ticks,
        int colonists,
        double moraleFloor,
        Optional<Identifier> research,
        boolean export,
        List<String> colonyTypes) {

    /** Stands in until the loader replaces it with the file-derived id. */
    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_job");

    public JobDefinition {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        ticks = Math.clamp(ticks, 1, 72_000);
        colonists = Math.clamp(colonists, 0, 64);
        moraleFloor = Math.clamp(moraleFloor, 0.0D, 100.0D);
        colonyTypes = colonyTypes == null ? List.of() : List.copyOf(colonyTypes);
    }

    /** A job with no {@code colony_types} filter (available to every colony). */
    public JobDefinition(Identifier id, Identifier station, List<ItemTarget> inputs, List<ItemAmount> outputs,
            int ticks, int colonists, double moraleFloor, Optional<Identifier> research, boolean export) {
        this(id, station, inputs, outputs, ticks, colonists, moraleFloor, research, export, List.of());
    }

    public static final Codec<JobDefinition> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("id", UNNAMED).forGetter(JobDefinition::id),
            Identifier.CODEC.fieldOf("station").forGetter(JobDefinition::station),
            ItemTarget.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(JobDefinition::inputs),
            ItemAmount.CODEC.listOf().optionalFieldOf("outputs", List.of()).forGetter(JobDefinition::outputs),
            Codec.INT.optionalFieldOf("ticks", 200).forGetter(JobDefinition::ticks),
            Codec.INT.optionalFieldOf("colonists", 1).forGetter(JobDefinition::colonists),
            Codec.DOUBLE.optionalFieldOf("morale_floor", 20.0D).forGetter(JobDefinition::moraleFloor),
            Identifier.CODEC.optionalFieldOf("research").forGetter(JobDefinition::research),
            Codec.BOOL.optionalFieldOf("export", false).forGetter(JobDefinition::export),
            Codec.STRING.listOf().optionalFieldOf("colony_types", List.of())
                    .forGetter(JobDefinition::colonyTypes)
    ).apply(inst, JobDefinition::new));

    /** The same job under its file-derived id. */
    public JobDefinition withId(Identifier newId) {
        return new JobDefinition(newId, station, inputs, outputs, ticks, colonists, moraleFloor,
                research, export, colonyTypes);
    }

    /** The same job with a cleaned {@code colony_types} filter. */
    public JobDefinition withColonyTypes(List<String> types) {
        return new JobDefinition(id, station, inputs, outputs, ticks, colonists, moraleFloor,
                research, export, types);
    }

    /** Whether a colony of this type may run this job (the {@code colony_types} filter). */
    public boolean availableTo(za.co.neroland.nerocolonies.colony.ColonyType type) {
        return za.co.neroland.nerocolonies.colony.ColonyType.admits(this.colonyTypes, type);
    }

    /** Whether the station block this job runs on is registered in this launch. */
    public boolean stationPresent() {
        return BuiltInRegistries.BLOCK.containsKey(this.station);
    }
}
