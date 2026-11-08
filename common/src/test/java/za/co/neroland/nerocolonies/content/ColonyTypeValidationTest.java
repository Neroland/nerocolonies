package za.co.neroland.nerocolonies.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.content.effect.ResearchEffect;

/**
 * The loader's rules for colony-type content, run without a game: planetary content naming an item that is
 * not installed is dropped with no validation issue, while the same content without a filter is reported as
 * before, and an unknown type name is reported.
 */
class ColonyTypeValidationTest {

    private static final String MISSING_NAMESPACE = "absentmod";

    /** Everything is present except items and tags from {@value #MISSING_NAMESPACE}. */
    private static final ColonyDefinitions.Presence NO_ABSENTMOD = new ColonyDefinitions.Presence() {

        @Override
        public boolean station(JobDefinition job) {
            return true;
        }

        @Override
        public boolean target(ItemTarget target) {
            return !target.label().replace("#", "").startsWith(MISSING_NAMESPACE + ":");
        }

        @Override
        public boolean amount(ItemAmount amount) {
            return !MISSING_NAMESPACE.equals(amount.item().getNamespace());
        }
    };

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nerocolonies", path);
    }

    private static JobDefinition job(String path, List<String> types) {
        return new JobDefinition(id(path), id("refinery_station"),
                List.of(new ItemTarget(Optional.empty(), Optional.of(id("planetary/regolith")), 4)),
                List.of(new ItemAmount(Identifier.fromNamespaceAndPath(MISSING_NAMESPACE, "canister"), 1)),
                300, 1, 20.0D, Optional.empty(), true, types);
    }

    private static <T> Map<Identifier, T> mapOf(Identifier key, T value) {
        Map<Identifier, T> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    @DisplayName("A planetary job making a missing item is skipped with no issue; an unfiltered one is reported")
    void quietJobs() {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<Identifier, JobDefinition> kept = ColonyDefinitions.validateJobs(
                mapOf(id("planetary/extract"), job("planetary/extract", List.of("lunar_outpost"))), issues,
                NO_ABSENTMOD);
        assertTrue(kept.isEmpty(), "the job cannot run here, so it is not loaded");
        assertTrue(issues.isEmpty(), "but filtered content is not reported: " + issues);

        ColonyDefinitions.validateJobs(mapOf(id("extract"), job("extract", List.of())), issues, NO_ABSENTMOD);
        assertEquals(1, issues.size());
        assertEquals(ValidationIssue.Severity.DROPPED, issues.get(0).severity());
    }

    @Test
    @DisplayName("A planetary export with an empty target is skipped with no issue")
    void quietExports() {
        List<ValidationIssue> issues = new ArrayList<>();
        ExportEntry entry = new ExportEntry(id("planetary/he3"),
                new ItemTarget(Optional.empty(), Optional.of(Identifier.fromNamespaceAndPath(MISSING_NAMESPACE,
                        "helium3")), 1), 14.0D, 16, Optional.empty(), List.of("lunar_outpost"));
        assertTrue(ColonyDefinitions.validateExports(mapOf(entry.id(), entry), issues, NO_ABSENTMOD).isEmpty());
        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    @DisplayName("A planetary research node unlocking a skipped job is not reported; an unfiltered one is")
    void quietResearch() {
        List<ValidationIssue> issues = new ArrayList<>();
        List<ResearchEffect> unlock = List.of(new ResearchEffect.JobUnlock(id("planetary/extract")));
        ResearchNode typed = new ResearchNode(id("planetary/lunar"), "planetary", "", List.of(), List.of(),
                unlock, List.of("lunar_outpost"));
        Map<Identifier, ResearchNode> kept = ColonyDefinitions.validateResearch(mapOf(typed.id(), typed),
                Map.of(), Map.of(), Map.of(), issues);
        assertEquals(1, kept.size(), "the node itself stays");
        assertTrue(issues.isEmpty(), issues.toString());

        ResearchNode plain = new ResearchNode(id("industry/x"), "industry", "", List.of(), List.of(), unlock);
        ColonyDefinitions.validateResearch(mapOf(plain.id(), plain), Map.of(), Map.of(), Map.of(), issues);
        assertEquals(1, issues.size());
        assertEquals(ValidationIssue.Severity.IGNORED, issues.get(0).severity());
    }

    @Test
    @DisplayName("Unknown type names are reported; a filter of only unknown names drops the definition")
    void unknownTypeNames() {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<Identifier, JobDefinition> parsed = new LinkedHashMap<>();
        parsed.put(id("a"), job("a", List.of("lunar_outpost", "plasma_city")));
        parsed.put(id("b"), job("b", List.of("plasma_city")));
        Map<Identifier, JobDefinition> cleaned = ColonyDefinitions.cleanColonyTypes(parsed,
                JobDefinition::colonyTypes, JobDefinition::withColonyTypes, issues);
        assertEquals(List.of(id("a")), List.copyOf(cleaned.keySet()));
        assertEquals(List.of("lunar_outpost"), cleaned.get(id("a")).colonyTypes());
        assertEquals(3, issues.size(), "two unknown names and one drop: " + issues);
    }
}
