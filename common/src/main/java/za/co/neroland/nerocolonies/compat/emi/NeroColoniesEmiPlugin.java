package za.co.neroland.nerocolonies.compat.emi;

import java.util.List;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.stack.EmiStack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.compat.RecipeViewerInfo;

/**
 * EMI information pages for the colony blocks — the same pages as the JEI plugin, from the same
 * list. EMI shows vanilla recipes on its own, so information is all this adds.
 *
 * <p>Only the {@code dev.emi.emi.api} package is used, so the class lives in {@code common}:
 * NeoForge finds it through {@link EmiEntrypoint}, Fabric through the {@code emi} entrypoint. EMI is
 * compile-time only; without it this class is never loaded.
 */
@EmiEntrypoint
public final class NeroColoniesEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        for (RecipeViewerInfo.Page page : RecipeViewerInfo.pages()) {
            Item item = page.item().get();
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            Identifier id = Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID,
                    "/info/" + itemId.getPath());
            registry.addRecipe(new EmiInfoRecipe(List.of(EmiStack.of(item)),
                    List.of(Component.translatable(page.key())), id));
        }
    }
}
