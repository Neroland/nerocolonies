package za.co.neroland.nerocolonies.colony;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Colony construction: the colony picks a blueprint, picks a spot inside its own claim, clears the
 * land and lays the building out a few blocks at a time.
 *
 * <h2>What gets built, in order</h2>
 *
 * <ol>
 *   <li>A building the owner or a Chief placed with the Colony Planner, oldest first.</li>
 *   <li>An upgrade of a standing building whose blueprint names an {@code upgrade_to} the colony's
 *       stage allows.</li>
 *   <li>The highest-priority blueprint the colony is eligible for, at the best site the bounded
 *       search finds.</li>
 * </ol>
 *
 * <h2>Stages</h2>
 *
 * <p>A blueprint needs the colony to have reached its {@code stage}. While a colony is
 * {@link ColonyStage#FOUNDING} only the Starter Works are eligible, and they build <b>only from
 * materials the player supplies</b>: nothing is fabricated from scrap, so the first four buildings
 * are the player's contribution. From {@link ColonyStage#SETTLED} on, everything builds slowly from
 * scrap ({@code constructionUnsuppliedFactor}) or at full speed when its materials are in colony
 * storage, and the whole rate scales with colony size (see {@link Growth}) and with professional
 * Builders on site.
 *
 * <h2>Where it may build</h2>
 *
 * <ul>
 *   <li>Inside the claim, re-checked per cell.</li>
 *   <li>On ground flat to within two blocks once vegetation is ignored. Natural blocks in the way
 *       (tag {@code nerocolonies:clearable}) are cleared first and their drops go to colony storage;
 *       anything in {@code nerocolonies:protected}, any block entity, and any block of another
 *       finished structure is never touched.</li>
 *   <li>At least {@value #SITE_GAP} blocks from every other structure, so every building keeps a
 *       street around it — the access corridor.</li>
 *   <li>Turned so its front faces the beacon, when the blueprint allows it.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 *
 * <p>A block is never placed into a cell where any living thing stands; Nerans in the way are moved
 * to the site's access spot just outside the footprint, which is also where the builder stands.
 * When a building with an interior is finished, a bounded flood fill checks it can be walked into
 * from the access spot; if not, a doorway is opened in the face nearest that spot.
 *
 * <h2>Catch-up</h2>
 *
 * <p>{@link #catchUp} advances credit only and places nothing; see the history of this class for
 * why a backlog is never laid on the tick a chunk loads.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing player-shaped. Plans do not record who placed them.
 */
public final class Construction {

    /** The job id a Neran carries while it is the site builder (a role, reassigned each cycle). */
    public static final Identifier BUILDER_JOB =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "build");

    /** The Builder profession, whose members speed construction up by being on site. */
    public static final Identifier BUILDER_PROFESSION =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "builder");

    /** Natural blocks a site may be cleared of. */
    public static final TagKey<Block> CLEARABLE = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "clearable"));

    /** The part of {@link #CLEARABLE} that sits on top of the ground (trees, plants, snow). */
    public static final TagKey<Block> VEGETATION = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "clearable_vegetation"));

    /** Blocks the colony never breaks, whatever else they are. */
    public static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "protected"));

    private static final int CANDIDATES_PER_CYCLE = 8;
    private static final int BLUEPRINTS_PER_CYCLE = 2;
    private static final int SEARCH_COOLDOWN_CYCLES = 10;

    /** Street left around every structure: the access corridor. */
    static final int SITE_GAP = 2;

    /** How far below the beacon a site's base may sit. */
    private static final int SITE_DROP = 4;

    /** How far above the beacon a site's base may sit. */
    private static final int SITE_LIFT = 4;

    /** Ground height difference tolerated across a footprint (bumps are cleared). */
    private static final int FLATNESS = 2;

    /** Free bunks below which a housing blueprint becomes eligible. */
    private static final int HOUSING_HEADROOM = 2;

    private static final int CATCH_UP_CREDIT_CYCLES = 4;
    private static final int MAX_CELLS_PER_CYCLE = 256;

    /** Clear cells examined per cycle. */
    private static final int MAX_CLEAR_CELLS_PER_CYCLE = 64;

    /** Builders within this distance of the access spot count as on site. */
    private static final double BUILDER_RANGE_SQR = 64.0D;

    /** Speed added per Builder on site, and the most Builders that count. */
    private static final double BUILDER_BONUS = 0.5D;
    private static final int MAX_COUNTED_BUILDERS = 4;

    /** Flood-fill bound for the entrance check. */
    private static final int ENTRANCE_FILL_LIMIT = 4096;

    private Construction() {
    }

    // --- session state ------------------------------------------------------

    /** One colony's site-search state, owned by its beacon. Never saved. */
    public static final class State {

        private int candidateCursor = 1;
        /** Eligible blueprints skipped because a whole sweep found no site for them. */
        private int blueprintOffset;
        private int cooldown;
        private boolean hadSite;
        private boolean sweptOut;
        private int clearCursor;
        private IdleReason status = IdleReason.NOTHING_NEEDED;

        public IdleReason status() {
            return this.status;
        }

        public void restart() {
            this.candidateCursor = 1;
            this.blueprintOffset = 0;
            this.cooldown = 0;
            this.sweptOut = false;
        }
    }

    /** What the construction loop is doing, as the beacon shows it. Ordinal is synced: append only. */
    public enum IdleReason {
        BUILDING, DISABLED, WORK_STOPPED, LIFE_SUPPORT, NO_COLONISTS, CAP_REACHED, NOTHING_NEEDED,
        NO_SITE, AWAITING_MATERIALS
    }

    /** Snapshot of one colony's build progress, read once per beacon tick. */
    public record Readout(int percent, int built, boolean supplied) {
        public static final Readout NONE = new Readout(0, 0, false);
    }

    /** Progress, total built and supply state from a single store lookup. */
    public static Readout readout(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        if (plan == null) {
            return Readout.NONE;
        }
        return new Readout(plan.progressPercent(), plan.totalBuilt(),
                plan.active() != null && plan.supplied());
    }

    // --- the cycle ----------------------------------------------------------

    /**
     * Advances one colony's construction by one colony cycle.
     *
     * @return the blueprint id of a structure completed by this cycle, or {@code null}
     */
    @Nullable
    public static Identifier tick(ServerLevel level, Colony colony, State state) {
        MinecraftServer server = level.getServer();
        if (server == null || !NeroColoniesConfig.CONSTRUCTION_ENABLED.get()) {
            state.status = IdleReason.DISABLED;
            return null;
        }
        IdleReason blocked = workBlocker(colony);
        if (blocked != null) {
            state.status = blocked;
            releaseBuilders(level, colony, state);
            return null;
        }

        ColonyConstruction index = ColonyConstruction.get(server);
        ColonyConstruction.Plan plan = index.plan(colony.colonyId());
        ColonyStage stage = ColonyProgress.stage(server, colony);

        Blueprint blueprint = activeBlueprint(server, plan);
        if (blueprint == null) {
            if (plan.active() != null) {
                plan.abandon(); // the blueprint left with a datapack change; what stands, stays
                index.touch();
            }
            blueprint = startNext(server, level, colony, stage, state, plan, index);
            if (blueprint == null) {
                releaseBuilders(level, colony, state);
                return null;
            }
        }

        List<BlockPos> order = blueprint.buildOrder();
        if (order.size() != plan.total()) {
            plan.abandon(); // reshaped by a reload mid-build: never build a chimera
            index.touch();
            releaseBuilders(level, colony, state);
            return null;
        }

        state.hadSite = true;
        BlockPos spot = accessSpot(level, colony, plan.origin(), blueprint, plan.rotation());
        assignBuilder(level, colony, spot);

        int base = NeroColoniesConfig.constructionBlocksPerCycle();
        if (base <= 0) {
            state.status = IdleReason.BUILDING;
            return null;
        }
        if (!plan.supplied() && payMaterials(server, colony.colonyId(), blueprint)) {
            plan.markSupplied();
            index.touch();
        }
        boolean scrapAllowed = !(blueprint.starter() && stage == ColonyStage.FOUNDING);
        if (!plan.supplied() && !scrapAllowed) {
            // Starter Works while founding: the player's materials or nothing. The needs list says so.
            state.status = IdleReason.AWAITING_MATERIALS;
            return null;
        }
        state.status = IdleReason.BUILDING;

        double rate = base * Growth.buildMultiplierFor(colony.population(), plan.totalBuilt())
                * (1.0D + BUILDER_BONUS * buildersOnSite(level, colony, spot));
        double factor = plan.supplied() ? 1.0D : NeroColoniesConfig.CONSTRUCTION_UNSUPPLIED_FACTOR.get();
        plan.addCredit(rate * factor, rate * CATCH_UP_CREDIT_CYCLES);

        int placeable = (int) Math.floor(plan.credit());
        if (placeable <= 0) {
            index.touch();
            return null;
        }

        int used = clearSite(level, colony, blueprint, plan, state, placeable);
        int placed = place(level, colony, blueprint, plan, order, placeable - used);
        plan.spendCredit(used + placed);
        index.touch();

        if (plan.cursor() >= plan.total()) {
            Identifier completed = plan.active();
            BlockPos origin = plan.origin();
            Rotation rotation = plan.rotation();
            plan.complete(blueprint.level());
            index.touch();
            state.clearCursor = 0;
            releaseBuilders(level, colony, state);
            if (origin != null) {
                ensureEntrance(level, colony, blueprint, origin, rotation);
            }
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] A colony finished building {} ({} structure(s) total).",
                    completed, plan.totalBuilt());
            return completed;
        }
        return null;
    }

    /** Advances credit for an offline colony. <b>Places nothing.</b> */
    public static void catchUp(MinecraftServer server, Colony colony, int cycles, double yield) {
        if (server == null || cycles <= 0 || !NeroColoniesConfig.CONSTRUCTION_ENABLED.get()) {
            return;
        }
        ColonyConstruction index = ColonyConstruction.get(server);
        ColonyConstruction.Plan plan = index.peek(colony.colonyId());
        if (plan == null || plan.active() == null) {
            return;
        }
        int budget = NeroColoniesConfig.constructionBlocksPerCycle();
        if (budget <= 0) {
            return;
        }
        Blueprint blueprint = activeBlueprint(server, plan);
        boolean founding = ColonyProgress.stage(server, colony) == ColonyStage.FOUNDING;
        if (!plan.supplied() && blueprint != null && blueprint.starter() && founding) {
            return; // no scrap fabrication for the Starter Works, offline or not
        }
        double factor = plan.supplied() ? 1.0D : NeroColoniesConfig.CONSTRUCTION_UNSUPPLIED_FACTOR.get();
        plan.addCredit((double) cycles * budget * factor * yield, budget * (double) CATCH_UP_CREDIT_CYCLES);
        index.touch();
    }

    // --- readouts -------------------------------------------------------------

    public static int progressPercent(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan == null ? 0 : plan.progressPercent();
    }

    public static int structuresBuilt(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan == null ? 0 : plan.totalBuilt();
    }

    public static String activeNameKey(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        if (plan == null || plan.active() == null) {
            return "";
        }
        return ColonyDefinitions.blueprint(plan.active()).map(Blueprint::nameKey).orElse("");
    }

    public static boolean isSupplied(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan != null && plan.active() != null && plan.supplied();
    }

    /** The blueprint under construction, or null. */
    @Nullable
    public static Blueprint activeBlueprint(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        return plan == null || server == null ? null : activeBlueprint(server, plan);
    }

    /**
     * Whether {@code pos} lies inside the footprint (and height) of the structure this colony is
     * building right now.
     */
    public static boolean insideActiveSite(@Nullable MinecraftServer server, @Nullable UUID colonyId,
            BlockPos pos) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        if (plan == null || plan.active() == null || plan.origin() == null || server == null) {
            return false;
        }
        Blueprint blueprint = ColonyDefinitions.blueprintsForServer(server).get(plan.active());
        return blueprint != null && inside(pos, plan.origin(), blueprint, plan.rotation(), 1);
    }

    /** How far a boxed-in Neran may be moved by {@link #rescue}. */
    private static final int RESCUE_RADIUS = 8;

    /**
     * The last rung of the stuck ladder. A Neran that has given up on a walk while standing inside (or
     * right against) something the colony built or is building, with no standable cell beside it, is
     * moved to the nearest safe spot in the claim. It is never used anywhere else: a Neran stuck in
     * open country just waits out its cooldown, so this can never become a general teleport.
     *
     * @return whether the Neran was moved
     */
    public static boolean rescue(ServerLevel level, Colony colony, ColonistEntity neran) {
        MinecraftServer server = level.getServer();
        UUID id = colony.colonyId();
        BlockPos here = neran.blockPosition();
        if (!insideStructure(server, id, here, 1) && !insideActiveSite(server, id, here)) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                if (Population.standable(level, here.relative(side).above(dy))) {
                    return false; // not boxed in: it can step out by itself
                }
            }
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RESCUE_RADIUS; dx <= RESCUE_RADIUS; dx++) {
            for (int dz = -RESCUE_RADIUS; dz <= RESCUE_RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = here.offset(dx, dy, dz);
                    double distance = candidate.distSqr(here);
                    if (distance >= bestDistance || !colony.contains(candidate) || !level.isLoaded(candidate)
                            || insideStructure(server, id, candidate, 0) || insideActiveSite(server, id, candidate)
                            || !Population.standable(level, candidate)) {
                        continue;
                    }
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        if (best == null) {
            return false;
        }
        neran.getNavigation().stop();
        neran.teleportTo(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D);
        return true;
    }

    /** Whether {@code pos} lies inside any finished structure of this colony (with a margin). */
    public static boolean insideStructure(MinecraftServer server, UUID colonyId, BlockPos pos, int margin) {
        ColonyConstruction.Plan plan = planOf(server, colonyId);
        if (plan == null) {
            return false;
        }
        for (ColonyConstruction.Structure structure : plan.structures()) {
            Blueprint blueprint = ColonyDefinitions.blueprintsForServer(server).get(structure.blueprint());
            if (blueprint != null && inside(pos, structure.origin(), blueprint, structure.rotation(), margin)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(BlockPos pos, BlockPos origin, Blueprint blueprint, Rotation rotation,
            int margin) {
        return pos.getX() >= origin.getX() - margin
                && pos.getX() < origin.getX() + blueprint.width(rotation) + margin
                && pos.getZ() >= origin.getZ() - margin
                && pos.getZ() < origin.getZ() + blueprint.depth(rotation) + margin
                && pos.getY() >= origin.getY() - margin
                && pos.getY() < origin.getY() + blueprint.height() + margin;
    }

    public static void forget(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        if (server == null || colonyId == null) {
            return;
        }
        ColonyConstruction.get(server).forget(colonyId);
        // Every other per-colony side store goes with it: role lists (player data), life, caches.
        ColonyRoles.get(server).forget(colonyId);
        ColonyLife.get(server).forget(colonyId);
        ColonyBuildings.invalidate(colonyId);
        GratitudeCache.forget(colonyId);
    }

    @Nullable
    private static ColonyConstruction.Plan planOf(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        if (server == null || colonyId == null) {
            return null;
        }
        return ColonyConstruction.get(server).peek(colonyId);
    }

    // --- gates --------------------------------------------------------------

    @Nullable
    private static IdleReason workBlocker(Colony colony) {
        if (Morale.workStopped(colony)) {
            return IdleReason.WORK_STOPPED;
        }
        if (LifeSupport.stateOf(colony) == LifeSupport.State.FAILED) {
            return IdleReason.LIFE_SUPPORT;
        }
        if (NeroColoniesConfig.CONSTRUCTION_REQUIRES_COLONIST.get() && colony.population() <= 0) {
            return IdleReason.NO_COLONISTS;
        }
        return null;
    }

    @Nullable
    private static Blueprint activeBlueprint(MinecraftServer server, ColonyConstruction.Plan plan) {
        if (plan.active() == null || plan.origin() == null) {
            return null;
        }
        return ColonyDefinitions.blueprintsForServer(server).get(plan.active());
    }

    // --- choosing what, and where -------------------------------------------

    @Nullable
    private static Blueprint startNext(MinecraftServer server, ServerLevel level, Colony colony,
            ColonyStage stage, State state, ColonyConstruction.Plan plan, ColonyConstruction index) {
        // 1. Hand-placed plans first. A plan whose site has been blocked since is dropped.
        ColonyConstruction.Planned planned;
        while ((planned = plan.pollPlanned()) != null) {
            index.touch();
            Blueprint blueprint = ColonyDefinitions.blueprintsForServer(server).get(planned.blueprint());
            if (blueprint == null || !blueprint.hasGrid()) {
                continue;
            }
            if (evaluateAt(server, level, colony, blueprint, planned.origin(), planned.rotation()) != null) {
                plan.begin(blueprint.id(), planned.origin(), planned.rotation(), blueprint.buildOrder().size());
                state.status = IdleReason.BUILDING;
                return blueprint;
            }
        }

        // 2. Upgrades of standing buildings.
        List<ColonyConstruction.Structure> structures = plan.structures();
        for (int i = 0; i < structures.size(); i++) {
            ColonyConstruction.Structure structure = structures.get(i);
            Blueprint current = ColonyDefinitions.blueprintsForServer(server).get(structure.blueprint());
            if (current == null || current.upgradeTo().isEmpty()) {
                continue;
            }
            Blueprint next = ColonyDefinitions.blueprintsForServer(server).get(current.upgradeTo().get());
            if (next == null || !next.hasGrid() || !stage.atLeast(next.stage())
                    || next.width() != current.width() || next.depth() != current.depth()
                    || !researched(colony, next)) {
                continue;
            }
            plan.beginUpgrade(i, next.id(), next.buildOrder().size());
            index.touch();
            state.status = IdleReason.BUILDING;
            return next;
        }

        if (plan.totalBuilt() >= autoStructureCap(stage)) {
            state.status = IdleReason.CAP_REACHED;
            return null;
        }
        if (state.cooldown > 0) {
            state.cooldown--;
            state.status = IdleReason.NO_SITE;
            return null;
        }

        // 3. The autonomous planner. Each cycle the most urgent eligible blueprints are tried against
        //    the same slice of the site spiral. When a whole sweep finds nowhere for them, the next
        //    sweep starts with the blueprints after them, so one building that fits nowhere never
        //    holds up the rest.
        state.status = IdleReason.NOTHING_NEEDED;
        int start = state.candidateCursor;
        int seen = 0;
        int tried = 0;
        boolean allSwept = true;
        for (Blueprint blueprint : ColonyDefinitions.blueprintsByPriority(server)) {
            if (!eligible(colony, stage, plan, blueprint)) {
                continue;
            }
            state.status = IdleReason.NO_SITE;
            if (seen++ < state.blueprintOffset) {
                continue;
            }
            if (tried >= BLUEPRINTS_PER_CYCLE) {
                break;
            }
            tried++;
            state.candidateCursor = start;
            state.sweptOut = false;
            Site site = findSite(server, level, colony, state, blueprint);
            if (site != null) {
                plan.begin(blueprint.id(), site.corner(), site.rotation(), blueprint.buildOrder().size());
                index.touch();
                state.candidateCursor = 1;
                state.blueprintOffset = 0;
                state.sweptOut = false;
                state.status = IdleReason.BUILDING;
                return blueprint;
            }
            allSwept &= state.sweptOut;
        }
        state.sweptOut = false;
        if (tried == 0) {
            // Past the end of the list: every eligible blueprint has had a full sweep. Rest, then
            // start again from the most urgent.
            if (state.blueprintOffset > 0) {
                state.blueprintOffset = 0;
                state.cooldown = SEARCH_COOLDOWN_CYCLES;
            }
            state.candidateCursor = 1;
            return null;
        }
        if (allSwept) {
            state.candidateCursor = 1;
            state.blueprintOffset += tried;
        } else {
            state.candidateCursor = start + CANDIDATES_PER_CYCLE;
        }
        return null;
    }

    /**
     * The autonomous structure cap. {@code maxAutoStructures} is the cap for a Settled colony; each
     * stage past that adds the same again, so a growing colony keeps building.
     */
    private static int autoStructureCap(ColonyStage stage) {
        int base = Math.max(0, NeroColoniesConfig.MAX_AUTO_STRUCTURES.get());
        int stagesPastSettled = Math.max(0, stage.ordinal() - ColonyStage.SETTLED.ordinal());
        return base * (1 + stagesPastSettled);
    }

    private static boolean researched(Colony colony, Blueprint blueprint) {
        return blueprint.research().isEmpty()
                || colony.researchUnlocked().contains(blueprint.research().get().toString());
    }

    /** Whether the colony may start this blueprint on its own right now. */
    static boolean eligible(Colony colony, ColonyStage stage, ColonyConstruction.Plan plan,
            Blueprint blueprint) {
        if (!blueprint.hasGrid() || blueprint.max() <= 0 || plan.builtCount(blueprint.id()) >= blueprint.max()) {
            return false;
        }
        if (stage == ColonyStage.FOUNDING ? !blueprint.starter() : !stage.atLeast(blueprint.stage())) {
            return false;
        }
        if (blueprint.level() > 1) {
            return false; // higher levels arrive only as upgrades of the level below
        }
        if (blueprint.width() > NeroColoniesConfig.MAX_BLUEPRINT_FOOTPRINT.get()
                || blueprint.depth() > NeroColoniesConfig.MAX_BLUEPRINT_FOOTPRINT.get()
                || blueprint.height() > NeroColoniesConfig.MAX_BLUEPRINT_HEIGHT.get()) {
            return false;
        }
        if (!researched(colony, blueprint)) {
            return false;
        }
        if (blueprint.category() == Blueprint.Category.HOUSING && !blueprint.starter()) {
            return colony.housingCapacity() - colony.population() < HOUSING_HEADROOM;
        }
        return true;
    }

    /** A chosen site: minimum corner (with its Y) and rotation. */
    public record Site(BlockPos corner, Rotation rotation) {
    }

    @Nullable
    private static Site findSite(MinecraftServer server, ServerLevel level, Colony colony, State state,
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
            Rotation rotation = blueprint.rotate() ? faceBeacon(cell[0], cell[1]) : Rotation.NONE;
            int w = blueprint.width(rotation);
            int d = blueprint.depth(rotation);
            BlockPos corner = evaluate(server, level, colony, blueprint, rotation, centreX - w / 2,
                    centreZ - d / 2);
            if (corner != null) {
                return new Site(corner, rotation);
            }
        }
        if (state.candidateCursor >= limit) {
            state.sweptOut = true;
        }
        return null;
    }

    /**
     * The rotation that turns a blueprint's front (its +Z, south face) towards the beacon, for a site
     * offset {@code (dx, dz)} from it. Pure; unit-tested.
     */
    public static Rotation faceBeacon(int dx, int dz) {
        if (Math.abs(dz) >= Math.abs(dx)) {
            return dz > 0 ? Rotation.CLOCKWISE_180 : Rotation.NONE;
        }
        return dx > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
    }

    /**
     * Checks a hand-picked corner (from a plan or the planner preview) and returns it with its
     * resolved Y, or null if the building will not fit there now.
     */
    @Nullable
    public static BlockPos evaluateAt(MinecraftServer server, ServerLevel level, Colony colony,
            Blueprint blueprint, BlockPos corner, Rotation rotation) {
        return evaluate(server, level, colony, blueprint, rotation, corner.getX(), corner.getZ());
    }

    /** Whether a turned footprint fits with its minimum corner at {@code (originX, originZ)}. */
    @Nullable
    private static BlockPos evaluate(MinecraftServer server, ServerLevel level, Colony colony,
            Blueprint blueprint, Rotation rotation, int originX, int originZ) {
        int beaconY = colony.beaconPos().getY();
        int width = blueprint.width(rotation);
        int depth = blueprint.depth(rotation);
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;

        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                if (!colony.contains(new BlockPos(x, beaconY, z)) || !level.hasChunk(x >> 4, z >> 4)) {
                    return null;
                }
                int ground = groundAt(level, x, z);
                lowest = Math.min(lowest, ground);
                highest = Math.max(highest, ground);
            }
        }
        if (lowest == Integer.MAX_VALUE || highest - lowest > FLATNESS) {
            return null;
        }
        int baseY = lowest;
        if (baseY < beaconY - SITE_DROP || baseY > beaconY + SITE_LIFT) {
            return null;
        }
        BlockPos origin = new BlockPos(originX, baseY, originZ);
        if (overlapsStructure(server, colony, origin, width, depth, blueprint.height())) {
            return null;
        }
        boolean clearing = NeroColoniesConfig.LAND_CLEARING_ENABLED.get();
        for (BlockPos cell : blueprint.buildOrder()) {
            BlockPos pos = origin.offset(blueprint.rotate(cell, rotation));
            BlockState existing = level.getBlockState(pos);
            if (!existing.canBeReplaced() && !(clearing && clearable(level, pos, existing))) {
                return null;
            }
            if (cell.getY() == 0 && level.getBlockState(pos.below()).canBeReplaced()) {
                return null; // no floating structures
            }
        }
        return origin;
    }

    /** The ground surface at a column, ignoring vegetation (trees, plants, snow). */
    private static int groundAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, y - 1, z);
        int floor = level.getMinY();
        while (cursor.getY() > floor) {
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && !state.is(VEGETATION)) {
                break;
            }
            cursor.move(Direction.DOWN);
        }
        return cursor.getY() + 1;
    }

    /** Whether a turned footprint, widened by the street gap, touches another finished structure. */
    private static boolean overlapsStructure(MinecraftServer server, Colony colony, BlockPos origin, int width,
            int depth, int height) {
        ColonyConstruction.Plan plan = planOf(server, colony.colonyId());
        if (plan == null) {
            return false;
        }
        for (ColonyConstruction.Structure structure : plan.structures()) {
            Blueprint other = ColonyDefinitions.blueprintsForServer(server).get(structure.blueprint());
            int ow = other == null ? 1 : other.width(structure.rotation());
            int od = other == null ? 1 : other.depth(structure.rotation());
            BlockPos o = structure.origin();
            boolean apartX = origin.getX() + width + SITE_GAP <= o.getX()
                    || o.getX() + ow + SITE_GAP <= origin.getX();
            boolean apartZ = origin.getZ() + depth + SITE_GAP <= o.getZ()
                    || o.getZ() + od + SITE_GAP <= origin.getZ();
            if (!apartX && !apartZ) {
                return true;
            }
        }
        return false;
    }

    /** Whether the colony may break this block to make room: natural, unprotected, no block entity. */
    static boolean clearable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.is(CLEARABLE) && !state.is(PROTECTED) && !state.hasBlockEntity()
                && level.getBlockEntity(pos) == null;
    }

    /** The {@code index}-th cell of a square spiral out from the origin. */
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

    // --- clearing and building ------------------------------------------------

    /** Clears natural blocks from the blueprint's clear cells, top down. Returns budget used. */
    private static int clearSite(ServerLevel level, Colony colony, Blueprint blueprint,
            ColonyConstruction.Plan plan, State state, int budget) {
        if (!NeroColoniesConfig.LAND_CLEARING_ENABLED.get() || plan.origin() == null || budget <= 0) {
            return 0;
        }
        List<BlockPos> cells = blueprint.clearCells();
        int used = 0;
        int examined = 0;
        while (used < budget && state.clearCursor < cells.size() && examined < MAX_CLEAR_CELLS_PER_CYCLE) {
            examined++;
            // Top down, so trees come down from the crown and sand never ends up floating.
            BlockPos cell = cells.get(cells.size() - 1 - state.clearCursor);
            BlockPos pos = plan.origin().offset(blueprint.rotate(cell, plan.rotation()));
            if (!level.isLoaded(pos)) {
                break;
            }
            state.clearCursor++;
            if (!colony.contains(pos)) {
                continue;
            }
            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir() && !existing.canBeReplaced() && clearable(level, pos, existing)) {
                breakToStorage(level, colony, pos, existing);
                used++;
            }
        }
        return used;
    }

    private static int place(ServerLevel level, Colony colony, Blueprint blueprint,
            ColonyConstruction.Plan plan, List<BlockPos> order, int budget) {
        BlockPos origin = plan.origin();
        if (origin == null || budget <= 0) {
            return 0;
        }
        boolean clearing = NeroColoniesConfig.LAND_CLEARING_ENABLED.get();
        boolean upgrade = plan.isUpgrade();
        int placed = 0;
        int examined = 0;
        while (placed < budget && plan.cursor() < order.size() && examined < MAX_CELLS_PER_CYCLE) {
            examined++;
            BlockPos cell = order.get(plan.cursor());
            BlockPos pos = origin.offset(blueprint.rotate(cell, plan.rotation()));
            if (!level.isLoaded(pos)) {
                break; // the chunk is away; hold the cursor
            }
            BlockState target = blueprint.stateAt(cell.getX(), cell.getY(), cell.getZ(), plan.rotation());
            BlockState existing = level.getBlockState(pos);
            boolean needed = target != null && colony.contains(pos) && !existing.equals(target);
            if (needed && occupied(level, pos)) {
                moveNeransOut(level, colony, origin, blueprint, plan.rotation(), pos);
                break; // never place a block into anybody
            }
            plan.advanceCursor(1);
            if (!needed || target == null) {
                continue;
            }
            if (existing.is(target.getBlock())) {
                level.setBlock(pos, target, Block.UPDATE_ALL); // same block, new orientation
                continue;
            }
            if (!existing.canBeReplaced()) {
                boolean mayClear = clearing && clearable(level, pos, existing);
                boolean mayReplace = upgrade && !existing.is(PROTECTED) && !existing.hasBlockEntity()
                        && level.getBlockEntity(pos) == null;
                if (!mayClear && !mayReplace) {
                    continue; // somebody built here; the player wins
                }
                breakToStorage(level, colony, pos, existing);
            }
            level.setBlock(pos, target, Block.UPDATE_ALL);
            placed++;
        }
        return placed;
    }

    /** Breaks a block and puts its drops in colony storage; what does not fit is dropped, never voided. */
    public static void breakToStorage(ServerLevel level, Colony colony, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null);
        level.removeBlock(pos, false);
        MinecraftServer server = level.getServer();
        int slots = ColonyStorage.usableSlots(level, colony);
        for (ItemStack drop : drops) {
            int left = ColonyStorage.insert(server, colony.colonyId(), drop.copy(), slots);
            if (left > 0) {
                ItemStack rest = drop.copy();
                rest.setCount(left);
                Block.popResource(level, pos, rest);
            }
        }
    }

    /** Whether any living entity's box overlaps the cell. */
    private static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), LivingEntity::isAlive).isEmpty();
    }

    private static void moveNeransOut(ServerLevel level, Colony colony, BlockPos origin, Blueprint blueprint,
            Rotation rotation, BlockPos pos) {
        BlockPos spot = accessSpot(level, colony, origin, blueprint, rotation);
        if (spot == null) {
            return;
        }
        for (ColonistEntity colonist : level.getEntitiesOfClass(ColonistEntity.class, new AABB(pos))) {
            colonist.getNavigation().stop();
            colonist.teleportTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
        }
    }

    /**
     * A standable cell just outside a turned footprint, preferring the front face, then nearest the
     * beacon: where the builder stands and where anyone in the way is moved to.
     */
    @Nullable
    public static BlockPos accessSpot(ServerLevel level, Colony colony, @Nullable BlockPos origin,
            Blueprint blueprint, Rotation rotation) {
        if (origin == null) {
            return null;
        }
        BlockPos beacon = colony.beaconPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int width = blueprint.width(rotation);
        int depth = blueprint.depth(rotation);
        int minX = origin.getX() - 1;
        int maxX = origin.getX() + width;
        int minZ = origin.getZ() - 1;
        int maxZ = origin.getZ() + depth;
        BlockPos front = frontCentre(origin, width, depth, rotation);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x != minX && x != maxX && z != minZ && z != maxZ) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = new BlockPos(x, origin.getY() + dy, z);
                    if (!colony.contains(candidate) || !level.isLoaded(candidate)
                            || !Population.standable(level, candidate)) {
                        continue;
                    }
                    double score = candidate.distSqr(front) * 4.0D + candidate.distSqr(beacon) * 0.01D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** The cell just outside the middle of a turned footprint's front face. */
    private static BlockPos frontCentre(BlockPos origin, int width, int depth, Rotation rotation) {
        Direction front = rotation.rotate(Direction.SOUTH);
        int cx = origin.getX() + width / 2;
        int cz = origin.getZ() + depth / 2;
        return switch (front) {
            case NORTH -> new BlockPos(cx, origin.getY(), origin.getZ() - 1);
            case EAST -> new BlockPos(origin.getX() + width, origin.getY(), cz);
            case WEST -> new BlockPos(origin.getX() - 1, origin.getY(), cz);
            default -> new BlockPos(cx, origin.getY(), origin.getZ() + depth);
        };
    }

    // --- entrance check ---------------------------------------------------------

    /**
     * Makes sure a finished building with an interior can be walked into from its access spot. If a
     * bounded flood fill cannot reach any interior cell, a 1×2 doorway is opened in the face nearest
     * the access spot (its blocks go to colony storage).
     */
    private static void ensureEntrance(ServerLevel level, Colony colony, Blueprint blueprint, BlockPos origin,
            Rotation rotation) {
        BlockPos spot = accessSpot(level, colony, origin, blueprint, rotation);
        if (spot == null) {
            return;
        }
        int width = blueprint.width(rotation);
        int depth = blueprint.depth(rotation);
        if (!hasInterior(level, origin, width, depth, blueprint.height()) || reachesInterior(level, spot,
                origin, width, depth, blueprint.height())) {
            return;
        }
        // Open the wall cell between the access spot and the footprint.
        int x = Math.clamp(spot.getX(), origin.getX(), origin.getX() + width - 1);
        int z = Math.clamp(spot.getZ(), origin.getZ(), origin.getZ() + depth - 1);
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos pos = new BlockPos(x, spot.getY() + dy, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(PROTECTED) && !state.hasBlockEntity()) {
                breakToStorage(level, colony, pos, state);
            }
        }
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        return (feet.isAir() || !feet.isSolid() || feet.is(net.minecraft.tags.BlockTags.DOORS))
                && (head.isAir() || !head.isSolid() || head.is(net.minecraft.tags.BlockTags.DOORS));
    }

    private static boolean hasInterior(ServerLevel level, BlockPos origin, int width, int depth, int height) {
        for (int y = 1; y < Math.min(height, 4); y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int z = 1; z < depth - 1; z++) {
                    if (passable(level, origin.offset(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean reachesInterior(ServerLevel level, BlockPos start, BlockPos origin, int width,
            int depth, int height) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty() && seen.size() < ENTRANCE_FILL_LIMIT) {
            BlockPos pos = open.poll();
            if (pos.getX() > origin.getX() && pos.getX() < origin.getX() + width - 1
                    && pos.getZ() > origin.getZ() && pos.getZ() < origin.getZ() + depth - 1) {
                return true;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = pos.relative(direction).above(dy);
                    if (next.getX() < origin.getX() - 2 || next.getX() > origin.getX() + width + 1
                            || next.getZ() < origin.getZ() - 2 || next.getZ() > origin.getZ() + depth + 1
                            || next.getY() < origin.getY() - 1 || next.getY() > origin.getY() + height) {
                        continue;
                    }
                    if (!seen.contains(next) && passable(level, next)) {
                        seen.add(next);
                        open.add(next);
                    }
                }
            }
        }
        return false;
    }

    // --- materials and builders -------------------------------------------------

    private static boolean payMaterials(MinecraftServer server, UUID colonyId, Blueprint blueprint) {
        if (blueprint.materials().isEmpty()) {
            return true;
        }
        return ColonyStorage.consume(server, colonyId, blueprint.materials());
    }

    /** Professional Builders standing near the access spot (capped). */
    private static int buildersOnSite(ServerLevel level, Colony colony, @Nullable BlockPos spot) {
        if (spot == null) {
            return 0;
        }
        int count = 0;
        for (ColonistEntity colonist : Population.colonistsOf(level, colony)) {
            if (BUILDER_PROFESSION.equals(colonist.professionId())
                    && colonist.blockPosition().distSqr(spot) <= BUILDER_RANGE_SQR) {
                count++;
            }
        }
        return Math.min(count, MAX_COUNTED_BUILDERS);
    }

    /** Points Builders (or, failing that, one idle adult) at the site's access spot. */
    private static void assignBuilder(ServerLevel level, Colony colony, @Nullable BlockPos spot) {
        if (spot == null) {
            return;
        }
        boolean anyBuilder = false;
        for (ColonistEntity colonist : Population.colonistsOf(level, colony)) {
            if (BUILDER_PROFESSION.equals(colonist.professionId()) && !colonist.isChildNeran()) {
                colonist.setJobStationPos(spot);
                colonist.setJobId(BUILDER_JOB);
                anyBuilder = true;
            }
        }
        if (anyBuilder) {
            return;
        }
        for (ColonistEntity colonist : Population.colonistsOf(level, colony)) {
            if (colonist.jobStationPos() == null && !colonist.isChildNeran()) {
                colonist.setJobStationPos(spot);
                colonist.setJobId(BUILDER_JOB);
                return;
            }
        }
    }

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
