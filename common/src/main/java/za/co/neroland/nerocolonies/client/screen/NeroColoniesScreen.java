package za.co.neroland.nerocolonies.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Base class for NeroColonies container screens. It paints a procedural sci-fi hull panel (no PNG
 * asset, so there is nothing to keep in sync with the layout) and themes the labels for a dark
 * background; subclasses draw their readouts in {@link #extractForeground}.
 *
 * <p>26.x renders container screens through {@code extract*(GuiGraphicsExtractor, ...)} rather than
 * the old {@code render*} pair — the panel is painted in {@link #extractContents}, custom drawing
 * on top of the vanilla slot pass.
 *
 * <h2>Three rules this class exists to enforce</h2>
 *
 * <ol>
 *   <li><b>Every slot gets a well.</b> {@link #paintSlotWells} walks {@code menu.slots} and paints a
 *       recessed 18x18 frame under each one, the player inventory included. A procedural screen that
 *       paints its background but not its slots leaves the player looking at floating items on a flat
 *       rectangle, which is exactly what the first pass at these screens did.</li>
 *   <li><b>No text ever leaves the panel.</b> {@link #wrappedLabel} and {@link #clampedLabel} measure
 *       against the font and fold or ellipsise; nothing here draws a raw string at a fixed width and
 *       hopes.</li>
 *   <li><b>A gauge always has a track.</b> The trough and its quarter ticks are painted before the
 *       fill, so a zero-valued gauge reads as "empty" rather than as "missing".</li>
 * </ol>
 *
 * <p>These screens render <b>synced state only</b>. Nothing here decides a colony outcome, and
 * nothing here has access to an owner UUID or an access list.
 */
public abstract class NeroColoniesScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    /** Shared palette. */
    protected static final int INK = 0xFF05080D;         // outlines
    protected static final int TROUGH = 0xFF0B1119;      // gauge backing
    protected static final int PANEL = 0xFF141C26;       // hull fill
    protected static final int PANEL_HEADER = 0xFF1C2735; // title band
    protected static final int PANEL_EDGE = 0xFF2A3A4D;  // hull bevel
    protected static final int DIVIDER = 0xFF243141;     // section rule
    protected static final int SLOT_EDGE = 0xFF070B12;   // slot well outline
    protected static final int SLOT_FILL = 0xFF232F3F;   // slot well interior
    protected static final int SLOT_BEVEL = 0xFF35485F;  // slot well top/left highlight
    protected static final int TITLE = 0xFFD6ECFF;       // bright label
    protected static final int SUBTLE = 0xFF8DA0B4;      // dim label
    protected static final int MUTED = 0xFF5B6472;       // disabled label
    protected static final int GOOD = 0xFF5BE08A;        // healthy readout
    protected static final int BAD = 0xFFE0645B;         // failing readout
    protected static final int WARN = 0xFFE0B45B;        // caution readout

    /** One line of body text, gauge heights, and the standard slot pitch. */
    protected static final int LINE = 10;
    protected static final int BAR_H = 6;
    protected static final int SEG_H = 5;
    protected static final int SLOT = 18;

    /** Screen accent colour (ARGB). */
    protected final int accent;

    /** Last mouse position seen by {@link #extractContents}, panel-relative — used for hover states. */
    protected int hoverX = -1;
    protected int hoverY = -1;

    protected NeroColoniesScreen(T menu, Inventory playerInventory, Component title, int accent,
            int width, int height) {
        super(menu, playerInventory, title, width, height);
        this.accent = accent;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.hoverX = mouseX - this.leftPos;
        this.hoverY = mouseY - this.topPos;
        drawPanel(extractor);
        super.extractContents(extractor, mouseX, mouseY, partialTick);
        extractForeground(extractor);
    }

    // --- panel --------------------------------------------------------------

    /** Height of the title band at the top of the hull. */
    protected int headerHeight() {
        return 16;
    }

    /** Whether to rule off the player inventory from the machine's own controls. */
    protected boolean drawInventoryDivider() {
        return true;
    }

    /** The backing hull, painted rather than blitted. */
    protected void drawPanel(GuiGraphicsExtractor g) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;

        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, INK);
        g.fill(x, y, x + w, y + h, PANEL);

        // Title band plus the accent rule that separates it from the working area.
        int header = headerHeight();
        if (header > 0) {
            g.fill(x, y, x + w, y + header, PANEL_HEADER);
            g.fill(x, y + header, x + w, y + header + 1, this.accent);
        }

        // Hull bevel.
        g.fill(x, y, x + w, y + 1, PANEL_EDGE);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
        g.fill(x, y, x + 1, y + h, PANEL_EDGE);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_EDGE);

        if (drawInventoryDivider() && this.inventoryLabelY > 0) {
            divider(g, 8, this.inventoryLabelY - 5, w - 16);
        }
        paintTrays(g);
        paintSlotWells(g);
    }

    /**
     * Subclass hook: recessed trays behind groups of slots. Called from {@link #drawPanel}, so it
     * paints <em>under</em> the wells and under the items — a tray drawn from the foreground pass
     * would cover the very stacks it is meant to frame.
     */
    protected void paintTrays(GuiGraphicsExtractor g) {
    }

    /**
     * Paints a recessed well under every slot the menu declares, so the slot grid is visible whether
     * or not anything is in it. Driven off {@code menu.slots} rather than off per-screen constants,
     * because a well that is derived from the same coordinates the slot uses cannot drift from it.
     */
    protected void paintSlotWells(GuiGraphicsExtractor g) {
        for (Slot slot : this.menu.slots) {
            slotWell(g, slot.x, slot.y);
        }
    }

    /** One 18x18 slot frame, given the slot's own (panel-relative) top-left. */
    protected void slotWell(GuiGraphicsExtractor g, int dx, int dy) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x - 1, y - 1, x + 17, y + 17, SLOT_EDGE);
        g.fill(x, y, x + 16, y + 16, SLOT_FILL);
        g.fill(x, y, x + 16, y + 1, SLOT_BEVEL);
        g.fill(x, y, x + 1, y + 16, SLOT_BEVEL);
    }

    /**
     * A recessed tray behind a block of slots — the visual that groups "these three sockets are one
     * thing" without needing a texture. Pass the top-left of the first slot.
     */
    protected void slotTray(GuiGraphicsExtractor g, int dx, int dy, int columns, int rows) {
        int x = this.leftPos + dx - 3;
        int y = this.topPos + dy - 3;
        int w = columns * SLOT + 4;
        int h = rows * SLOT + 4;
        g.fill(x, y, x + w, y + h, INK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, TROUGH);
    }

    /** The two trays behind the player's own inventory and hotbar. */
    protected void playerInventoryTray(GuiGraphicsExtractor g, int dx, int inventoryY, int hotbarY) {
        slotTray(g, dx, inventoryY, 9, 3);
        slotTray(g, dx, hotbarY, 9, 1);
    }

    /** A 1px section rule. */
    protected void divider(GuiGraphicsExtractor g, int dx, int dy, int width) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x, y, x + width, y + 1, DIVIDER);
    }

    /** Subclass hook: draw readouts on top of the panel (absolute coords via leftPos/topPos). */
    protected void extractForeground(GuiGraphicsExtractor extractor) {
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        extractor.text(this.font,
                Component.literal(clamp(this.title.getString(), this.imageWidth - this.titleLabelX - 8)),
                this.titleLabelX, this.titleLabelY, TITLE, false);
        extractor.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                SUBTLE, false);
    }

    // --- gauges (panel-relative dx/dy) --------------------------------------

    /**
     * The trough every gauge sits in: outline, backing and quarter ticks. Painted unconditionally, so
     * a gauge at zero still reads as an empty bar rather than as a hole in the layout.
     */
    private void track(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, INK);
        g.fill(x, y, x + w, y + h, TROUGH);
        for (int tick = 1; tick < 4; tick++) {
            int tx = x + w * tick / 4;
            g.fill(tx, y, tx + 1, y + h, 0x26FFFFFF);
        }
    }

    /** A horizontal gauge: dark trough with an accent fill {@code frac} (0..1) of its width. */
    protected void hGauge(GuiGraphicsExtractor g, int dx, int dy, int w, int h, float frac, int fill) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        track(g, x, y, w, h);
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
        track(g, x, y, w, h);
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

    /**
     * A caption line with an optional right-aligned value, and a gauge under it. Returns the y of the
     * next free line, so a tab's layout is a sequence of calls rather than a column of magic numbers.
     */
    protected int gaugeRow(GuiGraphicsExtractor g, int dx, int dy, int w, Component caption,
            Component value, float frac, int fill, boolean segmented) {
        clampedLabel(g, caption, dx, dy, value == null ? w : w - 40, SUBTLE);
        if (value != null) {
            labelRight(g, value, dx, w, dy, TITLE);
        }
        int gy = dy + LINE;
        int h = segmented ? SEG_H : BAR_H;
        if (segmented) {
            segGauge(g, dx, gy, w, h, frac, fill);
        } else {
            hGauge(g, dx, gy, w, h, frac, fill);
        }
        return gy + h + 4;
    }

    /** A safe 0..1 fraction from a value/scale pair, with no division by zero and no overshoot. */
    protected static float frac(int value, int scale) {
        if (scale <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value / (float) scale));
    }

    /** A percentage readout for a gauge's right-hand value. */
    protected static Component percent(int value, int scale) {
        int pct = scale <= 0 ? 0 : Math.max(0, Math.min(100, Math.round(value * 100.0F / scale)));
        return Component.translatable("gui.nerocolonies.value.percent", pct);
    }

    // --- text ---------------------------------------------------------------

    /** Left-aligned label text at a panel-relative position. */
    protected void label(GuiGraphicsExtractor g, Component text, int dx, int dy, int color) {
        g.text(this.font, text, this.leftPos + dx, this.topPos + dy, color, false);
    }

    /** Centred label text within {@code [dx, dx+width)}. */
    protected void labelCentered(GuiGraphicsExtractor g, Component text, int dx, int width, int dy,
            int color) {
        g.centeredText(this.font, text, this.leftPos + dx + width / 2, this.topPos + dy, color);
    }

    /** Right-aligned label text within {@code [dx, dx+width)}. */
    protected void labelRight(GuiGraphicsExtractor g, Component text, int dx, int width, int dy,
            int color) {
        String shown = clamp(text.getString(), width);
        g.text(this.font, Component.literal(shown), this.leftPos + dx + width - this.font.width(shown),
                this.topPos + dy, color, false);
    }

    /** A single line that is ellipsised rather than allowed to run past {@code maxWidth}. */
    protected void clampedLabel(GuiGraphicsExtractor g, Component text, int dx, int dy, int maxWidth,
            int color) {
        g.text(this.font, Component.literal(clamp(text.getString(), maxWidth)), this.leftPos + dx,
                this.topPos + dy, color, false);
    }

    /** Centred, ellipsised text within {@code [dx, dx+width)}. */
    protected void clampedCentered(GuiGraphicsExtractor g, Component text, int dx, int width, int dy,
            int color) {
        String shown = clamp(text.getString(), width);
        g.text(this.font, Component.literal(shown), this.leftPos + dx + (width - this.font.width(shown)) / 2,
                this.topPos + dy, color, false);
    }

    /**
     * Draws {@code text} folded to {@code maxWidth}, at most {@code maxLines} lines, ellipsising the
     * last one if there is more. Returns the y of the next free line.
     *
     * <p>Every hint line on these screens goes through here. The first pass drew hints as plain
     * single-line labels, and the long ones ran straight off the right edge of the hull.
     */
    protected int wrappedLabel(GuiGraphicsExtractor g, Component text, int dx, int dy, int maxWidth,
            int maxLines, int color) {
        int y = dy;
        for (String line : wrap(text.getString(), maxWidth, maxLines)) {
            g.text(this.font, Component.literal(line), this.leftPos + dx, this.topPos + y, color, false);
            y += LINE;
        }
        return y;
    }

    /** How tall {@link #wrappedLabel} will be for the same arguments. */
    protected int wrappedHeight(Component text, int maxWidth, int maxLines) {
        return wrap(text.getString(), maxWidth, maxLines).size() * LINE;
    }

    /**
     * Word-wraps to a pixel width using the font itself, folding on spaces and hard-breaking a word
     * that cannot fit on a line of its own. Deliberately measured rather than estimated: a character
     * count is wrong in every language whose glyphs are not 6px wide.
     */
    protected List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        String remaining = text == null ? "" : text.trim();
        int limit = Math.max(1, maxLines);
        while (!remaining.isEmpty() && out.size() < limit) {
            if (this.font.width(remaining) <= maxWidth) {
                out.add(remaining);
                return out;
            }
            int cut = fitLength(remaining, maxWidth);
            int space = remaining.lastIndexOf(' ', cut);
            int end = space > 0 ? space : cut;
            out.add(remaining.substring(0, end).trim());
            remaining = remaining.substring(end).trim();
        }
        if (!remaining.isEmpty() && !out.isEmpty()) {
            int last = out.size() - 1;
            out.set(last, clamp(out.get(last) + " " + remaining, maxWidth));
        }
        return out;
    }

    /** Ellipsises {@code text} to fit {@code maxWidth}. Returns it unchanged when it already fits. */
    protected String clamp(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (maxWidth <= 0) {
            return "";
        }
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        int budget = maxWidth - this.font.width(ellipsis);
        if (budget <= 0) {
            return "";
        }
        return value.substring(0, fitLength(value, budget)).trim() + ellipsis;
    }

    /** How many leading characters of {@code text} fit in {@code maxWidth} (at least one). */
    private int fitLength(String text, int maxWidth) {
        int fits = 0;
        while (fits < text.length() && this.font.width(text.substring(0, fits + 1)) <= maxWidth) {
            fits++;
        }
        return Math.max(1, fits);
    }

    // --- flat controls ------------------------------------------------------

    /**
     * A flat panel button, drawn by hand so there is no widget state to keep in step with the model —
     * these screens re-derive everything from synced state every frame, and a stateful widget would be
     * one more thing that can disagree with the server.
     */
    protected void button(GuiGraphicsExtractor g, int dx, int dy, int width, int height, Component text,
            boolean enabled) {
        boolean hovered = enabled && within(this.hoverX, this.hoverY, dx, dy, width, height);
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, INK);
        g.fill(x, y, x + width, y + height, enabled ? (hovered ? PANEL_EDGE : TROUGH) : PANEL);
        g.fill(x, y, x + width, y + 1, enabled ? this.accent : PANEL_EDGE);
        clampedCentered(g, text, dx + 2, width - 4, dy + (height - 8) / 2,
                enabled ? TITLE : MUTED);
    }

    /** Hit test in panel-relative coordinates. */
    protected static boolean within(double x, double y, int dx, int dy, int width, int height) {
        return x >= dx && x < dx + width && y >= dy && y < dy + height;
    }
}
