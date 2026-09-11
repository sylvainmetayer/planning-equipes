package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Three layering rules, all source scans: the MCP tools do not reach into the
 * REST layer, the business layer does not import JAX-RS, and a write to the
 * referential goes through the one path that warns and journals.
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

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");
    private static final Path MCP = SOURCES.resolve("mcp");

    /**
     * The two files outside {@code api/} allowed to import JAX-RS, each for a
     * reason that is about the type, not about convenience:
     * {@code ApplicationLinks} builds URLs with {@code UriBuilder}, and
     * {@code GlobalExceptionMapper} <em>is</em> a JAX-RS provider — it lives
     * in {@code observability/} because its job is the Sentry report, not
     * the response.
     */
    private static final Set<String> IMPORTS_JAX_RS_ADMIS =
            Set.of("service/espace/ApplicationLinks.java", "observability/GlobalExceptionMapper.java");

    /**
     * Any mention of the REST package in code, not just a plain {@code import}
     * of it. Two ways round a narrower check, and the second is exactly how the
     * dependency would come back: {@code import static
     * dev.sylvain.planning.api.ValidationError.…}, and a fully qualified
     * reference with no import at all.
     */
    private static final Pattern MENTION_DE_LA_COUCHE_REST = Pattern.compile("\\bdev\\.sylvain\\.planning\\.api\\.");

    private static final Pattern MENTION_DE_JAX_RS = Pattern.compile("\\bjakarta\\.ws\\.rs\\.");

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
                    if (!commentaire
                            && MENTION_DE_LA_COUCHE_REST.matcher(content).find()) {
                        offenders.add(MCP.relativize(file) + ":" + line + " — " + content.trim());
                    }
                }
            }
        }

        assertThat(offenders).as("""
                        MCP tools importing the REST layer. Both are callers of the same rules: \
                        move what is shared into service/ and let the two call it, rather than \
                        making one of them go through the other's transport types.""").isEmpty();
    }

    /**
     * Issue #392's A8: eight {@code jakarta.ws.rs.NotFoundException} thrown
     * from services and one MCP tool, while {@code BusinessError.NotFound}
     * existed and 175 {@code throw BusinessError} were already in place. The
     * HTTP status happened to be the same; the MCP one went out as a generic
     * 500, because the transport that knows what a JAX-RS exception means is
     * not the one that was calling. A refusal belongs to the domain, and
     * {@code BusinessErrorMapper} is the one place that says what it answers.
     */
    @Test
    void businessCodeDoesNotImportJaxRs() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relatif = SOURCES.relativize(file).toString();
                if (relatif.startsWith("api/") || IMPORTS_JAX_RS_ADMIS.contains(relatif)) {
                    continue;
                }
                int line = 0;
                for (String content : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    line++;
                    // The same three forms as MENTION_DE_LA_COUCHE_REST: an import,
                    // a static import, a fully qualified name in the code. Only
                    // the first was checked, and the third is exactly how a
                    // `throw new jakarta.ws.rs.NotFoundException(…)` would come back.
                    String nu = content.trim();
                    boolean commentaire = nu.startsWith("//") || nu.startsWith("*") || nu.startsWith("/*");
                    if (!commentaire && MENTION_DE_JAX_RS.matcher(content).find()) {
                        offenders.add(relatif + ":" + line + " — " + nu);
                    }
                }
            }
        }

        assertThat(offenders).as("""
                        JAX-RS imported outside api/. A refusal is a BusinessError (Invalid, NotFound, \
                        Conflict, Stale) and BusinessErrorMapper decides its status once for every \
                        caller, HTTP or MCP; a transport type in a service answers only one of them.""").isEmpty();
    }

    /**
     * Issue #392's A6: {@code ReferenceDataService} has two ways of writing an
     * animateur, a stand or a créneau. {@code write*} reads the fiche as it
     * stood, notes the fields that moved for the history and returns the
     * warnings; {@code create*} / {@code update*} just write. REST used the
     * first, the MCP tools the second — so an assistant's edit wrote a
     * history line with an empty « champs » column and warned nobody that the
     * volunteer it had just made unavailable on a date outside the event.
     * Same rule for both doors, and the façade is the only caller of its own
     * bare writes.
     */
    @Test
    void referentialWritesOutsideTheFacadeGoThroughTheJournaledPath() throws IOException {
        // Any receiver — a façade field is not always called referenceDataService
        // — and the services one layer below, which DeclarationDisponibiliteService
        // used to call directly. Matched on the whole source: palantir wraps a
        // long call as `referenceDataService\n.updateAnimateur(`, which a line
        // by line scan cannot see.
        Pattern facadeNue = Pattern.compile("\\.\\s*(create|update)(Animateur|Stand|Creneau)\\s*\\(");
        Pattern serviceNu =
                Pattern.compile("\\b(animateurService|standService|creneauService)\\s*\\.\\s*(create|update)\\s*\\(");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relatif = SOURCES.relativize(file).toString();
                if (relatif.startsWith("service/referentiel/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (Pattern pattern : List.of(facadeNue, serviceNu)) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        int line = (int) source.substring(0, matcher.start())
                                        .chars()
                                        .filter(c -> c == '\n')
                                        .count()
                                + 1;
                        offenders.add(
                                relatif + ":" + line + " — " + matcher.group().trim());
                    }
                }
            }
        }

        assertThat(offenders).as("""
                        bare create*/update* on the referential façade. Call write* instead: it \
                        is the one path that records which fields moved and returns the warnings, \
                        whichever door the write came through.""").isEmpty();
    }
}
