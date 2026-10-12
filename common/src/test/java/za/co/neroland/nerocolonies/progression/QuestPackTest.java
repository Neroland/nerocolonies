package za.co.neroland.nerocolonies.progression;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the shipped quest pack ({@code data/nerocolonies/neroquests/**}) and its gate files against
 * the pack rules a quest mod applies at load, without that mod on the classpath: translation keys
 * that exist in {@code en_us.json}, same-namespace prerequisites, server scope only under a chapter
 * that allows it, a {@code custom_event} on a player quest addressed to {@code everyone}, pack-legal
 * reward types, {@code mod_hook} gates of the shape {@code nerocolonies:hook/<id>} that ship as
 * player-scope gate files without {@code requires}, and {@code gate_open} objectives that name a
 * gate file this mod ships. Reads the resources from the test classpath.
 */
class QuestPackTest {

    private static final String NAMESPACE = "nerocolonies";
    private static final Set<String> OBJECTIVES = Set.of("collect_item", "craft_item", "kill_entity",
            "reach_dimension", "visit_structure", "gate_open", "quest_complete", "custom_event", "economy",
            "manual");
    private static final Set<String> REWARDS = Set.of("item", "xp", "currency", "mod_hook");
    private static final String KEY = "[a-z0-9_-]+(\\.[a-z0-9_-]+)+";

    @Test
    @DisplayName("The quest pack follows the pack rules and names only gates this mod ships")
    void packFollowsTheFormat() throws IOException, URISyntaxException {
        Path data = resource("data/" + NAMESPACE + "/neroquests");
        Map<String, JsonObject> quests = read(data.resolve("quests"));
        Map<String, JsonObject> chapters = read(data.resolve("chapters"));
        Map<String, JsonObject> gates = read(resource("data/" + NAMESPACE + "/neroland_gates"));
        JsonObject lang = JsonParser.parseString(Files.readString(
                resource("assets/" + NAMESPACE + "/lang/en_us.json"))).getAsJsonObject();
        assertTrue(quests.size() >= 22, "expected the full chapter, found " + quests.size());

        List<String> problems = new ArrayList<>();
        Set<String> serverOk = new HashSet<>();
        Set<String> listed = new HashSet<>();
        chapters.forEach((id, c) -> {
            key(problems, lang, id, c, "title", true);
            for (JsonElement e : c.getAsJsonArray("quests")) {
                String q = e.getAsJsonObject().get("quest").getAsString();
                if (!quests.containsKey(q)) {
                    problems.add(id + " lists unknown/foreign quest " + q);
                }
                if (!listed.add(q)) {
                    problems.add(id + " lists " + q + " twice");
                }
                if (c.has("allow_server_scope") && c.get("allow_server_scope").getAsBoolean()) {
                    serverOk.add(q);
                }
            }
        });
        quests.forEach((id, q) -> {
            if (!listed.contains(id)) {
                problems.add(id + ": not placed in any chapter");
            }
            key(problems, lang, id, q, "title", true);
            key(problems, lang, id, q, "description", false);
            boolean server = q.has("scope") && "server".equals(q.get("scope").getAsString());
            if (server && !serverOk.contains(id)) {
                problems.add(id + ": scope server needs allow_server_scope");
            }
            if (q.has("prerequisites")) {
                q.getAsJsonArray("prerequisites").forEach(p -> ref(problems, quests, id, p.getAsString()));
            }
            JsonArray objectives = q.getAsJsonArray("objectives");
            if (objectives == null || objectives.isEmpty()) {
                problems.add(id + ": no objectives");
            } else {
                for (JsonElement e : objectives) {
                    JsonObject o = e.getAsJsonObject();
                    String type = o.get("type").getAsString().replace("neroquests:", "");
                    if (!OBJECTIVES.contains(type)) {
                        problems.add(id + ": unknown objective " + type);
                    }
                    if (type.equals("quest_complete")) {
                        ref(problems, quests, id, o.get("quest").getAsString());
                    }
                    if (type.equals("custom_event")) {
                        if (!o.get("channel").getAsString().startsWith(NAMESPACE + ":")) {
                            problems.add(id + ": custom_event channel is not this mod's");
                        }
                        if (!server && !(o.has("audience") && "everyone".equals(o.get("audience").getAsString()))) {
                            problems.add(id + ": custom_event on a player quest needs \"audience\": \"everyone\"");
                        }
                    }
                    if (type.equals("gate_open")) {
                        gate(problems, gates, id, o.get("gate").getAsString(), false);
                    }
                }
            }
            if (q.has("rewards")) {
                for (JsonElement e : q.getAsJsonArray("rewards")) {
                    JsonObject r = e.getAsJsonObject();
                    String type = r.get("type").getAsString().replace("neroquests:", "");
                    if (!REWARDS.contains(type)) {
                        problems.add(id + ": reward " + type + " not allowed in a pack");
                    }
                    if (type.equals("mod_hook")) {
                        String g = r.get("gate").getAsString();
                        if (!g.startsWith(NAMESPACE + ":hook/")) {
                            problems.add(id + ": mod_hook gate must be " + NAMESPACE + ":hook/<id>");
                        }
                        gate(problems, gates, id, g, true);
                    }
                }
            }
            if (q.has("visible_gate")) {
                gate(problems, gates, id, q.get("visible_gate").getAsString(), false);
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** A gate the pack names must ship as a file; a hook gate must be player-scope with no requires. */
    private static void gate(List<String> problems, Map<String, JsonObject> gates, String id, String gate,
            boolean hook) {
        JsonObject def = gates.get(gate);
        if (def == null) {
            problems.add(id + ": names gate " + gate + " which this mod does not ship");
            return;
        }
        if (!"player".equals(def.has("scope") ? def.get("scope").getAsString() : "server")) {
            problems.add(id + ": gate " + gate + " is not player-scope");
        }
        if (hook && def.has("requires") && !def.getAsJsonArray("requires").isEmpty()) {
            problems.add(id + ": hook gate " + gate + " must not have requires");
        }
    }

    private static void ref(List<String> problems, Map<String, JsonObject> quests, String id, String target) {
        if (target.startsWith("neroquests:")) {
            return;
        }
        if (!target.startsWith(NAMESPACE + ":")) {
            problems.add(id + ": depends on another pack's quest " + target);
        } else if (!quests.containsKey(target)) {
            problems.add(id + ": unknown quest " + target);
        }
    }

    private static void key(List<String> problems, JsonObject lang, String id, JsonObject o, String field,
            boolean required) {
        if (!o.has(field)) {
            if (required) {
                problems.add(id + ": missing " + field);
            }
            return;
        }
        String key = o.get(field).getAsString();
        if (!key.matches(KEY)) {
            problems.add(id + ": " + field + " is not a translation key");
        } else if (!lang.has(key)) {
            problems.add(id + ": en_us.json has no " + key);
        }
    }

    /** Ids are the file path relative to {@code dir}, as the quest mod derives them. */
    private static Map<String, JsonObject> read(Path dir) throws IOException {
        Map<String, JsonObject> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                out.put(NAMESPACE + ":" + rel.substring(0, rel.length() - 5),
                        JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            }
        }
        return out;
    }

    private static Path resource(String name) throws URISyntaxException {
        URL url = QuestPackTest.class.getClassLoader().getResource(name);
        assertTrue(url != null, "resource missing from the test classpath: " + name);
        return Path.of(url.toURI());
    }
}
