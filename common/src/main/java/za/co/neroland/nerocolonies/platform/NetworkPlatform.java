package za.co.neroland.nerocolonies.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Cross-loader packet-send seam (counterpart to NeoForge {@code PacketDistributor} / Forge
 * {@code Channel} / Fabric {@code Server|ClientPlayNetworking.send}). Payload <em>types</em> and
 * their handlers are declared once in {@link za.co.neroland.nerocolonies.network.ColonyNetwork};
 * each loader module registers them on its own channel and implements this send interface,
 * resolved via {@link Services#NETWORK}.
 *
 * <h2>Why NeroColonies ships its own seam rather than reusing Neroland Core's</h2>
 *
 * <p>Core's {@code za.co.neroland.nerolandcore.platform.NetworkPlatform} is public and stable, and
 * its {@code sendToPlayer} would happily carry a NeroColonies payload. Payload <em>registration</em>,
 * however, is Core-internal: {@code CoreNetwork}'s payload lists are drained synchronously during
 * Core's own bootstrap — on Forge the channel is {@code build()}-sealed inside Core's constructor,
 * and on Fabric the types are registered from Core's {@code onInitialize}. Anything a downstream mod
 * adds afterwards is silently dropped. On NeoForge registration is deferred to an event, but it
 * would land on Core's registrar and protocol version, coupling our wire format to Core's.
 *
 * <p>NeroColonies therefore replicates Core's architecture 1:1 on its own channel
 * ({@code nerocolonies:main}): the same declare-once/register-per-loader split, the same handler
 * shapes, the same client/common registration split on Fabric.
 */
public interface NetworkPlatform {

    /** Server &rarr; one client. */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Client &rarr; server (call only on the physical client). */
    void sendToServer(CustomPacketPayload payload);
}
