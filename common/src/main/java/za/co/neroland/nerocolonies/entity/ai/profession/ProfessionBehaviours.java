package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyBuildings;
import za.co.neroland.nerocolonies.colony.ColonyDefence;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * The twelve work behaviours, bound to professions by id in their data files.
 *
 * <p>Each behaviour is called every second or so while a Neran is at work in working hours. It does
 * one real piece of its trade (harvest or sow or till one cell, fell one log, dig one block, cook one
 * dish, carry one load, refine one batch, tidy the store, heal one patient), walks towards the next
 * one, or tends its post, and returns whether it did work (which earns experience). The block-changing
 * trades follow the colony's land rules: inside the claim, re-checked per cell, never a protected block
 * or a block entity, never into a cell somebody occupies, every drop to colony storage. Trades that put
 * goods in storage mark the Neran's productive time, and the colony tick then skips its rolled outputs
 * for that Neran (see {@code colony/WorkAccounting}).
 *
 * <ul>
 *   <li>{@link FieldWork} — Farmer</li>
 *   <li>{@link ForestWork} — Forester</li>
 *   <li>{@link QuarryWork} — Miner</li>
 *   <li>{@link SiteWork} — Builder</li>
 *   <li>{@link CarryWork} — Hauler</li>
 *   <li>{@link KitchenWork} — Cook</li>
 *   <li>{@link ToolWork} — Toolsmith</li>
 *   <li>{@link StudyWork} — Researcher</li>
 *   <li>{@link StoreWork} — Quartermaster</li>
 *   <li>Guard, Beastkeeper and Medic below.</li>
 * </ul>
 */
public final class ProfessionBehaviours {

    /** One trade's work. */
    @FunctionalInterface
    public interface ProfessionBehaviour {
        /**
         * @param anchor the workplace (the spot in front of the trade's building, or a station)
         * @return whether the Neran did a piece of work this call
         */
        boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
                BlockPos anchor);
    }

    private static final Map<Identifier, ProfessionBehaviour> BEHAVIOURS = new HashMap<>();

    /** Stand at the post: the fallback for an unknown behaviour id. */
    public static final ProfessionBehaviour TEND = (level, neran, colony, profession, anchor) ->
            neran.blockPosition().distSqr(anchor) <= 9.0D;

    static {
        register("farmer", FieldWork::work);
        register("forester", ForestWork::work);
        register("miner", QuarryWork::work);
        register("builder", SiteWork::work);
        register("hauler", CarryWork::haul);
        register("cook", KitchenWork::work);
        register("toolsmith", ToolWork::work);
        register("guard", ProfessionBehaviours::patrol);
        register("beastkeeper", ProfessionBehaviours::tendBeasts);
        register("researcher", StudyWork::work);
        register("quartermaster", StoreWork::work);
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

    private static boolean reach(ColonistEntity neran, BlockPos pos) {
        return WorkSupport.reach(neran, pos);
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
