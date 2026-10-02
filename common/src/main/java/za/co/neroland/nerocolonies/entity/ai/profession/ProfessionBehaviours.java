package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyBuildings;
import za.co.neroland.nerocolonies.colony.ColonyDefence;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * The twelve work behaviours, bound to professions by id in their data files.
 *
 * <p>Each behaviour is called every second or so while a Neran is at its workplace in working hours.
 * It either does one visible thing (harvest one crop, fell one log, heal one wounded Neran), walks
 * towards the next thing, or simply tends its post, and returns whether it did work (which earns
 * experience). Real block changes stay inside the trade's work radius around the workplace and inside
 * the claim; their drops go to colony storage. The colony's share of each trade's goods is gathered
 * separately on the colony tick (see {@code colony/Professions}), so none of this has to happen for a
 * colony to produce.
 */
public final class ProfessionBehaviours {

    /** One trade's visible work. */
    @FunctionalInterface
    public interface ProfessionBehaviour {
        /**
         * @param anchor the workplace (the spot in front of the trade's building, or a station)
         * @return whether the Neran did a piece of work this call
         */
        boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
                BlockPos anchor);
    }

    /** Within this distance a Neran acts on a block instead of walking to it. */
    private static final double REACH_SQR = 6.25D;

    private static final Map<Identifier, ProfessionBehaviour> BEHAVIOURS = new HashMap<>();

    /** Stand at the post: the fallback for an unknown behaviour id. */
    public static final ProfessionBehaviour TEND = (level, neran, colony, profession, anchor) ->
            neran.blockPosition().distSqr(anchor) <= 9.0D;

    static {
        register("farmer", ProfessionBehaviours::farm);
        register("forester", ProfessionBehaviours::forest);
        register("miner", TEND);
        register("builder", TEND);
        register("hauler", ProfessionBehaviours::haul);
        register("cook", TEND);
        register("toolsmith", TEND);
        register("guard", ProfessionBehaviours::patrol);
        register("beastkeeper", ProfessionBehaviours::tendBeasts);
        register("researcher", TEND);
        register("quartermaster", TEND);
        register("medic", ProfessionBehaviours::heal);
    }

    private ProfessionBehaviours() {
    }

    private static void register(String path, ProfessionBehaviour behaviour) {
        BEHAVIOURS.put(Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, path), behaviour);
    }

    /** The behaviour bound to an id, or {@link #TEND}. */
    public static ProfessionBehaviour get(Identifier id) {
        return BEHAVIOURS.getOrDefault(id, TEND);
    }

    /** Every behaviour id compiled into the mod, for validation and the gallery. */
    public static java.util.Set<Identifier> ids() {
        return java.util.Set.copyOf(BEHAVIOURS.keySet());
    }

    // --- shared ---------------------------------------------------------------

    /** Walks towards {@code pos} if out of reach; returns true when in reach. */
    private static boolean reach(ColonistEntity neran, BlockPos pos) {
        if (neran.blockPosition().distSqr(pos) <= REACH_SQR) {
            neran.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            return true;
        }
        neran.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.9D);
        return false;
    }

    /** The nearest position around {@code centre} matching {@code test}, scanning a bounded cube. */
    @Nullable
    private static BlockPos nearest(ServerLevel level, Colony colony, BlockPos centre, int radius, int below,
            int above, java.util.function.BiPredicate<BlockPos, BlockState> test) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -below; dy <= above; dy++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!level.isLoaded(cursor) || !colony.contains(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (test.test(cursor, state)) {
                        double distance = cursor.distSqr(centre);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean takeFromStorage(ServerLevel level, Colony colony, ItemTarget target) {
        return ColonyStorage.consume(level.getServer(), colony.colonyId(), List.of(target));
    }

    // --- farmer -----------------------------------------------------------------

    private static final ItemTarget WHEAT_SEEDS = new ItemTarget(
            java.util.Optional.of(Identifier.withDefaultNamespace("wheat_seeds")), java.util.Optional.empty(), 1);

    /** Harvest a ripe crop and replant it; otherwise sow an empty plot from stored seeds. */
    private static boolean farm(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        int radius = profession.workRadius();
        BlockPos ripe = nearest(level, colony, neran.blockPosition(), radius, 1, 1,
                (pos, state) -> state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
                        && pos.distSqr(anchor) <= (double) radius * radius);
        if (ripe != null) {
            if (!reach(neran, ripe)) {
                return false;
            }
            BlockState state = level.getBlockState(ripe);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                Construction.breakToStorage(level, colony, ripe, state);
                level.setBlock(ripe, crop.getStateForAge(0), Block.UPDATE_ALL);
                return true;
            }
            return false;
        }
        BlockPos plot = nearest(level, colony, anchor, radius, 1, 1,
                (pos, state) -> state.isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND));
        if (plot != null && reach(neran, plot) && takeFromStorage(level, colony, WHEAT_SEEDS)) {
            level.setBlock(plot, Blocks.WHEAT.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        return neran.blockPosition().distSqr(anchor) <= (double) radius * radius;
    }

    // --- forester ---------------------------------------------------------------

    /** Fell the nearest log in the yard (drops to storage); replant a sapling where a trunk stood. */
    private static boolean forest(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        int radius = profession.workRadius();
        BlockPos log = nearest(level, colony, anchor, radius, 1, 12,
                (pos, state) -> state.is(BlockTags.LOGS) && !state.is(Construction.PROTECTED)
                        && !Construction.insideStructure(level.getServer(), colony.colonyId(), pos, 0));
        if (log == null) {
            return neran.blockPosition().distSqr(anchor) <= (double) radius * radius;
        }
        BlockPos stand = new BlockPos(log.getX(), neran.getBlockY(), log.getZ());
        if (!reach(neran, stand)) {
            return false;
        }
        BlockState state = level.getBlockState(log);
        Construction.breakToStorage(level, colony, log, state);
        BlockPos ground = log.below();
        if (level.getBlockState(ground).is(BlockTags.DIRT) && level.getBlockState(log).isAir()) {
            replant(level, colony, log);
        }
        return true;
    }

    /** Saplings a forester will replant, most common first. */
    private static final List<String> SAPLINGS = List.of("oak_sapling", "birch_sapling", "spruce_sapling",
            "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling");

    private static void replant(ServerLevel level, Colony colony, BlockPos pos) {
        for (String name : SAPLINGS) {
            Identifier id = Identifier.withDefaultNamespace(name);
            if (!BuiltInRegistries.ITEM.containsKey(id)
                    || !(BuiltInRegistries.ITEM.getValue(id) instanceof BlockItem item)) {
                continue;
            }
            BlockState sapling = item.getBlock().defaultBlockState();
            ItemTarget one = new ItemTarget(java.util.Optional.of(id), java.util.Optional.empty(), 1);
            if (sapling.canSurvive(level, pos) && takeFromStorage(level, colony, one)) {
                level.setBlock(pos, sapling, Block.UPDATE_ALL);
                return;
            }
        }
    }

    // --- hauler -----------------------------------------------------------------

    /** Carry between the colony's store (the beacon) and the building site, back and forth. */
    private static boolean haul(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        neran.setStatus(NeranStatus.HAULING);
        BlockPos site = siteOf(level, colony);
        BlockPos store = colony.beaconPos();
        BlockPos goal = site == null ? store
                : (neran.inventory().isEmpty() ? store : site);
        if (!reach(neran, goal)) {
            return false;
        }
        if (goal.equals(store)) {
            neran.inventory().addItem(new ItemStack(BuiltInRegistries.ITEM.getValue(
                    Identifier.withDefaultNamespace("stick"))));
        } else {
            neran.inventory().clearContent(); // the load is delivered; it was a token, not goods
        }
        return true;
    }

    @Nullable
    private static BlockPos siteOf(ServerLevel level, Colony colony) {
        var plan = za.co.neroland.nerocolonies.colony.ColonyConstruction.get(level.getServer()).peek(colony.colonyId());
        return plan == null ? null : plan.origin();
    }

    // --- guard ------------------------------------------------------------------

    /** Walk the colony's watch posts, one after another. */
    private static boolean patrol(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        if (neran.getTarget() != null) {
            return true; // fighting is GuardTargetGoal's business
        }
        if (neran.getNavigation().isInProgress()) {
            return false;
        }
        List<ColonyBuildings.Placed> posts = ColonyBuildings.withRole(level, colony, ColonyBuildings.ROLE_GUARD);
        BlockPos next = posts.isEmpty() ? anchor
                : posts.get(level.getRandom().nextInt(posts.size())).centre();
        neran.setStatus(NeranStatus.GUARDING);
        neran.getNavigation().moveTo(next.getX() + 0.5D, next.getY(), next.getZ() + 0.5D, 0.8D);
        return true;
    }

    // --- beastkeeper --------------------------------------------------------------

    /** Feed (heal) the colony's wolves and golems near the kennel or forge. */
    private static boolean tendBeasts(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        int radius = profession.workRadius();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, neran.getBoundingBox().inflate(radius),
                ColonyDefence::isGuardian)) {
            if (mob.getHealth() < mob.getMaxHealth()) {
                if (reach(neran, mob.blockPosition())) {
                    mob.heal(4.0F);
                    return true;
                }
                return false;
            }
        }
        return neran.blockPosition().distSqr(anchor) <= (double) radius * radius;
    }

    // --- medic ------------------------------------------------------------------

    /** Patch up the nearest wounded Neran. */
    private static boolean heal(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        int radius = profession.workRadius();
        LivingEntity patient = null;
        double bestDistance = Double.MAX_VALUE;
        for (ColonistEntity other : level.getEntitiesOfClass(ColonistEntity.class,
                neran.getBoundingBox().inflate(radius),
                o -> o != neran && o.getHealth() < o.getMaxHealth() && colony.colonyId().equals(o.colonyId()))) {
            double distance = other.distanceToSqr(neran);
            if (distance < bestDistance) {
                bestDistance = distance;
                patient = other;
            }
        }
        if (patient == null) {
            return neran.blockPosition().distSqr(anchor) <= (double) radius * radius;
        }
        if (!reach(neran, patient.blockPosition())) {
            return false;
        }
        patient.heal(2.0F);
        return true;
    }
}
