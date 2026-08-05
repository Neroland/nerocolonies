package za.co.neroland.nerocolonies.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.client.ClientColonySnapshot;
import za.co.neroland.nerocolonies.menu.ColonyBeaconMenu;
import za.co.neroland.nerocolonies.network.ColonyIntentPayload;
import za.co.neroland.nerocolonies.network.ColonySnapshotPayload;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * The colony beacon's screen: five status tabs over the shared hull panel, plus the food supply row.
 *
 * <h2>Two sources, and the split is deliberate</h2>
 *
 * <p>The live numbers — morale, power, population, food — come from the menu's <b>data slots</b>,
 * which resync every tick while the screen is open. The things that do not fit through a 16-bit data
 * slot — the export buffer's credit value, the access-list size, whether a currency provider exists,
 * the anchor block an intent must name — come from the per-player {@link ColonySnapshotPayload} sent
 * when the beacon is opened and after every action. Using the right channel for each is what keeps
 * the per-tick cost at fifteen shorts.
 *
 * <h2>The access editor, and what it does not show</h2>
 *
 * <p>The owner types a name and presses Add or Remove. The client is <b>never sent the access
 * list</b> — only its size — because a client told who is on a colony's list has been told where
 * those people play. The server resolves the name, applies the change and answers with a count.
 * Names are resolved against online players only; operators have the command path for the rest.
 */
public class ColonyBeaconScreen extends NeroColoniesScreen<ColonyBeaconMenu> {

    private static final int ACCENT = 0xFF4FB3D9; // colony cyan
    private static final int WIDTH = 176;
    private static final int HEIGHT = 200;

    private static final int TAB_COUNT = 5;
    private static final int TAB_WIDTH = 33;
    private static final int TAB_HEIGHT = 12;
    private static final int TAB_Y = 16;

    private static final int TAB_COLONY = 0;
    private static final int TAB_PEOPLE = 1;
    private static final int TAB_JOBS = 2;
    private static final int TAB_RESEARCH = 3;
    private static final int TAB_TRADE = 4;

    /** Life-support state ordinals, matching {@code LifeSupport.State}. */
    private static final int LIFE_OK = 0;
    private static final int LIFE_DEGRADED = 1;

    private static final int ACCESS_FIELD_X = 8;
    private static final int ACCESS_FIELD_Y = 44;
    private static final int ACCESS_FIELD_WIDTH = 96;
    private static final int ACCESS_FIELD_HEIGHT = 12;

    private static final int ACCESS_BUTTON_Y = 44;
    private static final int ACCESS_BUTTON_WIDTH = 30;
    private static final int ACCESS_BUTTON_HEIGHT = 12;
    private static final int ACCESS_ADD_X = 108;
    private static final int ACCESS_REMOVE_X = 108;
    private static final int ACCESS_REMOVE_Y = 58;

    private static final int SELL_BUTTON_X = 8;
    private static final int SELL_BUTTON_Y = 56;
    private static final int SELL_BUTTON_WIDTH = 60;
    private static final int SELL_BUTTON_HEIGHT = 14;

    private static final String[] TAB_KEYS = {
        "gui.nerocolonies.tab.overview",
        "gui.nerocolonies.tab.colonists",
        "gui.nerocolonies.tab.jobs",
        "gui.nerocolonies.tab.research",
        "gui.nerocolonies.tab.exports",
    };

    private int tab = TAB_COLONY;

    @Nullable
    private EditBox accessField;

    public ColonyBeaconScreen(ColonyBeaconMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = HEIGHT - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.accessField = new EditBox(this.font, this.leftPos + ACCESS_FIELD_X,
                this.topPos + ACCESS_FIELD_Y, ACCESS_FIELD_WIDTH, ACCESS_FIELD_HEIGHT,
                Component.translatable("gui.nerocolonies.access.field"));
        this.accessField.setMaxLength(32);
        this.accessField.setBordered(true);
        this.accessField.setHint(Component.translatable("gui.nerocolonies.access.hint"));
        this.addRenderableWidget(this.accessField);
        updateAccessField();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateAccessField();
    }

    /** The editor exists only on the People tab, and only for the colony's owner. */
    private void updateAccessField() {
        if (this.accessField == null) {
            return;
        }
        boolean show = this.tab == TAB_PEOPLE && this.menu.hasColony()
                && ClientColonySnapshot.get().isOwner();
        this.accessField.visible = show;
        this.accessField.active = show;
    }

    // --- drawing ------------------------------------------------------------

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        drawTabs(g);
        // The supply row is always visible, on every tab: it is the one thing a player interacts
        // with here, and hiding it behind a tab would make feeding a colony a scavenger hunt.
        label(g, Component.translatable("gui.nerocolonies.beacon.supply"), 8, 71, SUBTLE);

        if (!this.menu.hasColony()) {
            label(g, Component.translatable("gui.nerocolonies.beacon.unbound"), 8, 34, SUBTLE);
            return;
        }
        switch (this.tab) {
            case TAB_PEOPLE -> drawColonists(g);
            case TAB_JOBS -> drawJobs(g);
            case TAB_RESEARCH -> drawResearch(g);
            case TAB_TRADE -> drawExports(g);
            default -> drawOverview(g);
        }
    }

    private void drawTabs(GuiGraphicsExtractor g) {
        for (int i = 0; i < TAB_COUNT; i++) {
            int dx = 7 + i * (TAB_WIDTH + 1);
            boolean active = i == this.tab;
            int x = this.leftPos + dx;
            int y = this.topPos + TAB_Y;
            g.fill(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, active ? ACCENT : TROUGH);
            g.fill(x, y, x + TAB_WIDTH, y + 1, active ? 0x55FFFFFF : INK);
            labelCentered(g, Component.translatable(TAB_KEYS[i]), dx, TAB_WIDTH, TAB_Y + 2,
                    active ? INK : SUBTLE);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g) {
        int morale = this.menu.morale();
        label(g, Component.translatable("gui.nerocolonies.stat.morale", morale), 8, 32, TITLE);
        hGauge(g, 8, 42, 136, 6, morale / 100.0F, this.menu.workStopped() ? BAD : ACCENT);

        label(g, Component.translatable("gui.nerocolonies.stat.power"), 8, 51, SUBTLE);
        segGauge(g, 8, 60, 136, 5,
                this.menu.energyPermille() / (float) this.menu.energyScale(), ACCENT);

        // Three life-support states, three readings. A colony coasting on reserves must not look the
        // same as one that is fine, or the grace window is invisible until it has already expired.
        int state = this.menu.lifeSupportState();
        String key = switch (state) {
            case LIFE_OK -> "gui.nerocolonies.stat.life_support_ok";
            case LIFE_DEGRADED -> "gui.nerocolonies.stat.life_support_degraded";
            default -> "gui.nerocolonies.stat.life_support_failed";
        };
        label(g, Component.translatable(key), 92, 32, state == LIFE_OK ? GOOD : BAD);
    }

    private void drawColonists(GuiGraphicsExtractor g) {
        int population = this.menu.population();
        int capacity = this.menu.housingCapacity();
        label(g, Component.translatable("gui.nerocolonies.stat.population", population, capacity),
                8, 32, TITLE);

        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present() && snapshot.isOwner()) {
            // Owner: the access editor takes the space the gauges would have used.
            button(g, ACCESS_ADD_X, ACCESS_BUTTON_Y, ACCESS_BUTTON_WIDTH, ACCESS_BUTTON_HEIGHT,
                    Component.translatable("gui.nerocolonies.access.add"), true);
            button(g, ACCESS_REMOVE_X, ACCESS_REMOVE_Y, ACCESS_BUTTON_WIDTH, ACCESS_BUTTON_HEIGHT,
                    Component.translatable("gui.nerocolonies.access.remove"), true);
            label(g, Component.translatable("gui.nerocolonies.access.members", snapshot.accessCount()),
                    8, 60, SUBTLE);
            return;
        }
        hGauge(g, 8, 42, 136, 6, capacity <= 0 ? 0.0F : Math.min(1.0F, population / (float) capacity),
                population > capacity ? BAD : ACCENT);
        label(g, Component.translatable("gui.nerocolonies.stat.comfort", this.menu.comfortPercent()),
                8, 51, SUBTLE);
        label(g, Component.translatable("gui.nerocolonies.stat.food", this.menu.foodStock()), 8, 60,
                this.menu.foodStock() > 0 ? SUBTLE : BAD);
    }

    private void drawJobs(GuiGraphicsExtractor g) {
        if (this.menu.workStopped()) {
            label(g, Component.translatable("gui.nerocolonies.stat.work_stopped"), 8, 32, BAD);
            label(g, Component.translatable("gui.nerocolonies.beacon.work_stopped_hint"), 8, 44, SUBTLE);
            return;
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            label(g, Component.translatable("gui.nerocolonies.beacon.jobs_hint"), 8, 32, SUBTLE);
            return;
        }
        label(g, Component.translatable("gui.nerocolonies.stat.job_slots",
                snapshot.jobsActive(), snapshot.jobSlots()), 8, 32, TITLE);
        hGauge(g, 8, 42, 136, 6, snapshot.jobSlots() <= 0
                ? 0.0F : Math.min(1.0F, snapshot.jobsActive() / (float) snapshot.jobSlots()), ACCENT);
        label(g, Component.translatable("gui.nerocolonies.stat.stations", snapshot.jobStations()),
                8, 51, SUBTLE);
        label(g, Component.translatable("gui.nerocolonies.stat.storage",
                snapshot.storageUsed(), snapshot.storageSlots()), 8, 60, SUBTLE);
    }

    private void drawResearch(GuiGraphicsExtractor g) {
        label(g, Component.translatable("gui.nerocolonies.stat.research", this.menu.researchCount()),
                8, 32, TITLE);
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present()) {
            label(g, Component.translatable("gui.nerocolonies.stat.job_slots_total", snapshot.jobSlots()),
                    8, 44, SUBTLE);
        }
        label(g, Component.translatable("gui.nerocolonies.beacon.research_hint"), 8, 56, SUBTLE);
    }

    private void drawExports(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            label(g, Component.translatable("gui.nerocolonies.stat.outposts", this.menu.outpostCount()),
                    8, 32, TITLE);
            label(g, Component.translatable("gui.nerocolonies.beacon.exports_hint"), 8, 44, SUBTLE);
            return;
        }
        label(g, Component.translatable("gui.nerocolonies.stat.export_buffer",
                snapshot.exportFilled(), snapshot.exportSlots()), 8, 32, TITLE);
        hGauge(g, 8, 42, 136, 6, snapshot.exportSlots() <= 0
                ? 0.0F : Math.min(1.0F, snapshot.exportFilled() / (float) snapshot.exportSlots()),
                snapshot.exportFilled() >= snapshot.exportSlots() ? BAD : ACCENT);

        button(g, SELL_BUTTON_X, SELL_BUTTON_Y, SELL_BUTTON_WIDTH, SELL_BUTTON_HEIGHT,
                Component.translatable("gui.nerocolonies.export.sell"),
                snapshot.marketAvailable() && snapshot.exportValue() > 0L);
        label(g, Component.translatable("gui.nerocolonies.export.value", snapshot.exportValue()),
                74, SELL_BUTTON_Y + 3, snapshot.exportValue() > 0L ? GOOD : SUBTLE);
        if (!snapshot.marketAvailable()) {
            label(g, Component.translatable("gui.nerocolonies.export.no_market_hint"), 8, 33 + 40, SUBTLE);
        } else {
            label(g, Component.translatable("gui.nerocolonies.stat.outposts", snapshot.outpostCount()),
                    8, 33 + 40, SUBTLE);
        }
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

    // --- input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double localX = event.x() - this.leftPos;
        double localY = event.y() - this.topPos;
        if (localY >= TAB_Y && localY < TAB_Y + TAB_HEIGHT) {
            for (int i = 0; i < TAB_COUNT; i++) {
                int dx = 7 + i * (TAB_WIDTH + 1);
                if (localX >= dx && localX < dx + TAB_WIDTH) {
                    this.tab = i;
                    updateAccessField();
                    return true;
                }
            }
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (this.tab == TAB_PEOPLE && snapshot.present() && snapshot.isOwner()) {
            if (within(localX, localY, ACCESS_ADD_X, ACCESS_BUTTON_Y, ACCESS_BUTTON_WIDTH,
                    ACCESS_BUTTON_HEIGHT)) {
                sendAccess(true);
                return true;
            }
            if (within(localX, localY, ACCESS_REMOVE_X, ACCESS_REMOVE_Y, ACCESS_BUTTON_WIDTH,
                    ACCESS_BUTTON_HEIGHT)) {
                sendAccess(false);
                return true;
            }
        }
        if (this.tab == TAB_TRADE && snapshot.present()
                && within(localX, localY, SELL_BUTTON_X, SELL_BUTTON_Y, SELL_BUTTON_WIDTH,
                        SELL_BUTTON_HEIGHT)) {
            Services.NETWORK.sendToServer(ColonyIntentPayload.sell(snapshot.anchor()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static boolean within(double x, double y, int dx, int dy, int width, int height) {
        return x >= dx && x < dx + width && y >= dy && y < dy + height;
    }

    /**
     * Sends the typed name to the server. The client-side check is only "is there a name?" — whether
     * the sender is the owner, whether the target exists and whether anything changes are all decided
     * server-side, and the answer comes back as a message plus a fresh snapshot.
     */
    private void sendAccess(boolean grant) {
        if (this.accessField == null) {
            return;
        }
        String name = this.accessField.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            return;
        }
        Services.NETWORK.sendToServer(grant
                ? ColonyIntentPayload.accessAdd(snapshot.anchor(), name)
                : ColonyIntentPayload.accessRemove(snapshot.anchor(), name));
        this.accessField.setValue("");
    }
}
