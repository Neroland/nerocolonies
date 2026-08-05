package za.co.neroland.nerocolonies.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.content.ExportEntry;
import za.co.neroland.nerocolonies.content.ResearchNode;
import za.co.neroland.nerocolonies.network.ColonyDefinitionsPayload;

/**
 * The client's mirror of the server's colony content: the research graph and the export manifest.
 *
 * <h2>A mirror, never a source</h2>
 *
 * <p>Nothing here decides anything. The research screen draws this and sends an intent; the server
 * re-derives everything from its own copy before acting. A client whose mirror is stale or has been
 * tampered with can therefore draw a wrong screen and nothing worse.
 *
 * <p>The whole state is one immutable {@code Snapshot} in one {@code volatile} field, replaced
 * wholesale rather than mutated. Network handlers hop to the client thread before calling
 * {@link #accept}, and a reader always sees one internally consistent snapshot, so no locking is
 * needed anywhere.
 *
 * <p><b>No {@code net.minecraft.client} imports.</b> This class is referenced from
 * {@code ColonyNetwork}, which is common code that loads on a dedicated server; keeping it free of
 * client types is what makes that safe.
 */
public final class ClientColonyDefinitions {

    /** One coherent view of the content. Replaced as a unit; never edited in place. */
    private record Snapshot(int generation, Map<Identifier, ResearchNode> research,
            List<ResearchNode> researchOrder, Map<Identifier, ExportEntry> exports) {

        static final Snapshot EMPTY = new Snapshot(-1, Map.of(), List.of(), Map.of());
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private ClientColonyDefinitions() {
    }

    /** Replaces the mirror with the server's content. Called on the client thread. */
    public static void accept(ColonyDefinitionsPayload payload) {
        Map<Identifier, ResearchNode> research = new LinkedHashMap<>();
        List<ResearchNode> order = new ArrayList<>();
        for (ResearchNode node : decode(payload.research(), ResearchNode.CODEC, "research node")) {
            research.put(node.id(), node);
            order.add(node);
        }
        Map<Identifier, ExportEntry> exports = new LinkedHashMap<>();
        for (ExportEntry entry : decode(payload.exports(), ExportEntry.CODEC, "export entry")) {
            exports.put(entry.id(), entry);
        }
        snapshot = new Snapshot(payload.generation(),
                Collections.unmodifiableMap(research), List.copyOf(order),
                Collections.unmodifiableMap(exports));
    }

    /** Drops everything. Called when the client leaves a world or server. */
    public static void clear() {
        snapshot = Snapshot.EMPTY;
    }

    /** The content generation this mirror holds, or {@code -1} before anything has arrived. */
    public static int generation() {
        return snapshot.generation();
    }

    /** Whether any content has been received yet. */
    public static boolean isEmpty() {
        return snapshot.researchOrder().isEmpty();
    }

    /** Every research node, in the order the server sent them (which is dependency order). */
    public static List<ResearchNode> research() {
        return snapshot.researchOrder();
    }

    /** One research node by id, or {@code null}. */
    public static ResearchNode research(Identifier id) {
        return snapshot.research().get(id);
    }

    /** Every export entry, keyed by id. */
    public static Map<Identifier, ExportEntry> exports() {
        return snapshot.exports();
    }

    /**
     * Decodes the JSON bodies with the same codec that read them from the datapack. A body that will
     * not decode is logged and skipped rather than failing the packet — one bad definition must not
     * cost a client the other forty.
     */
    private static <T> List<T> decode(List<String> bodies, Codec<T> codec, String kind) {
        List<T> out = new ArrayList<>(bodies.size());
        for (String body : bodies) {
            try {
                codec.parse(JsonOps.INSTANCE, JsonParser.parseString(body))
                        .resultOrPartial(error -> NeroColoniesCommon.LOGGER.warn(
                                "[NeroColonies] Could not decode a synced {}: {}", kind, error))
                        .ifPresent(out::add);
            } catch (RuntimeException e) {
                NeroColoniesCommon.LOGGER.warn("[NeroColonies] Could not read a synced {}.", kind, e);
            }
        }
        return out;
    }
}
