package za.co.neroland.nerocolonies.content;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A Neran trade, loaded from {@code data/<ns>/nerocolonies/professions/<path>.json}.
 *
 * <pre>{@code
 * {
 *   "name": "profession.nerocolonies.farmer",
 *   "behaviour": "nerocolonies:farmer",
 *   "tool": "minecraft:iron_hoe",
 *   "stations": [ "nerocolonies:farm_station" ],
 *   "priority": 10,
 *   "per_building": 2,
 *   "max_per_colony": 8,
 *   "xp_per_level": 100,
 *   "max_level": 5,
 *   "work_radius": 10,
 *   "outputs": [ { "item": "minecraft:wheat", "count": 1 } ],
 *   "output_chance": 0.5
 * }
 * }</pre>
 *
 * <p><b>Numbers and bindings are data; behaviours are code.</b> {@code behaviour} names one of the
 * work behaviours compiled into the mod (an unknown id falls back to "stand at the workstation").
 * A profession is <b>unlocked</b> by any finished building whose blueprint lists it in
 * {@code unlocks}, and each such building opens {@code per_building} places, capped by
 * {@code max_per_colony}. {@code stations} are job-station blocks a Neran of this trade may staff.
 * {@code outputs} are what one Neran gathers per colony cycle of simulated work — the part of
 * the trade that keeps going while nobody is watching — with probability {@code output_chance}
 * (above 1, whole extra batches), scaled by level, morale and whether the Neran has its tool.
 */
public record ProfessionDefinition(
        Identifier id,
        String name,
        Identifier behaviour,
        Optional<Identifier> tool,
        List<Identifier> stations,
        int priority,
        int perBuilding,
        int maxPerColony,
        int xpPerLevel,
        int maxLevel,
        int workRadius,
        List<ItemAmount> outputs,
        double outputChance) {

    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_profession");

    public ProfessionDefinition {
        name = name == null ? "" : name;
        stations = stations == null ? List.of() : List.copyOf(stations);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        priority = Math.clamp(priority, 0, 10_000);
        perBuilding = Math.clamp(perBuilding, 0, 64);
        maxPerColony = Math.clamp(maxPerColony, 0, 256);
        xpPerLevel = Math.clamp(xpPerLevel, 1, 100_000);
        maxLevel = Math.clamp(maxLevel, 1, 10);
        workRadius = Math.clamp(workRadius, 1, 32);
        outputChance = Math.clamp(outputChance, 0.0D, 64.0D);
    }

    public static final Codec<ProfessionDefinition> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("name", "").forGetter(ProfessionDefinition::name),
            Identifier.CODEC.fieldOf("behaviour").forGetter(ProfessionDefinition::behaviour),
            Identifier.CODEC.optionalFieldOf("tool").forGetter(ProfessionDefinition::tool),
            Identifier.CODEC.listOf().optionalFieldOf("stations", List.of())
                    .forGetter(ProfessionDefinition::stations),
            Codec.INT.optionalFieldOf("priority", 100).forGetter(ProfessionDefinition::priority),
            Codec.INT.optionalFieldOf("per_building", 2).forGetter(ProfessionDefinition::perBuilding),
            Codec.INT.optionalFieldOf("max_per_colony", 8).forGetter(ProfessionDefinition::maxPerColony),
            Codec.INT.optionalFieldOf("xp_per_level", 100).forGetter(ProfessionDefinition::xpPerLevel),
            Codec.INT.optionalFieldOf("max_level", 5).forGetter(ProfessionDefinition::maxLevel),
            Codec.INT.optionalFieldOf("work_radius", 10).forGetter(ProfessionDefinition::workRadius),
            ItemAmount.CODEC.listOf().optionalFieldOf("outputs", List.of())
                    .forGetter(ProfessionDefinition::outputs),
            Codec.DOUBLE.optionalFieldOf("output_chance", 1.0D).forGetter(ProfessionDefinition::outputChance)
    ).apply(inst, (name, behaviour, tool, stations, priority, perBuilding, max, xp, maxLevel, radius,
            outputs, chance) -> new ProfessionDefinition(UNNAMED, name, behaviour, tool, stations, priority,
                    perBuilding, max, xp, maxLevel, radius, outputs, chance)));

    public ProfessionDefinition withId(Identifier newId) {
        return new ProfessionDefinition(newId, name, behaviour, tool, stations, priority, perBuilding,
                maxPerColony, xpPerLevel, maxLevel, workRadius, outputs, outputChance);
    }

    /** Translation key for the trade's name. */
    public String nameKey() {
        return this.name.isBlank()
                ? "profession." + this.id.getNamespace() + "." + this.id.getPath().replace('/', '.')
                : this.name;
    }

    /** The tool a Neran of this trade holds, or empty if none or not registered. */
    public ItemStack toolStack() {
        return this.tool.filter(BuiltInRegistries.ITEM::containsKey)
                .map(id -> new ItemStack(BuiltInRegistries.ITEM.getValue(id)))
                .orElse(ItemStack.EMPTY);
    }

    /** The tool item, or null. */
    public Item toolItem() {
        return this.tool.filter(BuiltInRegistries.ITEM::containsKey)
                .map(BuiltInRegistries.ITEM::getValue).orElse(null);
    }

    /** Level reached with {@code xp} experience, 1-based, capped at {@code max_level}. Pure. */
    public int levelFor(int xp) {
        return levelFor(xp, this.xpPerLevel, this.maxLevel);
    }

    /**
     * Level for an xp total on a gently rising curve: level {@code n} needs
     * {@code xpPerLevel * n * (n - 1) / 2} xp in total, so each level costs one step more than the
     * last. Pure; unit-tested.
     */
    public static int levelFor(int xp, int xpPerLevel, int maxLevel) {
        int level = 1;
        while (level < maxLevel && xp >= xpPerLevel * level * (level + 1) / 2) {
            level++;
        }
        return level;
    }
}
