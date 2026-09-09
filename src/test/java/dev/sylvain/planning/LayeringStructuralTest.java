package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The MCP tools do not reach into the REST layer.
 *
 * <p>Both are <em>callers</em> of the same business rules — one over HTTP, one
 * over MCP. When a tool injects a resource instead of a service, the rule it
 * needs stops being reachable except through a transport type: issue #392's A3
 * found {@code ScenarioMcpTools} reading {@code Response.getStatus()} to learn
 * whether an import had failed, on a call that never went through HTTP and
 * therefore never had a status to read. The branch was dead, and the dependency
 * pointed the wrong way.</p>
 *
 * <p>Written as a source scan rather than an annotation, for the same reason
 * the language policy is: there is nothing to hang an annotation on, and the
 * rule has to hold for a file somebody adds next month.</p>
 */
class LayeringStructuralTest {

    private static final Path MCP = Path.of("src/main/java/dev/sylvain/planning/mcp");

    /**
     * Any mention of the REST package in code, not just a plain {@code import}
     * of it. Two ways round a narrower check, and the second is exactly how the
     * dependency would come back: {@code import static
     * dev.sylvain.planning.api.ValidationError.…}, and a fully qualified
     * reference with no import at all.
     */
    private static final Pattern MENTION_DE_LA_COUCHE_REST = Pattern.compile("\\bdev\\.sylvain\\.planning\\.api\\.");

    @Test
    void noMcpToolImportsTheRestLayer() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MCP)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int line = 0;
                for (String content : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    line++;
                    // Comment lines excluded: this class's own javadoc names the
                    // resource the tools used to reach for, and a rule that
                    // forbids talking about the thing it forbids is a nuisance.
                    String nu = content.strip();
                    boolean commentaire = nu.startsWith("//") || nu.startsWith("*") || nu.startsWith("/*");
                    if (!commentaire && MENTION_DE_LA_COUCHE_REST.matcher(content).find()) {
                        offenders.add(MCP.relativize(file) + ":" + line + " — " + content.trim());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("""
                        MCP tools importing the REST layer. Both are callers of the same rules: \
                        move what is shared into service/ and let the two call it, rather than \
                        making one of them go through the other's transport types.""")
                .isEmpty();
    }
}
