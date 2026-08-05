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
 * <p>Four separate serverbound payloads would mean four registrations in each of three loader
 * modules, four stream codecs and four handlers — for four operations that all take "a block, and at
 * most one string". Core's own {@code SideConfigIntentPayload} makes the same call for the same
 * reason, and this follows it: an int op, the anchor block, and one argument.
 *
 * <h2>It is an intent, not a command</h2>
 *
 * <p>Nothing here is trusted. The handler re-derives the colony from the block, re-checks reach,
 * re-checks permission and re-checks affordability from the server's own state; the op code is
 * bounded before it is switched on. A client that sends {@code OP_SELL_EXPORTS} for a beacon in
 * another dimension gets a refused packet and a fresh snapshot, not a sale.
 *
 * <p><b>Privacy:</b> the one free-text field is a player <em>name</em>, and it exists solely so the
 * owner of a colony can type who to grant access to. It travels client → server only; nothing sends
 * a name back the other way, and the access list is never transmitted at all.
 */
public record ColonyIntentPayload(int op, BlockPos pos, String argument) implements CustomPacketPayload {

    /** Unlock a research node. {@link #argument} is the node id. */
    public static final int OP_RESEARCH = 0;

    /** Grant colony access to a player by name. Owner (or operator) only. */
    public static final int OP_ACCESS_ADD = 1;

    /** Revoke colony access from a player by name. Owner (or operator) only. */
    public static final int OP_ACCESS_REMOVE = 2;

    /** Sell the colony's export buffer. */
    public static final int OP_SELL_EXPORTS = 3;

    /** Ask for a fresh snapshot and nothing else. Always safe, always allowed for a member. */
    public static final int OP_REFRESH = 4;

    /** Flip a job station between "output to colony storage" and "output to the export buffer". */
    public static final int OP_TOGGLE_EXPORT = 5;

    private static final int OP_COUNT = 6;

    /** A generous cap on the one free-text field — a player name, never a sentence. */
    public static final int MAX_ARGUMENT_CHARS = 256;

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
