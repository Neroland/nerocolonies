package za.co.neroland.nerocolonies.content;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.content.effect.ResearchEffect;

/**
 * One node of the colony research tree, loaded from
 * {@code data/<ns>/nerocolonies/research/<path>.json}.
 *
 * <pre>{@code
 * {
 *   "branch": "habitation",
 *   "title": "research.nerocolonies.habitation.pressurised_pods",
 *   "requires": [ "nerocolonies:habitation/shelter" ],
 *   "cost": [ { "item": "minecraft:iron_ingot", "count": 8 } ],
 *   "effects": [ { "type": "nerocolonies:housing_tier", "tier": "nerocolonies:habitat_module" } ]
 * }
 * }</pre>
 *
 * <p>Research is <b>colony-local</b>: an unlocked node is stored on the colony record, not on a
 * player, and is therefore not personal data. Dissolving a colony discards its research.
 *
 * <p>{@code branch} is presentational only — it groups nodes in the research screen. The actual
 * graph is {@code requires}, which the loader validates for dangling references and cycles.
 */
public record ResearchNode(
        Identifier id,
        String branch,
        String title,
        List<Identifier> requires,
        List<ItemAmount> cost,
        List<ResearchEffect> effects) {

    public static final Identifier UNNAMED =
            Identifier.fromNamespaceAndPath("nerocolonies", "unnamed_research");

    public ResearchNode {
        branch = branch == null || branch.isBlank() ? "general" : branch;
        requires = requires == null ? List.of() : List.copyOf(requires);
        cost = cost == null ? List.of() : List.copyOf(cost);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    public static final Codec<ResearchNode> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("id", UNNAMED).forGetter(ResearchNode::id),
            Codec.STRING.optionalFieldOf("branch", "general").forGetter(ResearchNode::branch),
            Codec.STRING.optionalFieldOf("title", "").forGetter(ResearchNode::title),
            Identifier.CODEC.listOf().optionalFieldOf("requires", List.of())
                    .forGetter(ResearchNode::requires),
            ItemAmount.CODEC.listOf().optionalFieldOf("cost", List.of()).forGetter(ResearchNode::cost),
            ResearchEffect.CODEC.listOf().optionalFieldOf("effects", List.of())
                    .forGetter(ResearchNode::effects)
    ).apply(inst, ResearchNode::new));

    public ResearchNode withId(Identifier newId) {
        return new ResearchNode(newId, branch, title, requires, cost, effects);
    }

    /** The same node with a pruned prerequisite list (dangling references removed). */
    public ResearchNode withRequires(List<Identifier> pruned) {
        return new ResearchNode(id, branch, title, pruned, cost, effects);
    }

    /** The translation key for this node's title, falling back to a key derived from its id. */
    public String titleKey() {
        if (!this.title.isBlank()) {
            return this.title;
        }
        return "research." + this.id.getNamespace() + "." + this.id.getPath().replace('/', '.');
    }
}
