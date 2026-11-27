package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No rule, in either engine, reads the break-coverage flag of a timeslot, nor the seat-count methods that
 * used to depend on it: the flag only ever shaped seat generation, never a score. That is what let it be
 * removed from the model without moving any score.
 */
class NoRuleReadsBreakCoverageStructuralTest {

    private static final Path SOLVER = Path.of("src/main/java/dev/sylvain/planning/solver");

    @Test
    void noConstraintStreamNorHandSearchSourceReadsTheBreakCoverage() throws IOException {
        List<Path> sources = new java.util.ArrayList<>();
        sources.add(SOLVER.resolve("PlanningConstraintProvider.java"));
        for (String sub : List.of("constraints", "handsearch")) {
            try (Stream<Path> files = Files.walk(SOLVER.resolve(sub))) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        assertThat(sources).hasSizeGreaterThan(10);
        for (Path source : sources) {
            assertThat(Files.readString(source))
                    .as(source.toString())
                    .doesNotContainIgnoringCase("couverturePause")
                    .doesNotContain("siegesSegment")
                    .doesNotContain("siegesSimultanes");
        }
    }
}
