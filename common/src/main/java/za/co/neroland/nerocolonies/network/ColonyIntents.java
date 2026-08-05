package za.co.neroland.nerocolonies.network;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.block.entity.ColonyBeaconBlockEntity;
import za.co.neroland.nerocolonies.block.entity.ColonyDepotBlockEntity;
import za.co.neroland.nerocolonies.block.entity.JobStationBlockEntity;
import za.co.neroland.nerocolonies.block.entity.ResearchStationBlockEntity;
import za.co.neroland.nerocolonies.colony.AccessLog;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ExportBuffer;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.colony.Research;

/**
 * The server-side handler for {@link ColonyIntentPayload} — the one place a NeroColonies client can
 * cause anything to happen.
 *
 * <h2>Nothing off the wire is trusted</h2>
 *
 * <p>Every intent is re-derived from server state before it is acted on: the block must be loaded and
 * within reach, the colony is looked up from the block rather than taken from the packet, the op code
 * is bounded, and the permission check runs against the colony record. An intent that fails any of
 * these is dropped silently and the sender is sent a fresh snapshot — so a desynchronised client
 * corrects itself instead of retrying.
 *
 * <h2>The access-list editor, and why it takes a name</h2>
 *
 * <p>Granting access needs to identify a player, and NeroColonies will not send an access list to a
 * client — a client told who is on a colony's list has been told where those people play. So the flow
 * runs the other way: the owner <b>types a name</b>, the server resolves it against the players it
 * can see, and the client is told a <em>count</em>. The list itself never leaves the server.
 *
 * <p>The name is resolved against <b>online players only</b>. That is a real limitation and a
 * deliberate one for a GUI: an offline lookup means consulting the profile cache, which is a place
 * where names and UUIDs are correlated, and doing that from a packet a client can send at will is not
 * a trade this mod makes. Operators have the command path for offline members.
 */
public final class ColonyIntents {

    /** Squared reach for an intent, matching Core's side-config handler. */
    private static final double REACH_SQR = 64.0D;

    private ColonyIntents() {
    }

    /** Registered from {@code ColonyNetwork.init()}; called on the server thread by every loader. */
    public static void handle(ColonyIntentPayload payload, ServerPlayer player) {
        if (!payload.validOp()) {
            return;
        }
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        BlockPos pos = payload.pos();
        if (server == null || !level.isLoaded(pos)) {
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH_SQR) {
            return;
        }
        Colony colony = resolveColony(level, pos);
        if (colony == null || !ColonyClaims.canAccess(player, colony)) {
            ColonySync.sendSnapshot(player, null, pos);
            return;
        }

        switch (payload.op()) {
            case ColonyIntentPayload.OP_RESEARCH -> research(server, level, player, colony, pos,
                    payload.argument());
            case ColonyIntentPayload.OP_ACCESS_ADD -> access(server, player, colony, payload.argument(), true);
            case ColonyIntentPayload.OP_ACCESS_REMOVE ->
                    access(server, player, colony, payload.argument(), false);
            case ColonyIntentPayload.OP_SELL_EXPORTS -> sell(server, player, colony);
            case ColonyIntentPayload.OP_TOGGLE_EXPORT -> toggleExport(level, player, pos);
            default -> {
                // OP_REFRESH: the snapshot below is the whole of the response.
            }
        }
        // Always answer with the authoritative state, whatever happened.
        Colony latest = ColonyState.get(server).colony(colony.colonyId());
        ColonySync.sendSnapshot(player, latest, pos);
    }

    // --- operations ---------------------------------------------------------

    private static void research(MinecraftServer server, ServerLevel level, ServerPlayer player,
            Colony colony, BlockPos pos, String nodeId) {
        Identifier node = Identifier.tryParse(nodeId);
        if (node == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        ResearchStationBlockEntity station =
                blockEntity instanceof ResearchStationBlockEntity found ? found : null;
        long energy = station == null ? -1L : station.storedEnergy();
        long cost = station == null ? 0L : ResearchStationBlockEntity.ENERGY_PER_UNLOCK;

        Research.Result result = Research.unlock(server, colony, node, energy, cost);
        if (result == Research.Result.UNLOCKED && station != null) {
            station.spendUnlockEnergy();
        }
        player.sendSystemMessage(Component.translatable(switch (result) {
            case UNLOCKED -> "message.nerocolonies.research.unlocked";
            case ALREADY_UNLOCKED -> "message.nerocolonies.research.already";
            case UNKNOWN_NODE -> "message.nerocolonies.research.unknown";
            case PREREQUISITES_MISSING -> "message.nerocolonies.research.locked";
            case CANNOT_AFFORD -> "message.nerocolonies.research.cannot_afford";
            case NO_POWER -> "message.nerocolonies.research.no_power";
        }));
        if (result == Research.Result.UNLOCKED) {
            ColonySync.refresh(server, colony.colonyId());
        }
    }

    /**
     * Adds or removes an access-list member by name. Owner (or operator) only — an access-list member
     * may use a colony, not decide who else may.
     */
    private static void access(MinecraftServer server, ServerPlayer player, Colony colony, String name,
            boolean grant) {
        if (!colony.isOwner(player.getUUID()) && !ColonyClaims.isGamemaster(player)) {
            player.sendSystemMessage(Component.translatable("message.nerocolonies.access.owner_only"));
            return;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayerByName(trimmed);
        if (target == null) {
            player.sendSystemMessage(Component.translatable("message.nerocolonies.access.not_found"));
            return;
        }
        UUID targetId = target.getUUID();
        if (targetId.equals(colony.ownerId())) {
            player.sendSystemMessage(Component.translatable("message.nerocolonies.access.is_owner"));
            return;
        }
        Colony updated = grant ? colony.grantAccess(targetId) : colony.revokeAccess(targetId);
        if (updated == colony) {
            player.sendSystemMessage(Component.translatable(grant
                    ? "message.nerocolonies.access.already"
                    : "message.nerocolonies.access.absent"));
            return;
        }
        ColonyState colonies = ColonyState.get(server);
        colonies.put(updated);
        colonies.log(colony.colonyId(), targetId,
                grant ? AccessLog.Action.ACCESS_GRANT : AccessLog.Action.ACCESS_REVOKE);
        // The message names the colony and a count, never the member (POPIA/GDPR): the person who
        // typed the name already knows it, and nothing else needs to.
        player.sendSystemMessage(Component.translatable(
                grant ? "message.nerocolonies.access.granted" : "message.nerocolonies.access.revoked",
                updated.accessList().size()));
        ColonySync.refresh(server, colony.colonyId());
    }

    /**
     * Flips a job station's output routing. Access to the colony was already checked, which is the
     * right gate: deciding what a colony trades is a colony member's business, not only the owner's.
     */
    private static void toggleExport(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof JobStationBlockEntity station)) {
            return;
        }
        boolean next = !station.exportOutput();
        station.setExportOutput(next);
        player.sendSystemMessage(Component.translatable(next
                ? "message.nerocolonies.export.routed_to_buffer"
                : "message.nerocolonies.export.routed_to_storage"));
    }

    private static void sell(MinecraftServer server, ServerPlayer player, Colony colony) {
        ExportBuffer.SaleResult result = ExportBuffer.sell(server, colony);
        player.sendSystemMessage(switch (result.status()) {
            case SOLD -> Component.translatable("message.nerocolonies.export.sold",
                    result.items(), result.credits());
            case NOTHING_TO_SELL -> Component.translatable("message.nerocolonies.export.nothing");
            case NO_MARKET -> Component.translatable("message.nerocolonies.export.no_market");
            case NO_OWNER -> Component.translatable("message.nerocolonies.export.no_owner");
        });
        if (result.status() == ExportBuffer.SaleResult.Status.SOLD) {
            ColonySync.refresh(server, colony.colonyId());
        }
    }

    // --- resolution ---------------------------------------------------------

    /**
     * Works out which colony an anchor block belongs to. The block entity is asked first (it already
     * knows, and it knows even when the block sits at the very edge of a claim), then the claim index,
     * then any outpost's parent.
     */
    @Nullable
    private static Colony resolveColony(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        ColonyState colonies = ColonyState.get(server);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        UUID fromBlock = switch (blockEntity) {
            case ColonyBeaconBlockEntity beacon -> beacon.colonyId();
            case ResearchStationBlockEntity station -> station.colonyId();
            case JobStationBlockEntity station -> station.colonyId();
            case ColonyDepotBlockEntity depot -> depot.colonyId();
            case null, default -> null;
        };
        if (fromBlock != null) {
            Colony colony = colonies.colony(fromBlock);
            if (colony != null) {
                return colony;
            }
        }
        Colony colony = colonies.colonyAt(level.dimension(), pos);
        if (colony != null) {
            return colony;
        }
        Outpost outpost = colonies.outpostAt(level.dimension(), pos);
        return outpost == null ? null : colonies.colony(outpost.parentColonyId());
    }
}
