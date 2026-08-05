package za.co.neroland.nerocolonies.registry;

import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity;
import za.co.neroland.nerocolonies.block.entity.ColonyDepotBlockEntity;
import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.block.entity.OutpostBeaconBlockEntity;
import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.block.entity.ResearchStationBlockEntity;

/**
 * Block-entity types, through Neroland Core's {@link RegistrationProvider}.
 *
 * <p>{@link #machineTypes()} is the canonical list every loader's capability registration iterates,
 * so a new machine is exposed to energy/item/gas transport by being added here once rather than in
 * three loader modules.
 *
 * <p>{@link #gasTypes()} is the narrower list of machines that actually hold gas — only those get a
 * gas capability, so a machine with no tank is not advertised as one.
 *
 * <p><b>Fabric 26.1.2:</b> the two-argument {@code BlockEntityType} constructor is private there
 * (public in 26.2), which is why {@code fabric/src/main/resources/nerocolonies.accesswidener}
 * widens it. Removing that line breaks the 26.1.2 Fabric cell only.
 */
public final class NeroColoniesBlockEntities {

    public static final RegistrationProvider<BlockEntityType<?>> BLOCK_ENTITIES =
            RegistrationProvider.get(Registries.BLOCK_ENTITY_TYPE, NeroColoniesCommon.MOD_ID);

    public static final RegistryEntry<BlockEntityType<ColonyBeaconBlockEntity>> COLONY_BEACON =
            BLOCK_ENTITIES.register("colony_beacon",
                    key -> new BlockEntityType<>(ColonyBeaconBlockEntity::new,
                            Set.of(NeroColoniesBlocks.COLONY_BEACON.get())));

    public static final RegistryEntry<BlockEntityType<OxygenGeneratorBlockEntity>> OXYGEN_GENERATOR =
            BLOCK_ENTITIES.register("oxygen_generator",
                    key -> new BlockEntityType<>(OxygenGeneratorBlockEntity::new,
                            Set.of(NeroColoniesBlocks.OXYGEN_GENERATOR.get())));

    public static final RegistryEntry<BlockEntityType<OutpostBeaconBlockEntity>> OUTPOST_BEACON =
            BLOCK_ENTITIES.register("outpost_beacon",
                    key -> new BlockEntityType<>(OutpostBeaconBlockEntity::new,
                            Set.of(NeroColoniesBlocks.OUTPOST_BEACON.get())));

    public static final RegistryEntry<BlockEntityType<ColonyDepotBlockEntity>> COLONY_DEPOT =
            BLOCK_ENTITIES.register("colony_depot",
                    key -> new BlockEntityType<>(ColonyDepotBlockEntity::new,
                            Set.of(NeroColoniesBlocks.COLONY_DEPOT.get())));

    public static final RegistryEntry<BlockEntityType<ResearchStationBlockEntity>> RESEARCH_STATION =
            BLOCK_ENTITIES.register("research_station",
                    key -> new BlockEntityType<>(ResearchStationBlockEntity::new,
                            Set.of(NeroColoniesBlocks.RESEARCH_STATION.get())));

    /**
     * One block-entity type for every job station block. What a station <em>does</em> comes from the
     * datapack jobs that name its block id, so there is nothing to specialise per block and a fifth
     * station block would need no new type at all — only this set widening.
     */
    public static final RegistryEntry<BlockEntityType<JobStationBlockEntity>> JOB_STATION =
            BLOCK_ENTITIES.register("job_station",
                    key -> new BlockEntityType<>(JobStationBlockEntity::new,
                            Set.of(NeroColoniesBlocks.FARM_STATION.get(),
                                    NeroColoniesBlocks.HYDROPONICS_STATION.get(),
                                    NeroColoniesBlocks.REFINERY_STATION.get(),
                                    NeroColoniesBlocks.FABRICATOR_STATION.get())));

    private NeroColoniesBlockEntities() {
    }

    /** Every machine block-entity type that should carry the shared energy/item capabilities. */
    public static List<BlockEntityType<? extends AbstractMachineBlockEntity>> machineTypes() {
        return List.of(COLONY_BEACON.get(), OUTPOST_BEACON.get(), OXYGEN_GENERATOR.get(),
                COLONY_DEPOT.get(), RESEARCH_STATION.get(), JOB_STATION.get());
    }

    /** Every machine that holds gas, for the loader gas-capability registrations. */
    public static List<BlockEntityType<OxygenGeneratorBlockEntity>> gasTypes() {
        return List.of(OXYGEN_GENERATOR.get());
    }

    /** Empty by design — exists so Fabric class-loads this holder from common init. */
    public static void init() {
    }
}
