package za.co.neroland.nerocolonies.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.client.ClientColonyDefinitions;
import za.co.neroland.nerocolonies.client.ClientColonySnapshot;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyStage;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.content.ResearchNode;
import za.co.neroland.nerocolonies.menu.ColonyBeaconMenu;
import za.co.neroland.nerocolonies.network.ColonyIntentPayload;
import za.co.neroland.nerocolonies.network.ColonySnapshotPayload;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * The colony beacon's screen: a rail of seven tabs down the left, one content card beside it, and
 * under both the player inventory with the food supply and module slots next to it.
 *
 * <h2>Layout, and why it is this layout</h2>
 *
 * <p>The first version put seven tabs in two cramped rows above a five-line content area, with the
 * supply band and the inventory taking the other two thirds of the panel. Almost every tab had more
 * to say than five lines hold, so hints were ellipsised and the needs list hid most of itself behind
 * a "+6 more" pager. This version keeps the same height (a container screen must fit a 240px-tall
 * GUI) and spends it differently: the tabs are a vertical rail, the slots a player fills sit beside
 * the inventory instead of above it, and the content card is twice as tall. Every tab now shows
 * everything it knows, wrapped rather than cut, and the whole needs list is visible at once.
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
 * <h2>Everything is measured, not guessed</h2>
 *
 * <p>The rail's width comes from the <b>font's</b> measurement of the longest tab label at
 * {@link #init} time, and the content card takes the rest; the role editor's buttons are sized from
 * their own labels the same way. A fixed width is a mistranslation waiting to happen. Every label is
 * either wrapped or ellipsised against the card, and a need whose name had to be ellipsised shows
 * the whole line in a tooltip when hovered.
 *
 * <h2>Handing things over</h2>
 *
 * <p>A colony is paid from its shared storage, and a new colony has no depot yet, so the beacon has
 * its own way in: the <b>Give needed items</b> button over the player inventory, on every tab. It
 * asks the server to take whatever the player is carrying that the needs list wants — the server
 * decides what and how much, from its own list — and it lights up only when the player is carrying
 * something that counts. On the Needs tab a need the player can pay towards gets a green pip.
 *
 * <p>A tab that wants attention carries a small coloured pip on the rail — life support failing,
 * arrivals paused, a need the colony cannot meet alone, research that is ready, a full export
 * buffer — so the player can see where to look without opening every tab.
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
    private static final int CARD = 0xFF182230;   // content card fill, a shade above the hull
    private static final int TIP_FILL = 0xF20B1119;

    private static final int WIDTH = 268;
    private static final int HEIGHT = 238;

    /** The tab rail. Its width is measured from the labels in {@link #layout()}. */
    private static final int TAB_COUNT = 7;
    private static final int TAB_HEIGHT = 16;
    private static final int RAIL_X = 6;
    private static final int RAIL_MIN_WIDTH = 46;
    private static final int RAIL_MAX_WIDTH = 80;
    private static final int TAB_TEXT_INSET = 7;
    private static final int TAB_PIP_ROOM = 12;

    /**
     * The content card — every tab draws inside {@code [CONTENT_Y, CONTENT_BOTTOM)} and nothing draws
     * outside it. Seven 16px tabs and a 2px margin either end make the rail exactly as tall.
     */
    private static final int CARD_Y = 21;
    private static final int CARD_BOTTOM = CARD_Y + TAB_COUNT * TAB_HEIGHT + 4;
    private static final int CARD_RIGHT = WIDTH - 6;
    private static final int CARD_PAD_X = 6;
    private static final int CONTENT_Y = CARD_Y + 3;
    private static final int CONTENT_BOTTOM = CARD_BOTTOM - 3;
    private static final int TAB_TOP = CARD_Y + 2;

    /** A caption/value row, and one followed by a gauge. Wrapped text keeps the tighter {@link #LINE}. */
    private static final int ROW = 11;
    private static final int GAUGE_ROW = 20;

    /** Labels over the food and module slots, which sit beside the player inventory. */
    private static final int BAND_LABEL_INSET = 2;
    private static final int BAND_LABEL_DROP = 11;
    private static final int BAND_WIDTH = 3 * SLOT;

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

    /** Needs tab: the next milestone, every need the snapshot carries, a hint, the build estimate. */
    private static final int NEED_Y = CONTENT_Y + 11;
    private static final int NEED_PITCH = 10;
    private static final int NEED_HINT_Y = NEED_Y + ColonySnapshotPayload.MAX_NEEDS * NEED_PITCH + 2;
    private static final int NEED_ETA_Y = CONTENT_BOTTOM - 8;
    private static final int NEED_GAP = 5;
    private static final int NEED_PIP = 6;

    /** Roles tab: role and cache toggle, cache contents, counts, the editor row, the roster. */
    private static final int ROLE_TITLE_Y = CONTENT_Y + 2;
    private static final int CACHE_BUTTON_Y = CONTENT_Y;
    private static final int CACHE_BUTTON_HEIGHT = 12;
    private static final int ROLE_CACHE_Y = CONTENT_Y + 15;
    private static final int ROLE_COUNTS_Y = CONTENT_Y + 25;
    private static final int ROLE_ROW_Y = CONTENT_Y + 37;
    private static final int ROLE_ROW_HEIGHT = 14;
    private static final int ROLE_GAP = 3;
    private static final int ROLE_BUTTON_PADDING = 10;
    private static final int ROLE_FIELD_MIN_WIDTH = 50;
    private static final int ROSTER_Y = CONTENT_Y + 55;
    private static final int ROSTER_LINES = 5;
    private static final int ROSTER_GAP = 8;

    private static final int SELL_BUTTON_Y = CONTENT_Y + GAUGE_ROW + 2 * ROW + 3;
    private static final int SELL_BUTTON_WIDTH = 60;
    private static final int SELL_BUTTON_HEIGHT = 14;

    /** The "Give needed items" button, right-aligned over the player inventory. */
    private static final int GIVE_Y = ColonyBeaconMenu.INVENTORY_Y - 14;
    private static final int GIVE_HEIGHT = 10;
    private static final int GIVE_RIGHT = ColonyBeaconMenu.INVENTORY_X + 9 * SLOT - 2;

    /** The hotbar and the three rows above it: what "carrying" means, here and on the server. */
    private static final int CARRIED_SLOTS = 36;

    /** The food need's name key. Food is eaten from the supply slots, so Give never takes it. */
    private static final String FOOD_NAME_KEY = "need.nerocolonies.food";

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

    private int tab = TAB_COLONY;

    /** Measured geometry, panel-relative. Rebuilt whenever the screen is (re)initialised. */
    private int railWidth = RAIL_MIN_WIDTH;
    private int cardX = RAIL_X + RAIL_MIN_WIDTH;
    private int cx = RAIL_X + RAIL_MIN_WIDTH + CARD_PAD_X;
    private int cw = CARD_RIGHT - CARD_PAD_X - (RAIL_X + RAIL_MIN_WIDTH + CARD_PAD_X);

    private int roleFieldWidth;
    private int rolePickX;
    private int rolePickWidth;
    private int roleAddX;
    private int roleAddWidth;
    private int roleRemoveX;
    private int roleRemoveWidth;
    private int cacheButtonX;
    private int cacheButtonWidth;
    private int giveX;
    private int giveWidth;

    private final Inventory playerInventory;

    @Nullable
    private EditBox roleField;

    /** Which of {@link #ROLE_TOKENS} the editor's Add and Remove apply. */
    private int roleChoice;

    /** First roster entry shown, and where the pager drawn this frame leads (-1: no pager). */
    private int rosterOffset;
    private int rosterNext = -1;
    private int rosterPagerX;
    private int rosterPagerWidth;

    /** Where this frame drew each roster name, so a click can put it in the name field. */
    private final List<RosterHit> rosterHits = new ArrayList<>();

    /** The hint to draw next to the cursor this frame, if anything asked for one. */
    @Nullable
    private Component tip;

    private int refreshTicks;

    /** One drawn roster name and its box, panel-relative. */
    private record RosterHit(int x, int y, int width, String name) {
    }

    public ColonyBeaconScreen(ColonyBeaconMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.playerInventory = playerInventory;
        this.titleLabelX = 8;
        this.titleLabelY = 4;
        this.inventoryLabelX = ColonyBeaconMenu.INVENTORY_X;
        this.inventoryLabelY = ColonyBeaconMenu.INVENTORY_Y - BAND_LABEL_DROP;
    }

    @Override
    protected void init() {
        super.init();
        layout();
        this.roleField = new EditBox(this.font, this.leftPos + this.cx, this.topPos + ROLE_ROW_Y,
                this.roleFieldWidth, ROLE_ROW_HEIGHT,
                Component.translatable("gui.nerocolonies.access.field"));
        this.roleField.setMaxLength(32);
        this.roleField.setBordered(true);
        this.roleField.setHint(Component.translatable("gui.nerocolonies.access.hint"));
        this.addRenderableWidget(this.roleField);
        updateRoleField();
    }

    /**
     * Measures the rail from the longest tab label, gives the content card the rest of the panel,
     * and sizes the role editor's buttons from their own labels. Only a label that cannot fit even
     * the widest rail is ever cut.
     */
    private void layout() {
        int longest = 0;
        for (String key : TAB_KEYS) {
            longest = Math.max(longest, this.font.width(Component.translatable(key).getString()));
        }
        this.railWidth = Math.max(RAIL_MIN_WIDTH,
                Math.min(RAIL_MAX_WIDTH, longest + TAB_TEXT_INSET + TAB_PIP_ROOM));
        this.cardX = RAIL_X + this.railWidth;
        this.cx = this.cardX + CARD_PAD_X;
        this.cw = CARD_RIGHT - CARD_PAD_X - this.cx;

        int pick = 0;
        for (String token : ROLE_TOKENS) {
            pick = Math.max(pick, textWidth("role.nerocolonies." + token));
        }
        this.rolePickWidth = pick + ROLE_BUTTON_PADDING;
        this.roleAddWidth = textWidth("gui.nerocolonies.access.add") + ROLE_BUTTON_PADDING;
        this.roleRemoveWidth = textWidth("gui.nerocolonies.access.remove") + ROLE_BUTTON_PADDING;
        int buttons = this.rolePickWidth + this.roleAddWidth + this.roleRemoveWidth + 3 * ROLE_GAP;
        this.roleFieldWidth = Math.max(ROLE_FIELD_MIN_WIDTH, this.cw - buttons);
        this.rolePickX = this.cx + this.roleFieldWidth + ROLE_GAP;
        this.roleAddX = this.rolePickX + this.rolePickWidth + ROLE_GAP;
        this.roleRemoveX = this.roleAddX + this.roleAddWidth + ROLE_GAP;
        // The last button never leaves the card, whatever the translation did to the others.
        this.roleRemoveWidth = Math.max(8, Math.min(this.roleRemoveWidth,
                this.cx + this.cw - this.roleRemoveX));

        this.cacheButtonWidth = Math.min(this.cw / 2, Math.max(
                textWidth("gui.nerocolonies.roles.cache_shared"),
                textWidth("gui.nerocolonies.roles.cache_private")) + ROLE_BUTTON_PADDING);
        this.cacheButtonX = this.cx + this.cw - this.cacheButtonWidth;

        // The Give button takes what the inventory's own label leaves of that line.
        int labelEnd = this.inventoryLabelX + this.font.width(this.playerInventoryTitle.getString()) + 6;
        this.giveWidth = Math.max(8, Math.min(GIVE_RIGHT - labelEnd,
                textWidth("gui.nerocolonies.give.button") + ROLE_BUTTON_PADDING));
        this.giveX = GIVE_RIGHT - this.giveWidth;
    }

    private int textWidth(String key) {
        return this.font.width(Component.translatable(key).getString());
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

    /**
     * The rail's trough and the content card are painted here, with the slot trays, because this
     * runs <em>under</em> the widget pass: the Roles tab's name field is a real widget, and a card
     * painted from the foreground pass would cover it.
     */
    @Override
    protected void paintTrays(GuiGraphicsExtractor g) {
        fillPanel(g, RAIL_X - 1, CARD_Y, CARD_RIGHT, CARD_BOTTOM, INK);
        fillPanel(g, RAIL_X, CARD_Y + 1, this.cardX, CARD_BOTTOM - 1, TROUGH);
        fillPanel(g, this.cardX, CARD_Y, CARD_RIGHT, CARD_BOTTOM, PANEL_EDGE);
        fillPanel(g, this.cardX + 1, CARD_Y + 1, CARD_RIGHT - 1, CARD_BOTTOM - 1, CARD);

        slotTray(g, ColonyBeaconMenu.SUPPLY_X, ColonyBeaconMenu.SUPPLY_Y,
                ColonyBeaconMenu.SUPPLY_COLUMNS, ColonyBeaconMenu.SUPPLY_ROWS);
        slotTray(g, ColonyBeaconMenu.UPGRADE_X, ColonyBeaconMenu.UPGRADE_Y, 3, 1);
        playerInventoryTray(g, ColonyBeaconMenu.INVENTORY_X, ColonyBeaconMenu.INVENTORY_Y,
                ColonyBeaconMenu.HOTBAR_Y);
    }

    /** {@code fill} in panel-relative coordinates. */
    private void fillPanel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(this.leftPos + x0, this.topPos + y0, this.leftPos + x1, this.topPos + y1, color);
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        this.tip = null;
        drawRail(g);
        drawBand(g);

        if (!this.menu.hasColony()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.unbound"),
                    this.cx, CONTENT_Y, this.cw, 4, SUBTLE);
            drawTip(g);
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
        drawTip(g);
    }

    /**
     * The tab rail: the selected tab is the card's own colour and opens into it, with an accent bar
     * down its outer edge; an idle tab sits in the trough and lights up under the cursor. A tab that
     * wants attention carries a pip (see {@link #tabAlert}).
     */
    private void drawRail(GuiGraphicsExtractor g) {
        for (int i = 0; i < TAB_COUNT; i++) {
            int top = TAB_TOP + i * TAB_HEIGHT;
            boolean selected = i == this.tab;
            boolean hovered = !selected
                    && within(this.hoverX, this.hoverY, RAIL_X, top, this.railWidth, TAB_HEIGHT);
            if (selected) {
                // One pixel past the rail: erase the card's border so the tab opens into it.
                fillPanel(g, RAIL_X, top, this.cardX + 1, top + TAB_HEIGHT, CARD);
                fillPanel(g, RAIL_X, top, this.cardX, top + 1, PANEL_EDGE);
                fillPanel(g, RAIL_X, top + TAB_HEIGHT - 1, this.cardX, top + TAB_HEIGHT, PANEL_EDGE);
                fillPanel(g, RAIL_X, top, RAIL_X + 2, top + TAB_HEIGHT, ACCENT);
            } else if (hovered) {
                fillPanel(g, RAIL_X, top, this.cardX, top + TAB_HEIGHT, DIVIDER);
            }
            clampedLabel(g, Component.translatable(TAB_KEYS[i]), RAIL_X + TAB_TEXT_INSET, top + 4,
                    this.railWidth - TAB_TEXT_INSET - TAB_PIP_ROOM, selected || hovered ? TITLE : SUBTLE);
            int alert = this.menu.hasColony() ? tabAlert(i) : 0;
            if (alert != 0) {
                int px = this.cardX - 7;
                fillPanel(g, px - 1, top + 5, px + 4, top + 10, INK);
                fillPanel(g, px, top + 6, px + 3, top + 9, alert);
            }
        }
    }

    /**
     * The colour of a tab's attention pip, or 0 for none. Derived from the same synced state the tab
     * itself draws, so the pip can never disagree with what the tab says when opened.
     */
    private int tabAlert(int index) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        switch (index) {
            case TAB_COLONY -> {
                int state = this.menu.lifeSupportState();
                if (state > LIFE_DEGRADED || this.menu.workStopped()) {
                    return BAD;
                }
                if (state == LIFE_DEGRADED || this.menu.buildStatus() == BUILD_AWAITING_MATERIALS) {
                    return WARN;
                }
            }
            case TAB_PEOPLE -> {
                int growth = this.menu.growthStatus();
                if (growth == GROWTH_LIFE_SUPPORT || growth == GROWTH_FOOD) {
                    return WARN;
                }
            }
            case TAB_NEEDS -> {
                if (snapshot.present()) {
                    for (ColonySnapshotPayload.NeedLine line : snapshot.life().needs()) {
                        if (line.etaSoloMinutes() < 0) {
                            return WARN;
                        }
                    }
                }
            }
            case TAB_RESEARCH -> {
                if (snapshot.present() && !readyResearch().isEmpty()) {
                    return GOOD;
                }
            }
            case TAB_TRADE -> {
                if (snapshot.present() && snapshot.exportSlots() > 0
                        && snapshot.exportFilled() >= snapshot.exportSlots()) {
                    return WARN;
                }
            }
            default -> {
            }
        }
        return 0;
    }

    /**
     * The colony's growth stage, as a chip at the right of the title band on every tab — but only in
     * the room the title leaves, so a long colony name is never drawn over.
     */
    private void drawStage(GuiGraphicsExtractor g) {
        if (!ClientColonySnapshot.present()) {
            return;
        }
        String title = clamp(this.title.getString(), this.imageWidth - this.titleLabelX - 8);
        int from = this.titleLabelX + this.font.width(title) + 8;
        Component stage = stageName(ClientColonySnapshot.stage());
        int chipWidth = this.font.width(stage.getString()) + 10;
        int x1 = WIDTH - 6;
        int x0 = x1 - chipWidth;
        if (x0 < from) {
            return;
        }
        fillPanel(g, x0 - 1, 2, x1 + 1, 14, INK);
        fillPanel(g, x0, 3, x1, 13, TROUGH);
        fillPanel(g, x0, 12, x1, 13, ACCENT);
        label(g, stage, x0 + 5, this.titleLabelY, ACCENT);
    }

    private static Component stageName(ColonyStage stage) {
        return Component.translatable("stage.nerocolonies." + stage.key());
    }

    /**
     * The food supply and module slots, beside the player inventory. They are on every tab, on
     * purpose: they are the only part of this screen a player puts an item into, and hiding them
     * behind a tab would make feeding a colony a scavenger hunt. Hovering either group says what
     * goes in it — and where construction materials go, which is <b>not</b> here: those are drawn
     * from colony storage, and a player who puts iron in the food slots learns nothing.
     */
    private void drawBand(GuiGraphicsExtractor g) {
        int labelX = ColonyBeaconMenu.SUPPLY_X - BAND_LABEL_INSET;
        int labelWidth = WIDTH - 6 - labelX;
        int supplyLabelY = ColonyBeaconMenu.SUPPLY_Y - BAND_LABEL_DROP;
        int moduleLabelY = ColonyBeaconMenu.UPGRADE_Y - BAND_LABEL_DROP - 1;
        clampedLabel(g, Component.translatable("gui.nerocolonies.beacon.supply"), labelX, supplyLabelY,
                labelWidth, SUBTLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.slots.modules"), labelX, moduleLabelY,
                labelWidth, SUBTLE);

        int carrying = this.menu.hasColony() ? carriedTotal() : 0;
        button(g, this.giveX, GIVE_Y, this.giveWidth, GIVE_HEIGHT,
                Component.translatable("gui.nerocolonies.give.button"), carrying > 0 && mayContribute());

        boolean idle = this.menu.getCarried().isEmpty()
                && (this.hoveredSlot == null || !this.hoveredSlot.hasItem());
        if (!idle) {
            return;
        }
        if (within(this.hoverX, this.hoverY, this.giveX, GIVE_Y, this.giveWidth, GIVE_HEIGHT)) {
            this.tip = Component.translatable(carrying > 0 ? "gui.nerocolonies.give.tip"
                    : "gui.nerocolonies.give.tip_none");
            return;
        }
        int supplyHeight = ColonyBeaconMenu.SUPPLY_ROWS * SLOT + BAND_LABEL_DROP;
        if (within(this.hoverX, this.hoverY, labelX, supplyLabelY, BAND_WIDTH + 2 * BAND_LABEL_INSET,
                supplyHeight)) {
            this.tip = Component.translatable("gui.nerocolonies.beacon.supply_hint");
        } else if (within(this.hoverX, this.hoverY, labelX, moduleLabelY,
                BAND_WIDTH + 2 * BAND_LABEL_INSET, SLOT + BAND_LABEL_DROP + 1)) {
            this.tip = Component.translatable("gui.nerocolonies.slots.modules_hint");
        }
    }

    private static boolean mayContribute() {
        return ClientColonySnapshot.may(ColonyPermissions.Action.CONTRIBUTE);
    }

    /**
     * How many items the player is carrying that would go towards one need: what is in the hotbar
     * and the three rows above it, capped at what the colony is still short of. This is only what the
     * screen <em>offers</em> — the server counts again, from its own list, when Give is pressed.
     */
    private int carried(ColonySnapshotPayload.NeedLine line) {
        int missing = line.needed() - line.have();
        if (missing <= 0 || FOOD_NAME_KEY.equals(line.nameKey())) {
            return 0;
        }
        String label = line.label();
        if (label.isEmpty()) {
            return 0;
        }
        boolean tagged = label.charAt(0) == '#';
        Identifier id = Identifier.tryParse(tagged ? label.substring(1) : label);
        if (id == null) {
            return 0;
        }
        ItemTarget target = tagged
                ? new ItemTarget(Optional.empty(), Optional.of(id), 1)
                : new ItemTarget(Optional.of(id), Optional.empty(), 1);
        int total = 0;
        int slots = Math.min(CARRIED_SLOTS, this.playerInventory.getContainerSize());
        for (int slot = 0; slot < slots && total < missing; slot++) {
            ItemStack stack = this.playerInventory.getItem(slot);
            if (target.matches(stack)) {
                total += stack.getCount();
            }
        }
        return Math.min(total, missing);
    }

    /** {@link #carried} over the whole needs list. */
    private int carriedTotal() {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            return 0;
        }
        int total = 0;
        for (ColonySnapshotPayload.NeedLine line : snapshot.life().needs()) {
            total += carried(line);
        }
        return total;
    }

    /** The hint a draw pass asked for, next to the cursor and kept inside the panel. */
    private void drawTip(GuiGraphicsExtractor g) {
        if (this.tip == null) {
            return;
        }
        List<String> lines = wrap(this.tip.getString(), 150, 6);
        if (lines.isEmpty()) {
            return;
        }
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, this.font.width(line));
        }
        width += 8;
        int height = lines.size() * LINE + 5;
        int x = Math.max(2, Math.min(WIDTH - 2 - width, this.hoverX + 10));
        int y = this.hoverY - height - 4;
        if (y < 2) {
            y = Math.min(HEIGHT - 2 - height, this.hoverY + 14);
        }
        fillPanel(g, x - 1, y - 1, x + width + 1, y + height + 1, INK);
        fillPanel(g, x, y, x + width, y + height, TIP_FILL);
        fillPanel(g, x, y, x + width, y + 1, ACCENT);
        for (int i = 0; i < lines.size(); i++) {
            label(g, Component.literal(lines.get(i)), x + 4, y + 4 + i * LINE, TITLE);
        }
    }

    // --- rows ---------------------------------------------------------------

    /**
     * A caption on the left and its value on the right — the shape almost every line on this screen
     * has. The value keeps its width and the caption is what gives way. Returns the next free y.
     */
    private int row(GuiGraphicsExtractor g, int y, String captionKey, Component value, int valueColor) {
        return rowAt(g, this.cx, this.cw, y, captionKey, value, valueColor);
    }

    /** A {@link #row} in a column of its own: {@code width} pixels from {@code x}. */
    private int rowAt(GuiGraphicsExtractor g, int x, int width, int y, String captionKey, Component value,
            int valueColor) {
        int valueWidth = Math.min(width * 2 / 3, this.font.width(value.getString()));
        labelRight(g, value, x + width - valueWidth, valueWidth, y, valueColor);
        clampedLabel(g, Component.translatable(captionKey), x, y, width - valueWidth - 6, SUBTLE);
        return y + ROW;
    }

    /** A {@link #row} with a gauge under it. */
    private int gauge(GuiGraphicsExtractor g, int y, String captionKey, Component value, int valueColor,
            float frac, int fill, boolean segmented) {
        return gaugeAt(g, this.cx, this.cw, y, captionKey, value, valueColor, frac, fill, segmented);
    }

    /** A {@link #gauge} in a column of its own. */
    private int gaugeAt(GuiGraphicsExtractor g, int x, int width, int y, String captionKey,
            Component value, int valueColor, float frac, int fill, boolean segmented) {
        rowAt(g, x, width, y, captionKey, value, valueColor);
        if (segmented) {
            segGauge(g, x, y + LINE, width, SEG_H, frac, fill);
        } else {
            hGauge(g, x, y + LINE, width, BAR_H, frac, fill);
        }
        return y + GAUGE_ROW;
    }

    /** A small accent heading with a rule out to the right edge, and an optional value on that edge. */
    private int section(GuiGraphicsExtractor g, int y, String titleKey, @Nullable Component value) {
        String title = clamp(Component.translatable(titleKey).getString(), this.cw / 2);
        label(g, Component.literal(title), this.cx, y, ACCENT);
        int ruleFrom = this.cx + this.font.width(title) + 4;
        int ruleTo = this.cx + this.cw;
        if (value != null) {
            int valueWidth = Math.min(this.cw / 3, this.font.width(value.getString()));
            labelRight(g, value, this.cx + this.cw - valueWidth, valueWidth, y, SUBTLE);
            ruleTo -= valueWidth + 4;
        }
        if (ruleTo > ruleFrom) {
            fillPanel(g, ruleFrom, y + 4, ruleTo, y + 5, DIVIDER);
        }
        return y + LINE + 1;
    }

    /** Wraps {@code text} into whatever is left of the card below {@code y}. */
    private int paragraph(GuiGraphicsExtractor g, int y, Component text, int color) {
        // A line is 8px of text on a 10px pitch, so the last one may start 8px above the bottom.
        int lines = Math.max(1, (CONTENT_BOTTOM - y + 2) / LINE);
        return wrappedLabel(g, text, this.cx, y, this.cw, lines, color);
    }

    private static Component ratio(int value, int of) {
        return Component.translatable("gui.nerocolonies.value.ratio", value, of);
    }

    private static Component slots(int value, int of) {
        return Component.translatable("gui.nerocolonies.value.slots", value, of);
    }

    private static Component number(int value) {
        return Component.literal(Integer.toString(value));
    }

    // --- Colony ---------------------------------------------------------------

    private void drawOverview(GuiGraphicsExtractor g) {
        int y = CONTENT_Y;
        int morale = this.menu.morale();
        boolean stopped = this.menu.workStopped();
        // Morale and power side by side: the two gauges a player glances at first.
        int half = (this.cw - 8) / 2;
        gaugeAt(g, this.cx, half, y, "gui.nerocolonies.row.morale",
                Component.translatable("gui.nerocolonies.value.of_hundred", morale),
                stopped ? BAD : TITLE, morale / 100.0F, stopped ? BAD : ACCENT, false);
        y = gaugeAt(g, this.cx + this.cw - half, half, y, "gui.nerocolonies.stat.power",
                percent(this.menu.energyPermille(), this.menu.energyScale()), TITLE,
                frac(this.menu.energyPermille(), this.menu.energyScale()), ACCENT, true);
        if (stopped) {
            clampedLabel(g, Component.translatable("gui.nerocolonies.stat.work_stopped"), this.cx, y,
                    this.cw, BAD);
            y += LINE;
        }

        // Three life-support states, three readings. A colony coasting on reserves must not look the
        // same as one that is fine, or the grace window is invisible until it has already expired.
        int state = this.menu.lifeSupportState();
        String lifeKey = switch (state) {
            case LIFE_OK -> "gui.nerocolonies.value.life_ok";
            case LIFE_DEGRADED -> "gui.nerocolonies.value.life_degraded";
            default -> "gui.nerocolonies.value.life_failed";
        };
        y = row(g, y, "gui.nerocolonies.row.life_support", Component.translatable(lifeKey),
                state == LIFE_OK ? GOOD : (state == LIFE_DEGRADED ? WARN : BAD));
        y = row(g, y, "gui.nerocolonies.row.generators", number(this.menu.generatorCount()), TITLE);
        y = row(g, y, "gui.nerocolonies.row.claim_radius",
                Component.translatable("gui.nerocolonies.value.blocks", this.menu.claimRadius()), TITLE);

        drawConstruction(g, y + 2);
    }

    /**
     * What the colony is building for itself, and whether it has the materials.
     *
     * <p>Both sources again, and for the usual reason: the <b>percentage</b> is a data slot so it
     * moves while you watch, while the structure's <b>name</b> is a translation key from the
     * per-player snapshot, because a name does not fit through a 16-bit slot. The server pushes a
     * fresh snapshot whenever a structure completes, so the name never lags the bar.
     *
     * <p>When nothing is being built the tab says why, in full, because "not building" alone left
     * players guessing between "finished", "blocked" and "broken" — and while the colony is founding
     * the reason is the one thing the player has to act on: the Starter Works wait for their
     * materials, and the Needs tab lists them.
     */
    private void drawConstruction(GuiGraphicsExtractor g, int top) {
        int y = section(g, top, "gui.nerocolonies.section.construction",
                Component.translatable("gui.nerocolonies.value.built", this.menu.structuresBuilt()));
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        String name = snapshot.present() ? snapshot.buildName() : "";
        if (name.isEmpty()) {
            int status = this.menu.buildStatus();
            String reason = status >= 0 && status < BUILD_STATUS_KEYS.length ? BUILD_STATUS_KEYS[status] : "";
            if (reason.isEmpty()) {
                paragraph(g, y, Component.translatable("gui.nerocolonies.build.idle_short"), SUBTLE);
            } else {
                paragraph(g, y, Component.translatable(reason),
                        status == BUILD_AWAITING_MATERIALS ? WARN : SUBTLE);
            }
            return;
        }
        boolean supplied = this.menu.buildSupplied();
        int percent = this.menu.buildPercent();
        Component value = Component.translatable("gui.nerocolonies.value.percent", percent);
        int valueWidth = this.font.width(value.getString());
        labelRight(g, value, this.cx + this.cw - valueWidth, valueWidth, y, TITLE);
        clampedLabel(g, Component.translatable(
                supplied ? "gui.nerocolonies.build.now" : "gui.nerocolonies.build.scrap",
                Component.translatable(name)), this.cx, y, this.cw - valueWidth - 6,
                supplied ? ACCENT : WARN);
        hGauge(g, this.cx, y + LINE, this.cw, BAR_H, percent / 100.0F, supplied ? ACCENT : WARN);
        // With work stopped the banner above already says why the bar is not moving, and takes the
        // line this hint would need.
        if (!supplied && !this.menu.workStopped()) {
            paragraph(g, y + GAUGE_ROW, Component.translatable("gui.nerocolonies.build.materials_hint"),
                    SUBTLE);
        }
    }

    // --- People ---------------------------------------------------------------

    private void drawColonists(GuiGraphicsExtractor g) {
        int y = CONTENT_Y;
        int population = this.menu.population();
        int capacity = this.menu.housingCapacity();
        y = gauge(g, y, "gui.nerocolonies.row.nerans",
                Component.translatable("gui.nerocolonies.value.housed", population, capacity),
                population > capacity ? WARN : TITLE,
                capacity <= 0 ? 0.0F : Math.min(1.0F, population / (float) capacity),
                population > capacity ? WARN : ACCENT, false);
        if (ClientColonySnapshot.present()) {
            y = row(g, y, "gui.nerocolonies.row.children",
                    number(ClientColonySnapshot.life().children()), TITLE);
        }
        y = row(g, y, "gui.nerocolonies.row.comfort",
                Component.translatable("gui.nerocolonies.value.percent", this.menu.comfortPercent()), TITLE);
        int food = this.menu.foodStock();
        y = row(g, y, "gui.nerocolonies.row.food", number(food), food > 0 ? TITLE : BAD);
        if (ClientColonySnapshot.present() && ClientColonySnapshot.life().stuckEvents() > 0) {
            y = row(g, y, "gui.nerocolonies.row.stuck",
                    number(ClientColonySnapshot.life().stuckEvents()), SUBTLE);
        }

        int growth = this.menu.growthStatus();
        String growthKey = growth >= 0 && growth < GROWTH_STATUS_KEYS.length
                ? GROWTH_STATUS_KEYS[growth] : GROWTH_STATUS_KEYS[0];
        boolean paused = growth == GROWTH_LIFE_SUPPORT || growth == GROWTH_FOOD;
        y = section(g, y + 2, "gui.nerocolonies.section.growth", null);
        paragraph(g, y, Component.translatable(growthKey), paused ? WARN : SUBTLE);
    }

    // --- Needs ----------------------------------------------------------------

    /**
     * The next milestone, then everything the colony is short of — the whole list, not a page of it
     * — with how much it has against how much it wants and how long it will take to find the rest
     * alone, then how to hand it over and the estimate for the building under way. An owner or a
     * Chief can click a need to put it first (the trades that gather it work faster), and click it
     * again to stop.
     */
    private void drawNeeds(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            drawLoading(g);
            return;
        }
        ColonySnapshotPayload.Life life = snapshot.life();
        Component goal = null;
        if (life.nextPopulation() > 0 || life.nextStructures() > 0) {
            goal = Component.translatable("gui.nerocolonies.needs.goal", life.nextPopulation(),
                    life.nextStructures());
        } else if (ClientColonySnapshot.stage() == ColonyStage.FOUNDING) {
            goal = Component.translatable("gui.nerocolonies.needs.goal_founding");
        }
        if (goal != null) {
            clampedLabel(g, goal, this.cx, CONTENT_Y, this.cw, SUBTLE);
        }

        List<ColonySnapshotPayload.NeedLine> needs = life.needs();
        if (needs.isEmpty()) {
            wrappedLabel(g, Component.translatable("gui.nerocolonies.needs.none"), this.cx, NEED_Y,
                    this.cw, 3, SUBTLE);
        } else {
            drawNeedList(g, needs);
        }

        int solo = life.etaSoloMinutes();
        int help = life.etaHelpMinutes();
        if (solo != 0 || help != 0) {
            clampedLabel(g, solo < 0
                    ? Component.translatable("gui.nerocolonies.needs.build_eta_help", Math.max(0, help))
                    : Component.translatable("gui.nerocolonies.needs.build_eta", solo, Math.max(0, help)),
                    this.cx, NEED_ETA_Y, this.cw, solo < 0 ? WARN : SUBTLE);
        }
    }

    /**
     * The needs as a three-column table: name, have / wanted, estimate. The column widths are
     * measured from this list, so the numbers line up whatever the names are. The estimate is
     * spelled out when every name still fits beside it and shortened for the whole list when one
     * would not; if a name still has to be cut, hovering its row shows the line in full.
     */
    private void drawNeedList(GuiGraphicsExtractor g, List<ColonySnapshotPayload.NeedLine> needs) {
        int count = Math.min(needs.size(), ColonySnapshotPayload.MAX_NEEDS);
        String[] names = new String[count];
        String[] counts = new String[count];
        int nameWidth = 0;
        int countWidth = 0;
        int longWidth = 0;
        int shortWidth = 0;
        for (int i = 0; i < count; i++) {
            ColonySnapshotPayload.NeedLine line = needs.get(i);
            names[i] = needName(line).getString();
            counts[i] = Component.translatable("gui.nerocolonies.needs.count", line.have(), line.needed())
                    .getString();
            nameWidth = Math.max(nameWidth, this.font.width(names[i]));
            countWidth = Math.max(countWidth, this.font.width(counts[i]));
            longWidth = Math.max(longWidth, this.font.width(needEta(line, false).getString()));
            shortWidth = Math.max(shortWidth, this.font.width(needEta(line, true).getString()));
        }
        int fixed = NEED_PIP + NEED_GAP + countWidth + NEED_GAP;
        boolean brief = nameWidth > this.cw - fixed - longWidth;
        int etaWidth = brief ? shortWidth : longWidth;
        int nameRoom = Math.max(0, this.cw - fixed - etaWidth);
        int countX = this.cx + this.cw - etaWidth - NEED_GAP - countWidth;

        boolean mayPrioritise = ClientColonySnapshot.may(ColonyPermissions.Action.PLAN);
        int carrying = 0;
        for (int i = 0; i < count; i++) {
            ColonySnapshotPayload.NeedLine line = needs.get(i);
            int y = NEED_Y + i * NEED_PITCH;
            boolean alone = line.etaSoloMinutes() >= 0;
            boolean clickable = mayPrioritise && prioritisable(line);
            int inHand = carried(line);
            carrying += inHand;
            boolean hovered = within(this.hoverX, this.hoverY, this.cx - 2, y - 1, this.cw + 4, NEED_PITCH);
            if (hovered && clickable) {
                fillPanel(g, this.cx - 2, y - 1, this.cx + this.cw + 2, y + NEED_PITCH - 1, PANEL_EDGE);
            }
            // Green: you are carrying some of this. Otherwise cyan for the need put first, amber
            // for one the colony cannot meet alone.
            int pip = inHand > 0 ? GOOD : (line.priority() ? ACCENT : (alone ? SUBTLE : WARN));
            fillPanel(g, this.cx, y + 2, this.cx + 3, y + 5, pip);

            int color = line.priority() ? ACCENT : TITLE;
            clampedLabel(g, Component.literal(names[i]), this.cx + NEED_PIP, y, nameRoom, color);
            labelRight(g, Component.literal(counts[i]), countX, countWidth, y,
                    line.have() >= line.needed() ? GOOD : color);
            labelRight(g, needEta(line, brief), this.cx + this.cw - etaWidth, etaWidth, y,
                    alone ? SUBTLE : WARN);
            if (hovered) {
                StringBuilder text = new StringBuilder(Component.translatable("gui.nerocolonies.needs.tip",
                        names[i], counts[i], needEta(line, false)).getString());
                if (inHand > 0) {
                    text.append(' ').append(Component.translatable("gui.nerocolonies.needs.tip_carrying",
                            inHand).getString());
                }
                if (clickable) {
                    text.append(' ').append(Component.translatable(line.priority()
                            ? "gui.nerocolonies.needs.tip_unfirst"
                            : "gui.nerocolonies.needs.tip_first").getString());
                }
                this.tip = Component.literal(text.toString());
            }
        }
        // The one line that says how to pay: what you are carrying, or where to bring it.
        if (mayContribute()) {
            clampedLabel(g, carrying > 0
                    ? Component.translatable("gui.nerocolonies.needs.carrying", carrying)
                    : Component.translatable("gui.nerocolonies.needs.bring"),
                    this.cx, NEED_HINT_Y, this.cw, carrying > 0 ? GOOD : MUTED);
        }
    }

    private static Component needEta(ColonySnapshotPayload.NeedLine line, boolean brief) {
        if (line.etaSoloMinutes() >= 0) {
            return Component.translatable(brief ? "gui.nerocolonies.needs.alone_short"
                    : "gui.nerocolonies.needs.alone", line.etaSoloMinutes());
        }
        return Component.translatable(brief ? "gui.nerocolonies.needs.help_short"
                : "gui.nerocolonies.needs.help");
    }

    /**
     * Any need on the list can be put first: a single item, or an "any ..." need that stands for a
     * tag. The label goes back to the server exactly as it came, {@code #} and all.
     */
    private static boolean prioritisable(ColonySnapshotPayload.NeedLine line) {
        return !line.label().isEmpty();
    }

    /**
     * The need's translated name. An item always has one. A tag has one only when some mod ships a
     * translation for it, so a tag without one is spelled out from its own id — {@code #c:ingots/iron}
     * reads "Any iron ingots" — rather than shown as a raw id.
     */
    private static Component needName(ColonySnapshotPayload.NeedLine line) {
        if (Language.getInstance().has(line.nameKey())) {
            return Component.translatable(line.nameKey());
        }
        String label = line.label();
        if (label.length() > 1 && label.charAt(0) == '#') {
            String path = label.substring(label.indexOf(':') + 1);
            if (path.startsWith("#")) {
                path = path.substring(1);
            }
            String[] parts = path.split("/");
            StringBuilder words = new StringBuilder();
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i].isEmpty()) {
                    continue;
                }
                if (words.length() > 0) {
                    words.append(' ');
                }
                words.append(parts[i].replace('_', ' '));
            }
            if (words.length() > 0) {
                return Component.translatable("gui.nerocolonies.needs.any", words.toString());
            }
        }
        return Component.literal(label);
    }

    /**
     * "+N more", or "back to top" on the last page — the one control a list too long for its tab
     * needs. Only the roster uses it now: a colony can have sixty-four members and five lines.
     *
     * @return the width drawn
     */
    private int drawPager(GuiGraphicsExtractor g, int remaining, int dx, int dy, int width) {
        Component text = pagerLabel(remaining);
        int textWidth = Math.min(width, this.font.width(text.getString()));
        int x = dx + width - textWidth;
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
        wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.loading"), this.cx, CONTENT_Y,
                this.cw, 3, SUBTLE);
    }

    // --- Roles ----------------------------------------------------------------

    /**
     * The viewer's own role, the Gratitude Cache and the three counts for everybody; for a viewer who
     * may manage members, the editor and the roster the server sent them; for the owner, the
     * cache-sharing switch. See the class notes for what is shown to whom.
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
                roleName(ClientColonySnapshot.role())), this.cx, ROLE_TITLE_Y,
                owner ? this.cacheButtonX - this.cx - 6 : this.cw, TITLE);
        if (owner) {
            button(g, this.cacheButtonX, CACHE_BUTTON_Y, this.cacheButtonWidth, CACHE_BUTTON_HEIGHT,
                    Component.translatable(life.cacheShared()
                            ? "gui.nerocolonies.roles.cache_shared"
                            : "gui.nerocolonies.roles.cache_private"), true);
        }
        row(g, ROLE_CACHE_Y, "gui.nerocolonies.row.cache", slots(life.cacheFilled(), life.cacheSlots()),
                life.cacheSlots() > 0 && life.cacheFilled() >= life.cacheSlots() ? WARN : TITLE);
        clampedLabel(g, Component.translatable("gui.nerocolonies.roles.counts", life.allies(),
                life.chiefs(), life.enemies()), this.cx, ROLE_COUNTS_Y, this.cw, SUBTLE);

        if (!mayManageMembers()) {
            paragraph(g, ROLE_ROW_Y, Component.translatable("gui.nerocolonies.roles.hint_member"), MUTED);
            return;
        }
        // The EditBox is a real widget and paints itself; the three buttons and the roster are ours.
        button(g, this.rolePickX, ROLE_ROW_Y, this.rolePickWidth, ROLE_ROW_HEIGHT,
                Component.translatable("role.nerocolonies." + ROLE_TOKENS[this.roleChoice]), true);
        button(g, this.roleAddX, ROLE_ROW_Y, this.roleAddWidth, ROLE_ROW_HEIGHT,
                Component.translatable("gui.nerocolonies.access.add"), true);
        button(g, this.roleRemoveX, ROLE_ROW_Y, this.roleRemoveWidth, ROLE_ROW_HEIGHT,
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
            paragraph(g, ROSTER_Y, Component.translatable("gui.nerocolonies.roles.hint_manage"), MUTED);
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
            this.rosterPagerWidth = drawPager(g, remaining, this.cx, pagerY, this.cw);
            this.rosterPagerX = this.cx + this.cw - this.rosterPagerWidth;
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
            int limit = this.cw - (line == ROSTER_LINES - 1 ? reserve : 0);
            if (x > 0 && x + width > limit) {
                line++;
                x = 0;
                if (line >= ROSTER_LINES) {
                    break;
                }
                limit = this.cw - (line == ROSTER_LINES - 1 ? reserve : 0);
            }
            if (width > limit) {
                text = clamp(text, limit);
                width = this.font.width(text);
            }
            if (g != null) {
                int dy = ROSTER_Y + line * LINE;
                int color = role == ColonyPermissions.Role.ENEMY ? BAD
                        : (role == ColonyPermissions.Role.CHIEF ? ACCENT : TITLE);
                clampedLabel(g, Component.literal(text), this.cx + x, dy, width, color);
                this.rosterHits.add(new RosterHit(this.cx + x, dy - 1, width, member.name()));
            }
            x += width + ROSTER_GAP;
            placed++;
        }
        return Math.max(1, placed);
    }

    // --- Jobs, Tech, Trade ------------------------------------------------------

    private void drawJobs(GuiGraphicsExtractor g) {
        int y = CONTENT_Y;
        if (this.menu.workStopped()) {
            clampedLabel(g, Component.translatable("gui.nerocolonies.stat.work_stopped"),
                    this.cx, y, this.cw, BAD);
            y = wrappedLabel(g, Component.translatable("gui.nerocolonies.beacon.work_stopped_hint"),
                    this.cx, y + LINE, this.cw, 2, SUBTLE) + 2;
        }
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            paragraph(g, y, Component.translatable("gui.nerocolonies.beacon.jobs_hint"), SUBTLE);
            return;
        }
        y = gauge(g, y, "gui.nerocolonies.row.job_slots",
                ratio(snapshot.jobsActive(), snapshot.jobSlots()), TITLE,
                snapshot.jobSlots() <= 0 ? 0.0F
                        : Math.min(1.0F, snapshot.jobsActive() / (float) snapshot.jobSlots()),
                ACCENT, false);
        y = row(g, y, "gui.nerocolonies.row.stations", number(snapshot.jobStations()), TITLE);
        boolean full = snapshot.storageSlots() > 0 && snapshot.storageUsed() >= snapshot.storageSlots();
        y = gauge(g, y + 2, "gui.nerocolonies.row.storage",
                slots(snapshot.storageUsed(), snapshot.storageSlots()), full ? WARN : TITLE,
                snapshot.storageSlots() <= 0 ? 0.0F
                        : Math.min(1.0F, snapshot.storageUsed() / (float) snapshot.storageSlots()),
                full ? WARN : ACCENT, false);
        paragraph(g, y + 2, Component.translatable("gui.nerocolonies.beacon.jobs_hint"), MUTED);
    }

    private void drawResearch(GuiGraphicsExtractor g) {
        int y = CONTENT_Y;
        int unlocked = this.menu.researchCount();
        int total = Math.max(unlocked, ClientColonyDefinitions.research().size());
        y = gauge(g, y, "gui.nerocolonies.row.research", ratio(unlocked, total), TITLE,
                total <= 0 ? 0.0F : Math.min(1.0F, unlocked / (float) total), ACCENT, false);
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (snapshot.present()) {
            y = row(g, y, "gui.nerocolonies.row.job_slots_total", number(snapshot.jobSlots()), TITLE);
            List<ResearchNode> ready = readyResearch();
            y = row(g, y, "gui.nerocolonies.row.research_ready", number(ready.size()),
                    ready.isEmpty() ? TITLE : GOOD);
            if (!ready.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (ResearchNode node : ready) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(Component.translatable(node.titleKey()).getString());
                }
                y = wrappedLabel(g, Component.literal(names.toString()), this.cx, y, this.cw, 3, GOOD);
            }
        }
        paragraph(g, y + 2, Component.translatable("gui.nerocolonies.beacon.research_hint"), MUTED);
    }

    /**
     * Nodes the colony could research right now: not yet unlocked, every prerequisite unlocked, and
     * affordable as of the last snapshot. The same three tests the research station's screen applies.
     */
    private static List<ResearchNode> readyResearch() {
        List<ResearchNode> ready = new ArrayList<>();
        for (ResearchNode node : ClientColonyDefinitions.research()) {
            if (ClientColonySnapshot.isUnlocked(node.id()) || !ClientColonySnapshot.isAffordable(node.id())) {
                continue;
            }
            boolean available = true;
            for (var required : node.requires()) {
                if (!ClientColonySnapshot.isUnlocked(required)) {
                    available = false;
                    break;
                }
            }
            if (available) {
                ready.add(node);
            }
        }
        return ready;
    }

    private void drawExports(GuiGraphicsExtractor g) {
        ColonySnapshotPayload snapshot = ClientColonySnapshot.get();
        if (!snapshot.present()) {
            int y = row(g, CONTENT_Y, "gui.nerocolonies.row.outposts", number(this.menu.outpostCount()),
                    TITLE);
            paragraph(g, y + 2, Component.translatable("gui.nerocolonies.beacon.exports_hint"), SUBTLE);
            return;
        }
        boolean full = snapshot.exportSlots() > 0 && snapshot.exportFilled() >= snapshot.exportSlots();
        int y = gauge(g, CONTENT_Y, "gui.nerocolonies.row.export_buffer",
                slots(snapshot.exportFilled(), snapshot.exportSlots()), full ? WARN : TITLE,
                snapshot.exportSlots() <= 0 ? 0.0F
                        : Math.min(1.0F, snapshot.exportFilled() / (float) snapshot.exportSlots()),
                full ? WARN : ACCENT, false);
        y = row(g, y, "gui.nerocolonies.row.export_value",
                Component.translatable("gui.nerocolonies.export.value", snapshot.exportValue()),
                snapshot.exportValue() > 0L ? GOOD : TITLE);
        row(g, y, "gui.nerocolonies.row.outposts", number(this.menu.outpostCount()), TITLE);

        button(g, this.cx, SELL_BUTTON_Y, SELL_BUTTON_WIDTH, SELL_BUTTON_HEIGHT,
                Component.translatable("gui.nerocolonies.export.sell"),
                snapshot.marketAvailable() && snapshot.exportValue() > 0L);
        paragraph(g, SELL_BUTTON_Y + SELL_BUTTON_HEIGHT + 5, Component.translatable(
                snapshot.marketAvailable() ? "gui.nerocolonies.beacon.exports_hint"
                        : "gui.nerocolonies.export.no_market_hint"),
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
            if (within(localX, localY, RAIL_X, TAB_TOP + i * TAB_HEIGHT, this.railWidth, TAB_HEIGHT)) {
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
            if (within(localX, localY, this.giveX, GIVE_Y, this.giveWidth, GIVE_HEIGHT)) {
                // Offered only when there is something to give; the server decides what it takes.
                if (mayContribute() && carriedTotal() > 0) {
                    Services.NETWORK.sendToServer(ColonyIntentPayload.deliver(snapshot.anchor()));
                }
                return true;
            }
            if (this.tab == TAB_NEEDS && clickNeeds(localX, localY, snapshot)) {
                return true;
            }
            if (this.tab == TAB_ROLES && clickRoles(event, localX, localY, snapshot)) {
                return true;
            }
            if (this.tab == TAB_TRADE && within(localX, localY, this.cx, SELL_BUTTON_Y,
                    SELL_BUTTON_WIDTH, SELL_BUTTON_HEIGHT)) {
                Services.NETWORK.sendToServer(ColonyIntentPayload.sell(snapshot.anchor()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * A click on a need line asks the server to put that need first — or, if it already is, to stop.
     * The server decides whether the sender's rank allows it and whether the item is on the list at
     * all.
     */
    private boolean clickNeeds(double localX, double localY, ColonySnapshotPayload snapshot) {
        if (!ClientColonySnapshot.may(ColonyPermissions.Action.PLAN)) {
            return false;
        }
        List<ColonySnapshotPayload.NeedLine> needs = snapshot.life().needs();
        int count = Math.min(needs.size(), ColonySnapshotPayload.MAX_NEEDS);
        for (int i = 0; i < count; i++) {
            ColonySnapshotPayload.NeedLine line = needs.get(i);
            if (prioritisable(line) && within(localX, localY, this.cx - 2, NEED_Y + i * NEED_PITCH - 1,
                    this.cw + 4, NEED_PITCH)) {
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
                && within(localX, localY, this.cacheButtonX, CACHE_BUTTON_Y, this.cacheButtonWidth,
                        CACHE_BUTTON_HEIGHT)) {
            Services.NETWORK.sendToServer(ColonyIntentPayload.cacheShare(snapshot.anchor(),
                    !snapshot.life().cacheShared()));
            return true;
        }
        if (!mayManageMembers()) {
            return false;
        }
        if (within(localX, localY, this.rolePickX, ROLE_ROW_Y, this.rolePickWidth, ROLE_ROW_HEIGHT)) {
            this.roleChoice = (this.roleChoice + 1) % ROLE_TOKENS.length;
            return true;
        }
        if (within(localX, localY, this.roleAddX, ROLE_ROW_Y, this.roleAddWidth, ROLE_ROW_HEIGHT)) {
            sendRole(true, event.hasShiftDown());
            return true;
        }
        if (within(localX, localY, this.roleRemoveX, ROLE_ROW_Y, this.roleRemoveWidth, ROLE_ROW_HEIGHT)) {
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
