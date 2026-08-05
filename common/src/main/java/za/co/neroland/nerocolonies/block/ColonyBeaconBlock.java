package za.co.neroland.nerocolonies.block;

import java.util.UUID;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.progression.CoreGates;
import za.co.neroland.nerolandcore.progression.ProgressionGates;

import za.co.neroland.nerocolonies.progression.ColonyGates;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity;
import za.co.neroland.nerocolonies.colony.AccessLog;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.colony.Population;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.menu.MenuOpener;
import za.co.neroland.nerocolonies.network.ColonySync;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The colony command block. Placing it founds a colony; sneak-breaking it (as the owner or an
 * operator) dissolves one.
 *
 * <h2>Placement</h2>
 *
 * <p>{@link #setPlacedBy} validates through {@link ColonyClaims} <em>after</em> the block is in the
 * world (vanilla gives no pre-placement veto for a block item), so a refused placement drops the
 * beacon back to the player and removes the block. The alternative — leaving an inert beacon
 * standing — is the kind of thing players file bug reports about.
 *
 * <p>A successful founding also calls {@code ProgressionGates.tryOpen(FIRST_COLONY)}. <b>tryOpen,
 * not open</b>: the gate has its own requirements (Core makes {@code first_colony} depend on
 * {@code reached_orbit}) and it is not this mod's business to force past them. Nothing in
 * NeroColonies ever <em>requires</em> a gate to be open — standalone-first, per the Nerotech
 * precedent — so the write is purely a signal to the rest of the ecosystem, and it is idempotent
 * because Nerospace's Star-Guide opens the same gate.
 *
 * <h2>Breaking</h2>
 *
 * <p>{@link #getDestroyProgress} returns zero for anybody who may not dissolve the colony, which is
 * how the "owner or operator, and you must sneak" rule is enforced without a loader-specific event:
 * the block simply refuses to break. The sneak requirement is the confirmation prompt — a colony
 * record is too expensive to lose to a stray pickaxe swing.
 */
public class ColonyBeaconBlock extends BaseEntityBlock {

    public static final MapCodec<ColonyBeaconBlock> CODEC = simpleCodec(ColonyBeaconBlock::new);

    public ColonyBeaconBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ColonyBeaconBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ColonyBeaconBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, NeroColoniesBlockEntities.COLONY_BEACON.get(),
                (lvl, pos, st, be) -> AbstractMachineBlockEntity.tick(lvl, pos, st, be));
    }

    // --- interaction --------------------------------------------------------

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof ColonyBeaconBlockEntity beacon)) {
            return InteractionResult.SUCCESS;
        }
        Colony colony = beacon.colony();
        if (colony != null && !ColonyClaims.canAccess(serverPlayer, colony)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.nerocolonies.claim.no_access"));
            return InteractionResult.SUCCESS;
        }
        if (colony != null && level instanceof ServerLevel serverLevel) {
            ColonyState.get(serverLevel.getServer())
                    .log(colony.colonyId(), serverPlayer.getUUID(), AccessLog.Action.OPEN);
        }
        // The Trade tab and the access editor draw synced state that does not fit through a menu's
        // 16-bit data slots, so the snapshot goes out before the menu opens.
        ColonySync.open(serverPlayer, colony, pos);
        MenuOpener.open(serverPlayer, beacon);
        return InteractionResult.SUCCESS;
    }

    // --- founding -----------------------------------------------------------

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof ColonyBeaconBlockEntity beacon)) {
            return;
        }
        UUID owner = player.getUUID();
        Component refusal = ColonyClaims.validatePlacement(serverLevel, pos, owner);
        if (refusal != null) {
            refuse(serverLevel, pos, player, stack, refusal);
            return;
        }
        ColonyState colonies = ColonyState.get(serverLevel.getServer());
        Colony colony = Colony.found(UUID.randomUUID(), colonyName(player), serverLevel.dimension(), pos,
                NeroColoniesConfig.CLAIM_RADIUS.get(), owner, serverLevel.getGameTime());
        colonies.put(colony);
        colonies.log(colony.colonyId(), owner, AccessLog.Action.FOUND);
        beacon.bind(colony.colonyId());

        // The founders arrive with the beacon, not on the first colony tick a minute and a half
        // later: they are what starts the whole autonomous loop, and the player is standing here now.
        Population.spawnFounders(serverLevel, colony);

        if (NeroColoniesConfig.GATE_WRITES_ENABLED.get()) {
            // tryOpen, never open: requirements are Core's business, and Nerospace opens the same
            // gate from the Star-Guide, so a double open must be (and is) a no-op.
            ProgressionGates.tryOpen(player, CoreGates.FIRST_COLONY);
            // NeroColonies' own soft gate, for other mods and datapacks to key off. Nothing in this
            // mod reads it — see ColonyGates.
            ColonyGates.founded(player);
        }
        player.sendSystemMessage(
                Component.translatable("message.nerocolonies.claim.founded", colony.name()));
        NeroColoniesCommon.LOGGER.info("[NeroColonies] Colony founded in {} ({} total).",
                serverLevel.dimension().identifier(), colonies.size());
    }

    /** Undoes a refused placement: remove the block, hand the item back, explain why. */
    private static void refuse(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack stack,
            Component reason) {
        level.removeBlock(pos, false);
        if (!player.isCreative()) {
            ItemStack refund = stack.copy();
            refund.setCount(1);
            if (!player.getInventory().add(refund)) {
                player.drop(refund, false);
            }
        }
        player.sendSystemMessage(reason);
    }

    /**
     * The default name of a newly founded colony. It uses the founder's display name, which they
     * chose and which is already visible to every player on the server — and it is sanitised and
     * length-capped by {@link Colony}'s constructor like any other player-supplied string. Renaming
     * is a player-level command (Stage 10).
     */
    private static String colonyName(ServerPlayer player) {
        return player.getName().getString() + "'s Colony";
    }

    // --- dissolving ---------------------------------------------------------

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float base = super.getDestroyProgress(state, player, level, pos);
        if (!(level instanceof ServerLevel serverLevel)) {
            return base; // client: animate normally, the server is the authority
        }
        return ColonyClaims.mayDissolve(serverLevel, pos, player) ? base : 0.0F;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof ColonyBeaconBlockEntity beacon) {
            Containers.dropContents(level, pos, beacon.allContents());
            Colony colony = ColonyClaims.colonyAtBeacon(serverLevel, pos);
            if (colony != null) {
                ColonyState colonies = ColonyState.get(serverLevel.getServer());
                colonies.log(colony.colonyId(), player.getUUID(), AccessLog.Action.DISSOLVE);
                // The colony's goods are not the beacon block's to drop from allContents() — that
                // would duplicate them, because the store still holds the same stacks. Dropping and
                // forgetting is one operation for exactly that reason.
                ColonyStores.dropAndForget(level, pos, colony.colonyId());
                // The build record goes with the colony too. What it already put up stays standing:
                // NeroColonies never demolishes anything it built.
                Construction.forget(serverLevel.getServer(), colony.colonyId());
                colonies.remove(colony.colonyId());
                player.sendSystemMessage(
                        Component.translatable("message.nerocolonies.claim.dissolved", colony.name()));
                NeroColoniesCommon.LOGGER.info("[NeroColonies] Colony dissolved ({} remaining).",
                        colonies.size());
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** Comparator output tracks colony morale, so redstone can react to a colony in trouble. */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        if (!(level.getBlockEntity(pos) instanceof ColonyBeaconBlockEntity beacon)) {
            return 0;
        }
        Colony colony = beacon.colony();
        return colony == null ? 0 : (int) Math.clamp(Math.round(colony.morale() * 15.0D / 100.0D), 0L, 15L);
    }
}
