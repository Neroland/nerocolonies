package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.ai.DayCycle;

/**
 * Trades: who has which, where they work, what tools they carry, and what they gather.
 *
 * <h2>Assignment</h2>
 *
 * <p>Each finished building opens {@code per_building} places in every trade its blueprint
 * {@code unlocks}, capped by the trade's {@code max_per_colony}. Every colony cycle adults keep their
 * trade if a place still exists for it; adults without one take the open trade with the lowest
 * {@code priority}. Children have no trade until they grow up. A Neran's workplace is the spot in
 * front of the nearest building that opens its trade (a job station may claim it instead; see
 * {@code JobBoard}).
 *
 * <h2>Simulated work</h2>
 *
 * <p>During daylight each tradesperson gathers its trade's {@code outputs} into colony storage with
 * probability {@code output_chance}, scaled by morale, level and whether it has its tool. This runs on
 * the colony tick, so it does not depend on the Neran being loaded near a player or pathing anywhere:
 * the visible work in the world is extra, not the source of the goods. It is also how a colony slowly
 * fills its own needs list. Nothing is ever voided: output that does not fit in storage is simply not
 * produced that cycle.
 *
 * <h2>Special trades</h2>
 *
 * <ul>
 *   <li><b>Toolsmith</b> — hands out tools from storage, and makes them from an iron ingot when there
 *       are none.</li>
 *   <li><b>Cook</b> — turns stored food items into colony food stock.</li>
 *   <li><b>Medic</b> — lifts morale a little each cycle.</li>
 *   <li><b>Quartermaster</b> — stocks the Gratitude Cache (see {@link GratitudeCache}).</li>
 *   <li><b>Beastkeeper</b> — keeps the colony's guardian animals (see {@link ColonyDefence}).</li>
 * </ul>
 */
public final class Professions {

    public static final Identifier TOOLSMITH = id("toolsmith");
    public static final Identifier COOK = id("cook");
    public static final Identifier MEDIC = id("medic");
    public static final Identifier QUARTERMASTER = id("quartermaster");
    public static final Identifier BEASTKEEPER = id("beastkeeper");

    private static final ItemTarget IRON_INGOT = new ItemTarget(Optional.of(Identifier.withDefaultNamespace("iron_ingot")),
            Optional.empty(), 1);
    private static final ItemTarget FOOD_ITEM = new ItemTarget(Optional.empty(),
            Optional.of(FoodSupply.FOOD.location()), 1);

    /** Food stock a cook adds per stored food item turned into meals. */
    private static final int FOOD_PER_MEAL = 3;

    /** Morale a medic adds per cycle, and the ceiling on the total. */
    private static final double MEDIC_MORALE = 0.5D;
    private static final double MEDIC_MORALE_CAP = 2.0D;

    /** Output bonus per level above the first. */
    private static final double LEVEL_BONUS = 0.15D;

    private Professions() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, path);
    }

    /** How many places each trade has, from the colony's finished buildings. */
    public static Map<Identifier, Integer> places(ServerLevel level, Colony colony) {
        Map<Identifier, ProfessionDefinition> trades = ColonyDefinitions.professionsForServer(level.getServer());
        Map<Identifier, Integer> places = new HashMap<>();
        for (ColonyBuildings.Placed placed : ColonyBuildings.placed(level, colony)) {
            for (Identifier unlock : placed.blueprint().unlocks()) {
                ProfessionDefinition trade = trades.get(unlock);
                if (trade != null) {
                    places.merge(unlock, trade.perBuilding(), Integer::sum);
                }
            }
        }
        places.replaceAll((trade, count) -> Math.min(count, trades.get(trade).maxPerColony()));
        return places;
    }

    /** Hands out trades and workplaces. Called once per colony cycle, before the job board. */
    public static void assign(ServerLevel level, Colony colony) {
        Map<Identifier, ProfessionDefinition> trades = ColonyDefinitions.professionsForServer(level.getServer());
        Map<Identifier, Integer> open = new HashMap<>(places(level, colony));
        List<ColonistEntity> adults = new ArrayList<>();
        for (ColonistEntity neran : Population.colonistsOf(level, colony)) {
            if (neran.isChildNeran()) {
                if (neran.professionId() != null) {
                    neran.setProfession(null);
                }
            } else {
                adults.add(neran);
            }
        }
        adults.sort(Comparator.comparingLong(ColonistEntity::arrivedAt).thenComparing(ColonistEntity::getStringUUID));

        // Keep existing trades where places remain.
        List<ColonistEntity> unplaced = new ArrayList<>();
        for (ColonistEntity neran : adults) {
            Identifier trade = neran.professionId();
            int left = trade == null ? 0 : open.getOrDefault(trade, 0);
            if (trade != null && left > 0 && trades.containsKey(trade)) {
                open.put(trade, left - 1);
            } else {
                if (trade != null) {
                    neran.setProfession(null);
                }
                unplaced.add(neran);
            }
        }
        // Fill open places, highest priority first.
        List<ProfessionDefinition> byPriority = new ArrayList<>(trades.values());
        byPriority.sort(Comparator.comparingInt(ProfessionDefinition::priority)
                .thenComparing(p -> p.id().toString()));
        for (ColonistEntity neran : unplaced) {
            for (ProfessionDefinition trade : byPriority) {
                int left = open.getOrDefault(trade.id(), 0);
                if (left > 0) {
                    open.put(trade.id(), left - 1);
                    neran.setProfession(trade.id());
                    break;
                }
            }
        }
        // Workplaces.
        for (ColonistEntity neran : adults) {
            Identifier trade = neran.professionId();
            if (trade == null) {
                continue;
            }
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (ColonyBuildings.Placed placed : ColonyBuildings.unlocking(level, colony, trade)) {
                BlockPos spot = placed.access() != null ? placed.access() : placed.centre();
                double distance = spot.distSqr(neran.blockPosition());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = spot;
                }
            }
            if (best != null) {
                neran.setJobStationPos(best);
                neran.setJobId(trade);
            }
        }
    }

    /**
     * The colony tick's half of every trade: tools, gathered goods, meals, morale, the cache and the
     * guardians. Returns the colony record with food and morale updated.
     */
    public static Colony produce(ServerLevel level, Colony colony) {
        MinecraftServer server = level.getServer();
        boolean daylight = !DayCycle.isNight(level, false);
        int slots = ColonyStorage.usableSlots(level, colony);
        double morale = Morale.outputMultiplier(colony);
        RandomSource random = level.getRandom();
        Map<Identifier, Integer> counts = new HashMap<>();
        boolean toolsmith = false;
        ColonyLife.Life life = server == null ? null : ColonyLife.get(server).peek(colony.colonyId());
        Identifier priority = life == null ? null : life.priorityNeed();
        Identifier priorityTag = life == null ? null : life.priorityTag();

        List<ColonistEntity> roster = Population.colonistsOf(level, colony);
        for (ColonistEntity neran : roster) {
            if (neran.professionId() != null && !neran.isChildNeran()) {
                counts.merge(neran.professionId(), 1, Integer::sum);
                toolsmith |= TOOLSMITH.equals(neran.professionId());
            }
        }

        for (ColonistEntity neran : roster) {
            ProfessionDefinition trade = neran.profession();
            if (trade == null || neran.isChildNeran()) {
                continue;
            }
            provisionTool(server, colony, neran, trade, toolsmith);
            if (!daylight || Morale.workStopped(colony)) {
                continue;
            }
            neran.addProfessionXp(1);
            double chance = trade.outputChance() * morale * (neran.hasTool() ? 1.0D : 0.5D)
                    * (1.0D + LEVEL_BONUS * (neran.professionLevel() - 1));
            if (ColonyNeeds.boosted(trade, priority, priorityTag)) {
                chance *= ColonyNeeds.PRIORITY_BOOST;
            }
            int batches = (int) Math.floor(chance) + (random.nextDouble() < chance - Math.floor(chance) ? 1 : 0);
            for (int b = 0; b < batches; b++) {
                for (ItemAmount output : trade.outputs()) {
                    ItemStack stack = output.toStack();
                    if (!stack.isEmpty()) {
                        ColonyStorage.insert(server, colony.colonyId(), stack, slots);
                    }
                }
            }
        }

        Colony updated = colony;
        if (daylight) {
            int cooks = counts.getOrDefault(COOK, 0);
            for (int i = 0; i < cooks; i++) {
                if (updated.foodStock() >= FoodSupply.MAX_FOOD_STOCK
                        || !ColonyStorage.consume(server, colony.colonyId(), List.of(FOOD_ITEM))) {
                    break;
                }
                updated = updated.withFoodStock(updated.foodStock() + FOOD_PER_MEAL);
            }
            int medics = counts.getOrDefault(MEDIC, 0);
            if (medics > 0) {
                double lift = Math.min(MEDIC_MORALE_CAP, medics * MEDIC_MORALE);
                updated = updated.withMorale(Math.min(100.0D, updated.morale() + lift));
            }
        }
        GratitudeCache.tick(level, updated, counts.getOrDefault(QUARTERMASTER, 0));
        ColonyDefence.tick(level, updated, counts.getOrDefault(BEASTKEEPER, 0) > 0);
        return updated;
    }

    /** Gives a tradesperson its tool from storage, or has the toolsmith make one from an iron ingot. */
    private static void provisionTool(MinecraftServer server, Colony colony, ColonistEntity neran,
            ProfessionDefinition trade, boolean toolsmith) {
        if (neran.hasTool() || trade.toolItem() == null) {
            neran.updateHeldTool();
            return;
        }
        Identifier toolId = BuiltInRegistries.ITEM.getKey(trade.toolItem());
        ItemTarget stored = new ItemTarget(Optional.of(toolId), Optional.empty(), 1);
        boolean got = ColonyStorage.consume(server, colony.colonyId(), List.of(stored))
                || (toolsmith && ColonyStorage.consume(server, colony.colonyId(), List.of(IRON_INGOT)));
        if (got) {
            neran.inventory().addItem(trade.toolStack());
        }
        neran.updateHeldTool();
    }
}
