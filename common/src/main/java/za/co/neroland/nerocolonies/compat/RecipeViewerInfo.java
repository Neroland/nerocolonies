package za.co.neroland.nerocolonies.compat;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.world.item.Item;

import za.co.neroland.nerocolonies.registry.NeroColoniesItems;

/**
 * The information pages NeroColonies gives a recipe viewer (JEI or EMI): one short explanation per
 * colony block, because a colony beacon's recipe tells a player how to make it and nothing about
 * what it does once placed. Shared by both viewer plugins so the two can never drift apart.
 */
public final class RecipeViewerInfo {

    /** One page: the item it is shown on and the translation key of its text. */
    public record Page(Supplier<? extends Item> item, String key) {
    }

    private RecipeViewerInfo() {
    }

    /** Every page, in the order a player meets the blocks. */
    public static List<Page> pages() {
        return List.of(
                new Page(NeroColoniesItems.COLONY_BEACON::get, "info.nerocolonies.colony_beacon"),
                new Page(NeroColoniesItems.COLONY_DEPOT::get, "info.nerocolonies.colony_depot"),
                new Page(NeroColoniesItems.HABITAT_POD::get, "info.nerocolonies.housing"),
                new Page(NeroColoniesItems.HABITAT_MODULE::get, "info.nerocolonies.housing"),
                new Page(NeroColoniesItems.HABITAT_BLOCK::get, "info.nerocolonies.housing"),
                new Page(NeroColoniesItems.FARM_STATION::get, "info.nerocolonies.job_station"),
                new Page(NeroColoniesItems.HYDROPONICS_STATION::get, "info.nerocolonies.job_station"),
                new Page(NeroColoniesItems.REFINERY_STATION::get, "info.nerocolonies.job_station"),
                new Page(NeroColoniesItems.FABRICATOR_STATION::get, "info.nerocolonies.job_station"),
                new Page(NeroColoniesItems.RESEARCH_STATION::get, "info.nerocolonies.research_station"),
                new Page(NeroColoniesItems.OXYGEN_GENERATOR::get, "info.nerocolonies.oxygen_generator"),
                new Page(NeroColoniesItems.OUTPOST_BEACON::get, "info.nerocolonies.outpost_beacon"),
                new Page(NeroColoniesItems.NEEDS_BOARD::get, "info.nerocolonies.needs_board"),
                new Page(NeroColoniesItems.GRATITUDE_CACHE::get, "info.nerocolonies.gratitude_cache"),
                new Page(NeroColoniesItems.PLANNING_TABLE::get, "info.nerocolonies.planning_table"),
                new Page(NeroColoniesItems.COLONY_PLANNER::get, "info.nerocolonies.colony_planner"));
    }
}
