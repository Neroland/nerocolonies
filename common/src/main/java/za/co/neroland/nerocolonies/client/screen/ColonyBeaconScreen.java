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
 * The colony beacon's screen: five status tabs over the shared hull panel, a permanent supply and
 * module band, and the player inventory.
 *
 * <h2>Two sources, and the split is deliberate</h2>
 *
 * <p>The live numbers — morale, power, population, food — come from the menu's <b>data slots</b>,
 * which resync every tick while the screen is open. The things that do not fit through a 16-bit data
 * slot — the export buffer's credit value, the access-list size, whether a currency provider exists,
 * the anchor block an intent must name — come from the per-player {@link ColonySnapshotPayload} sent
 * when the beacon is opened and after every action. Using the right channel for each is what keeps
 * the per-tick cost at eighteen shorts.
 *
 * <h2>The tab strip is measured, not guessed</h2>
 *
 * <p>Tab widths are laid out from the <b>font's</b> measurement of each label at {@link #init} time
 * and spread across the panel with real padding. A fixed-width tab is a mistranslation waiting to
 * happen: the first pass used 33px cells, which ran "Colony" and "People" into each other in English
 * and would have clipped both in most other languages. Every label on this screen is either wrapped
 * or ellipsised against the panel width for the same reason.
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
    private static final int WIDTH = 208;
    private static final int HEIGHT = 236;

    /** Tab strip. */
    private static final int TAB_COUNT = 5;
    private static final int TAB_Y = 18;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_STRIP_X = 6;
    private static final int TAB_STRIP_WIDTH = WIDTH - 2 * TAB_STRIP_X;
    private static final int TAB_MIN_PADDING = 8;

    /** Tab content area — every tab draws inside this box and nothing draws outside it. */
    private static final int CONTENT_X = 8;
    private static final int CONTENT_WIDTH = WIDTH - 2 * CONTENT_X;
    private static final int ROW_1 = 38;
    private static final int GAUGE_1 = 48;
    private static final int ROW_2 = 58; // gaugeRow puts its bar at ROW_2 + LINE
    private static final int ROW_3 = 75;
    private static final int ROW_4 = 85;

    /** The supply / module band, always visible under the tabs. */
    private static final int BAND_DIVIDER_Y = 95;
    private static final int SECTION_Y = 98;
    private static final int HINT_Y = 131;

    private static final int TAB_COLONY = 0;
    private static final int TAB_PEOPLE = 1;
    private static final int TAB_JOBS = 2;
    private static final int TAB_RESEARCH = 3;
    private static final int TAB_TRADE = 4;

    /** Life-support state ordinals, matching {@code LifeSupport.State}. */
    private static final int LIFE_OK = 0;
    private static final int LIFE_DEGRADED = 1;

    private static final int ACCESS_FIELD_X = CONTENT_X;
    private static final int ACCESS_FIELD_Y = GAUGE_1;
    private static final int ACCESS_FIELD_WIDTH = 92;
    private static final int ACCESS_FIELD_HEIGHT = 14;

    private static final int ACCESS_ADD_X = 104;
    private static final int ACCESS_REMOVE_X = 152;
    private static final int ACCESS_BUTTON_Y = GAUGE_1;
    private static final int ACCESS_ADD_WIDTH = 44;
    private static final int ACCESS_REMOVE_WIDTH = 48;
    private static final int ACCESS_BUTTON_HEIGHT = 14;

    private static final int SELL_BUTTON_X = CONTENT_X;
    private static final int SELL_BUTTON_Y = 60;
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

    /** Measured tab geometry, panel-relative. Rebuilt whenever the screen is (re)initialised. */
    private final int[] tabX = new int[TAB_COUNT];
    private final int[] tabWidth = new int[TAB_COUNT];

    @Nullable
    private EditBox accessField;

    public ColonyBeaconScreen(ColonyBeaconMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = ColonyBeaconMenu.INVENTORY_X;
        this.inventoryLabelY = ColonyBeaconMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        layoutTabs();
        this.accessField = new EditBox(this.font, this.leftPos + ACCESS_FIELD_X,
                this.topPos + ACCESS_FIELD_Y, ACCESS_FIELD_WIDTH, ACCESS_FIELD_HEIGHT,
                Component.translatable("gui.nerocolonies.access.field"));
        this.accessField.setMaxLength(32);
        this.accessField.setBordered(true);
        this.accessField.setHint(Component.translatable("gui.nerocolonies.access.hint"));
        this.addRenderableWidget(this.accessField);
        updateAccessField();
    }

    /**
     * Spreads the five tabs across the strip using the font's own measurement of each label, so every
     * label is fully visible with even padding in any language. When the labels genuinely cannot fit
     * (a very long translation), the strip degrades to a proportional split and the labels ellipsise
     * rather than overrunning their neighbours.
     */
    private void layoutTabs() {
        int gaps = TAB_COUNT - 1;
        int budget = TAB_STRIP_WIDTH - gaps;
        int[] textWidth = new int[TAB_COUNT];
        int total = 0;
        for (int i = 0; i < TAB_COUNT; i++) {
            textWidth[i] = this.font.width(Component.translatable(TAB_KEYS[i]).getString());
            total += textWidth[i];
        }
        int padded = total + TAB_COUNT * TAB_MIN_PADDING;
        if (padded <= budget) {
            int extra = budget - padded;
            for (int i = 0; i < TAB_COUNT; i++) {
                this.tabWidth[i] = textWidth[i] + TAB_MIN_PADDING + extra / TAB_COUNT
                        + (i < extra % TAB_COUNT ? 1 : 0);
            }
        } else {
            int used = 0;
            for (int i = 0; i < TAB_COUNT; i++) {
                this.tabWidth[i] = i == TAB_COUNT - 1
                        ? budget - used
                        : Math.max(8, budget * textWidth[i] / Math.max(1, total));
                used += this.tabWidth[i];
            }
        }
        int x = TAB_STRIP_X;
        for (int i = 0; i < TAB_COUNT; i++) {
            this.tabX[i] = x;
            x += this.tabWidth[i] + 1;
        }
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
    protected void paintTrays(GuiGraphicsExtractor g) {
        slotTray(g, ColonyBeaconMenu.SUPPLY_ROW_X, ColonyBeaconMenu.SUPPLY_ROW_Y, 6, 1);
        slotTray(g, ColonyBeaconMenu.UPGRADE_ROW_X, ColonyBeaconMenu.UPGRADE_ROW_Y, 3, 1);
        playerInventoryTray(g, ColonyBeaconMenu.INVENTORY_X, ColonyBeaconMenu.INVENTORY_Y,
                ColonyBeaconMenu.HOTBAR_Y);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        drawTabs(g);
        drawSupplyBand(g);

        if (!this.menu.hasColony()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.unbound"),
                    CONTENT_X, ROW_1, CONTENT_WIDTH, 2, SUBTLE);
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

    /**
     * The tab strip: a selected tab that merges into the content area below it, an idle tab that is
     * ruled off from it, and a hover state so the strip reads as clickable before it is clicked.
     */
    private void drawTabs(GuiGraphicsExtractor g) {
        int ruleY = this.topPos + TAB_Y + TAB_HEIGHT;
        g.fill(this.leftPos + TAB_STRIP_X, ruleY, this.leftPos + TAB_STRIP_X + TAB_STRIP_WIDTH,
                ruleY + 1, ACCENT);

        for (int i = 0; i < TAB_COUNT; i++) {
            int dx = this.tabX[i];
            int width = this.tabWidth[i];
            boolean selected = i == this.tab;
            boolean hovered = !selected && within(this.hoverX, this.hoverY, dx, TAB_Y, width, TAB_HEIGHT);
            int x = this.leftPos + dx;
            int y = this.topPos + TAB_Y;

            g.fill(x, y, x + width, y + TAB_HEIGHT, selected ? PANEL : (hovered ? PANEL_EDGE : TROUGH));
            g.fill(x, y, x + width, y + 1, selected ? ACCENT : INK);
            g.fill(x, y, x + 1, y + TAB_HEIGHT, INK);
            g.fill(x + width - 1, y, x + width, y + TAB_HEIGHT, INK);
            if (selected) {
                // The selected tab opens into the content area: erase the rule beneath it.
                g.fill(x + 1, ruleY, x + width - 1, ruleY + 1, PANEL);
            } else {
                g.fill(x, y + TAB_HEIGHT - 1, x + width, y + TAB_HEIGHT, INK);
            }
            clampedCentered(g, Component.translatable(TAB_KEYS[i]), dx + 3, width - 6, TAB_Y + 4,
                    selected || hovered ? TITLE : SUBTLE);
        }
    }

    /**
     * The supply and module band. It is on every tab, on purpose: it is the only part of this screen
     * a player puts an item into, and hiding it behind a tab would make feeding a colony a scavenger
     * hunt. The hint under it names where construction materials go, which is <b>not</b> here — those
     * are drawn from colony storage, and a player who puts iron in the food row learns nothing.
     */
    private void drawSupplyBand(GuiGraphicsExtractor g) {
        divider(g, CONTENT_X, BAND_DIVIDER_Y, CONTENT_WIDTH);

        int supplyWidth = 6 * SLOT;
        int moduleWidth = 3 * SLOT;
        clampedLabel(g, Component.translatable("gui.nerocolonies.beacon.supply"),
                ColonyBeaconMenu.SUPPLY_ROW_X, SECTION_Y, supplyWidth, SUBTLE);
        labelRight(g, Component.translatable("gui.nerocolonies.slots.modules"),
                ColonyBeaconMenu.UPGRADE_ROW_X, moduleWidth, SECTION_Y, SUBTLE);

        wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.supply_hint"),
                CONTENT_X, HINT_Y, CONTENT_WIDTH, 1, MUTED);
    }

    private void drawOverview(GuiGraphicsExtractor g) {
        int morale = this.menu.morale();

        // Three life-support states, three readings. A colony coasting on reserves must not look the
        // same as one that is fine, or the grace window is invisible until it has already expired.
        int state = this.menu.lifeSupportState();
        String lifeKey = switch (state) {
            case LIFE_OK -> "gui.nerocolonies.stat.life_support_ok";
            case LIFE_DEGRADED -> "gui.nerocolonies.stat.life_support_degraded";
            default -> "gui.nerocolonies.stat.life_support_failed";
        };
        Component life = Component.translatable(lifeKey);
        int lifeWidth = Math.min(CONTENT_WIDTH / 2, this.font.width(life.getString()));
        labelRight(g, life, CONTENT_X, CONTENT_WIDTH, ROW_1,
                state == LIFE_OK ? GOOD : (state == LIFE_DEGRADED ? WARN : BAD));

        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.morale", morale),
                CONTENT_X, ROW_1, CONTENT_WIDTH - lifeWidth - 6, TITLE);
        hGauge(g, CONTENT_X, GAUGE_1, CONTENT_WIDTH, BAR_H, morale / 100.0F,
                this.menu.workStopped() ? BAD : ACCENT);

        gaugeRow(g, CONTENT_X, ROW_2, CONTENT_WIDTH,
                Component.translatable("gui.nerocolonies.stat.power"),
                percent(this.menu.energyPermille(), this.menu.energyScale()),
                frac(this.menu.energyPermille(), this.menu.energyScale()), ACCENT, true);

        drawConstruction(g);
    }

    /**
     * What the colony is building for itself, and whether it has the materials.
     *
     * <p>Both sources again, and for the usual reason: the <b>percentage</b> is a data slot so it
     * moves while you watch, while the structure's <b>name</b> is a translation key from the
     * per-player snapshot, because a name does not fit through a 16-bit slot. The server pushes a
     * fresh snapshot whenever a structure completes, so the name never lags the bar.
     *
     * <p>"No materials" is the only actionable line on this tab: it means the colonists are
     * fabricating from scrap at a quarter speed, and that putting the blueprint's materials into
     * colony storage will speed the same build up.
     */
    private void drawConstruction(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        String name = snapshot.present() ? snapshot.buildName() : "";
        if (name.isEmpty()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.build.idle",
                    this.menu.structuresBuilt()), CONTENT_X, ROW_3, CONTENT_WIDTH, 2, SUBTLE);
            return;
        }
        boolean supplied = this.menu.buildSupplied();
        clampedLabel(g, Component.translatable(
                supplied ? "gui.nerocolonies.build.active" : "gui.nerocolonies.build.unsupplied",
                Component.translatable(name), this.menu.buildPercent()),
                CONTENT_X, ROW_3, CONTENT_WIDTH, supplied ? ACCENT : WARN);
        if (!supplied) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.build.materials_hint"),
                    CONTENT_X, ROW_4, CONTENT_WIDTH, 1, MUTED);
        }
    }

    private void drawColonists(GuiGraphicsExtractor g) {
        int population = this.menu.population();
        int capacity = this.menu.housingCapacity();
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.population", population, capacity),
                CONTENT_X, ROW_1, CONTENT_WIDTH, TITLE);

        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present() && snapshot.isOwner()) {
            // Owner: the access editor takes the space the gauges would have used. The EditBox is a
            // real widget and paints itself; only the two buttons and the count are ours.
            button(g, ACCESS_ADD_X, ACCESS_BUTTON_Y, ACCESS_ADD_WIDTH, ACCESS_BUTTON_HEIGHT,
                    Component.translatable("gui.nerocolonies.access.add"), true);
            button(g, ACCESS_REMOVE_X, ACCESS_BUTTON_Y, ACCESS_REMOVE_WIDTH, ACCESS_BUTTON_HEIGHT,
                    Component.translatable("gui.nerocolonies.access.remove"), true);
            clampedLabel(g, Component.translatable("gui.nerocolonies.access.members",
                    snapshot.accessCount()), CONTENT_X, ROW_2 + 10, CONTENT_WIDTH, SUBTLE);
            wrappedLabel(g, Component.translatable("gui.nerocolonies.access.online_hint"),
                    CONTENT_X, ROW_4, CONTENT_WIDTH, 1, MUTED);
            return;
        }
        hGauge(g, CONTENT_X, GAUGE_1, CONTENT_WIDTH, BAR_H,
                capacity <= 0 ? 0.0F : Math.min(1.0F, population / (float) capacity),
                population > capacity ? BAD : ACCENT);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.comfort",
                this.menu.comfortPercent()), CONTENT_X, ROW_2, CONTENT_WIDTH, SUBTLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.food", this.menu.foodStock()),
                CONTENT_X, ROW_3, CONTENT_WIDTH, this.menu.foodStock() > 0 ? SUBTLE : BAD);
        wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.colonists_hint"),
                CONTENT_X, ROW_4, CONTENT_WIDTH, 1, MUTED);
    }

    private void drawJobs(GuiGraphicsExtractor g) {
        if (this.menu.workStopped()) {
            clampedLabel(g, Component.translatable("gui.nerocolonies.stat.work_stopped"),
                    CONTENT_X, ROW_1, CONTENT_WIDTH, BAD);
            wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.work_stopped_hint"),
                    CONTENT_X, GAUGE_1, CONTENT_WIDTH, 2, SUBTLE);
            return;
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.jobs_hint"),
                    CONTENT_X, ROW_1, CONTENT_WIDTH, 2, SUBTLE);
            return;
        }
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.job_slots",
                snapshot.jobsActive(), snapshot.jobSlots()), CONTENT_X, ROW_1, CONTENT_WIDTH, TITLE);
        hGauge(g, CONTENT_X, GAUGE_1, CONTENT_WIDTH, BAR_H, snapshot.jobSlots() <= 0
                ? 0.0F : Math.min(1.0F, snapshot.jobsActive() / (float) snapshot.jobSlots()), ACCENT);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.stations", snapshot.jobStations()),
                CONTENT_X, ROW_2, CONTENT_WIDTH, SUBTLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.storage",
                snapshot.storageUsed(), snapshot.storageSlots()), CONTENT_X, ROW_3, CONTENT_WIDTH, SUBTLE);
        wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.jobs_hint"),
                CONTENT_X, ROW_4, CONTENT_WIDTH, 1, MUTED);
    }

    private void drawResearch(GuiGraphicsExtractor g) {
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.research",
                this.menu.researchCount()), CONTENT_X, ROW_1, CONTENT_WIDTH, TITLE);
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present()) {
            clampedLabel(g, Component.translatable("gui.nerocolonies.stat.job_slots_total",
                    snapshot.jobSlots()), CONTENT_X, ROW_2, CONTENT_WIDTH, SUBTLE);
        }
        wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.research_hint"),
                CONTENT_X, ROW_3, CONTENT_WIDTH, 2, MUTED);
    }

    private void drawExports(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            clampedLabel(g, Component.translatable("gui.nerocolonies.stat.outposts",
                    this.menu.outpostCount()), CONTENT_X, ROW_1, CONTENT_WIDTH, TITLE);
            wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.exports_hint"),
                    CONTENT_X, ROW_2, CONTENT_WIDTH, 2, SUBTLE);
            return;
        }
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.export_buffer",
                snapshot.exportFilled(), snapshot.exportSlots()), CONTENT_X, ROW_1, CONTENT_WIDTH, TITLE);
        hGauge(g, CONTENT_X, GAUGE_1, CONTENT_WIDTH, BAR_H, snapshot.exportSlots() <= 0
                ? 0.0F : Math.min(1.0F, snapshot.exportFilled() / (float) snapshot.exportSlots()),
                snapshot.exportFilled() >= snapshot.exportSlots() ? BAD : ACCENT);

        button(g, SELL_BUTTON_X, SELL_BUTTON_Y, SELL_BUTTON_WIDTH, SELL_BUTTON_HEIGHT,
                Component.translatable("gui.nerocolonies.export.sell"),
                snapshot.marketAvailable() && snapshot.exportValue() > 0L);
        int valueX = SELL_BUTTON_X + SELL_BUTTON_WIDTH + 8;
        clampedLabel(g, Component.translatable("gui.nerocolonies.export.value", snapshot.exportValue()),
                valueX, SELL_BUTTON_Y + 3, CONTENT_X + CONTENT_WIDTH - valueX,
                snapshot.exportValue() > 0L ? GOOD : SUBTLE);

        wrappedLabel(g, Component.translatable(snapshot.marketAvailable()
                        ? "gui.nerocolonies.beacon.exports_hint"
                        : "gui.nerocolonies.export.no_market_hint"),
                CONTENT_X, ROW_3 + 1, CONTENT_WIDTH, 2,
                snapshot.marketAvailable() ? MUTED : BAD);
    }

    // --- input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double localX = event.x() - this.leftPos;
        double localY = event.y() - this.topPos;
        if (localY >= TAB_Y && localY < TAB_Y + TAB_HEIGHT) {
            for (int i = 0; i < TAB_COUNT; i++) {
                if (within(localX, localY, this.tabX[i], TAB_Y, this.tabWidth[i], TAB_HEIGHT)) {
                    this.tab = i;
                    updateAccessField();
                    return true;
                }
            }
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (this.tab == TAB_PEOPLE && snapshot.present() && snapshot.isOwner()) {
            if (within(localX, localY, ACCESS_ADD_X, ACCESS_BUTTON_Y, ACCESS_ADD_WIDTH,
                    ACCESS_BUTTON_HEIGHT)) {
                sendAccess(true);
                return true;
            }
            if (within(localX, localY, ACCESS_REMOVE_X, ACCESS_BUTTON_Y, ACCESS_REMOVE_WIDTH,
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
