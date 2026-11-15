package za.co.neroland.nerocolonies.colony;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The colony food chain: what counts as food, how it gets into the store, and how it leaves.
 *
 * <h2>Tags, never item ids</h2>
 *
 * <p>Food is recognised entirely through item tags. NeroColonies hard-codes <b>no</b> food item
 * anywhere, which is what lets NeroAgriculture's produce, Nerospace's rations, a third-party farming
 * mod's crops and plain vanilla bread all feed a colony with no compat code on either side — and
 * lets a pack author redefine the whole diet without touching a line of Java.
 *
 * <p>Two tags, and the split matters:
 * <ul>
 *   <li>{@link #STAPLE} — bulk produce. Eaten <b>first</b>.</li>
 *   <li>{@link #FOOD} — everything the colony will accept, including the staples.</li>
 * </ul>
 *
 * <p>Eating staples first is the whole reason there are two tags: without it, a colony fed from a
 * mixed supply line would happily eat the rare, high-value item that was on its way to the export
 * buffer. The shipped {@code nerocolonies:colony_food} tag includes {@code #c:foods} and
 * {@code #c:crops} as optional entries, so it is populated the moment any mod that follows the
 * common-tag convention is installed and is never empty on vanilla either.
 *
 * <h2>The store</h2>
 *
 * <p>{@code Colony.foodStock} is an abstract ration count, not an inventory: food items placed in
 * the beacon's supply slots are converted into it and the item is consumed. Food kept in colony
 * storage (its Storage Crates and depots) counts too: each cycle the ration store is topped up from
 * storage to {@value #COMFORTABLE_BUFFER_CYCLES} cycles' worth ({@link #intakeFromStorage}), staples
 * first, so the rest stays in the crates where players can see and take it. That keeps the hot path
 * (every colony, every cycle) to integer arithmetic rather than an inventory walk, and it means a
 * colony's food reserve survives its beacon's chunk unloading without keeping an inventory loaded.
 */
public final class FoodSupply {

    /** Everything a colony will eat. Datapack-overridable; ships including {@code #c:foods}. */
    public static final TagKey<Item> FOOD = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colony_food"));

    /** Bulk produce, eaten before anything else so valuable food is not consumed by accident. */
    public static final TagKey<Item> STAPLE = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colony_food/staple"));

    /** Rations one food item is worth. One item, one ration — the interesting numbers are in config. */
    private static final int RATIONS_PER_ITEM = 1;

    /** Hard ceiling on the stored ration count, so a comparator/data slot can never wrap. */
    public static final int MAX_FOOD_STOCK = 30_000;

    /**
     * Cycles of buffer that count as "well fed" for the morale term. Below this the food term falls
     * off proportionally; above it there is no further bonus, so hoarding is not a morale strategy.
     */
    private static final int COMFORTABLE_BUFFER_CYCLES = 8;

    private FoodSupply() {
    }

    /** Whether this stack is something a colony will eat at all. */
    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.typeHolder().is(FOOD);
    }

    /** Whether this stack is bulk produce, and therefore first in the queue to be eaten. */
    public static boolean isStaple(ItemStack stack) {
        return !stack.isEmpty() && stack.typeHolder().is(STAPLE);
    }

    /**
     * Moves food out of the beacon's supply slots and into the colony's ration store, staples first.
     *
     * @return the colony record with its food stock raised (possibly the same instance)
     */
    public static Colony intake(Container supply, Colony colony) {
        int stock = colony.foodStock();
        if (stock >= MAX_FOOD_STOCK) {
            return colony;
        }
        int gained = 0;
        // Two passes so staples are drawn down before anything else in the same container.
        gained += drawFrom(supply, true, MAX_FOOD_STOCK - stock - gained);
        gained += drawFrom(supply, false, MAX_FOOD_STOCK - stock - gained);
        return gained <= 0 ? colony : colony.withFoodStock(Math.min(MAX_FOOD_STOCK, stock + gained));
    }

    /**
     * Seed a colony keeps back from the table: a crop item that is also what its Farmers plant
     * (carrots, potatoes) is only eaten down to this many.
     */
    public static final int SEED_RESERVE = 16;

    /**
     * Tops the ration store up from colony storage to {@value #COMFORTABLE_BUFFER_CYCLES} cycles of
     * demand, staples first, keeping {@value #SEED_RESERVE} of each plantable crop back. Food above
     * that buffer stays in storage. The export buffer is never touched.
     *
     * @return the colony record with its food stock raised (possibly the same instance)
     */
    public static Colony intakeFromStorage(net.minecraft.server.MinecraftServer server, Colony colony) {
        return intakeFromStorage(server, colony, 0);
    }

    /**
     * {@link #intakeFromStorage(net.minecraft.server.MinecraftServer, Colony)} with room for
     * {@code extraCycles} more cycles of eating on top of the buffer — the offline catch-up, which
     * charges a whole absence at once.
     */
    public static Colony intakeFromStorage(net.minecraft.server.MinecraftServer server, Colony colony,
            int extraCycles) {
        long target = demandPerCycle(colony) * ((long) COMFORTABLE_BUFFER_CYCLES + Math.max(0, extraCycles));
        int stock = colony.foodStock();
        int room = (int) Math.min(MAX_FOOD_STOCK - (long) stock, target - stock);
        if (room <= 0) {
            return colony;
        }
        ColonyStores stores = ColonyStores.get(server);
        ColonyStores.Store store = stores.peek(colony.colonyId());
        if (store == null) {
            return colony;
        }
        net.minecraft.core.NonNullList<ItemStack> items = store.storage();
        java.util.Map<Item, Integer> spare = new java.util.HashMap<>();
        for (ItemStack stack : items) {
            if (isFood(stack)) {
                spare.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        spare.replaceAll((item, count) -> plantable(item) ? Math.max(0, count - SEED_RESERVE) : count);
        int gained = 0;
        for (boolean staplesOnly : new boolean[] {true, false}) {
            for (int slot = 0; slot < items.size() && gained < room; slot++) {
                ItemStack stack = items.get(slot);
                if (!isFood(stack) || staplesOnly != isStaple(stack)) {
                    continue;
                }
                int take = Math.min(Math.min(stack.getCount(), spare.getOrDefault(stack.getItem(), 0)),
                        (room - gained) / RATIONS_PER_ITEM);
                if (take <= 0) {
                    continue;
                }
                spare.merge(stack.getItem(), -take, Integer::sum);
                stack.shrink(take);
                if (stack.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
                gained += take * RATIONS_PER_ITEM;
            }
        }
        if (gained <= 0) {
            return colony;
        }
        stores.touch();
        return colony.withFoodStock(Math.min(MAX_FOOD_STOCK, stock + gained));
    }

    /** How many food items colony storage holds (the needs list counts them as food in hand). */
    public static int storedFood(net.minecraft.server.MinecraftServer server, java.util.UUID colonyId) {
        ColonyStores.Store store = ColonyStores.get(server).peek(colonyId);
        if (store == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : store.storage()) {
            if (isFood(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Whether an item is a crop Farmers plant (a block item whose block is a crop). */
    private static boolean plantable(Item item) {
        return item instanceof net.minecraft.world.item.BlockItem block
                && block.getBlock() instanceof net.minecraft.world.level.block.CropBlock;
    }

    private static int drawFrom(Container supply, boolean staplesOnly, int room) {
        if (room <= 0) {
            return 0;
        }
        int gained = 0;
        for (int slot = 0; slot < supply.getContainerSize() && gained < room; slot++) {
            ItemStack stack = supply.getItem(slot);
            if (!isFood(stack) || (staplesOnly != isStaple(stack))) {
                continue;
            }
            int take = Math.min(stack.getCount(), (room - gained) / RATIONS_PER_ITEM);
            if (take <= 0) {
                continue;
            }
            stack.shrink(take);
            if (stack.isEmpty()) {
                supply.setItem(slot, ItemStack.EMPTY);
            }
            gained += take * RATIONS_PER_ITEM;
        }
        if (gained > 0) {
            supply.setChanged();
        }
        return gained;
    }

    /**
     * Consumes one cycle's rations.
     *
     * @param cycles how many cycles to charge for (1 normally; more during offline catch-up)
     * @param yield  the catch-up efficiency multiplier (1.0 while loaded)
     * @return the colony record with its food stock reduced (possibly the same instance)
     */
    public static Colony consume(Colony colony, int cycles, double yield) {
        long demand = demandPerCycle(colony);
        if (demand <= 0 || cycles <= 0) {
            return colony;
        }
        long total = Math.round(demand * (double) cycles * Math.clamp(yield, 0.0D, 1.0D));
        if (total <= 0) {
            return colony;
        }
        int remaining = (int) Math.max(0L, colony.foodStock() - total);
        return remaining == colony.foodStock() ? colony : colony.withFoodStock(remaining);
    }

    /** Rations this colony eats per colony cycle. */
    public static long demandPerCycle(Colony colony) {
        int perColonist = NeroColoniesConfig.FOOD_PER_COLONIST_PER_CYCLE.get();
        return perColonist <= 0 ? 0L : (long) perColonist * Math.max(0, colony.population());
    }

    /** Whether the colony ran out of food (the flag the morale engine and the alerts read). */
    public static boolean starving(Colony colony) {
        return demandPerCycle(colony) > 0 && colony.foodStock() <= 0;
    }

    /**
     * The food term for the morale engine, 0..1: how close the store is to
     * {@value #COMFORTABLE_BUFFER_CYCLES} cycles of reserve. A colony that eats nothing (population
     * zero, or the config set to zero) is never unhappy about food.
     */
    public static double foodRatio(Colony colony) {
        long perCycle = demandPerCycle(colony);
        if (perCycle <= 0) {
            return 1.0D;
        }
        double comfortable = perCycle * (double) COMFORTABLE_BUFFER_CYCLES;
        return Math.clamp(colony.foodStock() / comfortable, 0.0D, 1.0D);
    }
}
