package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code mcp-renommages.tsv} maps every tool name published on {@code v3.1.0} to its current
 * name, or to {@code retiré} (ADR 0100). The 3.1.0 list is frozen in a test resource.
 */
class McpRenommageTest {

    private static final Path TABLE = Path.of("src/main/resources/mcp-renommages.tsv");
    private static final Path V3_1_0 = Path.of("src/test/resources/mcp-outils-v3.1.0.txt");
    private static final String REMOVED = "retiré";

    /** {@code <objet>_<verbe>}, the verb drawn from the closed set of ADR 0100, or a bare object. */
    private static final Pattern CONVENTION = Pattern.compile("[a-z]+(?:_[a-z]+)*_(?:lire|lister|creer|modifier"
            + "|supprimer|analyser|simuler|lancer|arreter|importer|exporter|publier|envoyer)|[a-z]+");

    private static Map<String, String> table() throws IOException {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(TABLE)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            assertThat(cells).as("two tab-separated columns: %s", line).hasSize(2);
            assertThat(rows.put(cells[0], cells[1]))
                    .as("duplicate row for %s", cells[0])
                    .isNull();
        }
        return rows;
    }

    private static Set<String> published() {
        Set<String> names = new TreeSet<>();
        try (var files = Files.list(Path.of("src/main/java/dev/sylvain/planning/mcp"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = Pattern.compile("@Tool\\(\\s*name\\s*=\\s*\"([a-z0-9_]+)\"")
                        .matcher(Files.readString(file));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return names;
    }

    @Test
    void everyNameOfV310HasARow() throws IOException {
        List<String> frozen =
                Files.readAllLines(V3_1_0).stream().filter(s -> !s.isBlank()).toList();
        assertThat(frozen).as("the frozen 3.1.0 list is read").hasSizeGreaterThan(100);
        assertThat(table().keySet())
                .as("a name published on v3.1.0 without a row")
                .containsAll(frozen);
    }

    @Test
    void everyNewNameIsPublishedOrRemovedAndFollowsTheConvention() throws IOException {
        Set<String> published = published();
        assertThat(published).as("the scan finds the tools").hasSizeGreaterThan(100);
        table().forEach((old, now) -> {
            if (REMOVED.equals(now)) {
                assertThat(published)
                        .as("%s is retired but still published", old)
                        .doesNotContain(old);
                return;
            }
            assertThat(published)
                    .as("row %s -> %s: the new name is not published", old, now)
                    .contains(now);
            if (!old.equals(now)) {
                assertThat(now).as("renamed tool %s", now).matches(CONVENTION);
            }
        });
    }

    @Test
    void theScanAgreesWithReflection() {
        long reflected = 0;
        try (var files = Files.list(Path.of("src/main/java/dev/sylvain/planning/mcp"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String simple = file.getFileName().toString().replace(".java", "");
                try {
                    for (var m : Class.forName(
                                    "dev.sylvain.planning.mcp." + simple,
                                    false,
                                    getClass().getClassLoader())
                            .getDeclaredMethods()) {
                        reflected += m.isAnnotationPresent(Tool.class) ? 1 : 0;
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError _) {
                    // not a loadable class
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat((long) published().size()).isEqualTo(reflected);
    }
}
