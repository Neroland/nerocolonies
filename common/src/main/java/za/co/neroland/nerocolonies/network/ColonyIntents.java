package za.co.neroland.nerocolonies.network;

import java.util.Locale;
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
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyMembership;
import za.co.neroland.nerocolonies.colony.ColonyNeeds;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
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
 * is bounded, and the sender's role is worked out from the colony record by
 * {@link ColonyPermissions} — first "may this player interact at all?", then the action the
 * particular operation needs. An intent that fails any of these is dropped and the sender is sent a
 * fresh snapshot — so a desynchronised client corrects itself instead of retrying.
 *
 * <h2>The role editor, and why it takes a name</h2>
 *
 * <p>Changing somebody's role needs to identify a player, and no client is ever sent a UUID. So the
 * flow runs the other way: a member-manager <b>types a name</b>, the server resolves it against the
 * players it can see, {@link ColonyMembership} applies the same rules the commands use, and the
 * answer is a message that says what happened <b>without naming anybody</b> — the person who typed
 * the name already knows it, and nothing else needs to. The name is not stored and not logged.
 *
 * <p>The name is resolved against <b>online players only</b>. That is a real limitation and a
 * deliberate one for a GUI: resolving a typed name for somebody offline means asking the profile
 * cache "who is called this?", and doing that from a packet a client can send at will is not a trade
 * this mod makes. Operators and owners have the command path, by UUID, for offline members.
 *
 * <p>What the editor's user sees in return is the colony's roster, which the server sends only to a
 * viewer who may manage members; {@link ColonySnapshotPayload} describes exactly what that is.
 */
public final class ColonyIntents {

    /** Squared reach for an intent, matching Core's side-config handler. */
    private static final double REACH_SQR = 64.0D;

    /** Longest name the role editor will try to resolve. The screen's field is capped the same. */
    private static final int MAX_PLAYER_NAME_CHARS = 32;

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
        if (colony == null
                || !ColonyPermissions.can(player, colony, ColonyPermissions.Action.INTERACT)) {
            ColonySync.sendSnapshot(player, null, pos);
            return;
        }

        switch (payload.op()) {
            case ColonyIntentPayload.OP_RESEARCH -> research(server, level, player, colony, pos,
                    payload.argument());
            case ColonyIntentPayload.OP_ACCESS_ADD -> role(server, player, colony,
                    ColonyIntentPayload.ROLE_ALLY, payload.argument(), true);
            case ColonyIntentPayload.OP_ACCESS_REMOVE -> role(server, player, colony,
                    ColonyIntentPayload.ROLE_ALLY, payload.argument(), false);
            case ColonyIntentPayload.OP_ROLE_ADD -> roleIntent(server, player, colony, payload.argument(),
                    true);
            case ColonyIntentPayload.OP_ROLE_REMOVE -> roleIntent(server, player, colony,
                    payload.argument(), false);
            case ColonyIntentPayload.OP_PRIORITISE_NEED -> prioritise(server, player, colony,
                    payload.argument());
            case ColonyIntentPayload.OP_CACHE_SHARE -> cacheShare(server, player, colony,
                    payload.argument());
            case ColonyIntentPayload.OP_DELIVER -> deliver(level, player, colony);
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

    /** Splits {@code "<role>|<player name>"} and hands it on. A malformed argument is dropped. */
    private static void roleIntent(MinecraftServer server, ServerPlayer player, Colony colony,
            String argument, boolean add) {
        int split = argument.indexOf(ColonyIntentPayload.ROLE_SEPARATOR);
        if (split <= 0) {
            return;
        }
        role(server, player, colony, argument.substring(0, split).trim().toLowerCase(Locale.ROOT),
                argument.substring(split + 1), add);
    }

    /**
     * Gives an online player a role, or takes one away. What is allowed is {@link ColonyMembership}'s
     * decision, made from the sender's role on the server; all this does is turn a typed name into an
     * online player and an outcome into a message.
     */
    private static void role(MinecraftServer server, ServerPlayer player, Colony colony, String roleToken,
            String name, boolean add) {
        ColonyPermissions.Role actor = ColonyPermissions.effectiveRole(player, colony);
        // Every role change needs at least this, and asking first means somebody who may not manage
        // members cannot use the editor to ask the server who is online.
        if (!ColonyPermissions.allows(actor, ColonyPermissions.Action.MANAGE_MEMBERS, false)) {
            tell(player, ColonyMembership.Result.NOT_ALLOWED);
            return;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_PLAYER_NAME_CHARS) {
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayerByName(trimmed);
        if (target == null) {
            player.sendSystemMessage(Component.translatable("message.nerocolonies.access.not_found"));
            return;
        }
        UUID actorId = player.getUUID();
        UUID targetId = target.getUUID();
        ColonyMembership.Result result = switch (roleToken) {
            case ColonyIntentPayload.ROLE_ALLY ->
                    ColonyMembership.setAlly(server, colony, actor, actorId, targetId, add);
            case ColonyIntentPayload.ROLE_CHIEF ->
                    ColonyMembership.setChief(server, colony, actor, actorId, targetId, add);
            case ColonyIntentPayload.ROLE_ENEMY, ColonyIntentPayload.ROLE_ENEMY_CONFIRMED ->
                    ColonyMembership.setEnemy(server, colony, actor, actorId, targetId, add,
                            ColonyIntentPayload.ROLE_ENEMY_CONFIRMED.equals(roleToken),
                            ColonyClaims.isGamemaster(target));
            default -> null;
        };
        if (result != null) {
            // The message says what happened and never to whom (POPIA/GDPR).
            tell(player, result);
        }
    }

    /** The player's own carried inventory: the hotbar and the three rows above it, nothing worn. */
    private static final int CARRIED_SLOTS = 36;

    /**
     * Hands the colony what the sender is carrying that its needs list wants. Any member may: this
     * is the same permission the Needs Board asks for, and it only ever <em>adds</em> to colony
     * storage. What is taken is worked out here, from the server's needs list and the sender's own
     * inventory — the packet says nothing but "I am offering".
     */
    private static void deliver(ServerLevel level, ServerPlayer player, Colony colony) {
        if (!ColonyPermissions.check(player, colony, ColonyPermissions.Action.CONTRIBUTE)) {
            return;
        }
        ColonyNeeds.Delivery delivery = ColonyNeeds.contributeAll(level, colony, player.getInventory(),
                CARRIED_SLOTS);
        if (delivery.accepted() > 0) {
            player.containerMenu.broadcastChanges();
            player.sendSystemMessage(Component.translatable(delivery.storageFull()
                    ? "message.nerocolonies.needs.delivered_full"
                    : "message.nerocolonies.needs.delivered", delivery.accepted()));
        } else {
            player.sendSystemMessage(Component.translatable(delivery.storageFull()
                    ? "message.nerocolonies.needs.storage_full"
                    : "message.nerocolonies.needs.nothing_to_deliver"));
        }
    }

    /**
     * Prioritises one of the colony's current needs, or clears the priority. The label has to be an
     * item id — or {@code #tag}, for an "any ..." need — that is on the needs list <em>now</em>, as
     * the server derives it; a client cannot use this to store an arbitrary string.
     */
    private static void prioritise(MinecraftServer server, ServerPlayer player, Colony colony,
            String argument) {
        ColonyPermissions.Role actor = ColonyPermissions.effectiveRole(player, colony);
        if (!ColonyPermissions.allows(actor, ColonyPermissions.Action.PLAN, false)) {
            tell(player, ColonyMembership.Result.NOT_ALLOWED);
            return;
        }
        String label = argument == null ? "" : argument.trim();
        Identifier item = null;
        Identifier tag = null;
        if (!label.isEmpty()) {
            boolean tagged = label.charAt(0) == '#';
            Identifier id = Identifier.tryParse(tagged ? label.substring(1) : label);
            if (id == null || !isCurrentNeed(server, colony, id, tagged)) {
                return;
            }
            if (tagged) {
                tag = id;
            } else {
                item = id;
            }
        }
        ColonyMembership.Result result = ColonyMembership.setPriority(server, colony, actor, item, tag);
        if (result == ColonyMembership.Result.DONE || result == ColonyMembership.Result.ALREADY) {
            player.sendSystemMessage(Component.translatable(item == null && tag == null
                    ? "message.nerocolonies.need.cleared" : "message.nerocolonies.need.prioritised"));
        } else {
            tell(player, result);
        }
    }

    private static boolean isCurrentNeed(MinecraftServer server, Colony colony, Identifier id,
            boolean tagged) {
        ServerLevel home = server.getLevel(colony.dimension());
        if (home == null) {
            return false;
        }
        for (ColonyNeeds.Need need : ColonyNeeds.derive(home, colony)) {
            if ((tagged ? need.target().tag() : need.target().item()).map(id::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** Shares the Gratitude Cache with Allies, or stops. Owner (or operator) only. */
    private static void cacheShare(MinecraftServer server, ServerPlayer player, Colony colony,
            String argument) {
        boolean shared;
        if ("1".equals(argument)) {
            shared = true;
        } else if ("0".equals(argument)) {
            shared = false;
        } else {
            return;
        }
        ColonyMembership.Result result = ColonyMembership.setCacheShared(server, colony,
                ColonyPermissions.effectiveRole(player, colony), shared);
        if (result == ColonyMembership.Result.DONE || result == ColonyMembership.Result.ALREADY) {
            player.sendSystemMessage(Component.translatable(shared
                    ? "message.nerocolonies.cache.shared" : "message.nerocolonies.cache.private"));
        } else {
            tell(player, result);
        }
    }

    private static void tell(ServerPlayer player, ColonyMembership.Result result) {
        player.sendSystemMessage(Component.translatable(ColonyMembership.messageKey(result)));
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
