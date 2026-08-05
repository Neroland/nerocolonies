package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Base class for NeroColonies container screens. It paints a procedural sci-fi hull panel (no PNG
 * asset, so there is nothing to keep in sync with the layout) and themes the labels for a dark
 * background; subclasses draw their readouts in {@link #extractForeground}.
 *
 * <p>26.x renders container screens through {@code extract*(GuiGraphicsExtractor, ...)} rather than
 * the old {@code render*} pair — the panel is painted in {@link #extractContents}, custom drawing
 * on top of the vanilla slot pass.
 *
 * <p>These screens render <b>synced state only</b>. Nothing here decides a colony outcome, and
 * nothing here has access to an owner UUID or an access list.
 */
public abstract class NeroColoniesScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    /** Shared palette. */
    protected static final int INK = 0xFF05080D;        // outlines
    protected static final int TROUGH = 0xFF0B1119;     // gauge backing
    protected static final int PANEL = 0xFF141C26;      // hull fill
    protected static final int PANEL_EDGE = 0xFF2A3A4D; // hull bevel
    protected static final int TITLE = 0xFFD6ECFF;      // bright label
    protected static final int SUBTLE = 0xFF8DA0B4;     // dim label
    protected static final int GOOD = 0xFF5BE08A;       // healthy readout
    protected static final int BAD = 0xFFE0645B;        // failing readout

    /** Screen accent colour (ARGB). */
    protected final int accent;

    protected NeroColoniesScreen(T menu, Inventory playerInventory, Component title, int accent,
            int width, int height) {
        super(menu, playerInventory, title, width, height);
        this.accent = accent;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        drawPanel(extractor);
        super.extractContents(extractor, mouseX, mouseY, partialTick);
        extractForeground(extractor);
    }

    /** The backing hull, painted rather than blitted. */
    protected void drawPanel(GuiGraphicsExtractor g) {
        int x = this.leftPos;
        int y = this.topPos;
        g.fill(x - 1, y - 1, x + this.imageWidth + 1, y + this.imageHeight + 1, INK);
        g.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);
        g.fill(x, y, x + this.imageWidth, y + 1, PANEL_EDGE);
        g.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, PANEL_EDGE);
        g.fill(x, y, x + 1, y + this.imageHeight, PANEL_EDGE);
        g.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, PANEL_EDGE);
    }

    /** Subclass hook: draw readouts on top of the panel (absolute coords via leftPos/topPos). */
    protected void extractForeground(GuiGraphicsExtractor extractor) {
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        extractor.text(this.font, this.title, this.titleLabelX, this.titleLabelY, TITLE, false);
        extractor.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                SUBTLE, false);
    }

    // --- drawing helpers (panel-relative dx/dy) -----------------------------

    /** A horizontal gauge: dark trough with an accent fill {@code frac} (0..1) of its width. */
    protected void hGauge(GuiGraphicsExtractor g, int dx, int dy, int w, int h, float frac, int fill) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, INK);
        g.fill(x, y, x + w, y + h, TROUGH);
        int fw = Math.max(0, Math.min(w, Math.round(w * frac)));
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, fill);
            g.fill(x, y, x + fw, y + 1, 0x55FFFFFF); // top sheen
        }
    }

    /** A segmented gauge — energy buffers read at a glance, exact values stay on the labels. */
    protected void segGauge(GuiGraphicsExtractor g, int dx, int dy, int w, int h, float frac, int fill) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, INK);
        g.fill(x, y, x + w, y + h, TROUGH);
        int segments = Math.max(4, w / 10);
        int segW = Math.max(1, w / segments);
        int fw = Math.max(0, Math.min(w, Math.round(w * frac)));
        for (int s = 0; s < segments; s++) {
            int sx = x + s * segW;
            int sw = (s == segments - 1) ? (x + w - sx) : segW - 1; // 1px tick gap between cells
            int lit = Math.max(0, Math.min(sw, fw - s * segW));
            if (lit > 0) {
                g.fill(sx, y, sx + lit, y + h, fill);
                g.fill(sx, y, sx + lit, y + 1, 0x55FFFFFF);
            }
        }
    }

    /** Left-aligned label text at a panel-relative position. */
    protected void label(GuiGraphicsExtractor g, Component text, int dx, int dy, int color) {
        g.text(this.font, text, this.leftPos + dx, this.topPos + dy, color, false);
    }

    /** Centred label text within {@code [dx, dx+width)}. */
    protected void labelCentered(GuiGraphicsExtractor g, Component text, int dx, int width, int dy,
            int color) {
        g.centeredText(this.font, text, this.leftPos + dx + width / 2, this.topPos + dy, color);
    }
}
