package za.co.neroland.nerocolonies.entity.ai.profession;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Cook: at the canteen, turns raw food in colony storage into cooked food, one item (or one loaf
 * from three wheat) at a time, all or nothing; potatoes are kept back while the Farmers' seed reserve
 * is low. With nothing to cook it carries food to the beacon's supply slots when the colony's ration
 * stock is short.
 */
final class KitchenWork {

    private KitchenWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        Boolean busy = CarryWork.continueCarry(level, neran, colony);
        if (busy != null) {
            return busy;
        }
        MinecraftServer server = level.getServer();
        for (TradeRecipes.Conversion dish : TradeRecipes.COOKING) {
            if (!ready(server, colony, dish)) {
                continue;
            }
            if (!WorkSupport.reach(neran, anchor)) {
                return false;
            }
            if (WorkSupport.convert(level, colony, dish, anchor)) {
                WorkSupport.strike(neran, anchor);
                return true;
            }
        }
        if (CarryWork.wantsFood(level, colony)) {
            return CarryWork.fetchFood(level, neran, colony, anchor);
        }
        return WorkSupport.atPost(neran, anchor, profession.workRadius());
    }

    /** Whether storage holds the dish's inputs, leaving the seed reserve alone. */
    private static boolean ready(MinecraftServer server, Colony colony, TradeRecipes.Conversion dish) {
        for (TradeRecipes.Part part : dish.inputs()) {
            int stored = WorkSupport.stored(server, colony, part.item());
            int keep = isSeed(part.item()) ? CarryWork.SEED_RESERVE : 0;
            if (stored - keep < part.count()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSeed(String item) {
        for (TradeRecipes.Part seed : TradeRecipes.SEEDS) {
            if (seed.item().equals(item)) {
                return true;
            }
        }
        return false;
    }
}
