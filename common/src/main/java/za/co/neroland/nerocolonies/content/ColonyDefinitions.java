package za.co.neroland.nerocolonies.content;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.content.effect.ResearchEffect;
import za.co.neroland.nerocolonies.content.effect.ResearchEffectTypes;

/**
 * The active set of colony content — jobs, research nodes, housing tiers and export entries — loaded
 * from datapacks. One JSON per definition under
 * {@code data/<namespace>/nerocolonies/{jobs,research,housing,exports}/<path>.json}; in every case
 * the id is the file's namespace + path without the extension, so a pack overrides a definition
 * simply by shipping the same id.
 *
 * <p>Lifecycle follows the NeroQuests {@code QuestDefinitions} pattern (itself derived from Core's
 * {@code GateDefinitions}): definitions are read from the running server's {@link ResourceManager}
 * lazily on first use and cached, so a colony that never opens its research screen costs no I/O. The
 * cache is keyed on the <em>{@link ResourceManager} instance</em> as well as the server, and
 * {@code MinecraftServer.reloadResources} replaces that instance wholesale — so {@code /reload} is
 * detected by an identity comparison in pure common code, with no per-loader reload-listener API to
 * register three different ways and no divergence between loaders.
 *
 * <p>{@link #generation()} counts loads, so anything that caches something derived from the
 * definitions (the client sync snapshot, once it exists) can tell when to rebuild it.
 *
 * <p><b>Nothing here ever crashes on bad content.</b> Every malformed file, unknown effect type,
 * dangling reference, cycle and duplicate is logged at warn level against its resource id and the
 * offending entry is dropped or pruned. The same complaints are collected as
 * {@link ValidationIssue}s ({@link #issuesForServer}) so an operator command can show what a pack
 * got wrong without making them read the server log.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> everything logged from this class is a resource id or a codec
 * message. No player data reaches this path at all — content definitions are not player-scoped.
 */
public final class ColonyDefinitions {

    private static final String JOB_DIRECTORY = "nerocolonies/jobs";
    private static final String RESEARCH_DIRECTORY = "nerocolonies/research";
    private static final String HOUSING_DIRECTORY = "nerocolonies/housing";
    private static final String EXPORT_DIRECTORY = "nerocolonies/exports";
    private static final String EXTENSION = ".json";

    /** Stands in for "the whole load", which belongs to no single resource. */
    private static final Identifier LOAD_ISSUE_ID =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "load");

    /** The server whose datapacks produced the current maps, or null before the first load. */
    private static MinecraftServer loadedFor;

    /**
     * The resource-manager instance the current maps were read from. {@code /reload} replaces the
     * server's whole reloadable-resources object (and with it this one), so an identity change here
     * means "the datapacks were reloaded" — the loader-free reload signal.
     */
    private static ResourceManager loadedFrom;

    /** Incremented on every (re)load, so derived caches can tell when they are stale. */
    private static int generation;

    private static Map<Identifier, JobDefinition> jobs = Map.of();
    private static Map<Identifier, ResearchNode> research = Map.of();
    private static Map<Identifier, HousingTier> housing = Map.of();
    private static Map<Identifier, ExportEntry> exports = Map.of();

    /** Housing lookup by block id — the housing sweep's hot path, rebuilt with the definitions. */
    private static Map<Identifier, HousingTier> housingByBlock = Map.of();

    /** What the last load complained about, in the order it complained. Replaced wholesale. */
    private static List<ValidationIssue> issues = List.of();

    private ColonyDefinitions() {
    }

    // --- lifecycle ----------------------------------------------------------

    public static synchronized Map<Identifier, JobDefinition> jobsForServer(MinecraftServer server) {
        ensureLoaded(server);
        return jobs;
    }

    public static synchronized Map<Identifier, ResearchNode> researchForServer(MinecraftServer server) {
        ensureLoaded(server);
        return research;
    }

    public static synchronized Map<Identifier, HousingTier> housingForServer(MinecraftServer server) {
        ensureLoaded(server);
        return housing;
    }

    public static synchronized Map<Identifier, ExportEntry> exportsForServer(MinecraftServer server) {
        ensureLoaded(server);
        return exports;
    }

    /**
     * Housing tiers keyed by the block id they match — what the housing sweep walks per block. Built
     * once per load so a scan is a map lookup and never a pass over the tier list.
     */
    public static synchronized Map<Identifier, HousingTier> housingByBlock(MinecraftServer server) {
        ensureLoaded(server);
        return housingByBlock;
    }

    /** The validation problems this server's content produced (loads + caches on first use). */
    public static synchronized List<ValidationIssue> issuesForServer(MinecraftServer server) {
        ensureLoaded(server);
        return issues;
    }

    /**
     * Everything the last load dropped or ignored, empty when the packs are clean. The list is
     * immutable and replaced (never mutated) per load, so a caller may hold on to a snapshot.
     */
    public static List<ValidationIssue> validationIssues() {
        return issues;
    }

    /** Re-reads every definition from {@code server}'s current datapacks. Safe at any time. */
    public static synchronized void reload(MinecraftServer server) {
        loadFrom(server);
    }

    /**
     * Re-reads the definitions if — and only if — {@code server}'s datapacks have been reloaded (or
     * this is a different server) since the last load. Cheap enough to call from a colony tick: the
     * common case is one reference comparison.
     *
     * @return {@code true} if the definitions were re-read
     */
    public static synchronized boolean refreshIfReloaded(MinecraftServer server) {
        if (server == loadedFor && server.getResourceManager() == loadedFrom) {
            return false;
        }
        loadFrom(server);
        return true;
    }

    /** How many times the definitions have been loaded; changes whenever the content may have. */
    public static synchronized int generation() {
        return generation;
    }

    /** Drops the cache so the next access re-reads. Called when a server shuts down. */
    public static synchronized void forgetServer() {
        loadedFor = null;
        loadedFrom = null;
    }

    private static void ensureLoaded(MinecraftServer server) {
        if (server != loadedFor || server.getResourceManager() != loadedFrom) {
            loadFrom(server);
        }
    }

    private static void loadFrom(MinecraftServer server) {
        load(server);
        loadedFor = server;
        loadedFrom = server.getResourceManager();
        generation++;
    }

    // --- accessors ----------------------------------------------------------

    public static Optional<JobDefinition> job(Identifier id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public static Optional<ResearchNode> researchNode(Identifier id) {
        return Optional.ofNullable(research.get(id));
    }

    public static Optional<HousingTier> housingTier(Identifier id) {
        return Optional.ofNullable(housing.get(id));
    }

    public static Optional<ExportEntry> exportEntry(Identifier id) {
        return Optional.ofNullable(exports.get(id));
    }

    /** The currently loaded jobs, keyed by id (empty until a server loads its datapacks). */
    public static Map<Identifier, JobDefinition> jobs() {
        return jobs;
    }

    public static Map<Identifier, ResearchNode> research() {
        return research;
    }

    public static Map<Identifier, HousingTier> housing() {
        return housing;
    }

    public static Map<Identifier, ExportEntry> exports() {
        return exports;
    }

    // --- loading ------------------------------------------------------------

    private static void load(MinecraftServer server) {
        // Effect types must exist before any research node is decoded, or every effect would fall
        // through to Unknown. init() is idempotent, so calling it here as well as from common init
        // costs nothing and removes an ordering trap.
        ResearchEffectTypes.init();

        Map<Identifier, JobDefinition> loadedJobs = Map.of();
        Map<Identifier, ResearchNode> loadedResearch = Map.of();
        Map<Identifier, HousingTier> loadedHousing = Map.of();
        Map<Identifier, ExportEntry> loadedExports = Map.of();
        List<ValidationIssue> collected = new ArrayList<>();
        try {
            ResourceManager resources = server.getResourceManager();
            loadedHousing = validateHousing(
                    read(resources, HOUSING_DIRECTORY, HousingTier.CODEC, HousingTier::withId,
                            "housing tier", collected),
                    collected);
            loadedJobs = validateJobs(
                    read(resources, JOB_DIRECTORY, JobDefinition.CODEC, JobDefinition::withId,
                            "job", collected),
                    collected);
            loadedExports = validateExports(
                    read(resources, EXPORT_DIRECTORY, ExportEntry.CODEC, ExportEntry::withId,
                            "export", collected),
                    collected);
            loadedResearch = validateResearch(
                    read(resources, RESEARCH_DIRECTORY, ResearchNode.CODEC, ResearchNode::withId,
                            "research node", collected),
                    loadedJobs, loadedHousing, loadedExports, collected);
        } catch (RuntimeException e) {
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] Colony content load failed; no colony content is active.", e);
            loadedJobs = Map.of();
            loadedResearch = Map.of();
            loadedHousing = Map.of();
            loadedExports = Map.of();
            collected.clear();
            // The exception's own message can carry a filesystem path, so only its type is kept for
            // the operator-facing report; the full trace stays in the log line above.
            collected.add(ValidationIssue.dropped(LOAD_ISSUE_ID,
                    "load failed (" + e.getClass().getSimpleName() + "); no colony content is active"));
        }
        jobs = loadedJobs;
        research = loadedResearch;
        housing = loadedHousing;
        exports = loadedExports;
        housingByBlock = indexHousingByBlock(loadedHousing);
        issues = List.copyOf(collected);
        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Loaded {} job(s), {} research node(s), {} housing tier(s), "
                        + "{} export entr(ies){}.",
                jobs.size(), research.size(), housing.size(), exports.size(),
                issues.isEmpty() ? "" : " with " + issues.size() + " validation issue(s)");
    }

    /**
     * Reads one content directory. Generic over the four definition kinds because they differ only
     * in their codec and their "stamp the file-derived id on" function.
     */
    private static <T> Map<Identifier, T> read(ResourceManager resources, String directory,
            Codec<T> codec, BiFunction<T, Identifier, T> withId, String kind,
            List<ValidationIssue> collected) {
        Map<Identifier, T> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> file
                : resources.listResources(directory, path -> path.getPath().endsWith(EXTENSION))
                        .entrySet()) {
            Identifier definitionId = toDefinitionId(file.getKey(), directory);
            if (definitionId == null) {
                continue;
            }
            try (BufferedReader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                List<String> errors = new ArrayList<>(1);
                Optional<T> parsed = codec.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> {
                            NeroColoniesCommon.LOGGER.warn("[NeroColonies] Bad {} {}: {}", kind,
                                    definitionId, error);
                            errors.add(error);
                        });
                parsed.ifPresent(value -> loaded.put(definitionId, withId.apply(value, definitionId)));
                recordParseErrors(collected, definitionId, kind, parsed.isPresent(), errors);
            } catch (Exception e) {
                NeroColoniesCommon.LOGGER.warn("[NeroColonies] Could not read {} {}", kind,
                        definitionId, e);
                collected.add(ValidationIssue.dropped(definitionId,
                        "could not be read (" + e.getClass().getSimpleName() + ")"));
            }
        }
        return loaded;
    }

    /**
     * Turns a codec's complaints into report rows. A codec may complain and still produce a value (a
     * partial decode), so the severity follows whether anything survived rather than whether
     * anything was said.
     */
    private static void recordParseErrors(List<ValidationIssue> collected, Identifier id, String kind,
            boolean survived, List<String> errors) {
        if (errors.isEmpty()) {
            if (!survived) {
                collected.add(ValidationIssue.dropped(id, "not a readable " + kind + " definition"));
            }
            return;
        }
        String prefix = survived ? "partly bad " + kind + ": " : "bad " + kind + ": ";
        for (String error : errors) {
            collected.add(survived
                    ? ValidationIssue.ignored(id, prefix + error)
                    : ValidationIssue.dropped(id, prefix + error));
        }
    }

    // --- validation ---------------------------------------------------------

    /** Drops housing tiers whose block is not registered — an unmatchable tier is dead weight. */
    private static Map<Identifier, HousingTier> validateHousing(Map<Identifier, HousingTier> parsed,
            List<ValidationIssue> collected) {
        Map<Identifier, HousingTier> accepted = new LinkedHashMap<>();
        for (HousingTier tier : parsed.values()) {
            if (!tier.blockPresent()) {
                warn(collected, tier.id(), "names unregistered housing block " + tier.block(), true);
                continue;
            }
            if (tier.capacity() <= 0) {
                warn(collected, tier.id(), "houses nobody (capacity 0)", true);
                continue;
            }
            accepted.put(tier.id(), tier);
        }
        return Collections.unmodifiableMap(accepted);
    }

    /**
     * Drops jobs that can never run — no outputs, an unregistered station block, or an input naming
     * content that is not installed. A job whose only problem is a missing input is dropped rather
     * than pruned: a recipe missing an ingredient is not a cheaper recipe, it is a broken one.
     */
    private static Map<Identifier, JobDefinition> validateJobs(Map<Identifier, JobDefinition> parsed,
            List<ValidationIssue> collected) {
        Map<Identifier, JobDefinition> accepted = new LinkedHashMap<>();
        for (JobDefinition job : parsed.values()) {
            if (job.outputs().isEmpty()) {
                warn(collected, job.id(), "produces nothing (no outputs)", true);
                continue;
            }
            if (!job.stationPresent()) {
                warn(collected, job.id(), "names unregistered station block " + job.station(), true);
                continue;
            }
            ItemTarget missingInput = null;
            for (ItemTarget input : job.inputs()) {
                if (!input.present()) {
                    missingInput = input;
                    break;
                }
            }
            if (missingInput != null) {
                warn(collected, job.id(), "input " + missingInput.label()
                        + " is not present in this launch", true);
                continue;
            }
            ItemAmount missingOutput = null;
            for (ItemAmount output : job.outputs()) {
                if (!output.present()) {
                    missingOutput = output;
                    break;
                }
            }
            if (missingOutput != null) {
                warn(collected, job.id(), "output " + missingOutput.item()
                        + " is not registered in this launch", true);
                continue;
            }
            accepted.put(job.id(), job);
        }
        return Collections.unmodifiableMap(accepted);
    }

    /** Drops export entries whose target resolves to nothing — an empty tag sells nothing. */
    private static Map<Identifier, ExportEntry> validateExports(Map<Identifier, ExportEntry> parsed,
            List<ValidationIssue> collected) {
        Map<Identifier, ExportEntry> accepted = new LinkedHashMap<>();
        for (ExportEntry entry : parsed.values()) {
            if (!entry.target().present()) {
                warn(collected, entry.id(),
                        "target " + entry.target().label() + " resolves to no item in this launch", true);
                continue;
            }
            accepted.put(entry.id(), entry);
        }
        return Collections.unmodifiableMap(accepted);
    }

    /**
     * Validates research in three passes: prune effects that point at content which did not survive
     * (the node stays), prune dangling prerequisites (the node stays), then peel the graph with
     * Kahn's algorithm — anything left is in, or behind, a cycle and can never be unlocked, so it is
     * dropped. The surviving map is in dependency order.
     */
    private static Map<Identifier, ResearchNode> validateResearch(Map<Identifier, ResearchNode> parsed,
            Map<Identifier, JobDefinition> validJobs, Map<Identifier, HousingTier> validHousing,
            Map<Identifier, ExportEntry> validExports, List<ValidationIssue> collected) {

        // Pass 1: report effects that reference content which is not loaded. They stay in the node
        // (they simply match nothing) so that removing one mod does not silently reshape a tree.
        for (ResearchNode node : parsed.values()) {
            for (ResearchEffect effect : node.effects()) {
                switch (effect) {
                    case ResearchEffect.Unknown unknown -> warn(collected, node.id(),
                            "unknown effect type " + unknown.unresolvedType() + " (ignored)", false);
                    case ResearchEffect.JobUnlock unlock -> {
                        if (!validJobs.containsKey(unlock.job())) {
                            warn(collected, node.id(),
                                    "unlocks unknown job " + unlock.job() + " (ignored)", false);
                        }
                    }
                    case ResearchEffect.HousingTierUnlock unlock -> {
                        if (!validHousing.containsKey(unlock.tier())) {
                            warn(collected, node.id(),
                                    "unlocks unknown housing tier " + unlock.tier() + " (ignored)", false);
                        }
                    }
                    case ResearchEffect.ExportUnlock unlock -> {
                        if (!validExports.containsKey(unlock.export())) {
                            warn(collected, node.id(),
                                    "unlocks unknown export " + unlock.export() + " (ignored)", false);
                        }
                    }
                    default -> {
                        // Numeric effects reference nothing and cannot dangle.
                    }
                }
            }
        }

        // Pass 2: prune dangling prerequisites.
        Map<Identifier, ResearchNode> pruned = new LinkedHashMap<>();
        for (ResearchNode node : parsed.values()) {
            List<Identifier> kept = new ArrayList<>(node.requires().size());
            for (Identifier prerequisite : node.requires()) {
                if (parsed.containsKey(prerequisite)) {
                    kept.add(prerequisite);
                } else {
                    warn(collected, node.id(),
                            "requires unknown node " + prerequisite + " (that prerequisite is ignored)",
                            false);
                }
            }
            pruned.put(node.id(),
                    kept.size() == node.requires().size() ? node : node.withRequires(kept));
        }

        // Pass 3: Kahn peel. Whatever cannot be peeled is in or behind a cycle, and unreachable.
        Map<Identifier, ResearchNode> accepted = new LinkedHashMap<>();
        Set<Identifier> resolved = new HashSet<>();
        Map<Identifier, ResearchNode> remaining = new LinkedHashMap<>(pruned);
        boolean progressed = true;
        while (progressed && !remaining.isEmpty()) {
            progressed = false;
            Iterator<Map.Entry<Identifier, ResearchNode>> it = remaining.entrySet().iterator();
            while (it.hasNext()) {
                ResearchNode node = it.next().getValue();
                if (resolved.containsAll(node.requires())) {
                    accepted.put(node.id(), node);
                    resolved.add(node.id());
                    it.remove();
                    progressed = true;
                }
            }
        }
        for (ResearchNode node : remaining.values()) {
            warn(collected, node.id(),
                    "is in (or behind) a prerequisite cycle and can never unlock", true);
        }
        return Collections.unmodifiableMap(accepted);
    }

    private static Map<Identifier, HousingTier> indexHousingByBlock(Map<Identifier, HousingTier> tiers) {
        Map<Identifier, HousingTier> index = new LinkedHashMap<>();
        for (HousingTier tier : tiers.values()) {
            HousingTier previous = index.put(tier.block(), tier);
            if (previous != null && previous.tier() > tier.tier()) {
                // Two tiers claiming one block: the higher tier wins, deterministically.
                index.put(tier.block(), previous);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static void warn(List<ValidationIssue> collected, Identifier id, String detail,
            boolean dropped) {
        NeroColoniesCommon.LOGGER.warn("[NeroColonies] {} {}{}", id, detail,
                dropped ? "; dropped." : ".");
        collected.add(dropped ? ValidationIssue.dropped(id, detail) : ValidationIssue.ignored(id, detail));
    }

    /** {@code <ns>:nerocolonies/jobs/foo/bar.json} -> {@code <ns>:foo/bar}. */
    private static Identifier toDefinitionId(Identifier file, String directory) {
        String path = file.getPath();
        if (!path.startsWith(directory + "/") || !path.endsWith(EXTENSION)) {
            return null;
        }
        String trimmed = path.substring(directory.length() + 1, path.length() - EXTENSION.length());
        return Identifier.fromNamespaceAndPath(file.getNamespace(), trimmed);
    }
}
