package za.co.neroland.nerocolonies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source and content guards that read the repository itself: the Nerospace import boundary, which shipped
 * content may carry a {@code colony_types} filter, and that no file names the private planning repository.
 */
class RepositoryGuardTest {

    /** Directories never scanned: build output, VCS and tool caches. */
    private static final Set<String> SKIP = Set.of("build", ".git", ".gradle", "bin", "out", "runs", "node_modules",
            ".idea", "versions");

    /** The repository root: the nearest ancestor of the working directory that holds {@code common/src}. */
    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("common").resolve("src").resolve("main"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "repository root not found from " + Path.of("").toAbsolutePath());
        return dir;
    }

    private static List<Path> files(Path from, String... extensions) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(from)) {
            return out;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(from) && SKIP.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                boolean wanted = extensions.length == 0;
                for (String extension : extensions) {
                    wanted |= name.endsWith(extension);
                }
                if (wanted && attrs.isRegularFile()) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    @DisplayName("Only compat/nerospace imports Nerospace")
    void nerospaceImportBoundary() throws IOException {
        Path java = root().resolve("common/src/main/java");
        List<Path> sources = files(java, ".java");
        assertFalse(sources.isEmpty());
        List<String> offenders = new ArrayList<>();
        int inBridge = 0;
        for (Path source : sources) {
            boolean imports = Files.readString(source, StandardCharsets.UTF_8)
                    .contains("import za.co.neroland.nerospace.");
            boolean bridge = slash(java.relativize(source)).startsWith("za/co/neroland/nerocolonies/compat/nerospace/");
            if (imports && !bridge) {
                offenders.add(slash(java.relativize(source)));
            }
            if (imports && bridge) {
                inBridge++;
            }
        }
        assertTrue(offenders.isEmpty(), "Nerospace imported outside compat/nerospace: " + offenders);
        assertEquals(2, inBridge, "the bridge and the claim policy are the Nerospace-typed classes");
    }

    @Test
    @DisplayName("Only the planetary content carries a colony_types filter")
    void onlyPlanetaryContentIsFiltered() throws IOException {
        Path content = root().resolve("common/src/main/resources/data/nerocolonies/nerocolonies");
        int planetary = 0;
        for (String kind : List.of("blueprints", "jobs", "research", "exports")) {
            for (Path file : files(content.resolve(kind), ".json")) {
                JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                boolean filtered = json.isJsonObject() && json.getAsJsonObject().has("colony_types");
                boolean isPlanetary = slash(content.relativize(file)).startsWith(kind + "/planetary/");
                assertEquals(isPlanetary, filtered, slash(content.relativize(file)));
                if (isPlanetary) {
                    planetary++;
                }
            }
        }
        assertEquals(12 + 12 + 6 + 2, planetary);
    }

    @Test
    @DisplayName("No file names the private planning repository")
    void noPrivateRepositoryName() throws IOException {
        String forbidden = "neroland-mc-" + "ecosystem";
        Path root = root();
        List<String> offenders = new ArrayList<>();
        // Every file outside build output and VCS metadata; anything that is not UTF-8 text is skipped.
        for (Path file : files(root)) {
            if (file.getFileName().toString().equals("RepositoryGuardTest.java")) {
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | java.io.UncheckedIOException e) {
                continue; // not UTF-8 text
            }
            if (text.contains(forbidden)) {
                offenders.add(slash(root.relativize(file)));
            }
        }
        assertTrue(offenders.isEmpty(), "private repository named in: " + offenders);
    }
}
