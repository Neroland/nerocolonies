package za.co.neroland.nerocolonies.network;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ExportEntry;
import za.co.neroland.nerocolonies.content.ResearchNode;

/**
 * The datapack content a NeroColonies client needs in order to <em>draw</em> anything: the research
 * graph and the export manifest.
 *
 * <h2>Why JSON on the wire</h2>
 *
 * <p>Each definition is shipped as the same JSON its datapack file contains, encoded with the very
 * codec that read it. There is then exactly one serialisation format for each content type rather
 * than a codec plus a hand-written stream codec that has to be kept in step with it — and a datapack
 * that adds a field cannot desynchronise the two, because there is only one. This is the NeroQuests
 * {@code QuestDefinitionsPayload} pattern.
 *
 * <p>The volume is small (tens of nodes) and it is sent once per client per content generation, so
 * the cost of the format is irrelevant next to the cost of getting it wrong.
 *
 * <h2>Generation</h2>
 *
 * <p>{@link #generation()} is {@link ColonyDefinitions#generation()} at the moment of encoding. The
 * client stores it beside the cache; the server compares it before re-sending, so opening a GUI
 * twice costs one packet, not two, and {@code /reload} is picked up without a reload listener.
 *
 * <p><b>Privacy:</b> content definitions are not player-scoped. Nothing here is, or could be,
 * personal data.
 */
public record ColonyDefinitionsPayload(int generation, List<String> research, List<String> exports)
        implements CustomPacketPayload {

    public static final ColonyDefinitionsPayload EMPTY =
            new ColonyDefinitionsPayload(-1, List.of(), List.of());

    public static final Type<ColonyDefinitionsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colony_definitions"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyDefinitionsPayload> STREAM_CODEC =
            StreamCodec.of(ColonyDefinitionsPayload::write, ColonyDefinitionsPayload::read);

    /** Upper bounds, so a malformed packet cannot make a client pre-allocate without limit. */
    private static final int MAX_ENTRIES = 4_096;
    private static final int MAX_BODY_CHARS = 262_144;

    public ColonyDefinitionsPayload {
        research = List.copyOf(research);
        exports = List.copyOf(exports);
    }

    /** Encodes this server's current content. Call through {@code ColonySync}, which caches it. */
    public static ColonyDefinitionsPayload of(MinecraftServer server) {
        return new ColonyDefinitionsPayload(
                ColonyDefinitions.generation(),
                encode(ColonyDefinitions.researchForServer(server).values(), ResearchNode.CODEC,
                        "research node"),
                encode(ColonyDefinitions.exportsForServer(server).values(), ExportEntry.CODEC,
                        "export entry"));
    }

    private static <T> List<String> encode(Iterable<T> values, Codec<T> codec, String kind) {
        List<String> out = new ArrayList<>();
        for (T value : values) {
            codec.encodeStart(JsonOps.INSTANCE, value)
                    .resultOrPartial(error -> NeroColoniesCommon.LOGGER.warn(
                            "[NeroColonies] Could not encode a {} for the client: {}", kind, error))
                    .map(JsonElement::toString)
                    .ifPresent(out::add);
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return out;
    }

    private static void write(RegistryFriendlyByteBuf buf, ColonyDefinitionsPayload payload) {
        buf.writeVarInt(payload.generation);
        writeAll(buf, payload.research);
        writeAll(buf, payload.exports);
    }

    private static ColonyDefinitionsPayload read(RegistryFriendlyByteBuf buf) {
        int generation = buf.readVarInt();
        return new ColonyDefinitionsPayload(generation, readAll(buf), readAll(buf));
    }

    private static void writeAll(RegistryFriendlyByteBuf buf, List<String> bodies) {
        buf.writeVarInt(Math.min(bodies.size(), MAX_ENTRIES));
        int written = 0;
        for (String body : bodies) {
            if (written++ >= MAX_ENTRIES) {
                break;
            }
            buf.writeUtf(body, MAX_BODY_CHARS);
        }
    }

    private static List<String> readAll(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<String> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            out.add(buf.readUtf(MAX_BODY_CHARS));
        }
        return List.copyOf(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
