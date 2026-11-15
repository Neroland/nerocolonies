package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Toolsmith: finds a Neran whose trade tool is missing, takes the tool from storage or makes it at
 * the forge from the tool's real cost (an iron hoe is two iron ingots and two sticks), and carries it
 * over by hand. A tool that can no longer be delivered goes back to colony storage.
 */
public final class ToolWork {

    private ToolWork() {
    }

    /** What making {@code tool} costs, from {@link TradeRecipes#toolCost}. */
    public static List<TradeRecipes.Part> costOf(Item tool) {
        Identifier id = BuiltInRegistries.ITEM.getKey(tool);
        return TradeRecipes.toolCost(id.getNamespace(), id.getPath());
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        Boolean busy = CarryWork.continueCarry(level, neran, colony);
        if (busy != null) {
            return busy;
        }
        ColonistEntity recipient = CarryWork.lackingTool(level, neran, colony, true);
        if (recipient != null) {
            return CarryWork.fetchTool(level, neran, colony, anchor, recipient, true);
        }
        if (WorkSupport.atPost(neran, anchor, 3)) {
            WorkSupport.strike(neran, anchor);
            return true;
        }
        return WorkSupport.atPost(neran, anchor, profession.workRadius());
    }
}
