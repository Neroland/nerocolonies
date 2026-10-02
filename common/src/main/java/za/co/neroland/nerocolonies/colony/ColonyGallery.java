package za.co.neroland.nerocolonies.colony;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.GalleryKit.Site;
import za.co.neroland.nerocolonies.colony.GalleryKit.Tally;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlocks;

/**
 * The gallery: a showcase of everything a colony can build and everyone it can employ, laid out on
 * a floor of its own around whoever asked for it, inside one <b>sandbox colony</b>.
 *
 * <h2>What is built</h2>
 *
 * <ul>
 *   <li>A square floor one block above the ground the player stands on, lit so nothing spawns on it.</li>
 *   <li>Every blueprint with a grid, placed instantly with the build loop's own primitives
 *       ({@link Blueprint#buildOrder()}, {@link Blueprint#stateAt(int, int, int, Rotation)}) and
 *       recorded as a finished structure. Upgrade chains show their top level only. See
 *       {@link GalleryLayout} for the avenues and the outer ring.</li>
 *   <li>The court at the centre: the beacon, a Gratitude Cache stocked with one roll of every
 *       stage's table, a Needs Board, a Planning Table, a stall per trade, the AI course and the
 *       guard demo. See {@link GalleryCourt}.</li>
 * </ul>
 *
 * <p><b>Build it on flat, open ground.</b> Whatever stands above the floor inside the square
 * (a hillside, a tree, somebody's house) is cut away to make room, and clearing the gallery does not
 * put it back. No chunk is ever loaded for it: the build is refused unless the whole footprint is
 * already loaded, which in practice means a view distance of nine or ten chunks.
 *
 * <h2>The sandbox colony</h2>
 *
 * <p>The gallery is one {@link Colony} record, put in {@link ColonyState} the same way a founding
 * puts one, owned by {@link Colony#SERVER_OWNER}, flagged in {@link ColonyLife} as the sandbox and
 * set to {@link ColonyStage#METROPOLIS} with every research node unlocked. Its claim is exactly the
 * floor, so the floor can be found again from the record alone.
 *
 * <p><b>It never runs a colony cycle.</b> A real beacon block stands at its centre (the retention
 * sweep drops any colony whose beacon is gone) but that beacon is left <em>unbound</em>, and the
 * beacon's block entity is what drives a colony's tick. So the sandbox has no housing sweep, no
 * arrivals or departures, no trade reassignment, no job board, no building work, no food, morale or
 * life-support cycle, no stage advance, no progression-gate write and no threshold crossing. That is
 * what keeps a curated exhibit curated (a live cycle would re-home the Nerans, hand them other
 * trades and shrink the claim back to the configured radius), and it is also why the sandbox can
 * never show up in telemetry, a companion client, or another mod's quest trigger. The Nerans
 * themselves still think: walking, working, door use and guarding are entity AI, not colony tick.
 *
 * <h2>Counted, and not counted</h2>
 *
 * <ul>
 *   <li>The gallery's Nerans <b>do count</b> toward the server-wide {@code maxLoadedColonists}
 *       budget: the record carries their number, so a full gallery leaves that much less room for
 *       arrivals in real colonies until it is cleared.</li>
 *   <li>The record <b>does count</b> toward {@code maxColoniesTotal}, and the gallery is refused
 *       when that cap is already reached.</li>
 *   <li>It counts toward nobody's per-player cap and appears in nobody's {@code colony list}: it
 *       has no owner and no members.</li>
 * </ul>
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing about the player who ran the command is stored, logged or published. The colony has no
 * owner, no access list and no role lists, nobody is ever added to an enemy list, and the log lines
 * here are counts.
 */
public final class ColonyGallery {

    /** The sandbox colony's fixed name. */
    public static final String NAME = "Gallery";

    /** How far north of the player the centre (the beacon) goes, so nothing lands on them. */
    private static final int CENTRE_OFFSET = 3;

    /** One floor block in every {@value} each way is a light, which is what stops monsters spawning. */
    private static final int LIGHT_GRID = 8;

    /** Headroom cleared above the tallest thing the blueprint format allows. */
    private static final int CLEAR_MARGIN = 8;

    /** Held demo mobs this close to the player are the ones {@code release} lets go. */
    private static final double RELEASE_RANGE = 64.0D;

    /** How far outside the claim a strayed Neran or guardian is still looked for when clearing. */
    private static final int STRAY_MARGIN = 64;

    private static final float BUILDING_LABEL_SCALE = 1.6F;

    private ColonyGallery() {
    }

    // --- results ------------------------------------------------------------------

    /** Why a build was, or was not, carried out. */
    public enum Outcome {
        BUILT, EXISTS, TOO_BIG, NOT_LOADED, TOO_HIGH, OVERLAP, COLONY_CAP
    }

    /** What a build did. Counts and sizes only. */
    public record BuildResult(Outcome outcome, int buildings, int nerans, int side, int radius) {

        static BuildResult refused(Outcome outcome, GalleryLayout.Plan plan) {
            return new BuildResult(outcome, 0, 0, plan.side(), plan.radius());
        }
    }

    /** What a clear did. {@code unloadedChunks} above zero means it has to be run again from closer. */
    public record ClearResult(boolean found, int blocks, int entities, int unloadedChunks) {
    }

    // --- queries ------------------------------------------------------------------

    /** The sandbox colony, or null if this server has no gallery. */
    @Nullable
    public static Colony find(MinecraftServer server) {
        for (Colony colony : ColonyState.get(server).colonies()) {
            if (ColonyLife.isSandbox(server, colony.colonyId())) {
                return colony;
            }
        }
        return null;
    }

    /** The largest gallery side, in blocks; a blueprint set that needs more is refused. */
    public static int maxSide() {
        return GalleryLayout.MAX_SIDE;
    }

    /** Whether a floor cell, as an offset from the centre, is one of the lights. */
    static boolean lit(int dx, int dz) {
        return Math.floorMod(dx, LIGHT_GRID) == 0 && Math.floorMod(dz, LIGHT_GRID) == 0;
    }

    /** Whether an offset from the centre is on the floor: the claim square, or the strip past its south edge. */
    private static boolean onFloor(int dx, int dz, int radius) {
        if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) {
            return true;
        }
        return Math.abs(dx) <= GalleryCourt.STRIP_HALF && dz > radius && dz <= radius + GalleryCourt.STRIP_OUT;
    }

    // --- build --------------------------------------------------------------------

    /**
     * Builds the gallery around {@code player}, who ends up standing in its court. The caller has
     * already checked that this is an operator in creative mode.
     */
    public static BuildResult build(ServerPlayer player) {
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        ColonyDefinitions.refreshIfReloaded(server);
        Map<Identifier, Blueprint> blueprints = ColonyDefinitions.blueprintsForServer(server);

        List<Blueprint> exhibits = GalleryLayout.exhibits(blueprints);
        Blueprint guardPost = GalleryCourt.smallest(blueprints, ColonyBuildings.ROLE_GUARD);
        Blueprint kennel = GalleryCourt.smallest(blueprints, ColonyBuildings.ROLE_KENNEL);
        GalleryLayout.Court court = GalleryCourt.extent(GalleryCourt.trades(server).size(), guardPost, kennel);
        GalleryLayout.Plan plan = GalleryLayout.plan(exhibits, court);
        int radius = plan.radius();

        if (find(server) != null) {
            return BuildResult.refused(Outcome.EXISTS, plan);
        }
        if (plan.side() > GalleryLayout.MAX_SIDE) {
            return BuildResult.refused(Outcome.TOO_BIG, plan);
        }
        BlockPos feet = player.blockPosition();
        // The floor goes in at foot level, leaving the ground underneath as it was; everything
        // stands one block above that.
        BlockPos centre = new BlockPos(feet.getX(), feet.getY() + 1, feet.getZ() - CENTRE_OFFSET);
        int tallest = 6;
        for (Blueprint blueprint : exhibits) {
            tallest = Math.max(tallest, blueprint.height());
        }
        if (centre.getY() + tallest + 2 > level.getMaxY() || feet.getY() <= level.getMinY()) {
            return BuildResult.refused(Outcome.TOO_HIGH, plan);
        }
        if (unloadedChunks(level, centre, radius) > 0) {
            return BuildResult.refused(Outcome.NOT_LOADED, plan);
        }
        ColonyState state = ColonyState.get(server);
        if (state.size() >= NeroColoniesConfig.MAX_COLONIES_TOTAL.get()) {
            return BuildResult.refused(Outcome.COLONY_CAP, plan);
        }
        if (overlapsClaim(state, level, centre, radius + GalleryCourt.STRIP_OUT)) {
            return BuildResult.refused(Outcome.OVERLAP, plan);
        }

        // The record first, so that whatever happens next, `gallery clear` can find the floor.
        UUID id = UUID.randomUUID();
        Set<String> research = new LinkedHashSet<>();
        for (Identifier node : ColonyDefinitions.researchForServer(server).keySet()) {
            research.add(node.toString());
        }
        state.put(Colony.found(id, NAME, level.dimension(), centre, radius, Colony.SERVER_OWNER,
                level.getGameTime()).withResearch(research));
        ColonyLife lifeStore = ColonyLife.get(server);
        ColonyLife.Life life = lifeStore.life(id);
        life.setSandbox(true);
        life.setStage(ColonyStage.METROPOLIS);
        lifeStore.touch();

        Site site = new Site(level, id, centre);
        Tally tally = new Tally();
        layFloor(site, tally, radius, centre.getY() + tallest + CLEAR_MARGIN);
        // A real beacon, because a colony without one is swept away at the next start. Unbound on
        // purpose: see the class notes.
        GalleryKit.place(site, tally, 0, 0, 0, NeroColoniesBlocks.COLONY_BEACON.get().defaultBlockState());

        for (GalleryLayout.Slot slot : plan.slots()) {
            erect(site, tally, slot.blueprint(), centre.offset(slot.x(), 0, slot.z()), slot.rotation());
        }
        GalleryCourt.build(site, tally, court, radius);
        ColonyConstruction.get(server).touch();
        ColonyBuildings.invalidate(id);

        Colony colony = state.colony(id);
        if (colony != null) {
            provision(site, colony, kennel != null);
            state.put(colony.withPopulation(tally.nerans));
        }
        for (ColonyStage stage : ColonyStage.values()) {
            GratitudeCache.stock(level, life, stage);
        }
        life.markCacheStocked(level.getGameTime());
        lifeStore.touch();
        GratitudeCache.refresh(id);

        liftPlayers(level, centre, radius);
        // Counts only (POPIA/GDPR): never who built it, never where.
        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Gallery built: {} building(s), {} Neran(s), {} label(s), {} block(s).",
                tally.buildings, tally.nerans, tally.labels, tally.blocks);
        return new BuildResult(Outcome.BUILT, tally.buildings, tally.nerans, plan.side(), radius);
    }

    /** How many chunks under the floor are not loaded. Nothing here ever loads one. */
    private static int unloadedChunks(ServerLevel level, BlockPos centre, int radius) {
        int minX = (centre.getX() - radius) >> 4;
        int maxX = (centre.getX() + radius) >> 4;
        int minZ = (centre.getZ() - radius) >> 4;
        int maxZ = (centre.getZ() + radius + GalleryCourt.STRIP_OUT) >> 4;
        int missing = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(x, z)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /** Whether a square of {@code reach} around {@code centre} would touch any colony or outpost claim. */
    private static boolean overlapsClaim(ColonyState state, ServerLevel level, BlockPos centre, int reach) {
        for (Colony other : state.coloniesIn(level.dimension())) {
            if (touches(centre, reach, other.beaconPos(), other.claimRadius())) {
                return true;
            }
        }
        for (Outpost outpost : state.allOutposts()) {
            if (outpost.dimension().equals(level.dimension())
                    && touches(centre, reach, outpost.pos(), outpost.claimRadius())) {
                return true;
            }
        }
        return false;
    }

    private static boolean touches(BlockPos a, int reachA, BlockPos b, int reachB) {
        int reach = reachA + reachB;
        return Math.abs(a.getX() - b.getX()) <= reach && Math.abs(a.getZ() - b.getZ()) <= reach;
    }

    /**
     * Lays the floor and opens the air above it. Each column is cleared from the highest block the
     * heightmap knows down to the floor, so flat ground costs nothing and a hillside is cut away.
     */
    private static void layFloor(Site site, Tally tally, int radius, int ceiling) {
        ServerLevel level = site.level();
        BlockPos centre = site.centre();
        int floorY = centre.getY() - 1;
        BlockState plain = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius + GalleryCourt.STRIP_OUT; dz++) {
                if (!onFloor(dx, dz, radius)) {
                    continue;
                }
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, ceiling);
                for (int y = top; y > floorY; y--) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor, air, GalleryKit.QUIET);
                    }
                }
                cursor.set(x, floorY, z);
                if (level.setBlock(cursor, lit(dx, dz) ? light : plain, GalleryKit.QUIET)) {
                    tally.blocks++;
                }
            }
        }
    }

    /**
     * Puts one blueprint up at once and records it as a finished structure, so the colony's building
     * lookups (anchors, roles, unlocked trades) see it exactly as if the build loop had finished it.
     * Blocks go down in build order with the build loop's own update flags.
     */
    static void erect(Site site, Tally tally, Blueprint blueprint, BlockPos origin, Rotation rotation) {
        ServerLevel level = site.level();
        for (BlockPos cell : blueprint.buildOrder()) {
            BlockState state = blueprint.stateAt(cell.getX(), cell.getY(), cell.getZ(), rotation);
            if (state != null
                    && level.setBlock(origin.offset(blueprint.rotate(cell, rotation)), state, Block.UPDATE_ALL)) {
                tally.blocks++;
            }
        }
        ColonyConstruction.get(level.getServer()).plan(site.colonyId()).recordStructure(
                new ColonyConstruction.Structure(blueprint.id(), origin, rotation, blueprint.level()));
        tally.buildings++;

        // The label floats just outside the middle of the front face.
        int width = blueprint.width(rotation);
        int depth = blueprint.depth(rotation);
        double x = origin.getX() + width / 2.0D;
        double z = origin.getZ() + depth / 2.0D;
        switch (rotation.rotate(Direction.SOUTH)) {
            case NORTH -> z = origin.getZ() - 0.5D;
            case EAST -> x = origin.getX() + width + 0.5D;
            case WEST -> x = origin.getX() - 0.5D;
            default -> z = origin.getZ() + depth + 0.5D;
        }
        GalleryKit.label(site, tally, x, origin.getY() + 3.2D, z, describe(level.getServer(), blueprint),
                BUILDING_LABEL_SCALE);
    }

    /** A building's label: its name, its stage, category and level, and the trades it opens. */
    private static Component describe(MinecraftServer server, Blueprint blueprint) {
        Component name = GalleryKit.heading(blueprint.nameKey());
        Component tier = Component.translatable("gallery.nerocolonies.label.tier",
                Component.translatable("stage.nerocolonies." + blueprint.stage().key()),
                Component.translatable("gallery.nerocolonies.category." + blueprint.category().serialised()),
                blueprint.level());
        if (blueprint.unlocks().isEmpty()) {
            return GalleryKit.lines(name, tier);
        }
        Map<Identifier, ProfessionDefinition> trades = ColonyDefinitions.professionsForServer(server);
        MutableComponent names = Component.empty();
        boolean first = true;
        for (Identifier unlock : blueprint.unlocks()) {
            if (!first) {
                names.append(", ");
            }
            first = false;
            ProfessionDefinition trade = trades.get(unlock);
            names.append(trade == null ? Component.literal(unlock.toString())
                    : Component.translatable(trade.nameKey()));
        }
        return GalleryKit.lines(name, tier,
                Component.translatable("gallery.nerocolonies.label.unlocks", names).withStyle(ChatFormatting.GREEN));
    }

    /**
     * Stocks colony storage with what the trade stalls draw on (seed for the farmer, saplings for
     * the forester) and has the kennel raise its wolves through {@link ColonyDefence}, the same way a
     * Beastkeeper's colony would, then tags them for clearing.
     */
    private static void provision(Site site, Colony colony, boolean hasKennel) {
        ServerLevel level = site.level();
        MinecraftServer server = level.getServer();
        int slots = ColonyStorage.usableSlots(level, colony);
        insert(server, colony, GalleryKit.stack("minecraft:wheat_seeds", 32), slots);
        insert(server, colony, GalleryKit.stack("minecraft:oak_sapling", 8), slots);
        if (!hasKennel) {
            return;
        }
        // Two bones a wolf, one wolf a call.
        insert(server, colony, GalleryKit.stack("minecraft:bone", 4), slots);
        ColonyDefence.tick(level, colony, true);
        ColonyDefence.tick(level, colony, true);
        for (Mob guardian : guardians(level, colony)) {
            guardian.addTag(GalleryKit.TAG);
        }
    }

    private static void insert(MinecraftServer server, Colony colony, ItemStack stack, int slots) {
        if (!stack.isEmpty()) {
            ColonyStorage.insert(server, colony.colonyId(), stack, slots);
        }
    }

    /** Lifts anyone standing where the floor went in onto it, rather than leaving them inside it. */
    private static void liftPlayers(ServerLevel level, BlockPos centre, int radius) {
        for (ServerPlayer other : level.players()) {
            BlockPos at = other.blockPosition();
            if (onFloor(at.getX() - centre.getX(), at.getZ() - centre.getZ(), radius)
                    && at.getY() < centre.getY() && at.getY() >= centre.getY() - 2) {
                other.teleportTo(other.getX(), centre.getY(), other.getZ());
            }
        }
    }

    // --- shared lookups -------------------------------------------------------------

    /** The box over the whole floor, widened by {@code margin} and taken to the build limits. */
    private static AABB reach(ServerLevel level, Colony colony, int margin) {
        BlockPos centre = colony.beaconPos();
        int span = colony.claimRadius() + margin;
        return new AABB(centre.getX() - span, level.getMinY(), centre.getZ() - span,
                centre.getX() + span + 1, level.getMaxY() + 1,
                centre.getZ() + span + GalleryCourt.STRIP_OUT + 1);
    }

    private static List<Mob> guardians(ServerLevel level, Colony colony) {
        String tag = ColonyDefence.GUARDIAN_TAG + colony.colonyId();
        return level.getEntitiesOfClass(Mob.class, reach(level, colony, STRAY_MARGIN),
                mob -> mob.entityTags().contains(tag));
    }

    // --- release ------------------------------------------------------------------

    /**
     * Lets the held demo mobs go: the pen's hostile mobs get their AI back and the pen and the kennel
     * yard are opened, and the Neran waiting outside the claim edge starts walking home.
     *
     * <p>If any held mob is within {@value #RELEASE_RANGE} blocks of the player, only those nearby
     * are released, so the pen and the claim edge can each be watched in turn. From anywhere else,
     * everything held is released.
     *
     * @return how many mobs were released, or -1 if there is no gallery
     */
    public static int release(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Colony colony = find(server);
        if (colony == null) {
            return -1;
        }
        ServerLevel level = server.getLevel(colony.dimension());
        if (level == null) {
            return 0;
        }
        List<Mob> held = level.getEntitiesOfClass(Mob.class, reach(level, colony, STRAY_MARGIN),
                mob -> mob.entityTags().contains(GalleryKit.TAG_HELD));
        List<Mob> nearby = player.level() != level ? List.of() : held.stream()
                .filter(mob -> mob.distanceToSqr(player) <= RELEASE_RANGE * RELEASE_RANGE).toList();
        List<Mob> chosen = nearby.isEmpty() ? held : nearby;
        boolean pen = false;
        for (Mob mob : chosen) {
            mob.setNoAi(false);
            mob.removeTag(GalleryKit.TAG_HELD);
            pen |= mob.entityTags().contains(GalleryKit.TAG_PEN);
        }
        if (pen) {
            openPen(level, colony);
        }
        return chosen.size();
    }

    /** Opens the pen's east wall and the kennel yard's gate, and points the guardians at what came out. */
    private static void openPen(ServerLevel level, Colony colony) {
        BlockPos centre = colony.beaconPos();
        BlockState glass = GalleryKit.state("minecraft:glass");
        int wallX = GalleryCourt.PEN_X + GalleryCourt.PEN - 1;
        for (int dz = GalleryCourt.PEN_Z + 1; dz < GalleryCourt.PEN_Z + GalleryCourt.PEN - 1; dz++) {
            for (int dy = 0; dy < GalleryCourt.PEN_HEIGHT; dy++) {
                BlockPos pos = centre.offset(wallX, dy, dz);
                if (!glass.isAir() && level.getBlockState(pos).equals(glass)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos gatePos = centre.offset(GalleryCourt.GATE_X, 0, GalleryCourt.GATE_Z);
        BlockState gate = level.getBlockState(gatePos);
        if (gate.hasProperty(BlockStateProperties.OPEN) && !gate.getValue(BlockStateProperties.OPEN)) {
            level.setBlock(gatePos, gate.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
        }
        // Guardians are pointed at targets on the colony cycle, which the sandbox never runs.
        ColonyDefence.tick(level, colony, false);
    }

    // --- clear --------------------------------------------------------------------

    /**
     * Removes the gallery completely: every block from the floor layer up over the whole floor,
     * every entity the gallery spawned, every Neran and guardian of the sandbox colony, and every
     * record kept under its id. The ground one block below the floor is left as it was.
     *
     * <p>The floor is found from the sandbox colony alone (its beacon position and claim radius), so
     * this works after a restart and from anywhere. It never loads a chunk: if part of the floor is
     * not loaded, the loaded part is cleared, the records and the beacon are kept, and the result
     * says how many chunks are left so the command can ask to be run again from closer.
     */
    public static ClearResult clear(MinecraftServer server) {
        Colony colony = find(server);
        if (colony == null) {
            return new ClearResult(false, 0, 0, 0);
        }
        UUID id = colony.colonyId();
        ServerLevel level = server.getLevel(colony.dimension());
        int blocks = 0;
        int entities = 0;
        int unloaded = 0;
        if (level != null) {
            BlockPos centre = colony.beaconPos();
            int radius = colony.claimRadius();
            int floorY = centre.getY() - 1;
            int ceiling = Math.min(level.getMaxY(), centre.getY() + Blueprint.MAX_HEIGHT + CLEAR_MARGIN);
            // Nothing drops and nothing reacts: no neighbour updates, no shape updates, and no
            // block-entity side effects (a container would otherwise spill its contents).
            int flags = GalleryKit.QUIET | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
            BlockState air = Blocks.AIR.defaultBlockState();
            Set<Long> missing = new HashSet<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius + GalleryCourt.STRIP_OUT; dz++) {
                    if (!onFloor(dx, dz, radius)) {
                        continue;
                    }
                    int x = centre.getX() + dx;
                    int z = centre.getZ() + dz;
                    if (!level.hasChunk(x >> 4, z >> 4)) {
                        missing.add(ChunkPos.pack(x >> 4, z >> 4));
                        continue;
                    }
                    int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, ceiling);
                    for (int y = top; y >= floorY; y--) {
                        cursor.set(x, y, z);
                        BlockState state = level.getBlockState(cursor);
                        if (state.isAir() || (dx == 0 && dz == 0 && y == centre.getY())) {
                            continue; // the beacon goes last, once everything else has
                        }
                        if (state.hasBlockEntity()) {
                            // Stations and generators file themselves under the colony they stand in.
                            JobBoard.unregister(id, cursor);
                            LifeSupport.unregister(id, cursor);
                        }
                        level.setBlock(cursor, air, flags);
                        blocks++;
                    }
                }
            }
            unloaded = missing.size();
            entities = removeEntities(level, colony, floorY, ceiling);
            if (unloaded == 0 && level.setBlock(centre, air, flags)) {
                blocks++;
            }
        }
        if (unloaded > 0) {
            return new ClearResult(true, blocks, entities, unloaded);
        }

        // The same order a dissolve uses, except that the goods are forgotten rather than dropped:
        // they were conjured for the exhibit and there is no longer a floor to drop them on.
        for (JobBoard.Station station : JobBoard.stationsOf(id)) {
            JobBoard.unregister(id, BlockPos.of(station.packedPos()));
        }
        ColonyStores.get(server).forget(id);
        Construction.forget(server, id);
        ColonyState.get(server).remove(id);
        Population.invalidate(id);
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Gallery cleared: {} block(s), {} entit(ies).",
                blocks, entities);
        return new ClearResult(true, blocks, entities, 0);
    }

    /**
     * Discards everything the gallery spawned over the floor (labels, pen mobs, wolves, Nerans) and
     * the items lying on it, then the sandbox colony's Nerans and guardians wherever they have
     * wandered within {@value #STRAY_MARGIN} blocks of the claim. Players are never touched.
     */
    private static int removeEntities(ServerLevel level, Colony colony, int floorY, int ceiling) {
        UUID id = colony.colonyId();
        AABB floor = reach(level, colony, 2);
        AABB overFloor = new AABB(floor.minX, floorY - 2.0D, floor.minZ, floor.maxX, ceiling + 8.0D, floor.maxZ);
        Set<Entity> doomed = new LinkedHashSet<>(level.getEntitiesOfClass(Entity.class, overFloor,
                entity -> !(entity instanceof Player)
                        && (entity.entityTags().contains(GalleryKit.TAG) || entity instanceof ItemEntity)));
        AABB wide = reach(level, colony, STRAY_MARGIN);
        doomed.addAll(level.getEntitiesOfClass(ColonistEntity.class, wide,
                neran -> id.equals(neran.colonyId())));
        doomed.addAll(guardians(level, colony));
        for (Entity entity : doomed) {
            entity.discard();
        }
        return doomed.size();
    }
}
