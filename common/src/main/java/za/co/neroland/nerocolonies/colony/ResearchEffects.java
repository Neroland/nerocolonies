package za.co.neroland.nerocolonies.colony;

import java.util.Optional;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ResearchNode;
import za.co.neroland.nerocolonies.content.effect.ResearchEffect;

/**
 * Resolves a colony's unlocked research nodes into the aggregate numbers the rest of the mod asks
 * for.
 *
 * <p>Everything here is derived on demand from {@code Colony.researchUnlocked()} and the currently
 * loaded {@link ResearchNode}s. Nothing is cached on the colony record, and that is the point: a
 * datapack that re-tunes a node takes effect on {@code /reload} without a migration, and a node that
 * disappears from a pack simply stops contributing rather than leaving a stale bonus baked into a
 * saved world.
 *
 * <p><b>Research is colony-local.</b> It lives on the colony record, not on a player, so nothing
 * here is personal data and nothing here routes through the erasure hook.
 *
 * <p>Spending research points and unlocking nodes is the research station's job (a later stage);
 * this class only ever reads.
 */
public final class ResearchEffects {

    /** Floor on the compounded oxygen multiplier, so no amount of research makes life support free. */
    private static final double MIN_OXYGEN_MULTIPLIER = 0.1D;

    private ResearchEffects() {
    }

    /**
     * The colony's oxygen-burn multiplier: every unlocked {@code oxygen_efficiency} effect
     * compounds, floored at {@value #MIN_OXYGEN_MULTIPLIER}. Returns {@code 1.0} for a colony with
     * no relevant research.
     */
    public static double oxygenEfficiency(Colony colony) {
        double multiplier = 1.0D;
        for (ResearchNode node : unlockedNodes(colony)) {
            for (ResearchEffect effect : node.effects()) {
                if (effect instanceof ResearchEffect.OxygenEfficiency oxygen) {
                    multiplier *= oxygen.multiplier();
                }
            }
        }
        return Math.max(MIN_OXYGEN_MULTIPLIER, multiplier);
    }

    /** The colony's flat morale bonus from research. Zero for a colony with no relevant research. */
    public static double moraleBonus(Colony colony) {
        double total = 0.0D;
        for (ResearchNode node : unlockedNodes(colony)) {
            for (ResearchEffect effect : node.effects()) {
                if (effect instanceof ResearchEffect.MoraleBonus bonus) {
                    total += bonus.amount();
                }
            }
        }
        return total;
    }

    /** Simultaneously worked job slots: the configured base plus every unlocked {@code job_slots}. */
    public static int jobSlots(Colony colony) {
        int slots = NeroColoniesConfig.JOB_SLOTS_PER_COLONY.get();
        for (ResearchNode node : unlockedNodes(colony)) {
            for (ResearchEffect effect : node.effects()) {
                if (effect instanceof ResearchEffect.JobSlots extra) {
                    slots += extra.amount();
                }
            }
        }
        return Math.max(0, slots);
    }

    /**
     * Whether a job may be worked in this colony. A job with no research prerequisite is always
     * available; a job that names one needs either that node unlocked or an explicit
     * {@code job_unlock} effect from a node the colony has.
     */
    public static boolean jobUnlocked(Colony colony, Identifier jobId) {
        Optional<Identifier> prerequisite = ColonyDefinitions.job(jobId)
                .flatMap(job -> job.research());
        if (prerequisite.isEmpty()) {
            return true;
        }
        if (colony.researchUnlocked().contains(prerequisite.get().toString())) {
            return true;
        }
        for (ResearchNode node : unlockedNodes(colony)) {
            for (ResearchEffect effect : node.effects()) {
                if (effect instanceof ResearchEffect.JobUnlock unlock && unlock.job().equals(jobId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether an export entry may be sold from this colony. Same rule as {@link #jobUnlocked}. */
    public static boolean exportUnlocked(Colony colony, Identifier exportId) {
        Optional<Identifier> prerequisite = ColonyDefinitions.exportEntry(exportId)
                .flatMap(entry -> entry.research());
        if (prerequisite.isEmpty()) {
            return true;
        }
        if (colony.researchUnlocked().contains(prerequisite.get().toString())) {
            return true;
        }
        for (ResearchNode node : unlockedNodes(colony)) {
            for (ResearchEffect effect : node.effects()) {
                if (effect instanceof ResearchEffect.ExportUnlock unlock
                        && unlock.export().equals(exportId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The loaded nodes this colony has unlocked, skipping ids no longer present in any pack. */
    private static Iterable<ResearchNode> unlockedNodes(Colony colony) {
        java.util.List<ResearchNode> nodes = new java.util.ArrayList<>();
        if (colony.researchUnlocked().isEmpty()) {
            return nodes;
        }
        for (String raw : colony.researchUnlocked()) {
            Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                continue;
            }
            ColonyDefinitions.researchNode(id).ifPresent(nodes::add);
        }
        return nodes;
    }
}
