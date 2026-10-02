package za.co.neroland.nerocolonies.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.nerolandcore.upgrade.UpgradeType;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.item.ColonyBeaconItem;
import za.co.neroland.nerocolonies.item.ColonyBlockItem;
import za.co.neroland.nerocolonies.item.ColonyPlannerItem;
import za.co.neroland.nerocolonies.item.ColonyUpgradeItem;

/**
 * Item registrations, through Neroland Core's {@link RegistrationProvider}.
 *
 * <p>Every item registered here is listed in NeroColonies' <b>own creative tab</b>
 * ({@link NeroColoniesCreativeTab}), in registration order. Nothing is contributed to Neroland
 * Core's shared {@code Neroland} tab.
 */
public final class NeroColoniesItems {

    public static final RegistrationProvider<Item> ITEMS =
            RegistrationProvider.get(Registries.ITEM, NeroColoniesCommon.MOD_ID);

    private static final List<RegistryEntry<? extends Item>> TAB_ITEMS = new ArrayList<>();

    /** The colony beacon's item form; its tooltip explains founding and dissolving. */
    public static final RegistryEntry<BlockItem> COLONY_BEACON = register("colony_beacon",
            key -> new ColonyBeaconItem(NeroColoniesBlocks.COLONY_BEACON.get(),
                    new Item.Properties().setId(key).useBlockDescriptionPrefix().stacksTo(16)));

    public static final RegistryEntry<BlockItem> OUTPOST_BEACON =
            describedBlockItem("outpost_beacon", NeroColoniesBlocks.OUTPOST_BEACON);

    public static final RegistryEntry<BlockItem> OXYGEN_GENERATOR =
            blockItem("oxygen_generator", NeroColoniesBlocks.OXYGEN_GENERATOR);

    public static final RegistryEntry<BlockItem> COLONY_DEPOT =
            describedBlockItem("colony_depot", NeroColoniesBlocks.COLONY_DEPOT);

    public static final RegistryEntry<BlockItem> RESEARCH_STATION =
            describedBlockItem("research_station", NeroColoniesBlocks.RESEARCH_STATION);

    public static final RegistryEntry<BlockItem> HABITAT_POD =
            blockItem("habitat_pod", NeroColoniesBlocks.HABITAT_POD);
    public static final RegistryEntry<BlockItem> HABITAT_MODULE =
            blockItem("habitat_module", NeroColoniesBlocks.HABITAT_MODULE);
    public static final RegistryEntry<BlockItem> HABITAT_BLOCK =
            blockItem("habitat_block", NeroColoniesBlocks.HABITAT_BLOCK);

    public static final RegistryEntry<BlockItem> FARM_STATION =
            blockItem("farm_station", NeroColoniesBlocks.FARM_STATION);
    public static final RegistryEntry<BlockItem> HYDROPONICS_STATION =
            blockItem("hydroponics_station", NeroColoniesBlocks.HYDROPONICS_STATION);
    public static final RegistryEntry<BlockItem> REFINERY_STATION =
            blockItem("refinery_station", NeroColoniesBlocks.REFINERY_STATION);
    public static final RegistryEntry<BlockItem> FABRICATOR_STATION =
            blockItem("fabricator_station", NeroColoniesBlocks.FABRICATOR_STATION);

    public static final RegistryEntry<BlockItem> NEEDS_BOARD =
            describedBlockItem("needs_board", NeroColoniesBlocks.NEEDS_BOARD);
    public static final RegistryEntry<BlockItem> GRATITUDE_CACHE =
            describedBlockItem("gratitude_cache", NeroColoniesBlocks.GRATITUDE_CACHE);
    public static final RegistryEntry<BlockItem> PLANNING_TABLE =
            describedBlockItem("planning_table", NeroColoniesBlocks.PLANNING_TABLE);

    /** Places buildings by hand: pick, turn, preview, confirm. */
    public static final RegistryEntry<Item> COLONY_PLANNER = register("colony_planner",
            key -> new ColonyPlannerItem(new Item.Properties().setId(key).stacksTo(1)));

    /** A keepsake the Quartermaster sometimes leaves in the Gratitude Cache. Does nothing. */
    public static final RegistryEntry<Item> THANK_YOU_NOTE = register("thank_you_note",
            key -> new Item(new Item.Properties().setId(key).stacksTo(16)));

    public static final RegistryEntry<Item> SPEED_MODULE = upgrade("speed_module", UpgradeType.SPEED);
    public static final RegistryEntry<Item> EFFICIENCY_MODULE =
            upgrade("efficiency_module", UpgradeType.EFFICIENCY);
    public static final RegistryEntry<Item> RANGE_MODULE = upgrade("range_module", UpgradeType.RANGE);
    public static final RegistryEntry<Item> CAPACITY_MODULE =
            upgrade("capacity_module", UpgradeType.CAPACITY);

    private NeroColoniesItems() {
    }

    private static <T extends Item> RegistryEntry<T> register(String name,
            java.util.function.Function<ResourceKey<Item>, T> factory) {
        RegistryEntry<T> entry = ITEMS.register(name, factory);
        TAB_ITEMS.add(entry);
        return entry;
    }

    private static RegistryEntry<BlockItem> blockItem(String name,
            RegistryEntry<? extends Block> block) {
        return register(name, key -> new BlockItem(block.get(),
                new Item.Properties().setId(key).useBlockDescriptionPrefix()));
    }

    /**
     * A block item with one tooltip line, keyed {@code block.nerocolonies.<name>.tooltip}. Used for
     * the blocks whose behaviour is not obvious from their name — a depot that shares one stock, an
     * outpost that needs a parent, a research station that spends the colony's goods.
     */
    private static RegistryEntry<BlockItem> describedBlockItem(String name,
            RegistryEntry<? extends Block> block) {
        return register(name, key -> new ColonyBlockItem(block.get(),
                new Item.Properties().setId(key).useBlockDescriptionPrefix(),
                "block." + NeroColoniesCommon.MOD_ID + "." + name + ".tooltip"));
    }

    private static RegistryEntry<Item> upgrade(String name, UpgradeType type) {
        return register(name, key ->
                new ColonyUpgradeItem(new Item.Properties().setId(key).stacksTo(16), type));
    }

    /** Empty by design — exists so Fabric class-loads this holder from common init. */
    public static void init() {
    }

    /**
     * Every NeroColonies item, in registration order — the contents of the mod's own creative tab.
     * Read lazily by {@link NeroColoniesCreativeTab} when the tab is displayed, so the entries are
     * only resolved after every registry has been filled.
     */
    public static List<RegistryEntry<? extends Item>> creativeContents() {
        return Collections.unmodifiableList(TAB_ITEMS);
    }
}
