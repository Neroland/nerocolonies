package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;

import za.co.neroland.nerocolonies.content.Blueprint;

/**
 * Where everything in the gallery goes, worked out before a single block is placed.
 *
 * <p>Pure geometry: no world, no server. All coordinates are offsets from the gallery centre (the
 * sandbox colony's beacon), X east and Z south.
 *
 * <h2>The shape</h2>
 *
 * <pre>
 *            north ring (showpieces, facing in)
 *   west    avenue  avenue  avenue ...            east
 *   ring    --------- the court ----------        ring
 *           avenue  avenue  avenue ...
 *            south ring (showpieces, facing in)
 * </pre>
 *
 * <p>Ordinary buildings are sorted by stage, then category, then id, and packed into avenues: rows
 * running west to east, the earliest stages nearest the court, each row's fronts lined up on its
 * street and turned to face the court. Showpieces ({@link Blueprint.Category#LANDMARK} or
 * {@link ColonyStage#METROPOLIS}) are dealt round the four sides of the outer ring. The avenue
 * width is whichever one gives the smallest square.
 */
final class GalleryLayout {

    /** Space between two buildings in the same avenue. */
    static final int GAP = 4;

    /** Width of the street between two avenues, and between the avenues and the ring. */
    static final int STREET = 6;

    /** Walkway left between the outermost building and the edge of the floor. */
    static final int EDGE = 6;

    /** Largest gallery side, in blocks. A blueprint set that needs more is refused. */
    static final int MAX_SIDE = 400;

    private static final int WIDTH_STEP = 2;
    private static final int WIDEST_AVENUE = 280;

    private GalleryLayout() {
    }

    /** One building's place: its minimum corner relative to the centre, and how it is turned. */
    record Slot(Blueprint blueprint, int x, int z, Rotation rotation) {

        int width() {
            return this.blueprint.width(this.rotation);
        }

        int depth() {
            return this.blueprint.depth(this.rotation);
        }
    }

    /** A finished layout: every slot, and the half-side of the square that holds them all. */
    record Plan(List<Slot> slots, int radius) {

        int side() {
            return 2 * this.radius + 1;
        }
    }

    /** The rectangle the court needs, as extents from the centre. */
    record Court(int halfWidth, int north, int south) {
    }

    /** Stage, then category, then id: the order buildings appear along the avenues. */
    static final Comparator<Blueprint> ORDER = Comparator
            .comparingInt((Blueprint blueprint) -> blueprint.stage().ordinal())
            .thenComparingInt(blueprint -> blueprint.category().ordinal())
            .thenComparing(blueprint -> blueprint.id().toString());

    /**
     * The blueprints worth showing: every one with a grid, except those an upgrade chain replaces.
     * Following {@code upgrade_to} from any blueprint ends at the top of its chain, and only those
     * ends are shown. A chain that loops, or that points at something missing, ends where it stops.
     */
    static List<Blueprint> exhibits(Map<Identifier, Blueprint> all) {
        Set<Identifier> tops = new LinkedHashSet<>();
        for (Blueprint blueprint : all.values()) {
            if (!blueprint.hasGrid()) {
                continue;
            }
            Blueprint top = blueprint;
            for (int step = 0; step < 9; step++) {
                Blueprint next = top.upgradeTo().map(all::get).orElse(null);
                if (next == null || !next.hasGrid() || next == top) {
                    break;
                }
                top = next;
            }
            tops.add(top.id());
        }
        List<Blueprint> out = new ArrayList<>(tops.size());
        for (Identifier id : tops) {
            out.add(all.get(id));
        }
        out.sort(ORDER);
        return out;
    }

    /** Whether a blueprint belongs on the outer ring. */
    static boolean showpiece(Blueprint blueprint) {
        return blueprint.category() == Blueprint.Category.LANDMARK
                || blueprint.stage() == ColonyStage.METROPOLIS;
    }

    /** The layout with the smallest square, over every avenue width worth trying. */
    static Plan plan(List<Blueprint> exhibits, Court court) {
        List<Blueprint> ordinary = new ArrayList<>();
        List<Blueprint> showpieces = new ArrayList<>();
        int widest = 2 * court.halfWidth() + 1;
        for (Blueprint blueprint : exhibits) {
            if (showpiece(blueprint)) {
                showpieces.add(blueprint);
            } else {
                ordinary.add(blueprint);
                widest = Math.max(widest, blueprint.width());
            }
        }
        Plan best = null;
        for (int width = widest; width <= Math.max(widest, WIDEST_AVENUE); width += WIDTH_STEP) {
            Plan candidate = plan(ordinary, showpieces, court, width);
            if (best == null || candidate.radius() < best.radius()) {
                best = candidate;
            }
        }
        return best == null ? new Plan(List.of(), Math.max(court.halfWidth(),
                Math.max(court.north(), court.south())) + EDGE) : best;
    }

    private static Plan plan(List<Blueprint> ordinary, List<Blueprint> showpieces, Court court, int avenueWidth) {
        List<Slot> slots = new ArrayList<>();
        int north = court.north();
        int south = court.south();
        int half = court.halfWidth();

        // Avenues, nearest the court first, each on whichever side is shorter so far.
        for (List<Blueprint> row : rows(ordinary, avenueWidth)) {
            boolean goNorth = north <= south;
            half = Math.max(half, (span(row, false) + 1) / 2);
            int depth = placeRow(slots, row, goNorth, goNorth ? north : south);
            if (goNorth) {
                north += STREET + depth;
            } else {
                south += STREET + depth;
            }
        }

        // The ring: showpieces dealt north, south, east, west in turn.
        List<List<Blueprint>> sides = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());
        for (int i = 0; i < showpieces.size(); i++) {
            sides.get(i % 4).add(showpieces.get(i));
        }
        if (!sides.get(0).isEmpty()) {
            half = Math.max(half, (span(sides.get(0), false) + 1) / 2);
            north += STREET + placeRow(slots, sides.get(0), true, north);
        }
        if (!sides.get(1).isEmpty()) {
            half = Math.max(half, (span(sides.get(1), false) + 1) / 2);
            south += STREET + placeRow(slots, sides.get(1), false, south);
        }
        int east = half;
        int west = half;
        if (!sides.get(2).isEmpty()) {
            east += STREET + placeColumn(slots, sides.get(2), true, half);
        }
        if (!sides.get(3).isEmpty()) {
            west += STREET + placeColumn(slots, sides.get(3), false, half);
        }

        int radius = Math.max(Math.max(north, south), Math.max(east, west));
        for (Slot slot : slots) {
            radius = Math.max(radius, Math.max(Math.abs(slot.x()), Math.abs(slot.x() + slot.width() - 1)));
            radius = Math.max(radius, Math.max(Math.abs(slot.z()), Math.abs(slot.z() + slot.depth() - 1)));
        }
        return new Plan(List.copyOf(slots), radius + EDGE);
    }

    /** Greedy packing of the sorted list into rows no wider than {@code avenueWidth}. */
    private static List<List<Blueprint>> rows(List<Blueprint> ordinary, int avenueWidth) {
        List<List<Blueprint>> rows = new ArrayList<>();
        List<Blueprint> current = new ArrayList<>();
        int used = 0;
        for (Blueprint blueprint : ordinary) {
            int add = blueprint.width() + (current.isEmpty() ? 0 : GAP);
            if (!current.isEmpty() && used + add > avenueWidth) {
                rows.add(current);
                current = new ArrayList<>();
                used = 0;
                add = blueprint.width();
            }
            current.add(blueprint);
            used += add;
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        return rows;
    }

    /** Total length of a line of buildings, along X (a row) or along Z once turned (a column). */
    private static int span(List<Blueprint> line, boolean column) {
        int total = 0;
        for (Blueprint blueprint : line) {
            total += column ? columnLength(blueprint) : blueprint.width();
        }
        return total + GAP * Math.max(0, line.size() - 1);
    }

    /** A column building is turned a quarter if it may be, so its length along Z is its width. */
    private static int columnLength(Blueprint blueprint) {
        return blueprint.rotate() ? blueprint.width() : blueprint.depth();
    }

    /**
     * Places one west-to-east row beyond {@code extent} on the north or south side, fronts on the
     * street nearest the court.
     *
     * @return the depth of the deepest building in the row
     */
    private static int placeRow(List<Slot> slots, List<Blueprint> row, boolean northSide, int extent) {
        int deepest = 0;
        for (Blueprint blueprint : row) {
            deepest = Math.max(deepest, blueprint.depth());
        }
        int x = -(span(row, false) / 2);
        for (Blueprint blueprint : row) {
            // South of the court a building is turned right round so its front still faces the court.
            Rotation rotation = northSide || !blueprint.rotate() ? Rotation.NONE : Rotation.CLOCKWISE_180;
            int z = northSide ? -(extent + STREET) - blueprint.depth() + 1 : extent + STREET;
            slots.add(new Slot(blueprint, x, z, rotation));
            x += blueprint.width() + GAP;
        }
        return deepest;
    }

    /**
     * Places one north-to-south column beyond {@code extent} on the east or west side, fronts
     * facing the court.
     *
     * @return the thickness of the thickest building in the column
     */
    private static int placeColumn(List<Slot> slots, List<Blueprint> column, boolean eastSide, int extent) {
        int thickest = 0;
        int z = -(span(column, true) / 2);
        for (Blueprint blueprint : column) {
            Rotation rotation = !blueprint.rotate() ? Rotation.NONE
                    : eastSide ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
            int width = blueprint.width(rotation);
            int x = eastSide ? extent + STREET : -(extent + STREET) - width + 1;
            slots.add(new Slot(blueprint, x, z, rotation));
            thickest = Math.max(thickest, width);
            z += blueprint.depth(rotation) + GAP;
        }
        return thickest;
    }
}
