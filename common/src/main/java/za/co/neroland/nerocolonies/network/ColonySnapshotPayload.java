package za.co.neroland.nerocolonies.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerolandcore.economy.CurrencyApi;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.colony.ExportBuffer;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.colony.Research;
import za.co.neroland.nerocolonies.colony.ResearchEffects;

/**
 * One player's view of the one colony they currently have open.
 *
 * <h2>What is in it, and what is deliberately not</h2>
 *
 * <p>Colony <b>state</b>: name, morale, population, the research it has unlocked, its job slots, how
 * full its stores are, what its export buffer is worth. That is what a GUI legitimately draws.
 *
 * <p><b>No owner UUID. No access list. No player names.</b> The access-list editor sends a name to
 * the server and is told a count back; it is never sent the membership, because a client that has
 * been told who is on a colony's access list has been told where those people play. {@link #isOwner}
 * is a boolean about the <em>recipient</em>, which is the only membership question a client needs
 * answered and the only one this mod will answer. The whole payload is built for one player and sent
 * to that player.
 *
 * <p>{@link #affordable()} is the same principle applied to inventory: the server decides which
 * research nodes the colony can pay for and sends a list of ids, so the screen can grey out what is
 * unaffordable without ever being sent the colony's stock.
 */
public record ColonySnapshotPayload(
        boolean present,
        BlockPos anchor,
        String colonyId,
        String name,
        int morale,
        int population,
        int housingCapacity,
        int foodStock,
        int lifeSupportState,
        int claimRadius,
        int jobSlots,
        int jobsActive,
        int jobStations,
        int storageUsed,
        int storageSlots,
        int exportFilled,
        int exportSlots,
        long exportValue,
        int outpostCount,
        int accessCount,
        boolean isOwner,
        boolean marketAvailable,
        String buildName,
        int buildPercent,
        int structuresBuilt,
        List<String> researchUnlocked,
        List<String> affordable) implements CustomPacketPayload {

    /** "You have no colony open" — sent when a GUI is opened on an unbound or dissolved beacon. */
    public static final ColonySnapshotPayload EMPTY = new ColonySnapshotPayload(
            false, BlockPos.ZERO, "", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0, 0, false, false,
            "", 0, 0, List.of(), List.of());

    public static final Type<ColonySnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colony_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonySnapshotPayload> STREAM_CODEC =
            StreamCodec.of(ColonySnapshotPayload::write, ColonySnapshotPayload::read);

    private static final int MAX_IDS = 2_048;
    private static final int MAX_ID_CHARS = 256;
    private static final int MAX_NAME_CHARS = 64;

    public ColonySnapshotPayload {
        anchor = anchor.immutable();
        researchUnlocked = List.copyOf(researchUnlocked);
        affordable = List.copyOf(affordable);
    }

    /**
     * Builds {@code viewer}'s view of {@code colony}. Server-side only.
     *
     * <p>{@code anchor} is the block the player opened, and it is in the payload for one reason: a
     * screen has to tell the server <em>which</em> block its intents are about, and a menu's data
     * slots are 16-bit — a block position does not fit through one. Carrying it here is both exact
     * and free.
     */
    public static ColonySnapshotPayload of(MinecraftServer server, ServerPlayer viewer, Colony colony,
            BlockPos anchor) {
        List<String> unlocked = new ArrayList<>(colony.researchUnlocked());
        List<String> affordable = new ArrayList<>();
        for (Identifier id : Research.affordable(server, colony)) {
            affordable.add(id.toString());
        }
        int storageSlots = ColonyStorage.usableSlots(viewer.level(), colony);
        int storageUsed = 0;
        for (int slot = 0; slot < storageSlots; slot++) {
            if (!ColonyStores.get(server).store(colony.colonyId()).storage().get(slot).isEmpty()) {
                storageUsed++;
            }
        }
        return new ColonySnapshotPayload(
                true,
                anchor,
                colony.colonyId().toString(),
                colony.name(),
                (int) Math.round(colony.morale()),
                colony.population(),
                colony.housingCapacity(),
                colony.foodStock(),
                LifeSupport.stateOf(colony).ordinal(),
                colony.claimRadius(),
                ResearchEffects.jobSlots(colony),
                JobBoard.activeCount(colony.colonyId()),
                JobBoard.stationCount(colony.colonyId()),
                storageUsed,
                storageSlots,
                ExportBuffer.filledSlots(server, colony.colonyId()),
                ExportBuffer.usableSlots(),
                ExportBuffer.previewValue(server, colony),
                colony.outpostIds().size(),
                colony.accessList().size(),
                colony.isOwner(viewer.getUUID()),
                CurrencyApi.hasRealProvider(),
                // A translation key, not a rendered name: what the client shows is the client's
                // business, and it keeps the payload the same size in every language.
                Construction.activeNameKey(server, colony.colonyId()),
                Construction.progressPercent(server, colony.colonyId()),
                Construction.structuresBuilt(server, colony.colonyId()),
                unlocked,
                affordable);
    }

    private static void write(RegistryFriendlyByteBuf buf, ColonySnapshotPayload payload) {
        buf.writeBoolean(payload.present);
        if (!payload.present) {
            return;
        }
        buf.writeBlockPos(payload.anchor);
        buf.writeUtf(payload.colonyId, MAX_ID_CHARS);
        buf.writeUtf(payload.name, MAX_NAME_CHARS);
        buf.writeVarInt(payload.morale);
        buf.writeVarInt(payload.population);
        buf.writeVarInt(payload.housingCapacity);
        buf.writeVarInt(payload.foodStock);
        buf.writeVarInt(payload.lifeSupportState);
        buf.writeVarInt(payload.claimRadius);
        buf.writeVarInt(payload.jobSlots);
        buf.writeVarInt(payload.jobsActive);
        buf.writeVarInt(payload.jobStations);
        buf.writeVarInt(payload.storageUsed);
        buf.writeVarInt(payload.storageSlots);
        buf.writeVarInt(payload.exportFilled);
        buf.writeVarInt(payload.exportSlots);
        buf.writeVarLong(payload.exportValue);
        buf.writeVarInt(payload.outpostCount);
        buf.writeVarInt(payload.accessCount);
        buf.writeBoolean(payload.isOwner);
        buf.writeBoolean(payload.marketAvailable);
        buf.writeUtf(payload.buildName, MAX_ID_CHARS);
        buf.writeVarInt(payload.buildPercent);
        buf.writeVarInt(payload.structuresBuilt);
        writeIds(buf, payload.researchUnlocked);
        writeIds(buf, payload.affordable);
    }

    private static ColonySnapshotPayload read(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return EMPTY;
        }
        return new ColonySnapshotPayload(
                true,
                buf.readBlockPos(),
                buf.readUtf(MAX_ID_CHARS),
                buf.readUtf(MAX_NAME_CHARS),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(MAX_ID_CHARS),
                buf.readVarInt(),
                buf.readVarInt(),
                readIds(buf),
                readIds(buf));
    }

    private static void writeIds(RegistryFriendlyByteBuf buf, List<String> ids) {
        int count = Math.min(ids.size(), MAX_IDS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(ids.get(i), MAX_ID_CHARS);
        }
    }

    private static List<String> readIds(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_IDS);
        List<String> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            out.add(buf.readUtf(MAX_ID_CHARS));
        }
        return List.copyOf(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
