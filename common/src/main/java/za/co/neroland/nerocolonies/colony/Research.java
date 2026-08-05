package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ResearchNode;

/**
 * The <b>writing</b> side of colony research: spending a node's cost and adding it to the colony.
 *
 * <p>{@link ResearchEffects} is the reading side and stays the only thing the rest of the mod
 * consults. The split matters: everything downstream of research derives its numbers on demand from
 * the currently loaded {@link ResearchNode}s, so a datapack that re-tunes a node takes effect on
 * {@code /reload} with no migration. What is stored on the colony is a set of node <em>ids</em> and
 * nothing else.
 *
 * <h2>Research is colony-local</h2>
 *
 * <p>An unlock lives on the colony record, not on a player. It is therefore <b>not personal data</b>,
 * it is shared by everyone with access to the colony, and dissolving the colony discards it. There is
 * deliberately no per-player research: a colony is a place, and its technology belongs to the place.
 *
 * <h2>Everything is checked server-side</h2>
 *
 * <p>The client sends "unlock this node" and nothing more. Existence, prerequisites, duplicate
 * unlocks and affordability are all decided here, from the server's own copy of the content and the
 * colony's own storage.
 */
public final class Research {

    /** Why an unlock attempt ended the way it did. Reported to the player as a translated message. */
    public enum Result {
        UNLOCKED,
        ALREADY_UNLOCKED,
        UNKNOWN_NODE,
        PREREQUISITES_MISSING,
        CANNOT_AFFORD,
        NO_POWER
    }

    private Research() {
    }

    // --- queries ------------------------------------------------------------

    /** Whether this colony has already unlocked a node. */
    public static boolean isUnlocked(Colony colony, Identifier nodeId) {
        return colony.researchUnlocked().contains(nodeId.toString());
    }

    /** Whether every prerequisite of a node is unlocked (a node with none is always available). */
    public static boolean prerequisitesMet(Colony colony, ResearchNode node) {
        for (Identifier prerequisite : node.requires()) {
            if (!isUnlocked(colony, prerequisite)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a node is unlockable right now: not already taken, and its prerequisites met. */
    public static boolean isAvailable(Colony colony, ResearchNode node) {
        return !isUnlocked(colony, node.id()) && prerequisitesMet(colony, node);
    }

    /** Whether the colony's storage holds this node's whole cost. */
    public static boolean canAfford(MinecraftServer server, Colony colony, ResearchNode node) {
        return ColonyStorage.hasAmounts(server, colony.colonyId(), node.cost());
    }

    /**
     * Every node this colony could unlock and pay for right now. Computed server-side and shipped to
     * the client as a list of ids, so the research screen can show affordability without ever being
     * sent the colony's inventory.
     */
    public static List<Identifier> affordable(MinecraftServer server, Colony colony) {
        Map<Identifier, ResearchNode> nodes = ColonyDefinitions.researchForServer(server);
        List<Identifier> out = new ArrayList<>();
        for (ResearchNode node : nodes.values()) {
            if (isAvailable(colony, node) && canAfford(server, colony, node)) {
                out.add(node.id());
            }
        }
        return out;
    }

    // --- the write ----------------------------------------------------------

    /**
     * Unlocks a node for a colony: validates, spends the cost from colony storage, and adds the id to
     * the colony record.
     *
     * <p>Order matters. Everything that can refuse the unlock is checked before anything is spent, so
     * a refused unlock costs nothing at all.
     *
     * @param energy how much energy the research station has to offer, or a negative number to skip
     *               the power check entirely (an operator grant)
     * @return what happened; the caller re-reads the colony from {@link ColonyState}
     */
    public static Result unlock(MinecraftServer server, Colony colony, Identifier nodeId, long energy,
            long energyCost) {
        ResearchNode node = ColonyDefinitions.researchForServer(server).get(nodeId);
        if (node == null) {
            return Result.UNKNOWN_NODE;
        }
        if (isUnlocked(colony, nodeId)) {
            return Result.ALREADY_UNLOCKED;
        }
        if (!prerequisitesMet(colony, node)) {
            return Result.PREREQUISITES_MISSING;
        }
        if (energy >= 0 && energy < energyCost) {
            return Result.NO_POWER;
        }
        if (!ColonyStorage.payAmounts(server, colony.colonyId(), node.cost())) {
            return Result.CANNOT_AFFORD;
        }
        grant(server, colony, nodeId);
        return Result.UNLOCKED;
    }

    /**
     * Adds a node to a colony with no cost and no checks beyond "not already unlocked" — the
     * operator-command path, and the second half of {@link #unlock}.
     *
     * @return {@code true} if the colony changed
     */
    public static boolean grant(MinecraftServer server, Colony colony, Identifier nodeId) {
        if (isUnlocked(colony, nodeId)) {
            return false;
        }
        Set<String> unlocked = new LinkedHashSet<>(colony.researchUnlocked());
        unlocked.add(nodeId.toString());
        ColonyState.get(server).put(colony.withResearch(unlocked));
        // A node id and a count — never which colony belongs to whom (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A colony unlocked research {} ({} total).",
                nodeId, unlocked.size());
        return true;
    }
}
