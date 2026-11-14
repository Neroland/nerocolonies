package za.co.neroland.nerocolonies.compat.emi;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * An EMI information page for one colony block, drawn the way EMI's own info pages are (the item on top,
 * the wrapped text under it) but with the text wrapped lazily.
 *
 * <p>EMI's {@code EmiInfoRecipe} wraps its text in its constructor through {@code Font.split}. EMI builds
 * recipes on its reload worker thread, and wrapping text can bake font glyphs, which uploads to a GPU
 * texture and throws "Rendersystem called from wrong thread" off the render thread (MC-NEROCOLONIES-1).
 * This page keeps only data at construction time and wraps the text the first time EMI asks for its
 * height or widgets on the client thread.
 */
final class ColonyInfoEmiRecipe implements EmiRecipe {

    /** Same width as EMI's own info pages. */
    private static final int WIDTH = 144;
    private static final int SLOT_SIZE = 18;
    private static final int PADDING = 4;
    private static final int TEXT_COLOR = 0xFF000000;
    /** Line height used only for an estimate when the font cannot be touched (never on the render thread). */
    private static final int FALLBACK_LINE_HEIGHT = 9;

    private final Identifier id;
    private final EmiStack stack;
    private final List<EmiIngredient> inputs;
    private final List<EmiStack> outputs;
    private final Component text;

    /** The wrapped text; filled on the client thread on first use, never during EMI's reload. */
    private @Nullable List<FormattedCharSequence> lines;

    ColonyInfoEmiRecipe(Identifier id, EmiStack stack, Component text) {
        this.id = id;
        this.stack = stack;
        this.inputs = List.of(stack);
        this.outputs = List.of(stack);
        this.text = text;
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return VanillaEmiRecipeCategories.INFO;
    }

    @Override
    public Identifier getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return inputs;
    }

    @Override
    public List<EmiStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean supportsRecipeTree() {
        // An info page is not a way to make anything.
        return false;
    }

    @Override
    public int getDisplayWidth() {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        List<FormattedCharSequence> wrapped = lines();
        if (wrapped == null) {
            // Asked off the client thread: give a one-line estimate rather than touch the font.
            return SLOT_SIZE + PADDING + FALLBACK_LINE_HEIGHT;
        }
        return SLOT_SIZE + PADDING + wrapped.size() * font().lineHeight;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.addSlot(stack, (WIDTH - SLOT_SIZE) / 2, 0).recipeContext(this);
        List<FormattedCharSequence> wrapped = lines();
        if (wrapped == null) {
            return;
        }
        int lineHeight = font().lineHeight;
        int y = SLOT_SIZE + PADDING;
        for (FormattedCharSequence line : wrapped) {
            widgets.addText(line, 0, y, TEXT_COLOR, false);
            y += lineHeight;
        }
    }

    /**
     * The wrapped text, computed once on the client (render) thread; {@code null} when called from any other
     * thread, because wrapping may bake glyphs into a GPU texture.
     */
    private @Nullable List<FormattedCharSequence> lines() {
        List<FormattedCharSequence> wrapped = lines;
        if (wrapped != null) {
            return wrapped;
        }
        if (!Minecraft.getInstance().isSameThread()) {
            return null;
        }
        wrapped = List.copyOf(font().split(text, WIDTH - 4));
        lines = wrapped;
        return wrapped;
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }
}
