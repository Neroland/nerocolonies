package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the working trades turn into what. All ids are vanilla item paths ({@code minecraft:} items),
 * so the tables need no registry to be read and are unit-tested; a behaviour skips a line whose items
 * are not registered. Every conversion is all-or-nothing: the inputs leave storage only when the
 * output is known to fit.
 */
public final class TradeRecipes {

    /** One input line: so many of an item. */
    public record Part(String item, int count) {
    }

    /** A conversion: these inputs make {@code count} of {@code output}. */
    public record Conversion(List<Part> inputs, String output, int count) {
    }

    private TradeRecipes() {
    }

    private static Conversion one(String input, String output) {
        return new Conversion(List.of(new Part(input, 1)), output, 1);
    }

    /** What a Cook makes, in the order it looks for work. */
    public static final List<Conversion> COOKING = List.of(
            one("beef", "cooked_beef"),
            one("porkchop", "cooked_porkchop"),
            one("mutton", "cooked_mutton"),
            one("chicken", "cooked_chicken"),
            one("rabbit", "cooked_rabbit"),
            one("cod", "cooked_cod"),
            one("salmon", "cooked_salmon"),
            one("potato", "baked_potato"),
            one("kelp", "dried_kelp"),
            new Conversion(List.of(new Part("wheat", 3)), "bread", 1));

    /**
     * What a Researcher prepares when the next research needs it: the refining steps between what the
     * colony gathers and what a research cost asks for. Keyed by the output.
     */
    public static final Map<String, Conversion> RESEARCH_PREP;

    static {
        Map<String, Conversion> prep = new LinkedHashMap<>();
        prep.put("glass", one("sand", "glass"));
        prep.put("iron_ingot", one("raw_iron", "iron_ingot"));
        prep.put("copper_ingot", one("raw_copper", "copper_ingot"));
        prep.put("gold_ingot", one("raw_gold", "gold_ingot"));
        prep.put("paper", new Conversion(List.of(new Part("sugar_cane", 3)), "paper", 3));
        prep.put("book", new Conversion(List.of(new Part("paper", 3), new Part("leather", 1)), "book", 1));
        prep.put("smooth_stone", one("stone", "smooth_stone"));
        prep.put("stone", one("cobblestone", "stone"));
        prep.put("charcoal", one("oak_log", "charcoal"));
        RESEARCH_PREP = Map.copyOf(prep);
    }

    /** The vanilla crafting cost of the trade tools a Toolsmith makes. */
    public static final Map<String, List<Part>> TOOL_COSTS = Map.of(
            "iron_hoe", List.of(new Part("iron_ingot", 2), new Part("stick", 2)),
            "iron_axe", List.of(new Part("iron_ingot", 3), new Part("stick", 2)),
            "iron_pickaxe", List.of(new Part("iron_ingot", 3), new Part("stick", 2)),
            "iron_shovel", List.of(new Part("iron_ingot", 1), new Part("stick", 2)),
            "iron_sword", List.of(new Part("iron_ingot", 2), new Part("stick", 1)),
            "book", List.of(new Part("paper", 3), new Part("leather", 1)));

    /** What a datapack trade tool with no known recipe costs: one iron ingot, as before. */
    public static final List<Part> FALLBACK_TOOL_COST = List.of(new Part("iron_ingot", 1));

    /** The cost of making a tool by its vanilla item path (or the fallback for anything else). */
    public static List<Part> toolCost(String namespace, String path) {
        if ("minecraft".equals(namespace)) {
            List<Part> cost = TOOL_COSTS.get(path);
            if (cost != null) {
                return cost;
            }
        }
        return FALLBACK_TOOL_COST;
    }

    /** Seeds a Farmer sows, most common first, with the crop block each grows into. */
    public static final List<Part> SEEDS = List.of(
            new Part("wheat_seeds", 1), new Part("carrot", 1), new Part("potato", 1),
            new Part("beetroot_seeds", 1));

    /** The crop block path a seed item path grows into, or null. */
    public static String cropFor(String seed) {
        return switch (seed) {
            case "wheat_seeds" -> "wheat";
            case "carrot" -> "carrots";
            case "potato" -> "potatoes";
            case "beetroot_seeds" -> "beetroots";
            default -> null;
        };
    }

    /** The seed item path a crop block path is replanted from, or null. */
    public static String seedFor(String crop) {
        return switch (crop) {
            case "wheat" -> "wheat_seeds";
            case "carrots" -> "carrot";
            case "potatoes" -> "potato";
            case "beetroots" -> "beetroot_seeds";
            default -> null;
        };
    }
}
