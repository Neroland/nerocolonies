package za.co.neroland.nerocolonies.compat.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
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
 *
 * <p>EMI calls {@link #register} on its reload worker thread, so it must stay pure data: nothing here
 * may touch the font, textures or {@code RenderSystem}. That is why the pages are
 * {@link ColonyInfoEmiRecipe}s, which wrap their text lazily on the client thread, and not EMI's
 * {@code EmiInfoRecipe}, which wraps (and may bake font glyphs) in its constructor.
 */
@EmiEntrypoint
public final class NeroColoniesEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        for (RecipeViewerInfo.Page page : RecipeViewerInfo.pages()) {
            Item item = page.item().get();
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            // A leading '/' marks a synthetic recipe id (no datapack recipe behind it), as EMI asks.
            Identifier id = Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID,
                    "/info/" + itemId.getPath());
            registry.addRecipe(new ColonyInfoEmiRecipe(id, EmiStack.of(item),
                    Component.translatable(page.key())));
        }
    }
}
