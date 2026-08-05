package za.co.neroland.nerocolonies.neoforge;

import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.platform.NeoForgeEnergyLookup;
import za.co.neroland.nerolandcore.platform.NeoForgeGasLookup;

import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * NeoForge capability exposure for every NeroColonies machine: Core's energy surface plus the
 * standard item capability, both gated through the machine's side config so a disabled face stops
 * accepting transfer immediately.
 *
 * <p>Core's own {@code NeoForgeEnergyLookup} already falls back to vanilla Forge Energy
 * ({@code Capabilities.Energy.BLOCK}) when it looks <em>outward</em>, converting through
 * {@code EnergyConversions} — so third-party cables reach our machines without NeroColonies
 * registering anything on the FE capability itself.
 */
public final class NeoForgeColonyCapabilities {

    private NeoForgeColonyCapabilities() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeColonyCapabilities::onRegister);
    }

    private static void onRegister(RegisterCapabilitiesEvent event) {
        for (BlockEntityType<? extends AbstractMachineBlockEntity> type
                : NeroColoniesBlockEntities.machineTypes()) {
            event.registerBlockEntity(NeoForgeEnergyLookup.ENERGY, type,
                    (be, side) -> be.sideConfig() == null ? be.getEnergy() : be.sideConfig().energyView(side));
            event.registerBlockEntity(Capabilities.Item.BLOCK, type, (be, side) -> {
                if (!(be instanceof WorldlyContainer container)) {
                    return null;
                }
                return side == null ? VanillaContainerWrapper.of(container)
                        : new WorldlyContainerWrapper(container, side);
            });
        }
        // Gas is only advertised on the machines that actually hold it.
        for (BlockEntityType<OxygenGeneratorBlockEntity> type : NeroColoniesBlockEntities.gasTypes()) {
            event.registerBlockEntity(NeoForgeGasLookup.GAS, type,
                    (be, side) -> be.sideConfig() == null ? be.getGas() : be.sideConfig().gasView(side));
        }
    }
}
