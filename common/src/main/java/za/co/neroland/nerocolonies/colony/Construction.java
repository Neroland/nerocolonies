package za.co.neroland.nerocolonies.colony;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Autonomous colony construction: the colony picks a blueprint, picks a spot inside its own claim,
 * and lays it out a few blocks at a time.
 *
 * <h2>The pillar this serves</h2>
 *
 * <p>NeroColonies is an <b>automation sink, not a management sim</b>. A player founds a colony and
 * the colony gets on with it: two founders arrive with the beacon, they put up a habitat, the habitat
 * houses more colonists, and the loop turns without a single build order being issued. The player's
 * lever is <b>supply</b>, not command — bring the materials and the same structure goes up four times
 * faster. There is deliberately no build queue UI, no "assign this colonist to that job", and no way
 * to place a blueprint by hand: every one of those is a step toward being a worse version of a mod
 * that already exists.
 *
 * <h2>Supplied and unsupplied</h2>
 *
 * <p>A structure's materials are looked for in <b>colony storage</b> at the start of every work
 * cycle until they are found. Once paid, the build runs at {@code constructionBlocksPerCycle}. Until
 * then the colonists fabricate from scrap: the same build, free, at
 * {@code constructionUnsuppliedFactor} of the rate (0.25 by default). Nothing is ever <em>blocked</em>
 * on materials — a colony that is left alone still grows, just slowly — because a colony that stops
 * dead waiting for iron is a colony the player has to babysit.
 *
 * <h2>Where it may build, and where it may not</h2>
 *
 * <ul>
 *   <li><b>Inside the claim only.</b> Every cell is re-checked against
 *       {@link Colony#contains(BlockPos)} immediately before it is placed, not merely when the site
 *       was chosen — a claim can shrink when a range module is pulled out.</li>
 *   <li><b>Never over anything.</b> A cell is placed only into a block that reports
 *       {@link BlockState#canBeReplaced()} — air, grass, snow, water. A player's chest, wall or torch
 *       is never overwritten, and neither is another structure.</li>
 *   <li><b>Loaded chunks only.</b> The site search skips unloaded chunks rather than loading them,
 *       and placement pauses if a chunk goes away mid-build. The beacon-driven colony tick already
 *       guarantees the beacon's own chunk is loaded; the far edge of a 48-block claim may not be.</li>
 *   <li><b>Near the beacon's level.</b> A site must sit within a few blocks of the beacon's height,
 *       which keeps a colony from terracing up a cliff and — the practical reason — keeps what it
 *       builds inside the band {@code HousingScan} actually reads.</li>
 * </ul>
 *
 * <h2>Stop conditions</h2>
 *
 * <p>Building pauses (never cancels, never demolishes) when morale has stopped work, when life
 * support has FAILED, when {@code constructionRequiresColonist} is set and the roster is empty, or
 * when the colony has hit {@code maxAutoStructures}. These are the same gates jobs use, for the same
 * reason: the failure curve is life support → morale → work stops → idle, and construction is work.
 *
 * <h2>Catch-up</h2>
 *
 * <p>{@link #catchUp} advances <b>fabrication credit only and places no blocks at all</b>. Placing a
 * backlog's worth of blocks on the tick a chunk loads would be a visible stutter and a lighting
 * update storm at exactly the worst moment. The credit is capped at
 * {@value #CATCH_UP_CREDIT_CYCLES} cycles' worth, so a returning player sees the build resume briskly
 * for a few cycles and then settle to the normal rate.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing here reads or writes anything player-shaped. A plan is keyed by colony id, a site is a
 * block position inside that colony's own claim, and every log line is a count or a blueprint id.
 */
public final class Construction {

    /**
     * The job id a colonist carries while it is the site builder.
     *
     * <p>A <b>role, not a personality</b>: it is one of the four fields a colonist already has, it is
     * reassigned from scratch every colony cycle, and any colonist will do. Builders are picked from
     * whoever the job board did not need this cycle, so building never competes with production for
     * hands.
     *
     * <p>It is also purely cosmetic. Block placement is colony-tick logic and does not consult the
     * builder at all — {@code constructionRequiresColonist} asks whether the colony <em>has</em>
     * anybody, never whether anybody arrived. A colonist that cannot path to the site (a wall, a
     * cliff, deep water) must not be able to stall a colony's growth.
     */
    public static final Identifier BUILDER_JOB =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "build");

    /** Candidate sites examined per colony cycle. Bounded: each one reads a footprint of blocks. */
    private static final int CANDIDATES_PER_CYCLE = 8;

    /** Blueprints a single cycle will try to find a site for before giving up until the next one. */
    private static final int BLUEPRINTS_PER_CYCLE = 2;

    /** Colony cycles to wait after a full unsuccessful sweep before searching again. */
    private static final int SEARCH_COOLDOWN_CYCLES = 10;

    /** Gap left between structures, in blocks, so a colony has streets rather than a solid slab. */
    private static final int SITE_GAP = 2;

    /** How far below the beacon a site's base may sit. Matches the housing sweep's lower band. */
    private static final int SITE_DROP = 4;

    /** How far above the beacon a structure's top may reach. Matches the housing sweep's upper band. */
    private static final int SITE_RISE = 12;

    /**
     * Free bunks below which a housing blueprint becomes eligible. A colony with room to spare does
     * not build another habitat — that is what stops "autonomous" turning into "sprawls forever".
     */
    private static final int HOUSING_HEADROOM = 2;

    /** Cycles' worth of fabrication credit an offline colony may bank. See the class notes. */
    private static final int CATCH_UP_CREDIT_CYCLES = 4;

    /** Ceiling on cells examined in one placement pass, so a wall of skips cannot spin the tick. */
    private static final int MAX_CELLS_PER_CYCLE = 256;

    private Construction() {
    }

    // --- session state ------------------------------------------------------

    /**
     * One colony's site-search state. Owned by the colony beacon's block entity alongside the housing
     * cursor, so it lives exactly as long as the beacon is loaded and never needs saving: the durable
     * half of a build (which blueprint, which corner, how far along) is in {@link ColonyConstruction},
     * and a restarted search costs one bounded sweep.
     */
    public static final class State {

        private int candidateCursor = 1; // 1, not 0: ring zero is the beacon's own column
        private int cooldown;
        private boolean hadSite;
        private boolean sweptOut;

        /** Forces a fresh site search (used when the claim radius changes). */
        public void restart() {
            this.candidateCursor = 1;
            this.cooldown = 0;
            this.sweptOut = false;
        }
    }

    // --- the cycle ----------------------------------------------------------

    /**
     * Advances one colony's construction by one colony cycle.
     *
     * <p>Called from {@code ColonyTicker} after the job board, so builders are drawn from the
     * colonists production did not need.
     *
     * @return the blueprint id of a structure completed by this cycle, or {@code null}
     */
    @Nullable
    public static Identifier tick(ServerLevel level, Colony colony, State state) {
        MinecraftServer server = level.getServer();
        if (server == null || !NeroColoniesConfig.CONSTRUCTION_ENABLED.get()) {
            return null;
        }
        if (!mayWork(colony)) {
            releaseBuilders(level, colony, state);
            return null;
        }

        ColonyConstruction index = ColonyConstruction.get(server);
        ColonyConstruction.Plan plan = index.plan(colony.colonyId());

        Blueprint blueprint = activeBlueprint(server, plan);
        if (blueprint == null) {
            if (plan.active() != null) {
                // The blueprint was removed by a datapack change mid-build. Drop the site quietly;
                // what has been placed stays standing, because nothing this mod builds is ever
                // demolished automatically.
                plan.abandon();
                index.touch();
            }
            blueprint = startSite(server, level, colony, state, plan, index);
            if (blueprint == null) {
                releaseBuilders(level, colony, state);
                return null;
            }
        }

        // The build order is resolved once per cycle and checked against the cursor's own idea of how
        // long it is. They can only disagree if a datapack reload reshaped the blueprint mid-build,
        // in which case the cursor means nothing any more: abandon the site rather than build a
        // chimera or stall forever short of a total that can no longer be reached.
        List<BlockPos> order = blueprint.buildOrder();
        if (order.size() != plan.total()) {
            plan.abandon();
            index.touch();
            releaseBuilders(level, colony, state);
            return null;
        }

        state.hadSite = true;
        assignBuilder(level, colony, plan.origin());

        int budget = Math.max(0, NeroColoniesConfig.CONSTRUCTION_BLOCKS_PER_CYCLE.get());
        if (budget <= 0) {
            return null;
        }
        // Supply is re-checked every cycle, not only when the site opened: bringing materials to a
        // build already under way is the whole "speed it up" lever.
        if (!plan.supplied() && payMaterials(server, colony.colonyId(), blueprint)) {
            plan.markSupplied();
            index.touch();
        }
        double factor = plan.supplied() ? 1.0D : NeroColoniesConfig.CONSTRUCTION_UNSUPPLIED_FACTOR.get();
        plan.addCredit(budget * factor, budget * (double) CATCH_UP_CREDIT_CYCLES);

        int placeable = Math.min(budget, (int) Math.floor(plan.credit()));
        if (placeable <= 0) {
            index.touch();
            return null;
        }

        int placed = place(level, colony, blueprint, plan, order, placeable);
        plan.spendCredit(placed);
        index.touch();

        if (plan.cursor() >= plan.total()) {
            Identifier completed = plan.active();
            plan.complete();
            index.touch();
            releaseBuilders(level, colony, state);
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] A colony finished building {} ({} structure(s) total).",
                    completed, plan.totalBuilt());
            return completed;
        }
        return null;
    }

    /**
     * Advances fabrication credit for an offline colony. <b>Places nothing</b> — see the class notes.
     *
     * @param cycles colony cycles missed while the beacon's chunk was unloaded
     * @param yield  {@code catchUpEfficiency}
     */
    public static void catchUp(MinecraftServer server, Colony colony, int cycles, double yield) {
        if (server == null || cycles <= 0 || !NeroColoniesConfig.CONSTRUCTION_ENABLED.get()) {
            return;
        }
        ColonyConstruction index = ColonyConstruction.get(server);
        ColonyConstruction.Plan plan = index.peek(colony.colonyId());
        if (plan == null || plan.active() == null) {
            return; // nothing was under way; a colony does not start a build while nobody is there
        }
        int budget = Math.max(0, NeroColoniesConfig.CONSTRUCTION_BLOCKS_PER_CYCLE.get());
        if (budget <= 0) {
            return;
        }
        double factor = plan.supplied() ? 1.0D : NeroColoniesConfig.CONSTRUCTION_UNSUPPLIED_FACTOR.get();
        plan.addCredit((double) cycles * budget * factor * yield, budget * (double) CATCH_UP_CREDIT_CYCLES);
        index.touch();
    }

    // --- readouts (server-side; the beacon turns these into synced values) ---

    /** Progress through the structure this colony is building, 0..100. Zero when idle. */
    public static int progressPercent(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan == null ? 0 : plan.progressPercent();
    }

    /** How many structures this colony has built for itself. */
    public static int structuresBuilt(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan == null ? 0 : plan.totalBuilt();
    }

    /**
     * The translation key of the structure being built, or {@code ""} when the colony is idle. A key
     * rather than a rendered string: what the client shows is the client's business.
     */
    public static String activeNameKey(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        if (plan == null || plan.active() == null) {
            return "";
        }
        return ColonyDefinitions.blueprint(plan.active()).map(Blueprint::nameKey).orElse("");
    }

    /** Whether this colony's current structure has had its materials paid out of storage. */
    public static boolean isSupplied(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan != null && plan.active() != null && plan.supplied();
    }

    /** Forgets a colony's construction record. Called from every dissolve path. */
    public static void forget(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        if (server == null || colonyId == null) {
            return;
        }
        ColonyConstruction.get(server).forget(colonyId);
    }

    @Nullable
    private static ColonyConstruction.Plan planOf(@Nullable MinecraftServer server,
            @Nullable UUID colonyId) {
        if (server == null || colonyId == null) {
            return null;
        }
        return ColonyConstruction.get(server).peek(colonyId);
    }

    // --- gates --------------------------------------------------------------

    /** The same gates jobs use: work stops, life support has failed, or nobody is left to build. */
    private static boolean mayWork(Colony colony) {
        if (Morale.workStopped(colony)) {
            return false;
        }
        if (LifeSupport.stateOf(colony) == LifeSupport.State.FAILED) {
            return false;
        }
        return !NeroColoniesConfig.CONSTRUCTION_REQUIRES_COLONIST.get() || colony.population() > 0;
    }

    @Nullable
    private static Blueprint activeBlueprint(MinecraftServer server, ColonyConstruction.Plan plan) {
        if (plan.active() == null || plan.origin() == null) {
            return null;
        }
        return ColonyDefinitions.blueprintsForServer(server).get(plan.active());
    }

    // --- choosing what, and where -------------------------------------------

    /**
     * Picks the next blueprint the colony is allowed to build and finds it a site.
     *
     * @return the blueprint that was started, or {@code null} if nothing was eligible or no site was
     *         found within this cycle's search budget
     */
    @Nullable
    private static Blueprint startSite(MinecraftServer server, ServerLevel level, Colony colony,
            State state, ColonyConstruction.Plan plan, ColonyConstruction index) {
        if (state.cooldown > 0) {
            state.cooldown--;
            return null;
        }
        if (plan.totalBuilt() >= NeroColoniesConfig.MAX_AUTO_STRUCTURES.get()) {
            return null;
        }

        state.sweptOut = false;
        int tried = 0;
        for (Blueprint blueprint : ColonyDefinitions.blueprintsByPriority(server)) {
            if (!eligible(colony, plan, blueprint)) {
                continue;
            }
            if (tried++ >= BLUEPRINTS_PER_CYCLE) {
                break;
            }
            BlockPos corner = findSite(level, colony, state, blueprint);
            if (corner != null) {
                plan.begin(blueprint.id(), corner, blueprint.buildOrder().size());
                index.touch();
                state.candidateCursor = 1; // the next structure starts its own search near the beacon
                state.sweptOut = false;
                return blueprint;
            }
        }
        // A whole sweep found nowhere to put anything: rest before looking again. Without this a
        // boxed-in colony would pay for a full search on every cycle forever.
        if (state.sweptOut) {
            state.candidateCursor = 1;
            state.sweptOut = false;
            state.cooldown = SEARCH_COOLDOWN_CYCLES;
        }
        return null;
    }

    /** Whether the colony may build this blueprint at all right now. */
    private static boolean eligible(Colony colony, ColonyConstruction.Plan plan, Blueprint blueprint) {
        if (blueprint.max() <= 0 || plan.builtCount(blueprint.id()) >= blueprint.max()) {
            return false;
        }
        if (blueprint.research().isPresent()
                && !colony.researchUnlocked().contains(blueprint.research().get().toString())) {
            return false;
        }
        if (blueprint.category() == Blueprint.Category.HOUSING) {
            // Only when the colony is actually short of bunks, so housing tracks population pressure
            // instead of sprawling to the edge of the claim.
            return colony.housingCapacity() - colony.population() < HOUSING_HEADROOM;
        }
        return true;
    }

    /**
     * Looks for somewhere to put {@code blueprint}, resuming from where the last search left off and
     * examining at most {@value #CANDIDATES_PER_CYCLE} candidates.
     *
     * <p>Candidates are walked in <b>rings out from the beacon</b>, so a colony grows outward from
     * its centre rather than filling the claim from one corner. Ring zero is skipped: that is the
     * beacon's own column.
     *
     * @return the structure's minimum corner, or {@code null} if nothing suitable turned up this cycle
     */
    @Nullable
    private static BlockPos findSite(ServerLevel level, Colony colony, State state,
            Blueprint blueprint) {
        int stride = Math.max(blueprint.width(), blueprint.depth()) + SITE_GAP;
        int rings = Math.max(1, colony.claimRadius() / stride);
        int limit = (2 * rings + 1) * (2 * rings + 1);
        BlockPos beacon = colony.beaconPos();

        int examined = 0;
        while (examined < CANDIDATES_PER_CYCLE && state.candidateCursor < limit) {
            int index = state.candidateCursor++;
            examined++;
            int[] cell = ringCell(index);
            int centreX = beacon.getX() + cell[0] * stride;
            int centreZ = beacon.getZ() + cell[1] * stride;
            BlockPos corner = evaluate(level, colony, blueprint,
                    centreX - blueprint.width() / 2, centreZ - blueprint.depth() / 2);
            if (corner != null) {
                return corner;
            }
        }
        if (state.candidateCursor >= limit) {
            state.sweptOut = true; // startSite turns this into a cooldown
        }
        return null;
    }

    /**
     * Whether a structure fits with its minimum corner at {@code (originX, originZ)}, and at what
     * height.
     *
     * @return the resolved minimum corner (with its Y), or {@code null} if the spot will not do
     */
    @Nullable
    private static BlockPos evaluate(ServerLevel level, Colony colony, Blueprint blueprint,
            int originX, int originZ) {
        int beaconY = colony.beaconPos().getY();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;

        for (int dx = 0; dx < blueprint.width(); dx++) {
            for (int dz = 0; dz < blueprint.depth(); dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                if (!colony.contains(new BlockPos(x, beaconY, z))) {
                    return null; // never outside the claim, not even by one column
                }
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    return null; // never load a chunk to look for somewhere to build
                }
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                lowest = Math.min(lowest, ground);
                highest = Math.max(highest, ground);
            }
        }
        if (lowest == Integer.MAX_VALUE || highest - lowest > 1) {
            return null; // not flat enough; colonies level nothing and dig nothing
        }
        int baseY = highest;
        if (baseY < beaconY - SITE_DROP || baseY + blueprint.height() > beaconY + SITE_RISE) {
            return null;
        }

        // Every cell the blueprint would fill must be free, and the bottom layer must have something
        // to stand on. Both are re-checked at placement time as well, because minutes pass between.
        for (int y = 0; y < blueprint.height(); y++) {
            for (int dz = 0; dz < blueprint.depth(); dz++) {
                for (int dx = 0; dx < blueprint.width(); dx++) {
                    if (blueprint.blockAt(dx, y, dz) == null) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(originX + dx, baseY + y, originZ + dz);
                    if (!level.getBlockState(pos).canBeReplaced()) {
                        return null;
                    }
                    if (y == 0 && level.getBlockState(pos.below()).canBeReplaced()) {
                        return null; // no floating structures
                    }
                }
            }
        }
        return new BlockPos(originX, baseY, originZ);
    }

    /**
     * The {@code index}-th cell of a square spiral out from the origin: index 0 is {@code (0, 0)},
     * indices 1..8 are the ring around it, and so on. Arithmetic rather than a precomputed list, so a
     * search that spans several colony cycles keeps its place in one {@code int}.
     */
    private static int[] ringCell(int index) {
        if (index <= 0) {
            return new int[] {0, 0};
        }
        int ring = 1;
        int start = 1;
        while (index >= start + 8 * ring) {
            start += 8 * ring;
            ring++;
        }
        int offset = index - start;
        int side = 2 * ring;
        return switch (offset / side) {
            case 0 -> new int[] {-ring + offset % side, -ring};
            case 1 -> new int[] {ring, -ring + offset % side};
            case 2 -> new int[] {ring - offset % side, ring};
            default -> new int[] {-ring, ring - offset % side};
        };
    }

    // --- building -----------------------------------------------------------

    /**
     * Places up to {@code budget} blocks of the current structure, advancing the cursor over cells it
     * skips.
     *
     * <p>A cell is skipped — cursor forward, budget untouched — when the block is already there (a
     * resumed build), when something unreplaceable has appeared since the site was chosen (a player
     * built there first, and the player wins), or when the claim has shrunk out from under it. A cell
     * whose <em>chunk</em> has gone away is not skipped: the pass stops and resumes next cycle, so a
     * structure never ends up with a hole in it because somebody walked away mid-build.
     *
     * @return how many blocks were actually placed
     */
    private static int place(ServerLevel level, Colony colony, Blueprint blueprint,
            ColonyConstruction.Plan plan, List<BlockPos> order, int budget) {
        BlockPos origin = plan.origin();
        if (origin == null) {
            return 0;
        }
        int placed = 0;
        int examined = 0;
        while (placed < budget && plan.cursor() < order.size() && examined < MAX_CELLS_PER_CYCLE) {
            examined++;
            BlockPos cell = order.get(plan.cursor());
            BlockPos pos = origin.offset(cell);
            if (!level.isLoaded(pos)) {
                break; // the chunk is away; hold the cursor and try again next cycle
            }
            plan.advanceCursor(1);
            if (!colony.contains(pos)) {
                continue;
            }
            Block block = blueprint.blockAt(cell.getX(), cell.getY(), cell.getZ());
            if (block == null) {
                continue;
            }
            BlockState existing = level.getBlockState(pos);
            if (existing.is(block)) {
                continue; // already standing (a resumed build, or the player got there first)
            }
            if (!existing.canBeReplaced()) {
                continue; // somebody built here since the site was chosen; leave it alone
            }
            level.setBlock(pos, block.defaultBlockState(), 3);
            placed++;
        }
        return placed;
    }

    /**
     * Tries to pay a blueprint's materials out of colony storage, all or nothing.
     *
     * <p>A blueprint with no material list is "paid" immediately and therefore always builds at full
     * speed — which is how a pack author writes a cheap starter structure.
     */
    private static boolean payMaterials(MinecraftServer server, UUID colonyId, Blueprint blueprint) {
        if (blueprint.materials().isEmpty()) {
            return true;
        }
        return ColonyStorage.consume(server, colonyId, blueprint.materials());
    }

    // --- the builder role ---------------------------------------------------

    /**
     * Points one otherwise-idle colonist at the site, so a player can see where the colony is working.
     *
     * <p>Picked from colonists the job board left unassigned this cycle, so building never takes
     * hands away from production. If everyone is busy, nobody is reassigned and the structure goes up
     * anyway — see {@link #BUILDER_JOB} for why that is deliberate rather than a shortcut.
     */
    private static void assignBuilder(ServerLevel level, Colony colony, @Nullable BlockPos site) {
        if (site == null) {
            return;
        }
        for (ColonistEntity colonist : Population.colonistsOf(level, colony)) {
            if (colonist.jobStationPos() == null) {
                colonist.setJobStationPos(site);
                colonist.setJobId(BUILDER_JOB);
                return;
            }
        }
    }

    /**
     * Clears every builder assignment when the colony stops building, so nobody keeps walking to a
     * structure that is finished or a site that was abandoned.
     *
     * <p>Only runs on the transition into idleness ({@code hadSite}), because the roster query is not
     * free and an idle colony would otherwise pay for it on every cycle forever.
     */
    private static void releaseBuilders(ServerLevel level, Colony colony, State state) {
        if (!state.hadSite) {
            return;
        }
        state.hadSite = false;
        for (ColonistEntity colonist : Population.colonistsOf(level, colony)) {
            if (BUILDER_JOB.equals(colonist.jobId())) {
                colonist.setJobStationPos(null);
                colonist.setJobId(null);
            }
        }
    }
}
