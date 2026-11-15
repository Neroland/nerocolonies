package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyStorageBlocks;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.colony.Population;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * Carrying real goods: food from colony storage to the beacon's supply slots (where it becomes the
 * colony's ration stock) and trade tools from storage to the Nerans who lack them. Used by the
 * Hauler, and by the Cook and Toolsmith for their own deliveries.
 *
 * <p>Goods only ever move: what is loaded is taken out of storage exactly (same item, same
 * components), what is delivered goes into the supply slots or the recipient's inventory, and
 * anything undeliverable goes back to colony storage (or, if storage is full, onto the ground). A
 * carrier that lost track of its job (a restart, a trade change) returns what it holds before doing
 * anything else.
 */
final class CarryWork {

    /** Most food items carried to the beacon in one trip. */
    private static final int FOOD_LOAD = 16;

    /** Seeds that are also food (carrots, potatoes) are left in storage up to this many. */
    static final int SEED_RESERVE = 16;

    /** A delivery taking longer than this is abandoned and the goods returned. */
    private static final long DELIVERY_TIMEOUT = 2400L;

    private CarryWork() {
    }

    /** The Hauler: keep the beacon fed, then get tools to whoever lacks one. */
    static boolean haul(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        Boolean busy = continueCarry(level, neran, colony);
        if (busy != null) {
            return busy;
        }
        BlockPos pickup = pickupPoint(level, colony, neran);
        if (wantsFood(level, colony)) {
            return fetchFood(level, neran, colony, pickup);
        }
        ColonistEntity recipient = lackingTool(level, neran, colony, false);
        if (recipient != null) {
            return fetchTool(level, neran, colony, pickup, recipient, false);
        }
        return WorkSupport.atPost(neran, anchor, profession.workRadius());
    }

    /**
     * Carries on with a delivery in progress, or returns stray goods to storage.
     *
     * @return the work result if the carrier was busy with that, or null if it is free
     */
    @Nullable
    static Boolean continueCarry(ServerLevel level, ColonistEntity neran, Colony colony) {
        WorkMemory memory = neran.workMemory();
        long now = level.getGameTime();
        if (!WorkSupport.carrying(neran)) {
            memory.carry = WorkMemory.Carry.NONE;
            memory.recipient = null;
            return null;
        }
        neran.setStatus(NeranStatus.HAULING);
        WorkSupport.showCarried(neran);
        if (memory.carry != WorkMemory.Carry.NONE && now - memory.carryStartedAt > DELIVERY_TIMEOUT) {
            memory.carry = WorkMemory.Carry.NONE;
        }
        return switch (memory.carry) {
            case FOOD -> deliverFood(level, neran, colony);
            case TOOL -> deliverTool(level, neran, colony);
            case NONE -> returnHome(level, neran, colony);
        };
    }

    // --- food -----------------------------------------------------------------

    @Nullable
    static ColonyBeaconBlockEntity beacon(ServerLevel level, Colony colony) {
        if (!level.dimension().equals(colony.dimension()) || !level.isLoaded(colony.beaconPos())) {
            return null;
        }
        return level.getBlockEntity(colony.beaconPos()) instanceof ColonyBeaconBlockEntity beacon ? beacon : null;
    }

    /** Whether the colony's ration stock is short and its supply slots have room. */
    static boolean wantsFood(ServerLevel level, Colony colony) {
        ColonyBeaconBlockEntity beacon = beacon(level, colony);
        if (beacon == null || FoodSupply.foodRatio(colony) >= 1.0D
                || colony.foodStock() >= FoodSupply.MAX_FOOD_STOCK) {
            return false;
        }
        ItemStack sample = peekStore(level.getServer(), colony, food(level.getServer(), colony));
        return !sample.isEmpty() && WorkSupport.roomFor(beacon.supply(), sample) > 0;
    }

    /** Colony food, staples counted like anything else, keeping a seed reserve. */
    private static Predicate<ItemStack> food(MinecraftServer server, Colony colony) {
        return stack -> FoodSupply.isFood(stack) && !reservedSeed(server, colony, stack);
    }

    private static boolean reservedSeed(MinecraftServer server, Colony colony, ItemStack stack) {
        for (TradeRecipes.Part seed : TradeRecipes.SEEDS) {
            Item item = WorkSupport.item(seed.item());
            if (item != null && stack.is(item)) {
                return WorkSupport.stored(server, colony, seed.item()) <= SEED_RESERVE;
            }
        }
        return false;
    }

    /** Walks to {@code pickup}, then loads one trip of food out of storage. */
    static boolean fetchFood(ServerLevel level, ColonistEntity neran, Colony colony, BlockPos pickup) {
        if (!WorkSupport.reach(neran, pickup)) {
            return false;
        }
        ColonyBeaconBlockEntity beacon = beacon(level, colony);
        if (beacon == null) {
            return false;
        }
        MinecraftServer server = level.getServer();
        Predicate<ItemStack> food = food(server, colony);
        // Staples first, the same order the colony eats in.
        ItemStack sample = peekStore(server, colony, food.and(FoodSupply::isStaple));
        if (sample.isEmpty()) {
            sample = peekStore(server, colony, food);
        }
        if (sample.isEmpty()) {
            return false;
        }
        int room = Math.min(FOOD_LOAD, WorkSupport.roomFor(beacon.supply(), sample));
        ItemStack load = takeFromStore(server, colony, sample, room);
        if (load.isEmpty()) {
            return false;
        }
        load(level, neran, colony, load, WorkMemory.Carry.FOOD, null);
        WorkSupport.strike(neran, pickup);
        return true;
    }

    private static boolean deliverFood(ServerLevel level, ColonistEntity neran, Colony colony) {
        ColonyBeaconBlockEntity beacon = beacon(level, colony);
        if (beacon == null) {
            return returnHome(level, neran, colony);
        }
        if (!WorkSupport.reach(neran, colony.beaconPos())) {
            return false;
        }
        Container supply = beacon.supply();
        for (int i = 0; i < neran.inventory().getContainerSize(); i++) {
            ItemStack stack = neran.inventory().getItem(i);
            if (stack.isEmpty() || !FoodSupply.isFood(stack)) {
                continue;
            }
            ItemStack rest = WorkSupport.insertInto(supply, stack);
            neran.inventory().setItem(i, rest);
        }
        WorkSupport.strike(neran, colony.beaconPos());
        finish(level, neran, colony);
        return true;
    }

    // --- tools ----------------------------------------------------------------

    /**
     * The nearest adult of this colony whose trade has a tool it lacks, which storage can supply (or,
     * if {@code canMake}, the Toolsmith can make), and that no other carrier is already serving.
     */
    @Nullable
    static ColonistEntity lackingTool(ServerLevel level, ColonistEntity carrier, Colony colony, boolean canMake) {
        MinecraftServer server = level.getServer();
        List<ColonistEntity> roster = Population.colonistsOf(level, colony);
        ColonistEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ColonistEntity other : roster) {
            ProfessionDefinition trade = other.profession();
            if (other == carrier || other.isChildNeran() || !other.isAlive() || trade == null
                    || trade.toolItem() == null || other.hasTool() || servedByAnother(roster, carrier, other)) {
                continue;
            }
            Item tool = trade.toolItem();
            boolean stored = !peekStore(server, colony, s -> s.is(tool)).isEmpty();
            if (!stored && !(canMake && WorkSupport.hasParts(server, colony, ToolWork.costOf(tool)))) {
                continue;
            }
            double distance = other.distanceToSqr(carrier);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    private static boolean servedByAnother(List<ColonistEntity> roster, ColonistEntity carrier, ColonistEntity target) {
        for (ColonistEntity other : roster) {
            WorkMemory memory = other.workMemory();
            if (other != carrier && memory.carry == WorkMemory.Carry.TOOL
                    && target.getUUID().equals(memory.recipient)) {
                return true;
            }
        }
        return false;
    }

    /** Walks to {@code pickup}, then takes the recipient's tool from storage (or makes it). */
    static boolean fetchTool(ServerLevel level, ColonistEntity neran, Colony colony, BlockPos pickup,
            ColonistEntity recipient, boolean canMake) {
        if (!WorkSupport.reach(neran, pickup)) {
            return false;
        }
        ProfessionDefinition trade = recipient.profession();
        if (trade == null || trade.toolItem() == null) {
            return false;
        }
        Item tool = trade.toolItem();
        MinecraftServer server = level.getServer();
        ItemStack load = takeFromStore(server, colony, peekStore(server, colony, s -> s.is(tool)), 1);
        if (load.isEmpty() && canMake && WorkSupport.takeParts(server, colony, ToolWork.costOf(tool))) {
            load = trade.toolStack();
        }
        if (load.isEmpty()) {
            return false;
        }
        load(level, neran, colony, load, WorkMemory.Carry.TOOL, recipient);
        WorkSupport.strike(neran, pickup);
        return true;
    }

    private static boolean deliverTool(ServerLevel level, ColonistEntity neran, Colony colony) {
        WorkMemory memory = neran.workMemory();
        Entity entity = memory.recipient == null ? null : level.getEntity(memory.recipient);
        if (!(entity instanceof ColonistEntity recipient) || !recipient.isAlive()
                || !colony.colonyId().equals(recipient.colonyId()) || recipient.hasTool()
                || recipient.profession() == null || recipient.profession().toolItem() == null
                || !colony.contains(recipient.blockPosition())) {
            memory.carry = WorkMemory.Carry.NONE; // undeliverable: it goes back to storage
            return returnHome(level, neran, colony);
        }
        if (!WorkSupport.reach(neran, recipient.blockPosition())) {
            return false;
        }
        Item tool = recipient.profession().toolItem();
        for (int i = 0; i < neran.inventory().getContainerSize(); i++) {
            ItemStack stack = neran.inventory().getItem(i);
            if (!stack.isEmpty() && stack.is(tool)) {
                ItemStack rest = recipient.inventory().addItem(stack.copy());
                neran.inventory().setItem(i, rest);
                break;
            }
        }
        recipient.updateHeldTool();
        WorkSupport.strike(neran, recipient.blockPosition());
        finish(level, neran, colony);
        return true;
    }

    // --- shared ---------------------------------------------------------------

    private static void load(ServerLevel level, ColonistEntity neran, Colony colony, ItemStack load,
            WorkMemory.Carry kind, @Nullable ColonistEntity recipient) {
        ItemStack rest = neran.inventory().addItem(load);
        if (!rest.isEmpty()) {
            WorkSupport.store(level, colony, rest, neran.blockPosition()); // never voided
        }
        WorkMemory memory = neran.workMemory();
        memory.carry = kind;
        memory.recipient = recipient == null ? null : recipient.getUUID();
        memory.carryStartedAt = level.getGameTime();
        neran.setStatus(NeranStatus.HAULING);
        WorkSupport.showCarried(neran);
    }

    /** Ends a delivery: whatever was not delivered goes back to storage right away. */
    private static void finish(ServerLevel level, ColonistEntity neran, Colony colony) {
        WorkSupport.returnCarried(level, colony, neran);
        WorkMemory memory = neran.workMemory();
        memory.carry = WorkMemory.Carry.NONE;
        memory.recipient = null;
        neran.setStatus(NeranStatus.WORKING);
    }

    /** Takes stray goods back to the nearest store (or straight into storage if none is here). */
    private static boolean returnHome(ServerLevel level, ColonistEntity neran, Colony colony) {
        BlockPos store = pickupPoint(level, colony, neran);
        if (level.dimension().equals(colony.dimension()) && level.isLoaded(store) && colony.contains(store)
                && !WorkSupport.reach(neran, store)) {
            return false;
        }
        finish(level, neran, colony);
        return true;
    }

    /** Where colony storage is fetched from: the nearest depot or crate here, else the beacon. */
    static BlockPos pickupPoint(ServerLevel level, Colony colony, ColonistEntity neran) {
        BlockPos best = colony.beaconPos();
        double bestDistance = level.dimension().equals(colony.dimension())
                ? best.distSqr(neran.blockPosition()) : Double.MAX_VALUE;
        for (ColonyStorageBlocks.Box box : ColonyStorageBlocks.get(level.getServer()).boxes(colony.colonyId())) {
            if (!box.dimension().equals(level.dimension())) {
                continue;
            }
            double distance = box.pos().distSqr(neran.blockPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = box.pos();
            }
        }
        return best;
    }

    /** A copy of the first stored stack matching {@code test}, or empty. Reads only. */
    static ItemStack peekStore(MinecraftServer server, Colony colony, Predicate<ItemStack> test) {
        ColonyStores.Store store = ColonyStores.get(server).peek(colony.colonyId());
        if (store == null) {
            return ItemStack.EMPTY;
        }
        for (ItemStack stack : store.storage()) {
            if (!stack.isEmpty() && test.test(stack)) {
                return stack.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Takes up to {@code max} items exactly like {@code sample} (same item, same components) out of
     * colony storage, as one stack. Nothing is created: the count is what left the store.
     */
    static ItemStack takeFromStore(MinecraftServer server, Colony colony, ItemStack sample, int max) {
        if (sample.isEmpty() || max <= 0) {
            return ItemStack.EMPTY;
        }
        ColonyStores stores = ColonyStores.get(server);
        ColonyStores.Store store = stores.peek(colony.colonyId());
        if (store == null) {
            return ItemStack.EMPTY;
        }
        int limit = Math.min(max, sample.getMaxStackSize());
        NonNullList<ItemStack> items = store.storage();
        int taken = 0;
        for (int slot = 0; slot < items.size() && taken < limit; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, sample)) {
                continue;
            }
            int take = Math.min(limit - taken, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                items.set(slot, ItemStack.EMPTY);
            }
            taken += take;
        }
        if (taken > 0) {
            stores.touch();
        }
        return taken <= 0 ? ItemStack.EMPTY : sample.copyWithCount(taken);
    }
}
