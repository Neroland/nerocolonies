package za.co.neroland.nerocolonies.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerocolonies.client.ClientColonyDefinitions;
import za.co.neroland.nerocolonies.client.ClientColonySnapshot;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * The cross-loader payload registry: NeroColonies declares its payloads here once (type + stream
 * codec + handler), and each loader module iterates the lists and wires them to its own networking
 * API — NeoForge's {@code PayloadRegistrar}, Forge's {@code ChannelBuilder}, Fabric's
 * {@code PayloadTypeRegistry} + {@code Client/ServerPlayNetworking}. Sending goes through the
 * {@link Services#NETWORK} seam. The channel is {@code nerocolonies:main}.
 *
 * <p>This is Neroland Core's {@code CoreNetwork} architecture reproduced on NeroColonies' own
 * channel. It cannot reuse Core's instance: Core drains its payload lists during Core's own
 * bootstrap (on Forge the channel is {@code build()}-sealed inside Core's constructor), so a
 * downstream registration would be silently dropped — see
 * {@link za.co.neroland.nerocolonies.platform.NetworkPlatform} for the full reasoning.
 *
 * <h2>What crosses the wire</h2>
 *
 * <ul>
 *   <li><b>Server → client:</b> {@link ColonyDefinitionsPayload} (the research graph and export
 *       manifest, one per content generation) and {@link ColonySnapshotPayload} (the viewing player's
 *       open colony). Both are sent when a player opens a colony interface, never on a timer.</li>
 *   <li><b>Client → server:</b> {@link ColonyIntentPayload} — one payload for every action a screen
 *       can request, none of it trusted (see {@link ColonyIntents}).</li>
 * </ul>
 *
 * <p>NeroColonies remains server-authoritative end to end: claims, population, life support, morale,
 * production and research are all decided server-side and the client renders what it is told.
 */
public final class ColonyNetwork {

    /** A server &rarr; client payload plus the client-side handler that consumes it. */
    public record Clientbound<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
    }

    /** A client &rarr; server payload plus the server-side handler (with the sending player). */
    public record Serverbound<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
    }

    private static final List<Clientbound<?>> CLIENTBOUND = new ArrayList<>();
    private static final List<Serverbound<?>> SERVERBOUND = new ArrayList<>();

    private static boolean declared;

    private ColonyNetwork() {
    }

    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        CLIENTBOUND.add(new Clientbound<>(type, codec, handler));
    }

    public static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        SERVERBOUND.add(new Serverbound<>(type, codec, handler));
    }

    /** Every declared server &rarr; client payload, for each loader's registration pass. */
    public static List<Clientbound<?>> clientbound() {
        return CLIENTBOUND;
    }

    /** Every declared client &rarr; server payload, for each loader's registration pass. */
    public static List<Serverbound<?>> serverbound() {
        return SERVERBOUND;
    }

    /**
     * Declares the payloads. Called once from common init, before any loader registers them (each
     * loader entry point runs common init first, then its own network registration). On Forge in
     * particular the channel is sealed at {@code build()}, so a payload declared later would never
     * exist.
     */
    public static void init() {
        if (declared) {
            return; // defensive: a second call must not duplicate registrations
        }
        declared = true;
        clientbound(ColonyDefinitionsPayload.TYPE, ColonyDefinitionsPayload.STREAM_CODEC,
                ClientColonyDefinitions::accept);
        clientbound(ColonySnapshotPayload.TYPE, ColonySnapshotPayload.STREAM_CODEC,
                ClientColonySnapshot::accept);
        serverbound(ColonyIntentPayload.TYPE, ColonyIntentPayload.STREAM_CODEC,
                ColonyIntents::handle);
    }

    /**
     * Drops every client-side mirror. Each loader calls this when the client leaves a world or
     * server, so one session's colony state can never bleed into the next — or appear at all on a
     * server that does not run NeroColonies.
     */
    public static void clearClientCaches() {
        ClientColonyDefinitions.clear();
        ClientColonySnapshot.clear();
    }
}
