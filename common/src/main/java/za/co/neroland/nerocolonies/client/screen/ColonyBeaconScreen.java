package za.co.neroland.nerocolonies.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.client.ClientColonySnapshot;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyStage;
import za.co.neroland.nerocolonies.menu.ColonyBeaconMenu;
import za.co.neroland.nerocolonies.network.ColonyIntentPayload;
import za.co.neroland.nerocolonies.network.ColonySnapshotPayload;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * The colony beacon's screen: seven status tabs over the shared hull panel, a permanent supply and
 * module band, and the player inventory.
 *
 * <h2>Two sources, and the split is deliberate</h2>
 *
 * <p>The live numbers — morale, power, population, food — come from the menu's <b>data slots</b>,
 * which resync every tick while the screen is open. The things that do not fit through a 16-bit data
 * slot — the export buffer's credit value, the needs list, the viewer's role, whether a currency
 * provider exists, the anchor block an intent must name — come from the per-player
 * {@link ColonySnapshotPayload} sent when the beacon is opened and after every action. While the
 * Needs or Roles tab is open the screen also asks for a fresh snapshot every few seconds, because
 * estimates and rosters go stale in a way a research list does not.
 *
 * <h2>The tab strip is measured, not guessed</h2>
 *
 * <p>Tab widths are laid out from the <b>font's</b> measurement of each label at {@link #init} time
 * and spread across the panel with real padding. A fixed-width tab is a mistranslation waiting to
 * happen. When the labels do not all fit on one row with that padding — seven English labels do not
 * — the strip becomes two shorter rows rather than seven ellipses; only a label that cannot fit in
 * its own row is ever cut. Every label on this screen is either wrapped or ellipsised against the
 * panel width for the same reason, and everything a tab draws stays inside the content box.
 *
 * <h2>The Roles tab, and what it shows to whom (POPIA/GDPR)</h2>
 *
 * <p>Every member sees their own role and three <b>counts</b>: Allies, Chiefs, Enemies. That is all
 * an Ally is ever sent.
 *
 * <p>A viewer who may manage the colony's members — its owner, a Chief, or a server operator — is
 * also sent the <b>names</b> of those members, because nobody can be asked to decide who belongs
 * without seeing who already does. The server resolves the names when it builds the snapshot and
 * sends them to that viewer alone; this screen draws them and keeps nothing: no file, no log, no
 * clipboard, and the next snapshot replaces them. There are no UUIDs anywhere on the client.
 *
 * <p>To change a role the viewer types a name (or clicks one in the roster), picks a role and
 * presses Add or Remove. The client sends the name and the role; the server decides whether the
 * sender may do that, applies it, and answers with a message that never repeats the name. Names are
 * matched against online players only; the command path, by UUID, covers the rest.
 */
public class ColonyBeaconScreen extends NeroColoniesScreen<ColonyBeaconMenu> {

    private static final int ACCENT = 0xFF4FB3D9; // colony cyan
    private static final int WIDTH = 208;
    private static final int HEIGHT = 236;

    /** Tab strip. One 16px row when the labels fit, two 9px rows when they do not. */
    private static final int TAB_COUNT = 7;
    private static final int TAB_Y = 18;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_ROW_HEIGHT = 9;
    private static final int TAB_STRIP_X = 6;
    private static final int TAB_STRIP_WIDTH = WIDTH - 2 * TAB_STRIP_X;
    private static final int TAB_MIN_PADDING = 8;

    /**
     * Tab content area — every tab draws inside this box and nothing draws outside it. A line of
     * text is 8px tall, so the last line a tab may start is {@link #LAST_LINE}.
     */
    private static final int CONTENT_X = 8;
    private static final int CONTENT_WIDTH = WIDTH - 2 * CONTENT_X;
    private static final int ROW_1 = 38;
    private static final int GAUGE_1 = 48;
    private static final int ROW_2 = 58; // gaugeRow puts its bar at ROW_2 + LINE
    private static final int ROW_3 = 75;
    private static final int ROW_4 = 85;
    private static final int LAST_LINE = 86;

    /** The tighter rhythm the Colony and People tabs use to leave two lines for a reason. */
    private static final int TIGHT_2 = 57;
    private static final int TIGHT_3 = 66;
    private static final int TIGHT_4 = 76;

    /** The supply / module band, always visible under the tabs. */
    private static final int BAND_DIVIDER_Y = 95;
    private static final int SECTION_Y = 98;
    private static final int HINT_Y = 131;

    private static final int TAB_COLONY = 0;
    private static final int TAB_PEOPLE = 1;
    private static final int TAB_NEEDS = 2;
    private static final int TAB_ROLES = 3;
    private static final int TAB_JOBS = 4;
    private static final int TAB_RESEARCH = 5;
    private static final int TAB_TRADE = 6;

    /** Life-support state ordinals, matching {@code LifeSupport.State}. */
    private static final int LIFE_OK = 0;
    private static final int LIFE_DEGRADED = 1;

    /** Needs tab: three list lines, then a hint and the build estimate. */
    private static final int NEED_Y = 48;
    private static final int NEED_SLOTS = 3;
    private static final int NEED_HINT_Y = 77;
    private static final int NEED_ETA_WIDTH = 96;
    private static final int NEED_GAP = 5;

    /**
     * Roles tab: role and cache toggle, counts, the editor row, the roster. The first line sits a
     * pixel lower than on the other tabs so the toggle's frame clears the rule under the tab strip.
     */
    private static final int ROLE_TITLE_Y = 39;
    private static final int ROLE_COUNTS_Y = 50;
    private static final int ROLE_ROW_Y = 60;
    private static final int ROLE_ROW_HEIGHT = 14;
    private static final int ROLE_FIELD_X = CONTENT_X;
    private static final int ROLE_FIELD_WIDTH = 62;
    private static final int ROLE_PICK_X = 75;
    private static final int ROLE_PICK_WIDTH = 40;
    private static final int ROLE_ADD_X = 120;
    private static final int ROLE_ADD_WIDTH = 32;
    private static final int ROLE_REMOVE_X = 157;
    private static final int ROLE_REMOVE_WIDTH = 43;
    private static final int ROSTER_Y = 76;
    private static final int ROSTER_LINES = 2;
    private static final int ROSTER_GAP = 8;
    private static final int ROLE_HINT_Y = 62;

    private static final int CACHE_BUTTON_X = 116;
    private static final int CACHE_BUTTON_Y = 38;
    private static final int CACHE_BUTTON_WIDTH = 84;
    private static final int CACHE_BUTTON_HEIGHT = 10;

    private static final int SELL_BUTTON_X = CONTENT_X;
    private static final int SELL_BUTTON_Y = 60;
    private static final int SELL_BUTTON_WIDTH = 60;
    private static final int SELL_BUTTON_HEIGHT = 14;

    /** How often an open Needs or Roles tab asks the server for a fresh snapshot, in client ticks. */
    private static final int REFRESH_TICKS = 60;

    private static final String[] TAB_KEYS = {
        "gui.nerocolonies.tab.overview",
        "gui.nerocolonies.tab.colonists",
        "gui.nerocolonies.tab.needs",
        "gui.nerocolonies.tab.roles",
        "gui.nerocolonies.tab.jobs",
        "gui.nerocolonies.tab.research",
        "gui.nerocolonies.tab.exports",
    };

    /** The roles the editor can hand out, in the order its button cycles through them. */
    private static final String[] ROLE_TOKENS = {
        ColonyIntentPayload.ROLE_ALLY,
        ColonyIntentPayload.ROLE_CHIEF,
        ColonyIntentPayload.ROLE_ENEMY,
    };

    private static final int ROLE_CHOICE_ENEMY = 2;

    private int tab = TAB_COLONY;

    /** Measured tab geometry, panel-relative. Rebuilt whenever the screen is (re)initialised. */
    private final int[] tabX = new int[TAB_COUNT];
    private final int[] tabTop = new int[TAB_COUNT];
    private final int[] tabWidth = new int[TAB_COUNT];
    private int tabRows = 1;
    private int tabHeight = TAB_HEIGHT;

    @Nullable
    private EditBox roleField;

    /** Which of {@link #ROLE_TOKENS} the editor's Add and Remove apply. */
    private int roleChoice;

    /** First need shown, when the list is longer than the tab. */
    private int needsOffset;

    /** First roster entry shown, and where the pager drawn this frame leads (-1: no pager). */
    private int rosterOffset;
    private int rosterNext = -1;
    private int rosterPagerX;
    private int rosterPagerWidth;

    /** Where this frame drew each roster name, so a click can put it in the name field. */
    private final List<RosterHit> rosterHits = new ArrayList<>();

    private int refreshTicks;

    /** One drawn roster name and its box, panel-relative. */
    private record RosterHit(int x, int y, int width, String name) {
    }

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
        this.roleField = new EditBox(this.font, this.leftPos + ROLE_FIELD_X,
                this.topPos + ROLE_ROW_Y, ROLE_FIELD_WIDTH, ROLE_ROW_HEIGHT,
                Component.translatable("gui.nerocolonies.access.field"));
        this.roleField.setMaxLength(32);
        this.roleField.setBordered(true);
        this.roleField.setHint(Component.translatable("gui.nerocolonies.access.hint"));
        this.addRenderableWidget(this.roleField);
        updateRoleField();
    }

    /**
     * Lays the tabs out from the font's own measurement of each label. If they all fit on one row
     * with real padding, that is the strip. If they do not, the strip becomes two rows — the first
     * holding one more tab than the second when the count is odd — and each row is spread
     * separately, so every label is still fully visible. Only when a single row's labels cannot fit
     * even then (a very long translation) does that row fall back to a proportional split with
     * ellipsised labels, rather than overrunning its neighbours.
     */
    private void layoutTabs() {
        int[] textWidth = new int[TAB_COUNT];
        int total = 0;
        for (int i = 0; i < TAB_COUNT; i++) {
            textWidth[i] = this.font.width(Component.translatable(TAB_KEYS[i]).getString());
            total += textWidth[i];
        }
        if (total + TAB_COUNT * TAB_MIN_PADDING <= TAB_STRIP_WIDTH - (TAB_COUNT - 1)) {
            this.tabRows = 1;
            this.tabHeight = TAB_HEIGHT;
            layoutTabRow(textWidth, 0, TAB_COUNT, TAB_Y);
            return;
        }
        this.tabRows = 2;
        this.tabHeight = TAB_ROW_HEIGHT;
        int firstRow = (TAB_COUNT + 1) / 2;
        layoutTabRow(textWidth, 0, firstRow, TAB_Y);
        layoutTabRow(textWidth, firstRow, TAB_COUNT, TAB_Y + TAB_ROW_HEIGHT);
    }

    /** Spreads tabs {@code [from, to)} across the strip at height {@code y}. */
    private void layoutTabRow(int[] textWidth, int from, int to, int y) {
        int count = to - from;
        int budget = TAB_STRIP_WIDTH - (count - 1);
        int total = 0;
        for (int i = from; i < to; i++) {
            total += textWidth[i];
        }
        int padded = total + count * TAB_MIN_PADDING;
        int used = 0;
        for (int i = from; i < to; i++) {
            int slot = i - from;
            if (padded <= budget) {
                int extra = budget - padded;
                this.tabWidth[i] = textWidth[i] + TAB_MIN_PADDING + extra / count
                        + (slot < extra % count ? 1 : 0);
            } else {
                this.tabWidth[i] = i == to - 1
                        ? budget - used
                        : Math.max(8, budget * textWidth[i] / Math.max(1, total));
            }
            used += this.tabWidth[i];
        }
        int x = TAB_STRIP_X;
        for (int i = from; i < to; i++) {
            this.tabX[i] = x;
            this.tabTop[i] = y;
            x += this.tabWidth[i] + 1;
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateRoleField();
        // Estimates and rosters go stale while you look at them; a research list does not.
        if ((this.tab == TAB_NEEDS || this.tab == TAB_ROLES) && this.menu.hasColony()) {
            if (++this.refreshTicks >= REFRESH_TICKS) {
                requestRefresh();
            }
        } else {
            this.refreshTicks = 0;
        }
    }

    private void requestRefresh() {
        this.refreshTicks = 0;
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present()) {
            Services.NETWORK.sendToServer(ColonyIntentPayload.refresh(snapshot.anchor()));
        }
    }

    /** The name field exists only on the Roles tab, and only for a viewer who may manage members. */
    private void updateRoleField() {
        if (this.roleField == null) {
            return;
        }
        boolean show = this.tab == TAB_ROLES && this.menu.hasColony() && mayManageMembers();
        this.roleField.visible = show;
        this.roleField.active = show;
        if (!show && this.roleField.isFocused()) {
            this.roleField.setFocused(false);
            if (getFocused() == this.roleField) {
                setFocused(null);
            }
        }
    }

    private static boolean mayManageMembers() {
        return ClientColonySnapshot.may(ColonyPermissions.Action.MANAGE_MEMBERS);
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
        drawStage(g);
        switch (this.tab) {
            case TAB_PEOPLE -> drawColonists(g);
            case TAB_NEEDS -> drawNeeds(g);
            case TAB_ROLES -> drawRoles(g);
            case TAB_JOBS -> drawJobs(g);
            case TAB_RESEARCH -> drawResearch(g);
            case TAB_TRADE -> drawExports(g);
            default -> drawOverview(g);
        }
    }

    /**
     * The tab strip: a selected tab that stands out from its neighbours (and, on the row that touches
     * the content area, merges into it), an idle tab that is ruled off, and a hover state so the
     * strip reads as clickable before it is clicked.
     */
    private void drawTabs(GuiGraphicsExtractor g) {
        int stripBottom = TAB_Y + this.tabRows * this.tabHeight;
        int ruleY = this.topPos + stripBottom;
        g.fill(this.leftPos + TAB_STRIP_X, ruleY, this.leftPos + TAB_STRIP_X + TAB_STRIP_WIDTH,
                ruleY + 1, ACCENT);

        for (int i = 0; i < TAB_COUNT; i++) {
            int dx = this.tabX[i];
            int dy = this.tabTop[i];
            int width = this.tabWidth[i];
            int height = this.tabHeight;
            boolean selected = i == this.tab;
            boolean hovered = !selected && within(this.hoverX, this.hoverY, dx, dy, width, height);
            int x = this.leftPos + dx;
            int y = this.topPos + dy;

            g.fill(x, y, x + width, y + height, selected ? PANEL : (hovered ? PANEL_EDGE : TROUGH));
            g.fill(x, y, x + width, y + 1, selected ? ACCENT : INK);
            g.fill(x, y, x + 1, y + height, INK);
            g.fill(x + width - 1, y, x + width, y + height, INK);
            if (selected && dy + height == stripBottom) {
                // The selected tab opens into the content area: erase the rule beneath it.
                g.fill(x + 1, ruleY, x + width - 1, ruleY + 1, PANEL);
            } else if (!selected && this.tabRows == 1) {
                g.fill(x, y + height - 1, x + width, y + height, INK);
            }
            // One tall row centres its label; a short row has exactly a line of text under its edge.
            clampedCentered(g, Component.translatable(TAB_KEYS[i]), dx + 3, width - 6,
                    dy + (this.tabRows == 1 ? 4 : 1), selected || hovered ? TITLE : SUBTLE);
        }
    }

    /**
     * The colony's growth stage, right-aligned in the title band on every tab — but only in the room
     * the title leaves, so a long translated block name is never drawn over.
     */
    private void drawStage(GuiGraphicsExtractor g) {
        if (!ClientColonySnapshot.present()) {
            return;
        }
        String title = clamp(this.title.getString(), this.imageWidth - this.titleLabelX - 8);
        int from = this.titleLabelX + this.font.width(title) + 8;
        int room = WIDTH - CONTENT_X - from;
        if (room >= 24) {
            labelRight(g, stageName(ClientColonySnapshot.stage()), from, room, this.titleLabelY, ACCENT);
        }
    }

    private static Component stageName(ColonyStage stage) {
        return Component.translatable("stage.nerocolonies." + stage.key());
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

    // --- Colony ---------------------------------------------------------------

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
        labelRight(g, life, CONTENT_X + CONTENT_WIDTH - lifeWidth, lifeWidth, ROW_1,
                state == LIFE_OK ? GOOD : (state == LIFE_DEGRADED ? WARN : BAD));

        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.morale", morale),
                CONTENT_X, ROW_1, CONTENT_WIDTH - lifeWidth - 6, TITLE);
        hGauge(g, CONTENT_X, GAUGE_1, CONTENT_WIDTH, BAR_H, morale / 100.0F,
                this.menu.workStopped() ? BAD : ACCENT);

        inlineGauge(g, TIGHT_2, Component.translatable("gui.nerocolonies.stat.power"),
                percent(this.menu.energyPermille(), this.menu.energyScale()),
                frac(this.menu.energyPermille(), this.menu.energyScale()), ACCENT);

        drawConstruction(g);
    }

    /**
     * A caption, a segmented gauge and its value on one line. The Colony tab uses it for power so
     * that the line it saves can go to the reason the colony is not building, which needs two.
     */
    private void inlineGauge(GuiGraphicsExtractor g, int dy, Component caption, Component value, float frac,
            int fill) {
        int captionWidth = Math.min(72, this.font.width(caption.getString()));
        int valueWidth = 30;
        clampedLabel(g, caption, CONTENT_X, dy, captionWidth, SUBTLE);
        labelRight(g, value, CONTENT_X + CONTENT_WIDTH - valueWidth, valueWidth, dy, TITLE);
        int gaugeX = CONTENT_X + captionWidth + 6;
        int gaugeWidth = CONTENT_X + CONTENT_WIDTH - valueWidth - 4 - gaugeX;
        segGauge(g, gaugeX, dy + 1, gaugeWidth, SEG_H, frac, fill);
    }

    /**
     * What the colony is building for itself, and whether it has the materials.
     *
     * <p>Both sources again, and for the usual reason: the <b>percentage</b> is a data slot so it
     * moves while you watch, while the structure's <b>name</b> is a translation key from the
     * per-player snapshot, because a name does not fit through a 16-bit slot. The server pushes a
     * fresh snapshot whenever a structure completes, so the name never lags the bar.
     *
     * <p>When nothing is being built the tab says why, on two lines, because "not building" alone
     * left players guessing between "finished", "blocked" and "broken" — and while the colony is
     * founding the reason is the one thing the player has to act on: the Starter Works wait for
     * their materials, and the Needs tab lists them.
     */
    private void drawConstruction(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        String name = snapshot.present() ? snapshot.buildName() : "";
        if (name.isEmpty()) {
            int status = this.menu.buildStatus();
            clampedLabel(g, Component.translatable("gui.nerocolonies.build.idle",
                    this.menu.structuresBuilt()), CONTENT_X, TIGHT_3, CONTENT_WIDTH, SUBTLE);
            String reason = status >= 0 && status < BUILD_STATUS_KEYS.length ? BUILD_STATUS_KEYS[status] : "";
            if (!reason.isEmpty()) {
                wrappedLabel(g, Component.translatable(reason), CONTENT_X, TIGHT_4, CONTENT_WIDTH, 2,
                        status == BUILD_AWAITING_MATERIALS ? WARN : MUTED);
            }
            return;
        }
        boolean supplied = this.menu.buildSupplied();
        clampedLabel(g, Component.translatable(
                supplied ? "gui.nerocolonies.build.active" : "gui.nerocolonies.build.unsupplied",
                Component.translatable(name), this.menu.buildPercent()),
                CONTENT_X, TIGHT_3, CONTENT_WIDTH, supplied ? ACCENT : WARN);
        if (!supplied) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.build.materials_hint"),
                    CONTENT_X, TIGHT_4, CONTENT_WIDTH, 2, MUTED);
        }
    }

    // --- People ---------------------------------------------------------------

    private void drawColonists(GuiGraphicsExtractor g) {
        int population = this.menu.population();
        int capacity = this.menu.housingCapacity();

        int childrenWidth = 0;
        if (ClientColonySnapshot.present()) {
            Component children = Component.translatable("gui.nerocolonies.stat.children",
                    ClientColonySnapshot.life().children());
            childrenWidth = Math.min(CONTENT_WIDTH / 2, this.font.width(children.getString()));
            labelRight(g, children, CONTENT_X + CONTENT_WIDTH - childrenWidth, childrenWidth, ROW_1, SUBTLE);
        }
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.nerans", population, capacity),
                CONTENT_X, ROW_1, CONTENT_WIDTH - (childrenWidth == 0 ? 0 : childrenWidth + 6), TITLE);

        hGauge(g, CONTENT_X, GAUGE_1, CONTENT_WIDTH, BAR_H,
                capacity <= 0 ? 0.0F : Math.min(1.0F, population / (float) capacity),
                population > capacity ? BAD : ACCENT);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.comfort",
                this.menu.comfortPercent()), CONTENT_X, TIGHT_2, CONTENT_WIDTH, SUBTLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.stat.food", this.menu.foodStock()),
                CONTENT_X, TIGHT_3, CONTENT_WIDTH, this.menu.foodStock() > 0 ? SUBTLE : BAD);
        int growth = this.menu.growthStatus();
        String growthKey = growth >= 0 && growth < GROWTH_STATUS_KEYS.length
                ? GROWTH_STATUS_KEYS[growth] : GROWTH_STATUS_KEYS[0];
        boolean paused = growth == GROWTH_LIFE_SUPPORT || growth == GROWTH_FOOD;
        wrappedLabel(g, Component.translatable(growthKey), CONTENT_X, TIGHT_4, CONTENT_WIDTH, 2,
                paused ? WARN : MUTED);
    }

    /** Hint per {@code Population.GrowthStatus} ordinal. */
    private static final String[] GROWTH_STATUS_KEYS = {
        "gui.nerocolonies.beacon.colonists_hint",
        "gui.nerocolonies.growth.life_support",
        "gui.nerocolonies.growth.food",
        "gui.nerocolonies.growth.housing_full",
        "gui.nerocolonies.growth.colony_cap",
        "gui.nerocolonies.growth.server_cap"
    };

    private static final int GROWTH_LIFE_SUPPORT = 1;
    private static final int GROWTH_FOOD = 2;

    /** Reason line per {@code Construction.IdleReason} ordinal; BUILDING has none. */
    private static final String[] BUILD_STATUS_KEYS = {
        "",
        "gui.nerocolonies.build.why.disabled",
        "gui.nerocolonies.build.why.work_stopped",
        "gui.nerocolonies.build.why.life_support",
        "gui.nerocolonies.build.why.no_colonists",
        "gui.nerocolonies.build.why.cap_reached",
        "gui.nerocolonies.build.why.nothing_needed",
        "gui.nerocolonies.build.why.no_site",
        "gui.nerocolonies.build.why.awaiting_materials"
    };

    private static final int BUILD_AWAITING_MATERIALS = 8;

    // --- Needs ----------------------------------------------------------------

    /**
     * Stage and next milestone, then what the colony is short of and how long it will take to find
     * it alone, then the estimate for the building under way. An owner or a Chief can click a need
     * to put it first (the trades that gather it work faster), and click it again to stop.
     */
    private void drawNeeds(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            drawLoading(g);
            return;
        }
        ColonySnapshotPayload.Life life = snapshot.life();
        ColonyStage stage = ClientColonySnapshot.stage();
        Component stageLabel = stageName(stage);
        int stageWidth = Math.min(CONTENT_WIDTH / 2, this.font.width(stageLabel.getString()));
        clampedLabel(g, stageLabel, CONTENT_X, ROW_1, stageWidth, TITLE);
        Component next = null;
        if (life.nextPopulation() > 0 || life.nextStructures() > 0) {
            next = Component.translatable("gui.nerocolonies.needs.next", life.nextPopulation(),
                    life.nextStructures());
        } else if (stage == ColonyStage.FOUNDING) {
            next = Component.translatable("gui.nerocolonies.needs.next_founding");
        }
        if (next != null) {
            int room = CONTENT_WIDTH - stageWidth - 6;
            labelRight(g, next, CONTENT_X + CONTENT_WIDTH - room, room, ROW_1, SUBTLE);
        }

        List<ColonySnapshotPayload.NeedLine> needs = life.needs();
        if (needs.isEmpty()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.needs.none"), CONTENT_X, NEED_Y,
                    CONTENT_WIDTH, 2, SUBTLE);
        } else {
            boolean mayPrioritise = ClientColonySnapshot.may(ColonyPermissions.Action.PLAN);
            boolean anyClickable = false;
            int shown = needsShown(needs.size());
            for (int slot = 0; slot < shown; slot++) {
                ColonySnapshotPayload.NeedLine line = needs.get(this.needsOffset + slot);
                boolean clickable = mayPrioritise && prioritisable(line);
                anyClickable |= clickable;
                drawNeedLine(g, line, NEED_Y + slot * LINE, clickable);
            }
            if (needs.size() > NEED_SLOTS) {
                drawPager(g, needs.size() - this.needsOffset - shown, CONTENT_X,
                        NEED_Y + (NEED_SLOTS - 1) * LINE, CONTENT_WIDTH, false);
            }
            if (anyClickable) {
                clampedLabel(g, Component.translatable("gui.nerocolonies.needs.hint"), CONTENT_X,
                        NEED_HINT_Y, CONTENT_WIDTH, MUTED);
            }
        }

        int solo = life.etaSoloMinutes();
        int help = life.etaHelpMinutes();
        if (solo != 0 || help != 0) {
            clampedLabel(g, solo < 0
                    ? Component.translatable("gui.nerocolonies.needs.build_eta_help", Math.max(0, help))
                    : Component.translatable("gui.nerocolonies.needs.build_eta", solo, Math.max(0, help)),
                    CONTENT_X, LAST_LINE, CONTENT_WIDTH, solo < 0 ? WARN : SUBTLE);
        }
    }

    /**
     * How many need lines are on screen, having first pulled the offset back into range. A list that
     * fits is shown whole; a longer one gives its last line to the pager.
     */
    private int needsShown(int total) {
        if (total <= NEED_SLOTS || this.needsOffset >= total || this.needsOffset < 0) {
            this.needsOffset = 0;
        }
        return total <= NEED_SLOTS ? total : Math.min(NEED_SLOTS - 1, total - this.needsOffset);
    }

    /** Only a single item can be put first; a need for "any food" or any other tag cannot. */
    private static boolean prioritisable(ColonySnapshotPayload.NeedLine line) {
        return !line.label().isEmpty() && line.label().charAt(0) != '#';
    }

    /**
     * One need: its name, how much of it the colony has against how much it wants, and how long the
     * colony will take to find the rest alone. The estimate is spelled out when there is room and
     * shortened when there is not, and if something still has to give it is the name — never the
     * numbers.
     */
    private void drawNeedLine(GuiGraphicsExtractor g, ColonySnapshotPayload.NeedLine line, int dy,
            boolean clickable) {
        boolean alone = line.etaSoloMinutes() >= 0;
        String name = needName(line).getString();
        String count = Component.translatable("gui.nerocolonies.needs.count", line.have(), line.needed())
                .getString();
        int countWidth = this.font.width(count);
        Component eta = alone
                ? Component.translatable("gui.nerocolonies.needs.alone", line.etaSoloMinutes())
                : Component.translatable("gui.nerocolonies.needs.help");
        if (this.font.width(name) + NEED_GAP + countWidth + NEED_GAP + this.font.width(eta.getString())
                > CONTENT_WIDTH) {
            eta = alone
                    ? Component.translatable("gui.nerocolonies.needs.alone_short", line.etaSoloMinutes())
                    : Component.translatable("gui.nerocolonies.needs.help_short");
        }
        int etaWidth = Math.min(NEED_ETA_WIDTH, this.font.width(eta.getString()));
        if (clickable && within(this.hoverX, this.hoverY, CONTENT_X, dy - 1, CONTENT_WIDTH, LINE)) {
            g.fill(this.leftPos + CONTENT_X - 1, this.topPos + dy - 1,
                    this.leftPos + CONTENT_X + CONTENT_WIDTH + 1, this.topPos + dy + LINE - 1, PANEL_EDGE);
        }
        if (line.priority()) {
            g.fill(this.leftPos + CONTENT_X - 4, this.topPos + dy - 1, this.leftPos + CONTENT_X - 2,
                    this.topPos + dy + LINE - 2, ACCENT);
        }
        int nameRoom = Math.max(0, CONTENT_WIDTH - etaWidth - NEED_GAP - countWidth - NEED_GAP);
        String shownName = clamp(name, nameRoom);
        int color = line.priority() ? ACCENT : TITLE;
        clampedLabel(g, Component.literal(shownName), CONTENT_X, dy, nameRoom, color);
        clampedLabel(g, Component.literal(count), CONTENT_X + this.font.width(shownName) + NEED_GAP, dy,
                countWidth, color);
        labelRight(g, eta, CONTENT_X + CONTENT_WIDTH - etaWidth, etaWidth, dy, alone ? SUBTLE : WARN);
    }

    /** The need's translated name, or its id when this client has no translation for the key. */
    private static Component needName(ColonySnapshotPayload.NeedLine line) {
        return Language.getInstance().has(line.nameKey())
                ? Component.translatable(line.nameKey())
                : Component.literal(line.label());
    }

    /**
     * "+N more", or "back to top" on the last page — the one control a list too long for its tab
     * needs. Right-aligned for the roster, which shares its line with names; left-aligned otherwise.
     *
     * @return the width drawn
     */
    private int drawPager(GuiGraphicsExtractor g, int remaining, int dx, int dy, int width,
            boolean alignRight) {
        Component text = pagerLabel(remaining);
        int textWidth = Math.min(width, this.font.width(text.getString()));
        int x = alignRight ? dx + width - textWidth : dx;
        int color = within(this.hoverX, this.hoverY, x, dy - 1, textWidth, LINE) ? ACCENT : SUBTLE;
        clampedLabel(g, text, x, dy, textWidth, color);
        return textWidth;
    }

    private static Component pagerLabel(int remaining) {
        return remaining > 0
                ? Component.translatable("gui.nerocolonies.list.more", remaining)
                : Component.translatable("gui.nerocolonies.list.top");
    }

    private void drawLoading(GuiGraphicsExtractor g) {
        wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.loading"), CONTENT_X, ROW_1,
                CONTENT_WIDTH, 2, SUBTLE);
    }

    // --- Roles ----------------------------------------------------------------

    /**
     * The viewer's own role and the three counts for everybody; for a viewer who may manage members,
     * the editor and the roster the server sent them; for the owner, the cache-sharing switch. See
     * the class notes for what is shown to whom.
     */
    private void drawRoles(GuiGraphicsExtractor g) {
        this.rosterNext = -1;
        this.rosterHits.clear();
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            drawLoading(g);
            return;
        }
        ColonySnapshotPayload.Life life = snapshot.life();
        boolean owner = ClientColonySnapshot.may(ColonyPermissions.Action.OWNER_SETTINGS);
        clampedLabel(g, Component.translatable("gui.nerocolonies.roles.yours",
                roleName(ClientColonySnapshot.role())), CONTENT_X, ROLE_TITLE_Y,
                owner ? CACHE_BUTTON_X - CONTENT_X - 6 : CONTENT_WIDTH, TITLE);
        if (owner) {
            button(g, CACHE_BUTTON_X, CACHE_BUTTON_Y, CACHE_BUTTON_WIDTH, CACHE_BUTTON_HEIGHT,
                    Component.translatable(life.cacheShared()
                            ? "gui.nerocolonies.roles.cache_shared"
                            : "gui.nerocolonies.roles.cache_private"), true);
        }
        clampedLabel(g, Component.translatable("gui.nerocolonies.roles.counts", life.allies(),
                life.chiefs(), life.enemies()), CONTENT_X, ROLE_COUNTS_Y, CONTENT_WIDTH, SUBTLE);

        if (!mayManageMembers()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.roles.hint_member"), CONTENT_X,
                    ROLE_HINT_Y, CONTENT_WIDTH, 3, MUTED);
            return;
        }
        // The EditBox is a real widget and paints itself; the three buttons and the roster are ours.
        button(g, ROLE_PICK_X, ROLE_ROW_Y, ROLE_PICK_WIDTH, ROLE_ROW_HEIGHT,
                Component.translatable("role.nerocolonies." + ROLE_TOKENS[this.roleChoice]), true);
        button(g, ROLE_ADD_X, ROLE_ROW_Y, ROLE_ADD_WIDTH, ROLE_ROW_HEIGHT,
                Component.translatable("gui.nerocolonies.access.add"), true);
        button(g, ROLE_REMOVE_X, ROLE_ROW_Y, ROLE_REMOVE_WIDTH, ROLE_ROW_HEIGHT,
                Component.translatable("gui.nerocolonies.access.remove"), true);
        drawRoster(g, life.members());
    }

    private static Component roleName(ColonyPermissions.Role role) {
        return Component.translatable("role.nerocolonies." + role.key());
    }

    /**
     * The roster, flowed over the lines under the editor: each name with a one-letter role tag,
     * enemies in the warning colour. When it does not fit, the end of the last line becomes a pager.
     */
    private void drawRoster(GuiGraphicsExtractor g, List<ColonySnapshotPayload.Member> members) {
        if (members.isEmpty()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.roles.hint_manage"), CONTENT_X,
                    ROSTER_Y, CONTENT_WIDTH, ROSTER_LINES, MUTED);
            return;
        }
        if (this.rosterOffset >= members.size() || this.rosterOffset < 0) {
            this.rosterOffset = 0;
        }
        int reserve = 0;
        int shown = flowRoster(null, members, this.rosterOffset, 0);
        if (this.rosterOffset > 0 || this.rosterOffset + shown < members.size()) {
            reserve = Math.max(this.font.width(pagerLabel(99).getString()),
                    this.font.width(pagerLabel(0).getString())) + ROSTER_GAP;
            shown = flowRoster(null, members, this.rosterOffset, reserve);
        }
        flowRoster(g, members, this.rosterOffset, reserve);
        if (reserve > 0) {
            int remaining = members.size() - this.rosterOffset - shown;
            int pagerY = ROSTER_Y + (ROSTER_LINES - 1) * LINE;
            this.rosterPagerWidth = drawPager(g, remaining, CONTENT_X, pagerY, CONTENT_WIDTH, true);
            this.rosterPagerX = CONTENT_X + CONTENT_WIDTH - this.rosterPagerWidth;
            this.rosterNext = remaining > 0 ? this.rosterOffset + shown : 0;
        }
    }

    /**
     * Places roster entries from {@code from} across the roster lines, keeping {@code reserve} pixels
     * free at the end of the last one, and draws them when {@code g} is not null.
     *
     * @return how many entries were placed (at least one, so a pager always makes progress)
     */
    private int flowRoster(@Nullable GuiGraphicsExtractor g, List<ColonySnapshotPayload.Member> members,
            int from, int reserve) {
        int line = 0;
        int x = 0;
        int placed = 0;
        for (int i = from; i < members.size(); i++) {
            ColonySnapshotPayload.Member member = members.get(i);
            ColonyPermissions.Role role = ClientColonySnapshot.roleOf(member.role());
            String text = Component.translatable("gui.nerocolonies.roles.entry", member.name(),
                    Component.translatable("gui.nerocolonies.roles.tag." + role.key())).getString();
            int width = this.font.width(text);
            int limit = CONTENT_WIDTH - (line == ROSTER_LINES - 1 ? reserve : 0);
            if (x > 0 && x + width > limit) {
                line++;
                x = 0;
                if (line >= ROSTER_LINES) {
                    break;
                }
                limit = CONTENT_WIDTH - (line == ROSTER_LINES - 1 ? reserve : 0);
            }
            if (width > limit) {
                text = clamp(text, limit);
                width = this.font.width(text);
            }
            if (g != null) {
                int dy = ROSTER_Y + line * LINE;
                int color = role == ColonyPermissions.Role.ENEMY ? BAD
                        : (role == ColonyPermissions.Role.CHIEF ? ACCENT : TITLE);
                clampedLabel(g, Component.literal(text), CONTENT_X + x, dy, width, color);
                this.rosterHits.add(new RosterHit(CONTENT_X + x, dy - 1, width, member.name()));
            }
            x += width + ROSTER_GAP;
            placed++;
        }
        return Math.max(1, placed);
    }

    // --- Jobs, Tech, Trade ------------------------------------------------------

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

    /**
     * While the name field has the keyboard, every key is the field's — otherwise the inventory key
     * would close the screen in the middle of a name that contains its letter. Escape still closes.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.roleField != null && this.roleField.canConsumeInput() && !event.isEscape()) {
            this.roleField.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double localX = event.x() - this.leftPos;
        double localY = event.y() - this.topPos;
        for (int i = 0; i < TAB_COUNT; i++) {
            if (within(localX, localY, this.tabX[i], this.tabTop[i], this.tabWidth[i], this.tabHeight)) {
                this.tab = i;
                updateRoleField();
                if (i == TAB_NEEDS || i == TAB_ROLES) {
                    requestRefresh();
                }
                return true;
            }
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present() && this.menu.hasColony()) {
            if (this.tab == TAB_NEEDS && clickNeeds(localX, localY, snapshot)) {
                return true;
            }
            if (this.tab == TAB_ROLES && clickRoles(event, localX, localY, snapshot)) {
                return true;
            }
            if (this.tab == TAB_TRADE && within(localX, localY, SELL_BUTTON_X, SELL_BUTTON_Y,
                    SELL_BUTTON_WIDTH, SELL_BUTTON_HEIGHT)) {
                Services.NETWORK.sendToServer(ColonyIntentPayload.sell(snapshot.anchor()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * A click on the needs list: the pager turns the page, and a need line asks the server to put
     * that need first — or, if it already is, to stop. The server decides whether the sender's rank
     * allows it and whether the item is on the list at all.
     */
    private boolean clickNeeds(double localX, double localY, ColonySnapshotPayload snapshot) {
        List<ColonySnapshotPayload.NeedLine> needs = snapshot.life().needs();
        if (needs.isEmpty()) {
            return false;
        }
        int shown = needsShown(needs.size());
        if (needs.size() > NEED_SLOTS && within(localX, localY, CONTENT_X,
                NEED_Y + (NEED_SLOTS - 1) * LINE - 1, CONTENT_WIDTH, LINE)) {
            int remaining = needs.size() - this.needsOffset - shown;
            this.needsOffset = remaining > 0 ? this.needsOffset + shown : 0;
            return true;
        }
        if (!ClientColonySnapshot.may(ColonyPermissions.Action.PLAN)) {
            return false;
        }
        for (int slot = 0; slot < shown; slot++) {
            ColonySnapshotPayload.NeedLine line = needs.get(this.needsOffset + slot);
            if (prioritisable(line)
                    && within(localX, localY, CONTENT_X, NEED_Y + slot * LINE - 1, CONTENT_WIDTH, LINE)) {
                Services.NETWORK.sendToServer(ColonyIntentPayload.prioritiseNeed(snapshot.anchor(),
                        line.priority() ? "" : line.label()));
                return true;
            }
        }
        return false;
    }

    /** A click on the Roles tab's own controls. The name field is a widget and handles itself. */
    private boolean clickRoles(MouseButtonEvent event, double localX, double localY,
            ColonySnapshotPayload snapshot) {
        if (ClientColonySnapshot.may(ColonyPermissions.Action.OWNER_SETTINGS)
                && within(localX, localY, CACHE_BUTTON_X, CACHE_BUTTON_Y, CACHE_BUTTON_WIDTH,
                        CACHE_BUTTON_HEIGHT)) {
            Services.NETWORK.sendToServer(ColonyIntentPayload.cacheShare(snapshot.anchor(),
                    !snapshot.life().cacheShared()));
            return true;
        }
        if (!mayManageMembers()) {
            return false;
        }
        if (within(localX, localY, ROLE_PICK_X, ROLE_ROW_Y, ROLE_PICK_WIDTH, ROLE_ROW_HEIGHT)) {
            this.roleChoice = (this.roleChoice + 1) % ROLE_TOKENS.length;
            return true;
        }
        if (within(localX, localY, ROLE_ADD_X, ROLE_ROW_Y, ROLE_ADD_WIDTH, ROLE_ROW_HEIGHT)) {
            sendRole(true, event.hasShiftDown());
            return true;
        }
        if (within(localX, localY, ROLE_REMOVE_X, ROLE_ROW_Y, ROLE_REMOVE_WIDTH, ROLE_ROW_HEIGHT)) {
            sendRole(false, false);
            return true;
        }
        if (this.rosterNext >= 0 && within(localX, localY, this.rosterPagerX,
                ROSTER_Y + (ROSTER_LINES - 1) * LINE - 1, this.rosterPagerWidth, LINE)) {
            this.rosterOffset = this.rosterNext;
            return true;
        }
        for (RosterHit hit : this.rosterHits) {
            if (within(localX, localY, hit.x(), hit.y(), hit.width(), LINE)) {
                // A name the server could not resolve is not one it could match either.
                if (this.roleField != null && !ColonySnapshotPayload.UNKNOWN_NAME.equals(hit.name())) {
                    this.roleField.setValue(hit.name());
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Sends the typed name and the chosen role to the server. The client-side check is only "is there
     * a name?" — whether the sender may do this, whether the target exists and whether anything
     * changes are all decided server-side, and the answer comes back as a message plus a fresh
     * snapshot. Shift with Add on Enemy sends the confirmed form, which is what marking somebody who
     * is currently a member requires.
     */
    private void sendRole(boolean add, boolean confirmed) {
        if (this.roleField == null) {
            return;
        }
        String name = this.roleField.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            return;
        }
        String token = ROLE_TOKENS[this.roleChoice];
        if (add && confirmed && this.roleChoice == ROLE_CHOICE_ENEMY) {
            token = ColonyIntentPayload.ROLE_ENEMY_CONFIRMED;
        }
        Services.NETWORK.sendToServer(add
                ? ColonyIntentPayload.roleAdd(snapshot.anchor(), token, name)
                : ColonyIntentPayload.roleRemove(snapshot.anchor(), token, name));
        this.roleField.setValue("");
    }
}
