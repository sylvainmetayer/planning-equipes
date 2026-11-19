package dev.sylvain.planning.solver.handsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A constraint cannot exist in one engine only (ADR 0087): the catalogue and
 * {@link Rule} name the same rules, and every rule is charged somewhere in the
 * engine's score. Whether it is charged <em>right</em> is
 * {@code HandSearchDifferentialTest}'s business; this test is the one that
 * fails first, by name, when a constraint is added to the constraint provider
 * and nobody wrote it a second time.
 */
class HandSearchCoverageStructuralTest {

    private static final Path ENGINE = Path.of("src/main/java/dev/sylvain/planning/solver/handsearch");

    private static Set<String> engineRules() {
        return Arrays.stream(Rule.values()).map(Rule::constraintName).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyConstraintOfTheCatalogueHasItsRuleInTheEngine() {
        Set<String> catalogue = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(catalogue)
                .as("constraints of ConstraintCatalog against the constants of Rule: a constraint missing here is"
                        + " scored by Timefold and ignored by the direct engine — write its term in"
                        + " IncrementalScore (or FlatProblem.staticTerms) and add its constant to Rule")
                .isEqualTo(engineRules());
    }

    @Test
    void everyRuleIsChargedSomewhereInTheScore() throws IOException {
        String score = Files.readString(ENGINE.resolve("IncrementalScore.java"), StandardCharsets.UTF_8)
                + Files.readString(ENGINE.resolve("FlatProblem.java"), StandardCharsets.UTF_8);

        Set<String> neverCharged = Arrays.stream(Rule.values())
                .filter(rule -> !Pattern.compile("\\(\\s*Rule\\." + rule.name() + ",")
                        .matcher(score)
                        .find())
                .map(Rule::constraintName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(neverCharged)
                .as("rules declared in Rule that no line of the engine's score charges"
                        + " (a charge reads « cost(Rule.X, n) » or « balance(Rule.X, … ) »)")
                .isEmpty();
    }

    @Test
    void aScoreTheSingleNumberCannotOrderIsRefused() {
        IncrementalScore.checkRange(new long[] {120, 40_000, -3_000});

        assertThatThrownBy(() -> IncrementalScore.checkRange(new long[] {0, 600_000, 0}))
                .isInstanceOf(HandSearchException.class)
                .hasMessageContaining("600000medium");
        assertThatThrownBy(() -> IncrementalScore.checkRange(new long[] {0, 0, 600_000}))
                .isInstanceOf(HandSearchException.class);
        assertThatThrownBy(() -> IncrementalScore.checkRange(new long[] {5_000_000, 0, 0}))
                .isInstanceOf(HandSearchException.class);
    }
}
