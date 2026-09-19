package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every file that reaches into {@code ai.timefold.solver.core.impl} is named
 * here, with the net that makes the dependency tenable.
 *
 * <p>{@code core.impl} is outside Timefold's semantic versioning: a minor bump
 * may rename, move or reshape anything in it, and the repository has already
 * paid one such break: the 2.x migration.
 * The rule is not "never depend on it" — the move-filter SPI only exists
 * there — but "never depend on it without saying what will catch the next
 * break". Issue #392's A9 found three filters that did.</p>
 *
 * <p>Two kinds of net, and they are not interchangeable:</p>
 * <ul>
 *   <li>A <b>compile error</b> catches a renamed or removed type. That is what
 *       covers the filters: they implement {@code SelectionFilter} and are
 *       typed on the concrete move classes Timefold hands them, so a bump that
 *       changes either does not build. What it does <em>not</em> catch is a
 *       semantic change — a filter still compiling but no longer being asked —
 *       which is why the scenario suite ({@code -Pscenario-tests}) must also
 *       run on every bump: a filter silently bypassed shows up as a solve that
 *       no longer converges.</li>
 *   <li>An <b>oracle</b> catches a semantic drift. The diagnostic reads the
 *       score director's constraint matches, which could change meaning
 *       without a type changing; {@code ConstraintDiagnosticServiceContractTest}
 *       compares it against Timefold's own {@code analyze()} for that reason.</li>
 * </ul>
 *
 * <p>A file added to the set below without an entry here fails the build —
 * that is the point: the next dependency on {@code core.impl} has to name its
 * net before it is accepted.</p>
 */
class TimefoldInternalApiStructuralTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** File → the net that catches the next Timefold break there. */
    private static final Map<String, String> DEPENDANTS_DOCUMENTES = Map.ofEntries(
            Map.entry(
                    "dev/sylvain/planning/service/diagnostic/ScoreDirectorConstraintDiagnosticService.java",
                    "ConstraintDiagnosticServiceContractTest compares it with Timefold's analyze() oracle"),
            Map.entry(
                    "dev/sylvain/planning/solver/EligibleAnimateurMoveFilter.java",
                    "compile error on the SelectionFilter / SelectorBased*Move types; -Pscenario-tests for a filter bypassed"),
            Map.entry(
                    "dev/sylvain/planning/solver/HoleNeighbourPosteFilter.java",
                    "compile error on SelectionFilter; HoleNeighbourPosteFilterTest and -Pscenario-tests for a filter bypassed"),
            Map.entry(
                    "dev/sylvain/planning/solver/UnassignedPosteFilter.java",
                    "compile error on SelectionFilter; -Pscenario-tests for a filter bypassed"),
            Map.entry(
                    "dev/sylvain/planning/solver/SiegeObligatoireFilter.java",
                    "compile error on SelectionFilter; -Pscenario-tests, where a filter bypassed shows as the "
                            + "hivernal festival no longer converging"),
            Map.entry(
                    "dev/sylvain/planning/solver/WeekRelocationMoveIteratorFactory.java",
                    "compile error on MoveIteratorFactory / ScoreDirector; WeekRelocationMoveIteratorFactoryTest, "
                            + "and -Pscenario-tests for a move the solver stops drawing"));

    private static final Pattern MENTION_DE_CORE_IMPL = Pattern.compile("\\bai\\.timefold\\.solver\\.core\\.impl\\.");

    @Test
    void everyDependencyOnTimefoldInternalsIsDocumentedWithItsNet() throws IOException {
        Map<String, String> dependants = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // An import, a static import or a fully qualified name in the
                // code: the inventory has to see the three forms, or the
                // dependency slips back in as `ai.timefold.solver.core.impl.X`
                // written in full. A javadoc citing the package is not one.
                for (String content : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String nu = content.trim();
                    boolean commentaire = nu.startsWith("//") || nu.startsWith("*") || nu.startsWith("/*");
                    if (!commentaire && MENTION_DE_CORE_IMPL.matcher(content).find()) {
                        dependants.put(SOURCES.relativize(file).toString(), "");
                        break;
                    }
                }
            }
        }

        Set<String> nonDocumentes = new java.util.TreeSet<>(dependants.keySet());
        nonDocumentes.removeAll(DEPENDANTS_DOCUMENTES.keySet());
        assertThat(nonDocumentes).as("""
                        files importing ai.timefold.solver.core.impl without an entry in this test. \
                        core.impl is outside semver: name the net that will catch the next break \
                        (a compile error on the types used, an oracle test, the scenario suite) \
                        and add the file to DEPENDANTS_DOCUMENTES.""").isEmpty();

        // The mirror: an entry that no file justifies any more is a stale
        // justification, and the list must stay the inventory it claims to be.
        Set<String> perimes = new java.util.TreeSet<>(DEPENDANTS_DOCUMENTES.keySet());
        perimes.removeAll(dependants.keySet());
        assertThat(perimes)
                .as("documented dependants that no longer import core.impl — remove them here")
                .isEmpty();
    }
}
