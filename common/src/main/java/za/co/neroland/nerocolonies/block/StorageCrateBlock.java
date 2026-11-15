package za.co.neroland.nerocolonies.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.registry.BlockCodecs;

import za.co.neroland.nerocolonies.block.entity.StorageCrateBlockEntity;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The Storage Crate: where a colony keeps its goods. It behaves like a {@link ColonyDepotBlock} (the
 * same window onto the colony's one stock, the same access rule, the same pipes) and holds more, so
 * building crates is how a colony's storage grows. A colony needs a crate or a depot in its claim
 * before it can be handed its Starter Works materials.
 */
public class StorageCrateBlock extends ColonyDepotBlock {

    public static final MapCodec<StorageCrateBlock> CRATE_CODEC = BlockCodecs.simple(StorageCrateBlock::new);

    public StorageCrateBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<StorageCrateBlock> codec() {
        return CRATE_CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageCrateBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.STORAGE_CRATE.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }
}
