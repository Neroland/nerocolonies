package za.co.neroland.nerocolonies.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;

import org.jetbrains.annotations.Nullable;

/**
 * Gauge hover tooltips for every NeroColonies screen: each bar names itself, gives amount /
 * capacity with units, and its rate (energy in/out, tank fill/drain, progress with time left and the
 * stall cause where the screen shows a status). Builders append lines to a list; a screen registers its
 * bars with a {@link Hover} each frame and emits the one under the mouse. Numbers come from
 * {@link GaugeMath}; every word is an English source lang key. Machine-scoped numbers only, the same
 * values the screen already draws: no player identity, no coordinates.
 */
public final class GaugeTooltips {

    /** Tank change per second treated as steady. */
    public static final double TANK_DEADBAND_PER_SECOND = 0.5D;

    private GaugeTooltips() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("nerocolonies.gui.gauge." + key, args);
    }

    /** The client's game tick (0 with no level), the clock every {@link GaugeMath.RateWindow} samples on. */
    public static long gameTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    // --- energy -----------------------------------------------------------------------------------

    /** Energy: name, stored / capacity in NE (and %), the net change per tick, and "full" at capacity. */
    public static void energy(List<Component> out, long stored, long capacity, double netPerTick) {
        out.add(tr("energy"));
        if (capacity <= 0) {
            out.add(tr("energy.no_buffer"));
            return;
        }
        out.add(tr("energy.amount", GaugeMath.compact(stored), GaugeMath.compact(capacity),
                GaugeMath.percent(stored, capacity)));
        out.add(tr("energy.net", GaugeMath.signedRate(netPerTick)));
        if (stored >= capacity) {
            out.add(tr("energy.full"));
        }
    }

    // --- progress ---------------------------------------------------------------------------------

    /**
     * Progress: {@code title}, percent, time left at the current speed, or the stall cause when one is
     * given. Not working reads as the cause (or idle).
     */
    public static void progress(List<Component> out, Component title, boolean working, int permille,
            double permillePerTick, @Nullable Component stallCause) {
        out.add(title);
        if (!working) {
            out.add(stallCause != null ? stallCause : tr("progress.idle"));
            return;
        }
        out.add(tr("progress.percent", Math.max(0, Math.min(100, Math.round(permille / 10F)))));
        if (stallCause != null) {
            out.add(stallCause);
        } else {
            long ticks = GaugeMath.ticksRemaining(permille, permillePerTick);
            if (ticks >= 0) {
                out.add(tr("progress.left", GaugeMath.duration(ticks)));
            }
        }
    }

    /**
     * A counted gauge (Nerans housed, slots in use, research unlocked): its name, the value the row
     * already shows, and how full it is.
     */
    public static void count(List<Component> out, Component name, Component value, float fraction) {
        out.add(name);
        out.add(value);
        out.add(tr("count.full", Math.max(0, Math.min(100, Math.round(fraction * 100F)))));
    }

    /** The default "Progress" title. */
    public static Component progressTitle() {
        return tr("progress");
    }

    /** A title by gauge key ({@code growth}, {@code splicing}, ...). */
    public static Component title(String key) {
        return tr(key);
    }

    // --- tanks ------------------------------------------------------------------------------------

    /** A tank: its name, "Empty" when dry, amount / capacity in mB, and the fill or drain rate. */
    public static void tank(List<Component> out, Component name, long amount, long capacity, double mbPerTick) {
        out.add(name);
        if (amount <= 0) {
            out.add(tr("tank.empty"));
        }
        out.add(tr("tank.amount", GaugeMath.grouped(Math.max(0L, amount)), GaugeMath.grouped(Math.max(0L, capacity)),
                GaugeMath.percent(amount, capacity)));
        double perSecond = mbPerTick * GaugeMath.TICKS_PER_SECOND;
        int trend = GaugeMath.trend(perSecond, TANK_DEADBAND_PER_SECOND);
        out.add(trend > 0 ? tr("tank.filling", GaugeMath.rate(perSecond))
                : trend < 0 ? tr("tank.draining", GaugeMath.rate(perSecond)) : tr("tank.steady"));
    }

    // --- emission ---------------------------------------------------------------------------------

    /**
     * Wrap every line to {@code width} pixels so a long cause never runs off a small window (GUI scale 4
     * on 1280x720 leaves 320 px). Wrapped lines lose styling; these tooltips use none.
     */
    public static List<Component> wrap(Font font, List<Component> lines, int width) {
        List<Component> wrapped = new ArrayList<>(lines.size());
        int w = Math.max(60, width);
        for (Component line : lines) {
            if (font.width(line) <= w) {
                wrapped.add(line);
                continue;
            }
            for (FormattedText part : font.getSplitter().splitLines(line, w, Style.EMPTY)) {
                wrapped.add(Component.literal(part.getString()));
            }
        }
        return wrapped;
    }

    /** The wrap width for a screen {@code screenWidth} wide: half the window, 100..220 px. */
    public static int wrapWidth(int screenWidth) {
        return Math.max(100, Math.min(220, screenWidth / 2));
    }

    /** Emit a tooltip at the mouse (deferred on 1.21.6+, drawn immediately before that). */
    public static void show(GuiGraphicsExtractor extractor, Font font, List<Component> lines, int mouseX, int mouseY) {
        if (lines.isEmpty()) {
            return;
        }
        //? if >=1.21.6 {
        extractor.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        //?} else {
        /*extractor.renderComponentTooltip(font, lines, mouseX, mouseY);
        *///?}
    }

    /**
     * The gauge areas a screen drew this frame (absolute screen coordinates). The screen clears it at the
     * start of each frame, adds one area per bar as it draws it, and emits once the slots are drawn. The
     * area added last wins, so a screen can refine a generic gauge tip by adding a richer one on top.
     */
    public static final class Hover {

        private record Area(int x0, int y0, int x1, int y1, Consumer<List<Component>> builder) {
        }

        private final List<Area> areas = new ArrayList<>();

        /** Forget last frame's areas. */
        public void clear() {
            this.areas.clear();
        }

        /** A gauge spanning {@code (x0,y0)} to {@code (x1,y1)} whose tooltip {@code builder} appends. */
        public void add(int x0, int y0, int x1, int y1, Consumer<List<Component>> builder) {
            this.areas.add(new Area(x0, y0, x1, y1, builder));
        }

        /** Emit the tooltip of the gauge under the mouse, unless an item tooltip owns that spot. */
        public void emit(GuiGraphicsExtractor extractor, Font font, int mouseX, int mouseY, @Nullable Slot hovered,
                int screenWidth) {
            if (hovered != null && hovered.hasItem()) {
                return;
            }
            for (int i = this.areas.size() - 1; i >= 0; i--) {
                Area area = this.areas.get(i);
                if (mouseX >= area.x0() && mouseX < area.x1() && mouseY >= area.y0() && mouseY < area.y1()) {
                    List<Component> lines = new ArrayList<>();
                    area.builder().accept(lines);
                    show(extractor, font, wrap(font, lines, wrapWidth(screenWidth)), mouseX, mouseY);
                    return;
                }
            }
        }
    }
}
