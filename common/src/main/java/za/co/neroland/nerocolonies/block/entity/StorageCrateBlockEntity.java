package za.co.neroland.nerocolonies.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import za.co.neroland.nerocolonies.colony.ColonyStorageBlocks;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * A Storage Crate: a {@link ColonyDepotBlockEntity} that holds more. It opens the same window onto the
 * colony's one stock, works with pipes the same way, and adds {@value ColonyStorageBlocks#CRATE_SLOTS}
 * slots of capacity where a depot adds {@value ColonyStorageBlocks#DEPOT_SLOTS}.
 */
public class StorageCrateBlockEntity extends ColonyDepotBlockEntity {

    public StorageCrateBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.STORAGE_CRATE.get(), pos, state);
    }

    @Override
    public boolean isCrate() {
        return true;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.nerocolonies.storage_crate");
    }
}
