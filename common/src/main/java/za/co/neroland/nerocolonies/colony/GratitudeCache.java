package za.co.neroland.nerocolonies.colony;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.link.ColonyLinkEvents;
import za.co.neroland.nerocolonies.link.ColonyLinkModule;

/**
 * The Gratitude Cache: the colony's thank-you to its owner.
 *
 * <p>Once the colony has a Gratitude Cache pavilion and a Quartermaster, it rolls the loot table
 * {@code nerocolonies:gratitude/tier_<n>} every {@code cacheStockIntervalCycles} colony cycles, where
 * {@code n} is the colony's stage (1 for Founding up to 5 for Metropolis). Rare rolls include a
 * Thank-you Note. The cache has {@value ColonyLife#CACHE_SLOTS} slots and <b>never voids</b>: when a
 * roll does not fit nothing is added (that roll is dropped whole, never part of it) and members
 * are told once, until there is room again.
 *
 * <p>Only the owner may open it, and allies too when the owner shares it.
 */
public final class GratitudeCache {

    /**
     * The one live window onto each colony's cache while the server runs. Every Gratitude Cache block
     * in a claim opens this same container, so two members looking at once see (and take from) the
     * same stock rather than two copies of it.
     */
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    /** Colonies whose members have already been told the cache is full. In memory only. */
    private static final java.util.Set<UUID> TOLD_FULL = new java.util.HashSet<>();

    private GratitudeCache() {
    }

    /** A container that writes every change straight back to the colony's record. */
    private static final class View extends SimpleContainer {
        private final ColonyLife store;
        private final ColonyLife.Life life;
        private boolean loading;

        View(ColonyLife store, ColonyLife.Life life) {
            super(ColonyLife.CACHE_SLOTS);
            this.store = store;
            this.life = life;
            reload();
        }

        void reload() {
            this.loading = true;
            try {
                clearContent();
                List<ItemStack> cache = List.copyOf(this.life.cache());
                for (int i = 0; i < cache.size() && i < ColonyLife.CACHE_SLOTS; i++) {
                    setItem(i, cache.get(i).copy());
                }
            } finally {
                this.loading = false;
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!this.loading) {
                writeBack(this, this.life);
                this.store.touch();
            }
        }
    }

    /** The live cache container for a colony, for the block's menu. */
    public static SimpleContainer view(MinecraftServer server, UUID colonyId) {
        ColonyLife store = ColonyLife.get(server);
        return VIEWS.computeIfAbsent(colonyId, id -> new View(store, store.life(id)));
    }

    /** Re-reads the live container after the record was changed behind it (a restock). */
    public static void refresh(UUID colonyId) {
        View view = VIEWS.get(colonyId);
        if (view != null) {
            view.reload();
        }
    }

    /**
     * Drops everything in a colony's cache at {@code pos} and empties it. Called when a colony is
     * dissolved, so its gifts are never lost with it.
     */
    public static void dropAll(ServerLevel level, net.minecraft.core.BlockPos pos, UUID colonyId) {
        ColonyLife store = ColonyLife.get(level.getServer());
        ColonyLife.Life life = store.peek(colonyId);
        if (life == null || life.cache().isEmpty()) {
            return;
        }
        for (ItemStack stack : List.copyOf(life.cache())) {
            if (!stack.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, pos, stack.copy());
            }
        }
        life.cache().clear();
        store.touch();
        refresh(colonyId);
    }

    /** Drops a dissolved colony's live container. */
    public static void forget(UUID colonyId) {
        VIEWS.remove(colonyId);
        TOLD_FULL.remove(colonyId);
    }

    /** Drops every live container. Called when the server stops. */
    public static void reset() {
        VIEWS.clear();
        TOLD_FULL.clear();
    }

    /** The loot table for a stage. */
    public static ResourceKey<LootTable> tableFor(ColonyStage stage) {
        return ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(
                NeroColoniesCommon.MOD_ID, "gratitude/tier_" + (stage.ordinal() + 1)));
    }

    /** Stocks the cache if it is time. Called once per colony cycle with the Quartermaster count. */
    public static void tick(ServerLevel level, Colony colony, int quartermasters) {
        MinecraftServer server = level.getServer();
        if (quartermasters <= 0 || server == null
                || !ColonyBuildings.hasRole(level, colony, ColonyBuildings.ROLE_CACHE)) {
            return;
        }
        ColonyLife store = ColonyLife.get(server);
        ColonyLife.Life life = store.life(colony.colonyId());
        long interval = (long) NeroColoniesConfig.CACHE_STOCK_INTERVAL_CYCLES.get()
                * NeroColoniesConfig.colonyTickInterval();
        long now = level.getGameTime();
        if (life.cacheLastStocked() != 0L && now - life.cacheLastStocked() < interval && now >= life.cacheLastStocked()) {
            return;
        }
        life.markCacheStocked(now);
        store.touch();
        boolean stocked = stock(level, life, ColonyProgress.stage(server, colony));
        refresh(colony.colonyId());
        if (stocked) {
            TOLD_FULL.remove(colony.colonyId());
        } else if (!TOLD_FULL.add(colony.colonyId())) {
            return; // still full, and they have been told
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("stocked", stocked);
        extra.addProperty("full", !stocked);
        extra.addProperty("filled", filled(life));
        extra.addProperty("slots", ColonyLife.CACHE_SLOTS);
        ColonyLinkEvents.colonyEvent(colony, ColonyLinkModule.TOPIC_CACHE, extra);
        if (!stocked) {
            ColonyLinkEvents.colonyAlert(server, colony, "cache_full", false,
                    "The Gratitude Cache at " + colony.name() + " is full.");
        }
    }

    /**
     * Rolls the stage's table into the cache. Returns false (and adds nothing) when the roll would not
     * fit, so nothing is ever thrown away.
     */
    public static boolean stock(ServerLevel level, ColonyLife.Life life, ColonyStage stage) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(tableFor(stage));
        List<ItemStack> roll = table.getRandomItems(new LootParams.Builder(level).create(LootContextParamSets.EMPTY));
        if (roll.isEmpty()) {
            return true;
        }
        SimpleContainer trial = container(life);
        for (ItemStack stack : roll) {
            ItemStack left = trial.addItem(stack.copy());
            if (!left.isEmpty()) {
                return false;
            }
        }
        writeBack(trial, life);
        return true;
    }

    /** How many cache slots hold something. */
    public static int filled(ColonyLife.Life life) {
        int filled = 0;
        for (ItemStack stack : life.cache()) {
            if (!stack.isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** A container copy of the cache, for menus and trial inserts. */
    public static SimpleContainer container(ColonyLife.Life life) {
        SimpleContainer container = new SimpleContainer(ColonyLife.CACHE_SLOTS);
        List<ItemStack> cache = life.cache();
        for (int i = 0; i < cache.size() && i < ColonyLife.CACHE_SLOTS; i++) {
            container.setItem(i, cache.get(i).copy());
        }
        return container;
    }

    /** Writes a container back as the cache contents. */
    public static void writeBack(SimpleContainer container, ColonyLife.Life life) {
        List<ItemStack> cache = life.cache();
        cache.clear();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                cache.add(stack.copy());
            }
        }
    }
}
