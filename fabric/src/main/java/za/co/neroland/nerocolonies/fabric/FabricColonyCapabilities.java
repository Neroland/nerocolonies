package za.co.neroland.nerocolonies.fabric;

import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.entity.BlockEntityType;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.platform.FabricEnergyLookup;
import za.co.neroland.nerolandcore.platform.FabricGasLookup;

import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * Fabric exposure of the shared Core energy surface plus the standard item storage for every
 * NeroColonies machine, so cables, pipes, hoppers and third-party automation reach them with no
 * NeroColonies-specific API.
 *
 * <p>Both views are resolved through the machine's side-config gate on each query, so a face the
 * player has disabled stops accepting transfer immediately without any invalidation callback. The
 * machine list is the canonical {@link NeroColoniesBlockEntities#machineTypes()}.
 */
public final class FabricColonyCapabilities {

    private FabricColonyCapabilities() {
    }

    public static void register() {
        for (BlockEntityType<? extends AbstractMachineBlockEntity> type
                : NeroColoniesBlockEntities.machineTypes()) {
            FabricEnergyLookup.ENERGY.registerForBlockEntity(
                    (be, side) -> be.sideConfig() == null ? be.getEnergy() : be.sideConfig().energyView(side),
                    type);
            ItemStorage.SIDED.registerForBlockEntity(
                    (be, side) -> be instanceof WorldlyContainer container
                            ? ContainerStorage.of(container, side)
                            : null,
                    type);
        }
        // Gas is only advertised on the machines that actually hold it.
        for (BlockEntityType<OxygenGeneratorBlockEntity> type : NeroColoniesBlockEntities.gasTypes()) {
            FabricGasLookup.GAS.registerForBlockEntity(
                    (be, side) -> be.sideConfig() == null ? be.getGas() : be.sideConfig().gasView(side),
                    type);
        }
    }
}
