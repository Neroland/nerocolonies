package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import za.co.neroland.nerocolonies.client.ClientColonySnapshot;
import za.co.neroland.nerocolonies.menu.JobStationMenu;
import za.co.neroland.nerocolonies.network.ColonyIntentPayload;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * A job station's screen: a progress bar, a power gauge, and — the part that earns its keep — a
 * single line saying why the station is not working when it is not working.
 *
 * <p>A station can be idle for five quite different reasons (no colony, no job content, no job slot
 * left, nobody to staff it, or nothing to work with), and they look identical from the outside. Each
 * one gets its own line, because "it just sits there" is the single most likely thing a player will
 * file a bug about.
 */
public class JobStationScreen extends NeroColoniesScreen<JobStationMenu> {

    private static final int ACCENT = 0xFF8FD96F; // work green
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    private static final int ROUTE_BUTTON_X = 96;
    private static final int ROUTE_BUTTON_Y = 24;
    private static final int ROUTE_BUTTON_WIDTH = 52;
    private static final int ROUTE_BUTTON_HEIGHT = 12;

    public JobStationScreen(JobStationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = HEIGHT - 94;
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        label(g, Component.translatable("gui.nerocolonies.station.progress"), 8, 18, SUBTLE);
        hGauge(g, 8, 27, 84, 6,
                this.menu.progressPermille() / (float) this.menu.progressScale(), ACCENT);

        label(g, Component.translatable("gui.nerocolonies.stat.power"), 8, 38, SUBTLE);
        segGauge(g, 8, 47, 136, 5, this.menu.energyPermille() / (float) this.menu.energyScale(), ACCENT);

        label(g, Component.translatable("gui.nerocolonies.station.workers",
                this.menu.assigned(), this.menu.required()), 8, 55,
                this.menu.required() > 0 && this.menu.assigned() < this.menu.required() ? BAD : SUBTLE);

        label(g, statusLine(), 8, 64, statusColour());

        // The output-routing switch: a colony's own decision, not a datapack's.
        button(g, ROUTE_BUTTON_X, ROUTE_BUTTON_Y, ROUTE_BUTTON_WIDTH, ROUTE_BUTTON_HEIGHT,
                Component.translatable(this.menu.exportOutput()
                        ? "gui.nerocolonies.station.route_exports"
                        : "gui.nerocolonies.station.route_storage"),
                this.menu.bound());
    }

    /** A flat panel button, drawn by hand so there is no widget state to keep in step with the model. */
    private void button(GuiGraphicsExtractor g, int dx, int dy, int width, int height, Component text,
            boolean enabled) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, INK);
        g.fill(x, y, x + width, y + height, enabled ? TROUGH : PANEL);
        g.fill(x, y, x + width, y + 1, enabled ? ACCENT : PANEL_EDGE);
        labelCentered(g, text, dx, width, dy + (height - 8) / 2, enabled ? TITLE : SUBTLE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double localX = event.x() - this.leftPos;
        double localY = event.y() - this.topPos;
        if (this.menu.bound() && ClientColonySnapshot.present()
                && localX >= ROUTE_BUTTON_X && localX < ROUTE_BUTTON_X + ROUTE_BUTTON_WIDTH
                && localY >= ROUTE_BUTTON_Y && localY < ROUTE_BUTTON_Y + ROUTE_BUTTON_HEIGHT) {
            Services.NETWORK.sendToServer(
                    ColonyIntentPayload.toggleExport(ClientColonySnapshot.get().anchor()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private Component statusLine() {
        if (!this.menu.bound()) {
            return Component.translatable("gui.nerocolonies.station.unclaimed");
        }
        if (!this.menu.hasJob()) {
            return Component.translatable("gui.nerocolonies.station.no_job");
        }
        if (!this.menu.active()) {
            return Component.translatable("gui.nerocolonies.station.no_slot");
        }
        if (this.menu.blocked()) {
            return Component.translatable("gui.nerocolonies.station.blocked");
        }
        if (this.menu.energyPermille() <= 0) {
            return Component.translatable("gui.nerocolonies.station.unpowered");
        }
        return Component.translatable(this.menu.outpost()
                ? "gui.nerocolonies.station.working_outpost"
                : "gui.nerocolonies.station.working");
    }

    private int statusColour() {
        if (!this.menu.bound() || !this.menu.hasJob() || this.menu.blocked()) {
            return BAD;
        }
        return this.menu.active() ? GOOD : SUBTLE;
    }
}
