package za.co.neroland.nerocolonies.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.client.ClientColonyDefinitions;
import za.co.neroland.nerocolonies.client.ClientColonySnapshot;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ResearchNode;
import za.co.neroland.nerocolonies.menu.ResearchMenu;
import za.co.neroland.nerocolonies.network.ColonyIntentPayload;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * The research screen: the colony's research graph, and one button that spends.
 *
 * <h2>The graph is drawn as an indented, branch-grouped list</h2>
 *
 * <p>Not as a free-form node canvas, and that is a considered choice rather than a shortcut. The
 * graph comes from datapacks, so its size and shape are unknown at build time; any real layout engine
 * would have to lay out an arbitrary DAG inside a fixed window and would produce something unreadable
 * for a pack with forty nodes. Grouping by branch and indenting by prerequisite depth shows exactly
 * the two things a player needs — what leads to what, and what is reachable now — at any pack size,
 * and it pages instead of overflowing.
 *
 * <h2>It renders synced state and decides nothing</h2>
 *
 * <p>Node states come from {@link ClientColonySnapshot}: researched, affordable and (derived from the
 * unlocked set) available. Pressing Research sends a {@link ColonyIntentPayload} and nothing else —
 * the server re-checks prerequisites, cost and permission before anything happens, and answers with a
 * fresh snapshot either way. A client that draws a node as affordable when it is not gets a refusal
 * message, not a free unlock.
 */
public class ResearchScreen extends NeroColoniesScreen<ResearchMenu> {

    private static final int ACCENT = 0xFF9F7FE0; // research violet
    private static final int WIDTH = ResearchMenu.WIDTH;
    private static final int HEIGHT = ResearchMenu.HEIGHT;

    private static final int LIST_X = 8;
    private static final int LIST_Y = 30;
    private static final int LIST_WIDTH = 140;
    private static final int ROW_HEIGHT = 11;
    private static final int ROWS_PER_PAGE = 9;

    private static final int PANEL_X = 154;
    private static final int PANEL_WIDTH = 94;

    private static final int BUTTON_X = PANEL_X;
    private static final int BUTTON_Y = 112;
    private static final int BUTTON_WIDTH = PANEL_WIDTH;
    private static final int BUTTON_HEIGHT = 14;

    private static final int PAGE_BUTTON_Y = LIST_Y + ROWS_PER_PAGE * ROW_HEIGHT + 2;
    private static final int PAGE_BUTTON_WIDTH = 18;
    private static final int PAGE_BUTTON_HEIGHT = 11;

    /** Row colours by state. */
    private static final int DONE = 0xFF5BE08A;
    private static final int READY = 0xFFD6ECFF;
    private static final int UNAFFORDABLE = 0xFF8DA0B4;
    private static final int LOCKED = 0xFF5B6472;

    private int page;

    @Nullable
    private Identifier selected;

    public ResearchScreen(ResearchMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, ACCENT, WIDTH, HEIGHT);
        this.titleLabelX = 8;
        this.titleLabelY = 5;
        this.inventoryLabelX = ResearchMenu.INVENTORY_X;
        this.inventoryLabelY = ResearchMenu.INVENTORY_Y - 11;
    }

    // --- model --------------------------------------------------------------

    /** One drawable row: a node plus how deeply nested it is under its prerequisites. */
    private record Row(ResearchNode node, int depth) {
    }

    /**
     * Builds the display order: nodes grouped by branch, each one indented one step further than its
     * deepest prerequisite. The server already sends them in dependency order, so a single pass is
     * enough to know every node's depth before it is used.
     */
    private List<Row> rows() {
        List<ResearchNode> nodes = ClientColonyDefinitions.research();
        java.util.Map<Identifier, Integer> depths = new java.util.HashMap<>();
        java.util.Map<String, List<Row>> byBranch = new java.util.LinkedHashMap<>();
        for (ResearchNode node : nodes) {
            int depth = 0;
            for (Identifier prerequisite : node.requires()) {
                depth = Math.max(depth, depths.getOrDefault(prerequisite, 0) + 1);
            }
            depths.put(node.id(), depth);
            byBranch.computeIfAbsent(node.branch(), key -> new ArrayList<>()).add(new Row(node, depth));
        }
        List<Row> out = new ArrayList<>(nodes.size());
        byBranch.values().forEach(out::addAll);
        return out;
    }

    private boolean researched(ResearchNode node) {
        return ClientColonySnapshot.isUnlocked(node.id());
    }

    private boolean available(ResearchNode node) {
        for (Identifier prerequisite : node.requires()) {
            if (!ClientColonySnapshot.isUnlocked(prerequisite)) {
                return false;
            }
        }
        return !researched(node);
    }

    @Nullable
    private ResearchNode selectedNode() {
        return this.selected == null ? null : ClientColonyDefinitions.research(this.selected);
    }

    // --- drawing ------------------------------------------------------------

    @Override
    protected void extractForeground(GuiGraphicsExtractor g) {
        if (!this.menu.bound()) {
            label(g, Component.translatable("gui.nerocolonies.research.unclaimed"), LIST_X, 18, BAD);
            return;
        }
        label(g, Component.translatable("gui.nerocolonies.research.header",
                this.menu.unlockedCount(), this.menu.jobSlots()), LIST_X, 18, SUBTLE);

        List<Row> rows = rows();
        if (rows.isEmpty()) {
            label(g, Component.translatable("gui.nerocolonies.research.no_content"), LIST_X, LIST_Y, SUBTLE);
        } else {
            drawList(g, rows);
            drawPager(g, rows.size());
        }
        drawDetail(g);
    }

    private void drawList(GuiGraphicsExtractor g, List<Row> rows) {
        int first = this.page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE && first + i < rows.size(); i++) {
            Row row = rows.get(first + i);
            int dy = LIST_Y + i * ROW_HEIGHT;
            boolean isSelected = row.node().id().equals(this.selected);
            if (isSelected) {
                int x = this.leftPos + LIST_X - 1;
                int y = this.topPos + dy - 1;
                g.fill(x, y, x + LIST_WIDTH + 2, y + ROW_HEIGHT, TROUGH);
            }
            int indent = Math.min(row.depth(), 4) * 6;
            label(g, Component.translatable(row.node().titleKey()), LIST_X + indent, dy, colourOf(row.node()));
        }
    }

    private int colourOf(ResearchNode node) {
        if (researched(node)) {
            return DONE;
        }
        if (!available(node)) {
            return LOCKED;
        }
        return ClientColonySnapshot.isAffordable(node.id()) ? READY : UNAFFORDABLE;
    }

    private void drawPager(GuiGraphicsExtractor g, int total) {
        int pages = Math.max(1, (total + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (pages <= 1) {
            return;
        }
        button(g, LIST_X, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT,
                Component.literal("<"), this.page > 0);
        button(g, LIST_X + PAGE_BUTTON_WIDTH + 2, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT,
                Component.literal(">"), this.page < pages - 1);
        label(g, Component.translatable("gui.nerocolonies.research.page", this.page + 1, pages),
                LIST_X + PAGE_BUTTON_WIDTH * 2 + 8, PAGE_BUTTON_Y + 2, SUBTLE);
    }

    private void drawDetail(GuiGraphicsExtractor g) {
        ResearchNode node = selectedNode();
        if (node == null) {
            label(g, Component.translatable("gui.nerocolonies.research.select"), PANEL_X, LIST_Y, SUBTLE);
            return;
        }
        label(g, Component.translatable(node.titleKey()), PANEL_X, LIST_Y, TITLE);
        label(g, Component.translatable("research.nerocolonies.branch." + node.branch()),
                PANEL_X, LIST_Y + 11, SUBTLE);

        int dy = LIST_Y + 26;
        label(g, Component.translatable("gui.nerocolonies.research.cost"), PANEL_X, dy, SUBTLE);
        dy += 10;
        if (node.cost().isEmpty()) {
            label(g, Component.translatable("gui.nerocolonies.research.cost_none"), PANEL_X, dy, SUBTLE);
            dy += 10;
        } else {
            for (ItemAmount amount : node.cost()) {
                label(g, Component.translatable("gui.nerocolonies.research.cost_line",
                        amount.count(), itemName(amount)), PANEL_X, dy, SUBTLE);
                dy += 10;
            }
        }
        label(g, stateLine(node), PANEL_X, dy + 2, colourOf(node));

        boolean enabled = available(node) && ClientColonySnapshot.isAffordable(node.id())
                && this.menu.powered();
        button(g, BUTTON_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("gui.nerocolonies.research.unlock"), enabled);
        if (!this.menu.powered()) {
            label(g, Component.translatable("gui.nerocolonies.research.needs_power"),
                    PANEL_X, BUTTON_Y + BUTTON_HEIGHT + 3, BAD);
        }
    }

    private Component stateLine(ResearchNode node) {
        if (researched(node)) {
            return Component.translatable("gui.nerocolonies.research.state_done");
        }
        if (!available(node)) {
            return Component.translatable("gui.nerocolonies.research.state_locked");
        }
        return Component.translatable(ClientColonySnapshot.isAffordable(node.id())
                ? "gui.nerocolonies.research.state_ready"
                : "gui.nerocolonies.research.state_unaffordable");
    }

    /**
     * The cost item's own display name, so a pack naming another mod's item reads correctly. The
     * name is asked of a stack rather than the item, because that is where it lives in 26.x, and an
     * unregistered id falls back to printing itself rather than showing "Air".
     */
    private Component itemName(ItemAmount amount) {
        if (!BuiltInRegistries.ITEM.containsKey(amount.item())) {
            return Component.literal(amount.item().toString());
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(amount.item())).getHoverName();
    }

    /** A flat panel button. Drawn by hand so there is no widget state to keep in step with the model. */
    private void button(GuiGraphicsExtractor g, int dx, int dy, int width, int height, Component text,
            boolean enabled) {
        int x = this.leftPos + dx;
        int y = this.topPos + dy;
        g.fill(x - 1, y - 1, x + width + 1, y + height + 1, INK);
        g.fill(x, y, x + width, y + height, enabled ? TROUGH : PANEL);
        g.fill(x, y, x + width, y + 1, enabled ? ACCENT : PANEL_EDGE);
        labelCentered(g, text, dx, width, dy + (height - 8) / 2, enabled ? TITLE : LOCKED);
    }

    // --- input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double localX = event.x() - this.leftPos;
        double localY = event.y() - this.topPos;
        List<Row> rows = rows();

        if (within(localX, localY, LIST_X, LIST_Y, LIST_WIDTH, ROWS_PER_PAGE * ROW_HEIGHT)) {
            int index = this.page * ROWS_PER_PAGE + (int) ((localY - LIST_Y) / ROW_HEIGHT);
            if (index >= 0 && index < rows.size()) {
                this.selected = rows.get(index).node().id();
                return true;
            }
        }
        int pages = Math.max(1, (rows.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (within(localX, localY, LIST_X, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                && this.page > 0) {
            this.page--;
            return true;
        }
        if (within(localX, localY, LIST_X + PAGE_BUTTON_WIDTH + 2, PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH,
                PAGE_BUTTON_HEIGHT) && this.page < pages - 1) {
            this.page++;
            return true;
        }
        if (within(localX, localY, BUTTON_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            requestUnlock();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static boolean within(double x, double y, int dx, int dy, int width, int height) {
        return x >= dx && x < dx + width && y >= dy && y < dy + height;
    }

    /**
     * Asks the server to unlock the selected node. The client-side guards below only avoid sending an
     * obviously pointless packet; the decision itself is entirely the server's.
     */
    private void requestUnlock() {
        ResearchNode node = selectedNode();
        if (node == null || researched(node) || !available(node)) {
            return;
        }
        if (!ClientColonySnapshot.present()) {
            return;
        }
        Services.NETWORK.sendToServer(
                ColonyIntentPayload.research(ClientColonySnapshot.get().anchor(), node.id()));
    }
}
