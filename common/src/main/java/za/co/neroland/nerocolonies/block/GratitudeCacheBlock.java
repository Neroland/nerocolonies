package za.co.neroland.nerocolonies.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.GratitudeCache;
import za.co.neroland.nerocolonies.menu.MenuOpener;

/**
 * The Gratitude Cache: the chest the colony's Quartermaster fills with thank-you gifts.
 *
 * <p>The block holds nothing. The cache is one per colony and lives in the colony's own record, so
 * every Gratitude Cache block in a claim opens the same contents and breaking the block loses
 * nothing. Only the owner may open it, plus allies if the owner has chosen to share it.
 */
public class GratitudeCacheBlock extends Block {

    public GratitudeCacheBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        Colony colony = ColonyState.get(serverLevel.getServer()).colonyAt(serverLevel.dimension(), pos);
        if (colony == null) {
            serverPlayer.sendSystemMessage(Component.translatable("message.nerocolonies.needs.no_colony"));
            return InteractionResult.SUCCESS;
        }
        if (!ColonyPermissions.check(serverPlayer, colony, ColonyPermissions.Action.OPEN_CACHE)) {
            return InteractionResult.SUCCESS;
        }
        SimpleContainer view = GratitudeCache.view(serverLevel.getServer(), colony.colonyId());
        MenuOpener.open(serverPlayer, new SimpleMenuProvider(
                (id, inventory, opener) -> ChestMenu.threeRows(id, inventory, view),
                Component.translatable("container.nerocolonies.gratitude_cache")));
        return InteractionResult.SUCCESS;
    }
}
