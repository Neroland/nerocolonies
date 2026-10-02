package za.co.neroland.nerocolonies.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.compat.RecipeViewerInfo;

/**
 * JEI information pages for the colony blocks. Every NeroColonies recipe is a vanilla recipe type,
 * which JEI shows on its own; what it cannot show is what a block does once placed, so that is all
 * this plugin adds.
 *
 * <p>Uses only the loader-agnostic JEI common API, so it lives in {@code common}: NeoForge and Forge
 * find it through {@link JeiPlugin}, Fabric through the {@code jei_mod_plugin} entrypoint. JEI is
 * compile-time only; without it this class is never loaded.
 */
@JeiPlugin
public final class NeroColoniesJeiPlugin implements IModPlugin {

    private static final Identifier PLUGIN_UID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "jei");

    @Override
    public Identifier getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (RecipeViewerInfo.Page page : RecipeViewerInfo.pages()) {
            registration.addItemStackInfo(new ItemStack(page.item().get()),
                    Component.translatable(page.key()));
        }
    }
}
