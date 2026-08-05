package za.co.neroland.nerocolonies.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * The one place the link surfaces decide <em>what a client may see</em>, plus the two questions they
 * have to answer before they may say anything: which server is running, and is this player online.
 *
 * <p>Core's snapshot/action API hands over a {@link UUID} and nothing else — no server, no player —
 * so the running server comes from {@code lifecycle/ServerStateReset}, which each loader's
 * server-started hook fills in. Before the first world is loaded there is no server and every section
 * answers empty, which is the honest result rather than a guess.
 *
 * <h2>The visibility rule, in exactly one place</h2>
 *
 * <p>{@link #coloniesOf} is the whole of it: a request sees the colonies its own UUID owns or is on
 * the access list of, in the store's own order, and nothing else. Snapshots, actions and events all
 * route through it, so there is one line to read to know what a companion client can reach — and one
 * line to change if that ever needs to be narrower.
 *
 * <p>Note what it does <b>not</b> do: it never widens for an operator. An operator's powers are a
 * property of a live command source, not of a UUID arriving over a bridge, and a link module that
 * honoured them would turn "I am an admin" into "my phone can read every base on the server".
 *
 * <p>Server thread only. Nothing here reads or stores player data beyond the UUID it is handed.
 */
final class ColonyLinkAccess {

    private ColonyLinkAccess() {
    }

    /** The running server, or {@code null} before the first world load / after shutdown. */
    @Nullable
    static MinecraftServer server() {
        return ServerStateReset.currentServer();
    }

    /** The online player with this UUID, or {@code null} if they are not connected. */
    @Nullable
    static ServerPlayer online(MinecraftServer server, UUID playerId) {
        return server.getPlayerList().getPlayer(playerId);
    }

    /** Whether this player is online right now. */
    static boolean isOnline(MinecraftServer server, UUID playerId) {
        return online(server, playerId) != null;
    }

    /**
     * The colonies this player may see: the ones they own, plus the ones they are on the access list
     * of. Never anybody else's, and never widened for permission level — see the class notes.
     */
    static List<Colony> coloniesOf(MinecraftServer server, UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        ColonyState state = ColonyState.get(server);
        List<Colony> out = new ArrayList<>();
        for (UUID id : state.memberOf(playerId)) {
            Colony colony = state.colony(id);
            if (colony != null) {
                out.add(colony);
            }
        }
        return out;
    }

    /**
     * The same list, optionally narrowed to the one colony a {@code colony} parameter names. An
     * unparseable or unknown id narrows to <em>nothing</em> rather than falling back to everything: a
     * typo must not quietly return more than the caller asked for.
     */
    static List<Colony> requested(MinecraftServer server, UUID playerId,
            @Nullable Map<String, String> params) {
        List<Colony> visible = coloniesOf(server, playerId);
        String wanted = params == null ? null : params.get("colony");
        if (wanted == null || wanted.isBlank()) {
            return visible;
        }
        String trimmed = wanted.trim();
        for (Colony colony : visible) {
            if (colony.colonyId().toString().equalsIgnoreCase(trimmed)) {
                return List.of(colony);
            }
        }
        return List.of();
    }

    /**
     * One colony this player may act on, named by an action's {@code colony} parameter, or
     * {@code null}. "Not yours" and "does not exist" are the same answer, so an action cannot be used
     * to probe for other people's colonies.
     */
    @Nullable
    static Colony colonyParam(MinecraftServer server, UUID playerId, @Nullable JsonObject params) {
        String raw = string(params, "colony");
        if (raw == null) {
            return null;
        }
        for (Colony colony : coloniesOf(server, playerId)) {
            if (colony.colonyId().toString().equalsIgnoreCase(raw)) {
                return colony;
            }
        }
        return null;
    }

    /** One string parameter, trimmed, or {@code null} if it is absent, null or blank. */
    @Nullable
    static String string(@Nullable JsonObject params, String key) {
        if (params == null || !params.has(key)) {
            return null;
        }
        JsonElement element = params.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String raw = element.getAsString();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** One boolean parameter, or {@code null} if it is absent or not a boolean. */
    @Nullable
    static Boolean bool(@Nullable JsonObject params, String key) {
        if (params == null || !params.has(key)) {
            return null;
        }
        JsonElement element = params.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return element.getAsBoolean();
    }

    /**
     * A printable English name derived from a registry path — never from a lang file, which a
     * dedicated server does not have for a mod's assets. {@code refinery_station} &rarr;
     * {@code Refinery Station}.
     */
    static String readablePath(String path) {
        StringBuilder out = new StringBuilder(path.length());
        boolean capitalise = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                out.append(' ');
                capitalise = true;
            } else if (capitalise) {
                out.append(Character.toUpperCase(c));
                capitalise = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString().trim().isEmpty() ? path.toLowerCase(Locale.ROOT) : out.toString();
    }
}
