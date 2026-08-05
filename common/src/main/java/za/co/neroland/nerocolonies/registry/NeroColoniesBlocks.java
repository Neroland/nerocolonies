package za.co.neroland.nerocolonies.registry;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.ColonyBeaconBlock;
import za.co.neroland.nerocolonies.block.ColonyDepotBlock;
import za.co.neroland.nerocolonies.block.JobStationBlock;
import za.co.neroland.nerocolonies.block.OutpostBeaconBlock;
import za.co.neroland.nerocolonies.block.OxygenGeneratorBlock;
import za.co.neroland.nerocolonies.block.ResearchStationBlock;
import za.co.neroland.nerocolonies.colony.ColonyState;

/**
 * Block registrations, through Neroland Core's {@link RegistrationProvider}. On Fabric these apply
 * the moment this class is loaded, which is why {@link #init()} exists and is called from common
 * init; on NeoForge/Forge the entry point attaches the deferred registers to the mod bus.
 *
 * <h2>Housing and job stations are plain blocks</h2>
 *
 * <p>The three habitat blocks carry <b>no block entity</b>. Housing is matched by block id against
 * the datapack {@code HousingTier} definitions during the housing sweep, so a habitat costs nothing
 * at all when nobody is looking at it — and, more usefully, a datapack can declare any block in the
 * game as colony housing without either side needing code.
 *
 * <p>The job stations, by contrast, <b>do</b> carry a block entity: one shared
 * {@code JobStationBlockEntity} type serves all four station blocks, because what a station does
 * comes from the datapack jobs that name its block id rather than from its class. Adding a station
 * block is therefore a registry line and a job JSON, never a new Java type.
 */
public final class NeroColoniesBlocks {

    public static final RegistrationProvider<Block> BLOCKS =
            RegistrationProvider.get(Registries.BLOCK, NeroColoniesCommon.MOD_ID);

    /** The colony command block: founds a colony, holds its upgrade modules, opens its GUI. */
    public static final RegistryEntry<Block> COLONY_BEACON = BLOCKS.register("colony_beacon",
            key -> new ColonyBeaconBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(4.0F, 12.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 7)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Turns grid power into breathable gas and feeds the colony's life support. */
    public static final RegistryEntry<Block> OXYGEN_GENERATOR = BLOCKS.register("oxygen_generator",
            key -> new OxygenGeneratorBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** A small remote claim tied to a parent colony; its stations feed the parent's storage. */
    public static final RegistryEntry<Block> OUTPOST_BEACON = BLOCKS.register("outpost_beacon",
            key -> new OutpostBeaconBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(3.5F, 8.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 5)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** A door onto the colony's shared storage — access, not capacity. */
    public static final RegistryEntry<Block> COLONY_DEPOT = BLOCKS.register("colony_depot",
            key -> new ColonyDepotBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .mapColor(MapColor.COLOR_BROWN)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)));

    /** Spends colony goods and power to unlock research nodes on the colony record. */
    public static final RegistryEntry<Block> RESEARCH_STATION = BLOCKS.register("research_station",
            key -> new ResearchStationBlock(BlockBehaviour.Properties.of()
                    .setId(key)
                    .mapColor(MapColor.COLOR_MAGENTA)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> 4)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    // --- housing ------------------------------------------------------------

    /** Tier 1 housing: cramped, cheap, and the reason morale has a comfort term. */
    public static final RegistryEntry<Block> HABITAT_POD = BLOCKS.register("habitat_pod",
            key -> housing(key, MapColor.COLOR_LIGHT_GRAY, 2.0F));

    /** Tier 2 housing: a proper pressurised module. */
    public static final RegistryEntry<Block> HABITAT_MODULE = BLOCKS.register("habitat_module",
            key -> housing(key, MapColor.COLOR_GRAY, 2.5F));

    /** Tier 3 housing: a residential block, and as comfortable as 0.1.0 gets. */
    public static final RegistryEntry<Block> HABITAT_BLOCK = BLOCKS.register("habitat_block",
            key -> housing(key, MapColor.COLOR_BLUE, 3.0F));

    // --- job stations -------------------------------------------------------

    public static final RegistryEntry<Block> FARM_STATION = BLOCKS.register("farm_station",
            key -> station(key, MapColor.COLOR_GREEN));

    public static final RegistryEntry<Block> HYDROPONICS_STATION =
            BLOCKS.register("hydroponics_station", key -> station(key, MapColor.COLOR_CYAN));

    public static final RegistryEntry<Block> REFINERY_STATION =
            BLOCKS.register("refinery_station", key -> station(key, MapColor.COLOR_ORANGE));

    public static final RegistryEntry<Block> FABRICATOR_STATION =
            BLOCKS.register("fabricator_station", key -> station(key, MapColor.COLOR_PURPLE));

    /** Every block that gets a plain {@code BlockItem} and a creative-tab entry. */
    public static final List<RegistryEntry<? extends Block>> ALL = List.of(
            COLONY_BEACON, OUTPOST_BEACON, OXYGEN_GENERATOR, COLONY_DEPOT, RESEARCH_STATION,
            HABITAT_POD, HABITAT_MODULE, HABITAT_BLOCK,
            FARM_STATION, HYDROPONICS_STATION, REFINERY_STATION, FABRICATOR_STATION);

    /** Every housing block, in tier order — the shipped {@code HousingTier} content names these. */
    public static final List<RegistryEntry<? extends Block>> HOUSING =
            List.of(HABITAT_POD, HABITAT_MODULE, HABITAT_BLOCK);

    /** Every job-station block. The shipped job content names these. */
    public static final List<RegistryEntry<? extends Block>> STATIONS =
            List.of(FARM_STATION, HYDROPONICS_STATION, REFINERY_STATION, FABRICATOR_STATION);

    private NeroColoniesBlocks() {
    }

    private static Block housing(net.minecraft.resources.ResourceKey<Block> key, MapColor colour,
            float strength) {
        return new Block(BlockBehaviour.Properties.of()
                .setId(key)
                .mapColor(colour)
                .strength(strength, 6.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL));
    }

    private static Block station(net.minecraft.resources.ResourceKey<Block> key, MapColor colour) {
        return new JobStationBlock(BlockBehaviour.Properties.of()
                .setId(key)
                .mapColor(colour)
                .strength(3.0F, 6.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL)
                .noOcclusion());
    }

    /**
     * Forces class-load (Fabric registers eagerly) and installs the "is the beacon still standing?"
     * tests the colony retention sweep uses, so the colony package never has to import this one.
     */
    public static void init() {
        ColonyState.setBeaconCheck((level, pos) ->
                level.getBlockState(pos).is(COLONY_BEACON.get()));
        ColonyState.setOutpostCheck((level, pos) ->
                level.getBlockState(pos).is(OUTPOST_BEACON.get()));
    }
}
