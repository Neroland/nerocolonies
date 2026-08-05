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
 * A job station's screen: a progress bar, a power gauge, an output-routing switch, and — the part
 * that earns its keep — a wrapped line saying why the station is not working when it is not working.
 *
 * <p>A station can be idle for five quite different reasons (no colony, no job content, no job slot
 * left, nobody to staff it, or nothing to work with), and they look identical from the outside. Each
 * one gets its own line, because "it just sits there" is the single most likely thing a player will
 * file a bug about. Those lines are long, so they are wrapped against the hull width rather than
 * drawn as one line that would overflow it.
 */
public class JobStationScreen extends NeroColoniesScreen<JobStationMenu> {

    private static final int ACCENT = 0xFF8FD96F; // work green
    private static final int WIDTH = 176;
    private static final int HEIGHT = 194;

    private static final int CONTENT_X = 8;
    private static final int CONTENT_WIDTH = WIDTH - 2 * CONTENT_X;
    private static final int GAUGE_WIDTH = 136;

    private static final int WORKERS_Y = 64;
    private static final int STATUS_Y = 78;

    private static final int ROUTE_BUTTON_X = 100;
    private static final int ROUTE_BUTTON_Y = 61;
    private static final int ROUTE_BUTTON_WIDTH = 68;
    private static final int ROUTE_BUTTON_HEIGHT = 14;

    public JobStationScreen(JobStationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = JobStationMenu.INVENTORY_X;
        this.inventoryLabelY = 102;
    }

    @Override
    protected void paintTrays(GuiGraphicsExtractor g) {
        slotTray(g, JobStationMenu.MODULE_X, JobStationMenu.MODULE_Y, 1, 2);
        playerInventoryTray(g, JobStationMenu.INVENTORY_X, JobStationMenu.INVENTORY_Y,
                JobStationMenu.HOTBAR_Y);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        labelRight(g, Component.translatable("gui.nerocolonies.slots.modules"), CONTENT_X,
                CONTENT_WIDTH, this.titleLabelY, SUBTLE);

        gaugeRow(g, CONTENT_X, 22, GAUGE_WIDTH,
                Component.translatable("gui.nerocolonies.station.progress"),
                percent(this.menu.progressPermille(), this.menu.progressScale()),
                frac(this.menu.progressPermille(), this.menu.progressScale()), ACCENT, false);

        gaugeRow(g, CONTENT_X, 43, GAUGE_WIDTH, Component.translatable("gui.nerocolonies.stat.power"),
                percent(this.menu.energyPermille(), this.menu.energyScale()),
                frac(this.menu.energyPermille(), this.menu.energyScale()), ACCENT, true);

        clampedLabel(g, Component.translatable("gui.nerocolonies.station.workers",
                this.menu.assigned(), this.menu.required()), CONTENT_X, WORKERS_Y,
                ROUTE_BUTTON_X - CONTENT_X - 6,
                this.menu.required() > 0 && this.menu.assigned() < this.menu.required() ? BAD : SUBTLE);

        // The output-routing switch: a colony's own decision, not a datapack's.
        button(g, ROUTE_BUTTON_X, ROUTE_BUTTON_Y, ROUTE_BUTTON_WIDTH, ROUTE_BUTTON_HEIGHT,
                Component.translatable(this.menu.exportOutput()
                        ? "gui.nerocolonies.station.route_exports"
                        : "gui.nerocolonies.station.route_storage"),
                this.menu.bound());

        wrappedLabel(g, statusLine(), CONTENT_X, STATUS_Y, CONTENT_WIDTH, 2, statusColour());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double localX = event.x() - this.leftPos;
        double localY = event.y() - this.topPos;
        if (this.menu.bound() && ClientColonySnapshot.present()
                && within(localX, localY, ROUTE_BUTTON_X, ROUTE_BUTTON_Y, ROUTE_BUTTON_WIDTH,
                        ROUTE_BUTTON_HEIGHT)) {
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
