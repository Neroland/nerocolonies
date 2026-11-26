package za.co.neroland.nerocolonies.forge;

//? if >=1.20.5 {
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.payload.PayloadFlow;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.network.ColonyNetwork;
import za.co.neroland.nerocolonies.platform.NetworkPlatform;

/**
 * Forge side of the cross-loader packet seam. Registered as the {@link NetworkPlatform}
 * implementation via {@code META-INF/services}.
 *
 * <p>Forge seals a channel at {@code build()}, so every payload must be declared before then —
 * which is exactly why NeroColonies owns this channel rather than adding to Neroland Core's (Core
 * builds its own inside its constructor, long before NeroColonies is constructed). This is also why
 * {@link #register()} must run <b>after</b> {@code NeroColoniesCommon.init()}, which is where the
 * payloads are declared. {@code optional()} keeps a NeroColonies-less client connectable.
 */
public final class ForgeColonyNetwork implements NetworkPlatform {

    private static Channel<CustomPacketPayload> channel;

    public static void register() {
        PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> play =
                ChannelBuilder.named(Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "main"))
                        .optional()
                        .payloadChannel()
                        .play()
                        .bidirectional();
        for (ColonyNetwork.Clientbound<?> cb : ColonyNetwork.clientbound()) {
            registerClientbound(play, cb);
        }
        for (ColonyNetwork.Serverbound<?> sb : ColonyNetwork.serverbound()) {
            registerServerbound(play, sb);
        }
        channel = play.build();
    }

    private static <T extends CustomPacketPayload> void registerClientbound(
            PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> play,
            ColonyNetwork.Clientbound<T> cb) {
        play.addMain(cb.type(), registryCodec(cb.codec()),
                (payload, context) -> cb.handler().accept(payload));
    }

    /** Both directions share the one bidirectional flow; the sender is what distinguishes them. */
    private static <T extends CustomPacketPayload> void registerServerbound(
            PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> play,
            ColonyNetwork.Serverbound<T> sb) {
        play.addMain(sb.type(), registryCodec(sb.codec()), (payload, context) -> {
            if (context.getSender() instanceof ServerPlayer serverPlayer) {
                sb.handler().accept(payload, serverPlayer);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> registryCodec(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return (StreamCodec<RegistryFriendlyByteBuf, T>) codec;
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (channel != null) {
            channel.send(payload, PacketDistributor.PLAYER.with(player));
        }
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        if (channel != null) {
            channel.send(payload, PacketDistributor.SERVER.noArg());
        }
    }
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
//? if >=1.20.2 {
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;
//?} else {
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
//?}

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.network.ColonyNetwork;
import za.co.neroland.nerolandcore.network.legacy.LegacyPayload;
import za.co.neroland.nerocolonies.platform.NetworkPlatform;

public final class ForgeColonyNetwork implements NetworkPlatform {
    private static final int PROTOCOL_VERSION = 1;
    private static SimpleChannel channel;

    public static void register() {
        //? if >=1.20.2 {
        channel = ChannelBuilder.named(new ResourceLocation(NeroColoniesCommon.MOD_ID, "main"))
                .networkProtocolVersion(PROTOCOL_VERSION).optional().simpleChannel();
        //?} else {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(NeroColoniesCommon.MOD_ID, "main"),
                () -> Integer.toString(PROTOCOL_VERSION), ForgeColonyNetwork::acceptVersion, ForgeColonyNetwork::acceptVersion);
        //?}
        int id = 0;
        for (ColonyNetwork.Clientbound<?> packet : ColonyNetwork.clientbound()) {
            registerClientbound(packet, id++);
        }
        for (ColonyNetwork.Serverbound<?> packet : ColonyNetwork.serverbound()) {
            registerServerbound(packet, id++);
        }
    }

    //? if <1.20.2 {
    private static boolean acceptVersion(String version) {
        return Integer.toString(PROTOCOL_VERSION).equals(version) || NetworkRegistry.ABSENT.version().equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }
    //?}

    private static <T extends LegacyPayload> void registerClientbound(ColonyNetwork.Clientbound<T> packet, int id) {
        channel.messageBuilder(packet.type().payloadClass(), id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((payload, buffer) -> packet.codec().encode(buffer, payload))
                .decoder(packet.codec()::decode)
                .consumerMainThread((payload, context) -> packet.handler().accept(payload))
                .add();
    }

    private static <T extends LegacyPayload> void registerServerbound(ColonyNetwork.Serverbound<T> packet, int id) {
        channel.messageBuilder(packet.type().payloadClass(), id, NetworkDirection.PLAY_TO_SERVER)
                .encoder((payload, buffer) -> packet.codec().encode(buffer, payload))
                .decoder(packet.codec()::decode)
                .consumerMainThread((payload, context) -> {
                    //? if >=1.20.2 {
                    ServerPlayer player = context.getSender();
                    //?} else {
                    ServerPlayer player = context.get().getSender();
                    //?}
                    if (player != null) {
                        packet.handler().accept(payload, player);
                    }
                })
                .add();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, LegacyPayload payload) {
        //? if >=1.20.2 {
        var connection = player.connection.getConnection();
        //?} else {
        var connection = player.connection.connection;
        //?}
        if (channel != null && channel.isRemotePresent(connection)) {
            //? if >=1.20.2 {
            channel.send(payload, connection);
            //?} else {
            channel.send(PacketDistributor.PLAYER.with(() -> player), payload);
            //?}
        }
    }

    @Override
    public void sendToServer(LegacyPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (channel != null && connection != null && channel.isRemotePresent(connection.getConnection())) {
            //? if >=1.20.2 {
            channel.send(payload, connection.getConnection());
            //?} else {
            channel.sendToServer(payload);
            //?}
        }
    }
}
*///?}
