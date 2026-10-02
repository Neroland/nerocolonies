package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The colony's needs list: what it is short of, why, and how long until it has it.
 *
 * <p>The list is <b>derived</b>, never stored: every time it is asked for it is worked out from
 * three sources, so it cannot drift from the truth.
 *
 * <ol>
 *   <li><b>Construction</b> — the unpaid materials of the building under way (or, while founding,
 *       of the next Starter Works). Marked {@code STARTER} when only the player can supply them.</li>
 *   <li><b>Food</b> — the shortfall against a few cycles of what the colony eats.</li>
 *   <li><b>Tools</b> — tradespeople without their trade's tool.</li>
 * </ol>
 *
 * <p>Each need shows two times. <em>Alone</em> is how long the colony's own gatherers will take at
 * their current rate (or "never" if nobody gathers that item, as with the Starter Works).
 * <em>With help</em> is zero: deliver it to a Colony Depot or the Needs Board and it is satisfied
 * at once. Contributions always go to colony storage, which is the one place needs are paid from, so
 * a contribution satisfies the list before anything else can use it.
 *
 * <p>The owner or a Chief may prioritise one need; trades that gather it work half as fast again.
 */
public final class ColonyNeeds {

    /** Why the colony needs something. Lower-case name is the payload value. */
    public enum Reason {
        STARTER, CONSTRUCTION, FOOD, TOOLS;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One line of the needs list.
     *
     * @param label           an item id, or {@code #tag} for a tag
     * @param nameKey         translation key for display
     * @param etaSoloMinutes  minutes until the colony gathers it alone, or -1 for "not without help"
     */
    public record Need(ItemTarget target, String label, String nameKey, int needed, int have, Reason reason,
            int etaSoloMinutes, boolean priority) {

        public int missing() {
            return Math.max(0, this.needed - this.have);
        }
    }

    /** Cycles of food the colony likes to keep in hand. */
    private static final int FOOD_BUFFER_CYCLES = 10;

    /** Extra gathering speed for the prioritised need. */
    public static final double PRIORITY_BOOST = 1.5D;

    private static final int TICKS_PER_MINUTE = 1_200;

    private ColonyNeeds() {
    }

    /** The colony's needs right now, most urgent first. */
    public static List<Need> derive(ServerLevel level, Colony colony) {
        MinecraftServer server = level.getServer();
        ColonyStage stage = ColonyProgress.stage(server, colony);
        Identifier priority = Optional.ofNullable(ColonyLife.get(server).peek(colony.colonyId()))
                .map(ColonyLife.Life::priorityNeed).orElse(null);
        Map<Identifier, Double> rates = gatherRates(level, colony, priority);
        List<Need> needs = new ArrayList<>();

        // 1. Construction materials.
        Blueprint building = Construction.activeBlueprint(server, colony.colonyId());
        boolean supplied = Construction.isSupplied(server, colony.colonyId());
        if (building == null && stage == ColonyStage.FOUNDING) {
            List<Blueprint> missing = ColonyProgress.missingStarterWorks(server, colony);
            building = missing.isEmpty() ? null : missing.get(0);
            supplied = false;
        }
        if (building != null && !supplied) {
            Reason reason = building.starter() && stage == ColonyStage.FOUNDING ? Reason.STARTER
                    : Reason.CONSTRUCTION;
            for (ItemTarget material : building.materials()) {
                int have = ColonyStorage.count(server, colony.colonyId(), material);
                if (have < material.count()) {
                    needs.add(need(material, material.count(), have, reason, rates, priority));
                }
            }
        }

        // 2. Food.
        long demand = FoodSupply.demandPerCycle(colony) * FOOD_BUFFER_CYCLES;
        if (demand > 0 && colony.foodStock() < demand) {
            ItemTarget food = new ItemTarget(Optional.empty(), Optional.of(FoodSupply.FOOD.location()), 1);
            needs.add(new Need(food, "#" + FoodSupply.FOOD.location(), "need.nerocolonies.food",
                    (int) Math.min(Integer.MAX_VALUE, demand), colony.foodStock(), Reason.FOOD, -1, false));
        }

        // 3. Tools.
        Map<Identifier, Integer> lacking = new HashMap<>();
        for (ColonistEntity neran : Population.colonistsOf(level, colony)) {
            ProfessionDefinition trade = neran.profession();
            if (trade != null && trade.toolItem() != null && !neran.hasTool()) {
                lacking.merge(BuiltInRegistries.ITEM.getKey(trade.toolItem()), 1, Integer::sum);
            }
        }
        lacking.forEach((tool, count) -> {
            ItemTarget target = new ItemTarget(Optional.of(tool), Optional.empty(), count);
            int have = ColonyStorage.count(server, colony.colonyId(), target);
            if (have < count) {
                needs.add(need(target, count, have, Reason.TOOLS, rates, priority));
            }
        });

        needs.sort(ORDER);
        return needs;
    }

    /** Priority first, then by reason (starter, construction, food, tools), then by most missing. */
    static final Comparator<Need> ORDER = Comparator
            .comparing((Need n) -> !n.priority())
            .thenComparing(Need::reason)
            .thenComparing(Comparator.comparingInt(Need::missing).reversed())
            .thenComparing(Need::label);

    private static Need need(ItemTarget target, int needed, int have, Reason reason,
            Map<Identifier, Double> rates, @Nullable Identifier priority) {
        double rate = rateFor(target, rates);
        boolean prioritised = priority != null && target.item().map(priority::equals).orElse(false);
        int eta = reason == Reason.STARTER ? -1
                : etaMinutes(needed - have, rate, NeroColoniesConfig.colonyTickInterval());
        return new Need(target, target.label(), nameKey(target), needed, have, reason, eta, prioritised);
    }

    /** Translation key for a target: the item's own description, or a generic tag line. */
    private static String nameKey(ItemTarget target) {
        if (target.item().isPresent() && BuiltInRegistries.ITEM.containsKey(target.item().get())) {
            return BuiltInRegistries.ITEM.getValue(target.item().get()).getDescriptionId();
        }
        return target.tag().map(tag -> "tag.item." + tag.getNamespace() + "." + tag.getPath().replace('/', '.'))
                .orElse("need.nerocolonies.unknown");
    }

    /**
     * Minutes for gatherers producing {@code ratePerCycle} items a cycle to gather {@code missing}
     * items, or -1 when nothing is gathering it. Pure; unit-tested.
     */
    public static int etaMinutes(int missing, double ratePerCycle, int cycleTicks) {
        if (missing <= 0) {
            return 0;
        }
        if (ratePerCycle <= 0.0D) {
            return -1;
        }
        double ticks = Math.ceil(missing / ratePerCycle) * Math.max(1, cycleTicks);
        return Math.clamp(Math.round(ticks / TICKS_PER_MINUTE), 1, Integer.MAX_VALUE);
    }

    /** Items gathered per colony cycle by the colony's tradespeople, by item id. */
    private static Map<Identifier, Double> gatherRates(ServerLevel level, Colony colony,
            @Nullable Identifier priority) {
        Map<Identifier, Double> rates = new HashMap<>();
        double morale = Morale.outputMultiplier(colony);
        for (ColonistEntity neran : Population.colonistsOf(level, colony)) {
            ProfessionDefinition trade = neran.profession();
            if (trade == null || neran.isChildNeran()) {
                continue;
            }
            // Half the day is night, so the average rate is half the daytime rate.
            double chance = trade.outputChance() * morale * (neran.hasTool() ? 1.0D : 0.5D) * 0.5D;
            if (priority != null && gathers(trade, priority)) {
                chance *= PRIORITY_BOOST;
            }
            for (ItemAmount output : trade.outputs()) {
                rates.merge(output.item(), chance * output.count(), Double::sum);
            }
        }
        return rates;
    }

    /** Whether a trade's outputs include an item. */
    public static boolean gathers(ProfessionDefinition trade, Identifier item) {
        for (ItemAmount output : trade.outputs()) {
            if (output.item().equals(item)) {
                return true;
            }
        }
        return false;
    }

    private static double rateFor(ItemTarget target, Map<Identifier, Double> rates) {
        double rate = 0.0D;
        for (Map.Entry<Identifier, Double> entry : rates.entrySet()) {
            if (!BuiltInRegistries.ITEM.containsKey(entry.getKey())) {
                continue;
            }
            if (target.matches(new ItemStack(BuiltInRegistries.ITEM.getValue(entry.getKey())))) {
                rate += entry.getValue();
            }
        }
        return rate;
    }

    // --- whole-build estimates --------------------------------------------------

    /** Minutes until the current building is finished: alone, and if supplied now. -1 = not alone. */
    public record BuildEta(int soloMinutes, int helpMinutes) {
        public static final BuildEta NONE = new BuildEta(0, 0);
    }

    /** The two estimates for the building under way (or the next Starter Works). */
    public static BuildEta buildEta(ServerLevel level, Colony colony, List<Need> needs) {
        MinecraftServer server = level.getServer();
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        Blueprint building = Construction.activeBlueprint(server, colony.colonyId());
        ColonyStage stage = ColonyProgress.stage(server, colony);
        int remaining;
        boolean supplied = false;
        if (building != null && plan != null) {
            remaining = Math.max(0, plan.total() - plan.cursor());
            supplied = plan.supplied();
        } else if (stage == ColonyStage.FOUNDING) {
            List<Blueprint> missing = ColonyProgress.missingStarterWorks(server, colony);
            if (missing.isEmpty()) {
                return BuildEta.NONE;
            }
            building = missing.get(0);
            remaining = building.blockCount();
        } else {
            return BuildEta.NONE;
        }
        int cycle = NeroColoniesConfig.colonyTickInterval();
        double rate = NeroColoniesConfig.constructionBlocksPerCycle() * Growth.buildMultiplierFor(
                colony.population(), plan == null ? 0 : plan.totalBuilt());
        int help = etaMinutes(remaining, rate, cycle);
        if (supplied) {
            return new BuildEta(help, help);
        }
        // Alone: either gather every material and then build at full speed, or (when scrap is
        // allowed) fabricate at the unsupplied rate — whichever is sooner.
        int gather = 0;
        for (Need need : needs) {
            if (need.reason() == Reason.STARTER || need.reason() == Reason.CONSTRUCTION) {
                gather = need.etaSoloMinutes() < 0 || gather < 0 ? -1 : Math.max(gather, need.etaSoloMinutes());
            }
        }
        int viaGather = gather < 0 || help < 0 ? -1 : gather + help;
        boolean scrap = !(building.starter() && stage == ColonyStage.FOUNDING);
        int viaScrap = scrap ? etaMinutes(remaining,
                rate * NeroColoniesConfig.CONSTRUCTION_UNSUPPLIED_FACTOR.get(), cycle) : -1;
        int solo = viaGather < 0 ? viaScrap : (viaScrap < 0 ? viaGather : Math.min(viaGather, viaScrap));
        return new BuildEta(solo, help);
    }

    // --- contributions ------------------------------------------------------------

    /**
     * Takes from {@code stack} whatever the needs list still wants of it and puts it in colony
     * storage. Returns how many items were accepted (the stack is shrunk by that much).
     */
    public static int contribute(ServerLevel level, Colony colony, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int wanted = 0;
        for (Need need : derive(level, colony)) {
            if (need.reason() != Reason.FOOD && need.target().matches(stack)) {
                wanted += need.missing();
            }
        }
        if (wanted <= 0) {
            return 0;
        }
        int offer = Math.min(wanted, stack.getCount());
        ItemStack give = stack.copyWithCount(offer);
        int left = ColonyStorage.insert(level.getServer(), colony.colonyId(), give,
                ColonyStorage.usableSlots(level, colony));
        int accepted = offer - left;
        stack.shrink(accepted);
        return accepted;
    }
}
