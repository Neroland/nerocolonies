package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.GalleryKit.Site;
import za.co.neroland.nerocolonies.colony.GalleryKit.Tally;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlocks;

/**
 * The court at the middle of the gallery: the beacon and the three colony blocks, a stall for every
 * trade, the AI course and the guard demo.
 *
 * <p>Everything here sits at a <b>fixed offset from the beacon</b>, which is what lets
 * {@code gallery release} find the pen and the kennel gate again after a restart with nothing saved
 * but the sandbox colony itself. Only the two building copies (a guard post and a kennel) vary in
 * size, and they are placed so that growing them only ever pushes outwards.
 *
 * <h2>What each part shows</h2>
 *
 * <ul>
 *   <li><b>Trade stalls</b> — one Neran per profession, holding its tool, standing at a small work
 *       area its behaviour acts on: ripe wheat for the farmer, trunks for the forester, a wounded
 *       patient for the medic, a wounded guardian wolf for the beastkeeper, a half-built site for
 *       the builder (which is also where the hauler carries to, from the beacon).</li>
 *   <li><b>Door house</b> — a Neran's home is inside and its workbench outside, so it has to use
 *       the door at dawn and at dusk.</li>
 *   <li><b>Maze</b> — the workbench at the end is glassed in. The Neran walks the maze, cannot get
 *       there, and works down the recovery ladder to "no path" instead of pushing at the glass.</li>
 *   <li><b>Claim edge</b> — a Neran held just outside the claim, which walks back in when released.</li>
 *   <li><b>Guard demo</b> — hostile mobs sealed in a glass pen with their AI off, a guard post and a
 *       guard, a kennel yard with guardian wolves. Releasing opens the pen and the yard.</li>
 * </ul>
 */
final class GalleryCourt {

    // --- trade stalls -------------------------------------------------------------

    private static final int PER_ROW = 6;
    private static final int STALL = 9;
    private static final int PITCH = 11;

    /** Distance from the centre to the first row's front line. */
    private static final int ROW_FRONT = 9;
    private static final int COL_START = -32;

    /** A bound on stalls, so a datapack with a hundred trades cannot grow the court without limit. */
    static final int MAX_TRADES = 24;

    private static final int HALF_WIDTH = 34;

    /** The behaviours with a stall of their own; anything else gets the plain "stands at its post" one. */
    private static final Set<String> KINDS = Set.of("farmer", "forester", "miner", "builder", "hauler", "cook",
            "toolsmith", "guard", "beastkeeper", "researcher", "quartermaster", "medic");

    // --- AI course ----------------------------------------------------------------

    private static final int HOUSE_X = -32;
    private static final int HOUSE_Z = 9;
    private static final int HOUSE = 7;

    private static final int MAZE_X = -22;
    private static final int MAZE_Z = 9;

    /** '#' wall, 'G' glass, 'S' start (and home), 'T' the workbench nobody can reach. */
    private static final String[] MAZE = {
        "#########",
        "#S......#",
        "#######.#",
        "#.......#",
        "#.#######",
        "#.......#",
        "#.GGGGG.#",
        "#.G...G.#",
        "#.G.T.G.#",
        "#.G...G.#",
        "#.GGGGG.#",
        "#.......#",
        "#########",
    };

    /** Half-width of the strip that sticks out past the claim edge, and how far it sticks out. */
    static final int STRIP_HALF = 3;
    static final int STRIP_OUT = 8;

    // --- guard demo ---------------------------------------------------------------

    static final int PEN_X = 4;
    static final int PEN_Z = 9;
    static final int PEN = 7;
    static final int PEN_HEIGHT = 3;

    private static final int GUARD_X = 13;
    private static final int GUARD_Z = 12;
    private static final int GUARD_POST_Z = 9;

    private static final int YARD_X = 4;
    private static final int YARD_Z = 19;
    private static final int KENNEL_X = 6;
    private static final int KENNEL_Z = 21;

    /** The kennel yard's gate, which {@code gallery release} opens. */
    static final int GATE_X = 5;
    static final int GATE_Z = YARD_Z;

    private GalleryCourt() {
    }

    // --- sizing -------------------------------------------------------------------

    /** The professions that get a stall, in stall order. */
    static List<ProfessionDefinition> trades(MinecraftServer server) {
        List<ProfessionDefinition> sorted = new ArrayList<>(ColonyDefinitions.professionsForServer(server).values());
        sorted.sort(Comparator.comparingInt(ProfessionDefinition::priority)
                .thenComparing(trade -> trade.id().toString()));
        if (sorted.size() > MAX_TRADES) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_TRADES));
        }
        // The hauler walks between the beacon and the building site, and the builder's stall is that
        // site, so those two take the middle of the first row, nearest the beacon and each other.
        ProfessionDefinition hauler = first(sorted, "hauler");
        ProfessionDefinition builder = first(sorted, "builder");
        sorted.remove(hauler);
        sorted.remove(builder);
        if (hauler != null) {
            sorted.add(Math.min(2, sorted.size()), hauler);
        }
        if (builder != null) {
            sorted.add(Math.min(3, sorted.size()), builder);
        }
        return sorted;
    }

    @Nullable
    private static ProfessionDefinition first(List<ProfessionDefinition> trades, String kind) {
        for (ProfessionDefinition trade : trades) {
            if (kind.equals(kind(trade))) {
                return trade;
            }
        }
        return null;
    }

    /** The stall a trade gets, from its behaviour id. */
    private static String kind(ProfessionDefinition trade) {
        Identifier behaviour = trade.behaviour();
        return NeroColoniesCommon.MOD_ID.equals(behaviour.getNamespace()) && KINDS.contains(behaviour.getPath())
                ? behaviour.getPath() : "default";
    }

    /** The smallest blueprint carrying a role, or null if none does. */
    @Nullable
    static Blueprint smallest(Map<Identifier, Blueprint> blueprints, String role) {
        Blueprint best = null;
        for (Blueprint blueprint : blueprints.values()) {
            if (blueprint.hasGrid() && blueprint.hasRole(role) && (best == null || smaller(blueprint, best))) {
                best = blueprint;
            }
        }
        return best;
    }

    /** The smallest blueprint that fits a stall's back corner, for the builder's half-built site. */
    @Nullable
    private static Blueprint smallSite(Map<Identifier, Blueprint> blueprints) {
        Blueprint best = null;
        for (Blueprint blueprint : blueprints.values()) {
            if (blueprint.hasGrid() && blueprint.width() <= 5 && blueprint.depth() <= 5
                    && (best == null || GalleryLayout.ORDER.compare(blueprint, best) < 0)) {
                best = blueprint;
            }
        }
        return best;
    }

    private static boolean smaller(Blueprint a, Blueprint b) {
        int areaA = a.width() * a.depth();
        int areaB = b.width() * b.depth();
        return areaA != areaB ? areaA < areaB : a.id().toString().compareTo(b.id().toString()) < 0;
    }

    private static int guardPostX(@Nullable Blueprint kennel) {
        int yardEast = kennel == null ? YARD_X : KENNEL_X + kennel.width() + 1;
        return Math.max(16, yardEast + 3);
    }

    /** How much room the court needs for this many trades and these two building copies. */
    static GalleryLayout.Court extent(int trades, @Nullable Blueprint guardPost, @Nullable Blueprint kennel) {
        int rows = Math.max(1, (Math.min(trades, MAX_TRADES) + PER_ROW - 1) / PER_ROW);
        int north = ROW_FRONT + PITCH * rows;
        int east = guardPostX(kennel) + (guardPost == null ? 0 : guardPost.width()) + 2;
        int south = MAZE_Z + MAZE.length + 2;
        if (guardPost != null) {
            south = Math.max(south, GUARD_POST_Z + guardPost.depth() + 2);
        }
        if (kennel != null) {
            south = Math.max(south, KENNEL_Z + kennel.depth() + 4);
        }
        return new GalleryLayout.Court(Math.max(HALF_WIDTH, east), north, south);
    }

    // --- building -----------------------------------------------------------------

    /** Builds the whole court. {@code radius} is the claim radius, for the strip at the claim edge. */
    static void build(Site site, Tally tally, GalleryLayout.Court court, int radius) {
        MinecraftServer server = site.level().getServer();
        Map<Identifier, Blueprint> blueprints = ColonyDefinitions.blueprintsForServer(server);

        for (int dx = -court.halfWidth(); dx <= court.halfWidth(); dx++) {
            for (int dz = -court.north(); dz <= court.south(); dz++) {
                pave(site, dx, dz, "minecraft:polished_andesite");
            }
        }

        colonyBlocks(site, tally);
        List<ProfessionDefinition> trades = trades(server);
        for (int i = 0; i < trades.size(); i++) {
            stall(site, tally, trades.get(i), i, blueprints);
        }
        doorHouse(site, tally);
        maze(site, tally);
        claimEdge(site, tally, radius);
        guardDemo(site, tally, blueprints);
    }

    /** Re-surfaces one floor cell, leaving the light grid alone. */
    private static void pave(Site site, int dx, int dz, String text) {
        if (ColonyGallery.lit(dx, dz)) {
            return;
        }
        BlockState state = GalleryKit.state(text);
        if (!state.isAir()) {
            site.level().setBlock(site.at(dx, -1, dz), state, GalleryKit.QUIET);
        }
    }

    private static Component lines(Component... parts) {
        return GalleryKit.lines(parts);
    }

    private static Component heading(String key) {
        return GalleryKit.heading(key);
    }

    /** A heading from {@code key} over the explanation at {@code key.text}. */
    private static Component titled(String key) {
        return lines(heading(key), Component.translatable(key + ".text"));
    }

    // --- the beacon and the three colony blocks -------------------------------------

    private static void colonyBlocks(Site site, Tally tally) {
        GalleryKit.label(site, tally, 0, 3.6D, 0, lines(
                Component.translatable("gallery.nerocolonies.title").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.translatable("gallery.nerocolonies.title.text")), 2.0F);

        block(site, tally, 3, NeroColoniesBlocks.GRATITUDE_CACHE.get(), "gallery.nerocolonies.demo.cache");
        block(site, tally, 5, NeroColoniesBlocks.NEEDS_BOARD.get(), "gallery.nerocolonies.demo.needs_board");
        block(site, tally, 7, NeroColoniesBlocks.PLANNING_TABLE.get(), "gallery.nerocolonies.demo.planning_table");
    }

    private static void block(Site site, Tally tally, int dx, Block block, String textKey) {
        GalleryKit.place(site, tally, dx, 0, -2, block.defaultBlockState());
        GalleryKit.label(site, tally, dx, 1.6D, -2, lines(
                Component.translatable(block.getDescriptionId()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.translatable(textKey)), 0.7F);
    }

    // --- trade stalls -------------------------------------------------------------

    private static void stall(Site site, Tally tally, ProfessionDefinition trade, int index,
            Map<Identifier, Blueprint> blueprints) {
        int x0 = COL_START + PITCH * (index % PER_ROW);
        int front = -(ROW_FRONT + PITCH * (index / PER_ROW));
        int z0 = front - (STALL - 1);
        for (int sx = 0; sx < STALL; sx++) {
            for (int sz = 0; sz < STALL; sz++) {
                pave(site, x0 + sx, z0 + sz, "minecraft:stone_bricks");
            }
        }

        String kind = kind(trade);
        BlockPos station = site.at(x0 + 4, 0, z0 + 6);
        Identifier job = trade.id();
        switch (kind) {
            case "farmer" -> {
                for (int sx = 2; sx <= 6; sx++) {
                    for (int sz = 1; sz <= 3; sz++) {
                        GalleryKit.put(site, tally, x0 + sx, -1, z0 + sz, "minecraft:farmland[moisture=7]");
                        GalleryKit.put(site, tally, x0 + sx, 0, z0 + sz, "minecraft:wheat[age=7]");
                    }
                }
            }
            case "forester" -> {
                trunk(site, tally, x0 + 2, z0 + 1);
                trunk(site, tally, x0 + 4, z0 + 2);
                trunk(site, tally, x0 + 6, z0 + 1);
            }
            case "miner" -> {
                String[] face = {"stone", "coal_ore", "stone", "iron_ore", "cobblestone"};
                for (int i = 0; i < face.length; i++) {
                    GalleryKit.put(site, tally, x0 + 2 + i, 0, z0, "minecraft:" + face[i]);
                    GalleryKit.put(site, tally, x0 + 2 + i, 1, z0, "minecraft:" + face[face.length - 1 - i]);
                }
            }
            case "builder" -> {
                BlockPos access = buildingSite(site, tally, blueprints, x0 + 2, z0);
                if (access != null) {
                    station = access;
                    job = Construction.BUILDER_JOB;
                }
            }
            case "hauler" -> dress(site, tally, x0, z0, "chest", "barrel", "chest");
            case "cook" -> dress(site, tally, x0, z0, "smoker", "barrel", "cauldron");
            case "toolsmith" -> dress(site, tally, x0, z0, "anvil", "smithing_table", "barrel");
            case "researcher" -> {
                dress(site, tally, x0, z0, "bookshelf", "lectern", "bookshelf");
                GalleryKit.put(site, tally, x0 + 3, 1, z0 + 1, "minecraft:bookshelf");
                GalleryKit.put(site, tally, x0 + 5, 1, z0 + 1, "minecraft:bookshelf");
            }
            case "quartermaster" -> {
                dress(site, tally, x0, z0, "barrel", "barrel", "barrel");
                GalleryKit.put(site, tally, x0 + 4, 1, z0 + 1, "minecraft:barrel");
            }
            case "medic" -> {
                dress(site, tally, x0, z0, "hay_block", "hay_block", "hay_block");
                ColonistEntity patient = GalleryKit.neran(site, tally, x0 + 4, z0 + 3, null);
                if (patient != null) {
                    patient.setHomePos(site.at(x0 + 4, 0, z0 + 3));
                    patient.setHealth(Math.max(1.0F, patient.getMaxHealth() * 0.3F));
                }
            }
            case "guard" -> {
                GalleryKit.put(site, tally, x0 + 4, 0, z0 + 1, "minecraft:hay_block");
                GalleryKit.put(site, tally, x0 + 4, 1, z0 + 1, "minecraft:hay_block");
            }
            case "beastkeeper" -> woundedWolf(site, tally, x0 + 4, z0 + 1);
            default -> GalleryKit.put(site, tally, x0 + 4, 0, z0 + 1, "minecraft:crafting_table");
        }

        ColonistEntity neran = GalleryKit.neran(site, tally, x0 + 4, z0 + 6, trade);
        if (neran != null) {
            // The same two fields Professions.assign sets: where the trade is worked, and which trade.
            neran.setJobStationPos(station);
            neran.setJobId(job);
            neran.setHomePos(station);
        }
        GalleryKit.label(site, tally, x0 + 4, 3.0D, front, lines(
                heading(trade.nameKey()),
                Component.translatable("gallery.nerocolonies.behaviour." + kind)), 1.0F);
    }

    /** Three plain blocks along the back of a stall. */
    private static void dress(Site site, Tally tally, int x0, int z0, String left, String middle, String right) {
        GalleryKit.put(site, tally, x0 + 3, 0, z0 + 1, "minecraft:" + left);
        GalleryKit.put(site, tally, x0 + 4, 0, z0 + 1, "minecraft:" + middle);
        GalleryKit.put(site, tally, x0 + 5, 0, z0 + 1, "minecraft:" + right);
    }

    /** A trunk on a square of soil, so a felled tree can be replanted. */
    private static void trunk(Site site, Tally tally, int dx, int dz) {
        GalleryKit.put(site, tally, dx, -1, dz, "minecraft:dirt");
        for (int dy = 0; dy < 4; dy++) {
            GalleryKit.put(site, tally, dx, dy, dz, "minecraft:oak_log");
        }
    }

    /** A guardian wolf at a quarter health in a one-block pen, for the beastkeeper to tend. */
    private static void woundedWolf(Site site, Tally tally, int dx, int dz) {
        List<BlockPos> ring = new ArrayList<>();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox != 0 || oz != 0) {
                    ring.add(new BlockPos(dx + ox, 0, dz + oz));
                }
            }
        }
        GalleryKit.connected(site, tally, ring, "minecraft:oak_fence");
        Wolf wolf = GalleryKit.mob(site, GalleryKit.wolfType(), dx, dz);
        if (wolf != null) {
            wolf.addTag(ColonyDefence.GUARDIAN_TAG + site.colonyId());
            wolf.setTame(true, false); // ownerless and tame, exactly as a kennel's wolves are
            wolf.setHealth(Math.max(1.0F, wolf.getMaxHealth() / 4.0F));
        }
    }

    /**
     * Starts a small blueprint as the sandbox colony's active build and lays its first two fifths,
     * so the builder has a site to stand at and the hauler somewhere to carry to. Nothing ever
     * finishes it: the sandbox colony does not run colony cycles.
     *
     * @return the site's access spot, or null if no blueprint is small enough or one is already begun
     */
    @Nullable
    private static BlockPos buildingSite(Site site, Tally tally, Map<Identifier, Blueprint> blueprints, int dx,
            int dz) {
        MinecraftServer server = site.level().getServer();
        Colony colony = site.colony();
        Blueprint blueprint = smallSite(blueprints);
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).plan(site.colonyId());
        if (colony == null || blueprint == null || plan.active() != null) {
            return null;
        }
        BlockPos origin = site.at(dx, 0, dz);
        List<BlockPos> order = blueprint.buildOrder();
        int laid = order.size() * 2 / 5;
        for (int i = 0; i < laid; i++) {
            BlockPos cell = order.get(i);
            BlockState state = blueprint.stateAt(cell.getX(), cell.getY(), cell.getZ(), Rotation.NONE);
            if (state != null && site.level().setBlock(origin.offset(cell), state, Block.UPDATE_ALL)) {
                tally.blocks++;
            }
        }
        plan.begin(blueprint.id(), origin, Rotation.NONE, order.size());
        plan.advanceCursor(laid);
        ColonyConstruction.get(server).touch();
        return Construction.accessSpot(site.level(), colony, origin, blueprint, Rotation.NONE);
    }

    // --- AI course ----------------------------------------------------------------

    private static void doorHouse(Site site, Tally tally) {
        for (int ox = 0; ox < HOUSE; ox++) {
            for (int oz = 0; oz < HOUSE; oz++) {
                boolean wall = ox == 0 || oz == 0 || ox == HOUSE - 1 || oz == HOUSE - 1;
                if (wall) {
                    for (int dy = 0; dy < 3; dy++) {
                        GalleryKit.put(site, tally, HOUSE_X + ox, dy, HOUSE_Z + oz, "minecraft:oak_planks");
                    }
                }
                GalleryKit.put(site, tally, HOUSE_X + ox, 3, HOUSE_Z + oz, "minecraft:oak_planks");
            }
        }
        int mid = HOUSE / 2;
        int doorX = HOUSE_X + mid;
        int doorZ = HOUSE_Z + HOUSE - 1;
        GalleryKit.put(site, tally, doorX, 0, doorZ,
                "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]");
        GalleryKit.put(site, tally, doorX, 1, doorZ,
                "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false]");
        GalleryKit.put(site, tally, HOUSE_X, 1, HOUSE_Z + mid, "minecraft:glass");
        GalleryKit.put(site, tally, HOUSE_X + HOUSE - 1, 1, HOUSE_Z + mid, "minecraft:glass");
        GalleryKit.put(site, tally, doorX, 1, HOUSE_Z, "minecraft:glass");
        GalleryKit.put(site, tally, HOUSE_X + 1, 0, HOUSE_Z + 1, "minecraft:lantern[hanging=false]");

        int benchZ = doorZ + 5;
        GalleryKit.put(site, tally, doorX, 0, benchZ, "minecraft:crafting_table");
        ColonistEntity neran = GalleryKit.neran(site, tally, doorX, HOUSE_Z + mid, null);
        if (neran != null) {
            neran.setHomePos(site.at(doorX, 0, HOUSE_Z + mid));
            neran.setJobStationPos(site.at(doorX, 0, benchZ));
        }
        GalleryKit.label(site, tally, doorX, 3.4D, doorZ + 1, titled("gallery.nerocolonies.demo.door"), 1.0F);
    }

    private static void maze(Site site, Tally tally) {
        int startX = 0;
        int startZ = 0;
        int benchX = 0;
        int benchZ = 0;
        for (int oz = 0; oz < MAZE.length; oz++) {
            String row = MAZE[oz];
            for (int ox = 0; ox < row.length(); ox++) {
                int dx = MAZE_X + ox;
                int dz = MAZE_Z + oz;
                switch (row.charAt(ox)) {
                    case '#' -> {
                        GalleryKit.put(site, tally, dx, 0, dz, "minecraft:stone_bricks");
                        GalleryKit.put(site, tally, dx, 1, dz, "minecraft:stone_bricks");
                    }
                    case 'G' -> {
                        GalleryKit.put(site, tally, dx, 0, dz, "minecraft:glass");
                        GalleryKit.put(site, tally, dx, 1, dz, "minecraft:glass");
                    }
                    case 'T' -> {
                        GalleryKit.put(site, tally, dx, 0, dz, "minecraft:crafting_table");
                        benchX = dx;
                        benchZ = dz;
                    }
                    case 'S' -> {
                        startX = dx;
                        startZ = dz;
                    }
                    default -> {
                        // open floor
                    }
                }
            }
        }
        ColonistEntity neran = GalleryKit.neran(site, tally, startX, startZ, null);
        if (neran != null) {
            neran.setHomePos(site.at(startX, 0, startZ));
            neran.setJobStationPos(site.at(benchX, 0, benchZ));
        }
        GalleryKit.label(site, tally, MAZE_X + MAZE[0].length() / 2, 3.4D, MAZE_Z - 1,
                titled("gallery.nerocolonies.demo.maze"), 1.0F);
    }

    /**
     * A strip of floor sticking out past the claim's south edge, with a Neran held on it. Released,
     * it walks back inside before doing anything else, then settles at its home just inside the edge.
     */
    private static void claimEdge(Site site, Tally tally, int radius) {
        for (int dx = -STRIP_HALF; dx <= STRIP_HALF; dx++) {
            for (int dz = radius + 1; dz <= radius + STRIP_OUT; dz++) {
                BlockState state = GalleryKit.state("minecraft:stone_bricks");
                if (!state.isAir() && site.level().setBlock(site.at(dx, -1, dz), state, GalleryKit.QUIET)) {
                    tally.blocks++;
                }
            }
        }
        ColonistEntity runner = GalleryKit.neran(site, tally, 0, radius + STRIP_OUT - 3, null);
        if (runner != null) {
            runner.setHomePos(site.at(0, 0, radius - 2));
            hold(runner, GalleryKit.TAG_RUNNER);
        }
        GalleryKit.label(site, tally, 0, 3.4D, radius - 1, titled("gallery.nerocolonies.demo.claim"), 1.0F);
        // A signpost in the court, because the edge is a long walk from it.
        BlockPos centre = site.centre();
        GalleryKit.label(site, tally, -8, 2.6D, 12, lines(
                heading("gallery.nerocolonies.demo.claim"),
                Component.translatable("gallery.nerocolonies.demo.claim.sign",
                        centre.getX(), centre.getZ() + radius)), 1.0F);
    }

    private static void hold(Mob mob, String group) {
        mob.setNoAi(true);
        mob.addTag(GalleryKit.TAG_HELD);
        mob.addTag(group);
    }

    // --- guard demo ---------------------------------------------------------------

    private static void guardDemo(Site site, Tally tally, Map<Identifier, Blueprint> blueprints) {
        // The pen: glass all round and a solid roof, so nothing inside can be reached, or burn.
        for (int ox = 0; ox < PEN; ox++) {
            for (int oz = 0; oz < PEN; oz++) {
                boolean wall = ox == 0 || oz == 0 || ox == PEN - 1 || oz == PEN - 1;
                if (wall) {
                    for (int dy = 0; dy < PEN_HEIGHT; dy++) {
                        GalleryKit.put(site, tally, PEN_X + ox, dy, PEN_Z + oz, "minecraft:glass");
                    }
                }
                GalleryKit.put(site, tally, PEN_X + ox, PEN_HEIGHT, PEN_Z + oz, "minecraft:smooth_stone");
            }
        }
        penned(GalleryKit.mob(site, GalleryKit.zombieType(), PEN_X + 2, PEN_Z + 2));
        penned(GalleryKit.mob(site, GalleryKit.huskType(), PEN_X + 4, PEN_Z + 2));
        penned(GalleryKit.mob(site, GalleryKit.huskType(), PEN_X + 3, PEN_Z + 4));
        GalleryKit.label(site, tally, PEN_X + PEN / 2, 4.6D, PEN_Z - 1, titled("gallery.nerocolonies.demo.pen"), 1.0F);

        Blueprint kennel = smallest(blueprints, ColonyBuildings.ROLE_KENNEL);
        Blueprint guardPost = smallest(blueprints, ColonyBuildings.ROLE_GUARD);
        if (guardPost != null) {
            ColonyGallery.erect(site, tally, guardPost, site.at(guardPostX(kennel), 0, GUARD_POST_Z), Rotation.NONE);
        }
        ProfessionDefinition guard = ColonyDefinitions.professionsForServer(site.level().getServer())
                .get(ColonistEntity.GUARD);
        ColonistEntity neran = GalleryKit.neran(site, tally, GUARD_X, GUARD_Z, guard);
        if (neran != null) {
            BlockPos post = site.at(GUARD_X, 0, GUARD_Z);
            neran.setJobStationPos(post);
            neran.setJobId(guard == null ? null : guard.id());
            neran.setHomePos(post);
        }
        GalleryKit.label(site, tally, GUARD_X, 3.0D, GUARD_Z - 3, titled("gallery.nerocolonies.demo.guard"), 1.0F);

        if (kennel != null) {
            kennelYard(site, tally, kennel);
        }
    }

    private static void penned(@Nullable Mob mob) {
        if (mob != null) {
            hold(mob, GalleryKit.TAG_PEN);
        }
    }

    /** The kennel copy inside a fenced yard, so its wolves are still there when the pen opens. */
    private static void kennelYard(Site site, Tally tally, Blueprint kennel) {
        int east = KENNEL_X + kennel.width() + 1;
        int south = KENNEL_Z + kennel.depth() + 2;
        List<BlockPos> fence = new ArrayList<>();
        for (int dx = YARD_X; dx <= east; dx++) {
            for (int dz = YARD_Z; dz <= south; dz++) {
                boolean ring = dx == YARD_X || dx == east || dz == YARD_Z || dz == south;
                if (ring && !(dx == GATE_X && dz == GATE_Z)) {
                    fence.add(new BlockPos(dx, 0, dz));
                }
            }
        }
        GalleryKit.put(site, tally, GATE_X, 0, GATE_Z, "minecraft:oak_fence_gate[facing=north,open=false]");
        GalleryKit.connected(site, tally, fence, "minecraft:oak_fence");
        ColonyGallery.erect(site, tally, kennel, site.at(KENNEL_X, 0, KENNEL_Z), Rotation.NONE);
        GalleryKit.label(site, tally, GATE_X + 2, 3.0D, GATE_Z - 1, titled("gallery.nerocolonies.demo.kennel"), 1.0F);
    }
}
