package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The short label is what a sentence written for an organiser says instead of
 * the technical name — the reading of the score, the titles of the Problèmes
 * screen. It is only worth something if every rule has one and none of them
 * leaks the identifier back: a rule added to the catalogue with a blank label,
 * or with its camelCase name pasted in, fails here, the same guard
 * {@code ConstraintFloorRulesTest} holds on the floor table.
 */
class ConstraintCatalogShortLabelTest {

    /** A lowercase run followed by an uppercase letter inside one word: {@code posteDoitEtrePourvu}. */
    private static final Pattern CAMEL_CASE = Pattern.compile("\\p{Ll}\\p{Lu}");

    private static final int LONGUEUR_MAX = 45;

    @Test
    void everyRuleOfTheCatalogueHasAShortLabel() {
        List<ConstraintDefinition> definitions = ConstraintCatalog.definitions();

        assertThat(definitions)
                .isNotEmpty()
                .allSatisfy(definition -> assertThat(definition.libelleCourt())
                        .as(definition.name())
                        .isNotBlank()
                        .hasSizeLessThanOrEqualTo(LONGUEUR_MAX)
                        .isNotEqualToIgnoringCase(definition.name()));
    }

    @Test
    void noShortLabelCarriesATechnicalName() {
        assertThat(ConstraintCatalog.definitions()).allSatisfy(definition -> {
            assertThat(CAMEL_CASE.matcher(definition.libelleCourt()).find())
                    .as("camelCase in the label of %s", definition.name())
                    .isFalse();
            assertThat(ConstraintCatalog.PAR_NOM.keySet())
                    .as("the label of %s names a rule", definition.name())
                    .noneMatch(name -> definition.libelleCourt().contains(name));
        });
    }

    @Test
    void twoRulesNeverShareALabel() {
        List<String> libelles = ConstraintCatalog.definitions().stream()
                .map(ConstraintDefinition::libelleCourt)
                .toList();

        assertThat(libelles).doesNotHaveDuplicates();
    }
}
