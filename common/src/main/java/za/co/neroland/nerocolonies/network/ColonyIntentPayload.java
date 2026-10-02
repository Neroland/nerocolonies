package za.co.neroland.nerocolonies.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * Everything a NeroColonies client may <b>ask</b> the server to do, in one payload.
 *
 * <h2>One payload, one op code</h2>
 *
 * <p>A separate serverbound payload per operation would mean a registration in each of three loader
 * modules, a stream codec and a handler apiece — for operations that all take "a block, and at most
 * one string". Core's own {@code SideConfigIntentPayload} makes the same call for the same reason,
 * and this follows it: an int op, the anchor block, and one argument.
 *
 * <h2>It is an intent, not a command</h2>
 *
 * <p>Nothing here is trusted. The handler re-derives the colony from the block, re-checks reach,
 * re-checks the sender's role and re-checks affordability from the server's own state; the op code
 * is bounded before it is switched on. A client that sends {@code OP_SELL_EXPORTS} for a beacon in
 * another dimension gets a refused packet and a fresh snapshot, not a sale.
 *
 * <p><b>Privacy:</b> the one free-text field can carry a player <em>name</em>, and it does so solely
 * so that somebody who manages a colony's members can type who to add, promote, mark or remove. The
 * name travels client → server, is matched against the players who are online, and is then dropped:
 * it is never stored, logged or echoed back in the answer. (Names do travel the other way in one
 * narrow case — the roster a member-manager sees — and that is described on
 * {@link ColonySnapshotPayload}.)
 */
public record ColonyIntentPayload(int op, BlockPos pos, String argument) implements CustomPacketPayload {

    /** Unlock a research node. {@link #argument} is the node id. */
    public static final int OP_RESEARCH = 0;

    /** Make an online player an Ally, by name. The same thing as {@link #OP_ROLE_ADD} with {@code ally}. */
    public static final int OP_ACCESS_ADD = 1;

    /** Remove an Ally, by name. The same thing as {@link #OP_ROLE_REMOVE} with {@code ally}. */
    public static final int OP_ACCESS_REMOVE = 2;

    /** Sell the colony's export buffer. */
    public static final int OP_SELL_EXPORTS = 3;

    /** Ask for a fresh snapshot and nothing else. Always safe, always allowed for a member. */
    public static final int OP_REFRESH = 4;

    /** Flip a job station between "output to colony storage" and "output to the export buffer". */
    public static final int OP_TOGGLE_EXPORT = 5;

    /**
     * Give an online player a role. {@link #argument} is {@code "<ally|chief|enemy>|<player name>"};
     * the role token {@code enemy!} is the confirmed form, needed to mark somebody who is a member.
     */
    public static final int OP_ROLE_ADD = 6;

    /** Take a role away. {@link #argument} is {@code "<ally|chief|enemy>|<player name>"}. */
    public static final int OP_ROLE_REMOVE = 7;

    /**
     * Prioritise a need. {@link #argument} is the need's label exactly as the snapshot sent it — an
     * item id, or {@code #tag} for an "any ..." need — or empty to clear the priority.
     */
    public static final int OP_PRIORITISE_NEED = 8;

    /** Share the Gratitude Cache with Allies ({@code "1"}) or stop sharing it ({@code "0"}). */
    public static final int OP_CACHE_SHARE = 9;

    /**
     * Hand the colony everything the sender is carrying that its needs list wants, and no more of
     * each thing than it is short of. Takes no argument: what is taken is decided on the server, from
     * the server's own needs list and the sender's own inventory.
     */
    public static final int OP_DELIVER = 10;

    private static final int OP_COUNT = 11;

    /** A generous cap on the one free-text field — a player name or an id, never a sentence. */
    public static final int MAX_ARGUMENT_CHARS = 256;

    /** Role tokens for {@link #OP_ROLE_ADD} and {@link #OP_ROLE_REMOVE}. */
    public static final String ROLE_ALLY = "ally";
    public static final String ROLE_CHIEF = "chief";
    public static final String ROLE_ENEMY = "enemy";

    /** {@link #ROLE_ENEMY}, confirmed: the sender knows the target is a member and means it. */
    public static final String ROLE_ENEMY_CONFIRMED = "enemy!";

    /** Separates the role token from the player name. Not a character a role token can contain. */
    public static final char ROLE_SEPARATOR = '|';

    public static final Type<ColonyIntentPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colony_intent"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyIntentPayload> STREAM_CODEC =
            StreamCodec.of(ColonyIntentPayload::write, ColonyIntentPayload::read);

    public ColonyIntentPayload {
        pos = pos.immutable();
        argument = argument == null ? "" : argument;
        if (argument.length() > MAX_ARGUMENT_CHARS) {
            argument = argument.substring(0, MAX_ARGUMENT_CHARS);
        }
    }

    public static ColonyIntentPayload research(BlockPos pos, Identifier node) {
        return new ColonyIntentPayload(OP_RESEARCH, pos, node.toString());
    }

    public static ColonyIntentPayload accessAdd(BlockPos pos, String playerName) {
        return new ColonyIntentPayload(OP_ACCESS_ADD, pos, playerName);
    }

    public static ColonyIntentPayload accessRemove(BlockPos pos, String playerName) {
        return new ColonyIntentPayload(OP_ACCESS_REMOVE, pos, playerName);
    }

    public static ColonyIntentPayload sell(BlockPos pos) {
        return new ColonyIntentPayload(OP_SELL_EXPORTS, pos, "");
    }

    public static ColonyIntentPayload refresh(BlockPos pos) {
        return new ColonyIntentPayload(OP_REFRESH, pos, "");
    }

    public static ColonyIntentPayload toggleExport(BlockPos pos) {
        return new ColonyIntentPayload(OP_TOGGLE_EXPORT, pos, "");
    }

    /**
     * @param roleToken one of {@link #ROLE_ALLY}, {@link #ROLE_CHIEF}, {@link #ROLE_ENEMY} or
     *                  {@link #ROLE_ENEMY_CONFIRMED}
     */
    public static ColonyIntentPayload roleAdd(BlockPos pos, String roleToken, String playerName) {
        return new ColonyIntentPayload(OP_ROLE_ADD, pos, roleToken + ROLE_SEPARATOR + playerName);
    }

    /** @param roleToken one of {@link #ROLE_ALLY}, {@link #ROLE_CHIEF} or {@link #ROLE_ENEMY} */
    public static ColonyIntentPayload roleRemove(BlockPos pos, String roleToken, String playerName) {
        return new ColonyIntentPayload(OP_ROLE_REMOVE, pos, roleToken + ROLE_SEPARATOR + playerName);
    }

    /** @param label the need's label from the snapshot, or empty to clear the priority */
    public static ColonyIntentPayload prioritiseNeed(BlockPos pos, String label) {
        return new ColonyIntentPayload(OP_PRIORITISE_NEED, pos, label);
    }

    public static ColonyIntentPayload deliver(BlockPos pos) {
        return new ColonyIntentPayload(OP_DELIVER, pos, "");
    }

    public static ColonyIntentPayload cacheShare(BlockPos pos, boolean shared) {
        return new ColonyIntentPayload(OP_CACHE_SHARE, pos, shared ? "1" : "0");
    }

    /** Whether the decoded op is one this jar knows. Never trust an int off the wire. */
    public boolean validOp() {
        return this.op >= 0 && this.op < OP_COUNT;
    }

    private static void write(RegistryFriendlyByteBuf buf, ColonyIntentPayload payload) {
        buf.writeVarInt(payload.op);
        buf.writeBlockPos(payload.pos);
        buf.writeUtf(payload.argument, MAX_ARGUMENT_CHARS);
    }

    private static ColonyIntentPayload read(RegistryFriendlyByteBuf buf) {
        int op = buf.readVarInt();
        BlockPos pos = buf.readBlockPos();
        String argument = buf.readUtf(MAX_ARGUMENT_CHARS);
        return new ColonyIntentPayload(op, pos, argument);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
