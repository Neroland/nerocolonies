package za.co.neroland.nerocolonies.forge;

import java.util.EnumMap;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.WorldlyContainer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.energy.NeroEnergyStorage;
import za.co.neroland.nerolandcore.gas.NeroGasStorage;
import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.platform.ForgeEnergyLookup;
import za.co.neroland.nerolandcore.platform.ForgeGasLookup;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.OxygenGeneratorBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * Forge capability exposure for every NeroColonies machine: Core's energy surface plus the standard
 * item handler.
 *
 * <p>The energy handler is a thin per-side view that re-resolves the machine's <em>current</em>
 * side-config gate on every operation — mirroring the per-query Fabric/NeoForge lookups — so a face
 * the player disables stops accepting transfer immediately, without needing an invalidation
 * callback from Core.
 */
public final class ForgeColonyCapabilities {

    private static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "machine_caps");

    private ForgeColonyCapabilities() {
    }

    public static void register() {
        AttachCapabilitiesEvent.BlockEntities.BUS.addListener(ForgeColonyCapabilities::attach);
    }

    private static void attach(AttachCapabilitiesEvent.BlockEntities event) {
        if (event.getObject() instanceof AbstractMachineBlockEntity machine
                && NeroColoniesBlockEntities.machineTypes().contains(machine.getType())) {
            MachineCaps caps = new MachineCaps(machine);
            event.addCapability(ID, caps);
            event.addListener(caps::invalidate);
        }
    }

    /** Energy view that re-resolves the side-config gate per call; no-ops when the face is disabled. */
    private record SideEnergyView(AbstractMachineBlockEntity machine, @Nullable Direction side)
            implements NeroEnergyStorage {

        @Nullable
        private NeroEnergyStorage view() {
            return machine.sideConfig() == null ? machine.getEnergy() : machine.sideConfig().energyView(side);
        }

        @Override
        public long getAmount() {
            NeroEnergyStorage view = view();
            return view == null ? 0L : view.getAmount();
        }

        @Override
        public long getCapacity() {
            NeroEnergyStorage view = view();
            return view == null ? 0L : view.getCapacity();
        }

        @Override
        public long insert(long maxAmount, boolean simulate) {
            NeroEnergyStorage view = view();
            return view == null ? 0L : view.insert(maxAmount, simulate);
        }

        @Override
        public long extract(long maxAmount, boolean simulate) {
            NeroEnergyStorage view = view();
            return view == null ? 0L : view.extract(maxAmount, simulate);
        }
    }

    /** Gas view with the same per-call side-config re-resolution as the energy view above. */
    private record SideGasView(OxygenGeneratorBlockEntity machine, @Nullable Direction side)
            implements NeroGasStorage {

        @Nullable
        private NeroGasStorage view() {
            return machine.sideConfig() == null ? machine.getGas() : machine.sideConfig().gasView(side);
        }

        @Override
        public net.minecraft.resources.Identifier getGas() {
            NeroGasStorage view = view();
            return view == null ? za.co.neroland.nerolandcore.gas.NeroGases.EMPTY : view.getGas();
        }

        @Override
        public long getAmount() {
            NeroGasStorage view = view();
            return view == null ? 0L : view.getAmount();
        }

        @Override
        public long getCapacity() {
            NeroGasStorage view = view();
            return view == null ? 0L : view.getCapacity();
        }

        @Override
        public long fill(net.minecraft.resources.Identifier gas, long amount, boolean simulate) {
            NeroGasStorage view = view();
            return view == null ? 0L : view.fill(gas, amount, simulate);
        }

        @Override
        public long drain(long amount, boolean simulate) {
            NeroGasStorage view = view();
            return view == null ? 0L : view.drain(amount, simulate);
        }
    }

    private static final class MachineCaps implements ICapabilityProvider {

        private final AbstractMachineBlockEntity machine;
        private final LazyOptional<NeroEnergyStorage> unsidedEnergy;
        private final LazyOptional<IItemHandler> unsidedItems;
        private final LazyOptional<NeroGasStorage> unsidedGas;
        private final EnumMap<Direction, LazyOptional<NeroEnergyStorage>> sidedEnergy =
                new EnumMap<>(Direction.class);
        private final EnumMap<Direction, LazyOptional<IItemHandler>> sidedItems =
                new EnumMap<>(Direction.class);
        private final EnumMap<Direction, LazyOptional<NeroGasStorage>> sidedGas =
                new EnumMap<>(Direction.class);

        MachineCaps(AbstractMachineBlockEntity machine) {
            this.machine = machine;
            this.unsidedEnergy = LazyOptional.of(() -> new SideEnergyView(machine, null));
            this.unsidedItems = machine instanceof WorldlyContainer container
                    ? LazyOptional.of(() -> new InvWrapper(container))
                    : LazyOptional.empty();
            this.unsidedGas = machine instanceof OxygenGeneratorBlockEntity generator
                    ? LazyOptional.of(() -> new SideGasView(generator, null))
                    : LazyOptional.empty();
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
            if (capability == ForgeEnergyLookup.ENERGY) {
                return (side == null ? this.unsidedEnergy
                        : this.sidedEnergy.computeIfAbsent(side,
                                direction -> LazyOptional.of(() -> new SideEnergyView(this.machine, direction))))
                        .cast();
            }
            if (capability == ForgeGasLookup.GAS
                    && this.machine instanceof OxygenGeneratorBlockEntity generator) {
                return (side == null ? this.unsidedGas
                        : this.sidedGas.computeIfAbsent(side,
                                direction -> LazyOptional.of(() -> new SideGasView(generator, direction))))
                        .cast();
            }
            if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER
                    && this.machine instanceof WorldlyContainer container) {
                return (side == null ? this.unsidedItems
                        : this.sidedItems.computeIfAbsent(side,
                                direction -> LazyOptional.of(() -> new SidedInvWrapper(container, direction))))
                        .cast();
            }
            return LazyOptional.empty();
        }

        void invalidate() {
            this.unsidedEnergy.invalidate();
            this.unsidedItems.invalidate();
            this.unsidedGas.invalidate();
            this.sidedEnergy.values().forEach(LazyOptional::invalidate);
            this.sidedItems.values().forEach(LazyOptional::invalidate);
            this.sidedGas.values().forEach(LazyOptional::invalidate);
        }
    }
}
