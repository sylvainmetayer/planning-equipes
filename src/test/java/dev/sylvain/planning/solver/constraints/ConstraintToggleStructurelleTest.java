package dev.sylvain.planning.solver.constraints;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The name of a constraint is written three times without any compiler tying
 * the three together: {@code ConstraintToggleSupport.actif(stream, "X")} which
 * switches it off, {@code .asConstraint("X")} which declares it, and
 * {@code ConstraintCatalog} which describes it to the UI.
 * {@code ConstraintCatalogTest} only compares the last two; a typo — or an
 * omission — on the {@code actif} side was therefore caught by nothing, and the
 * switch the UI offered simply did nothing.
 *
 * <p>This is not theoretical: {@code eviterChangementEmplacementEloigne} shipped
 * without its {@code actif} wrapper (see {@link ConstraintToggleTest}). This
 * test covers the 42 constraints, where {@link ConstraintToggleTest} checks the
 * mechanism itself on one representative per family — the two complete each
 * other, neither replaces the other.</p>
 */
class ConstraintToggleStructurelleTest {

    private static final Path FAMILLES = Path.of("src/main/java/dev/sylvain/planning/solver/constraints");

    /** {@code asConstraint("nom")} — where the constraint is declared. */
    private static final Pattern DECLARATION = Pattern.compile("asConstraint\\(\"([A-Za-z0-9_]+)\"\\)");

    /** The start of an {@code actif(…)} call; the closing argument is extracted by hand. */
    private static final Pattern ENVELOPPE = Pattern.compile("\\bactif\\(");

    /** The last string literal of a call, that is, the name passed to {@code actif}. */
    private static final Pattern DERNIER_LITTERAL = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*$");

    private static List<Path> families() throws IOException {
        try (Stream<Path> files = Files.list(FAMILLES)) {
            return files.filter(f -> f.getFileName().toString().endsWith("Constraints.java"))
                    .sorted()
                    .toList();
        }
    }

    /** The names passed to {@code actif(…)}, balancing the parentheses of the call. */
    private static List<String> nomsEnveloppes(String source) {
        List<String> noms = new ArrayList<>();
        Matcher debut = ENVELOPPE.matcher(source);
        while (debut.find()) {
            int profondeur = 1;
            int i = debut.end();
            while (i < source.length() && profondeur > 0) {
                char c = source.charAt(i++);
                if (c == '(') {
                    profondeur++;
                } else if (c == ')') {
                    profondeur--;
                }
            }
            Matcher nom = DERNIER_LITTERAL.matcher(
                    source.substring(debut.end(), i - 1).stripTrailing());
            if (nom.find()) {
                noms.add(nom.group(1));
            }
        }
        return noms;
    }

    private static List<String> nomsDeclares(String source) {
        return DECLARATION
                .matcher(source)
                .results()
                .map(r -> r.group(1))
                .sorted()
                .toList();
    }

    /**
     * Every declared constraint can be switched off, and nothing can be switched
     * off that is not declared — otherwise the switch points at nothing.
     */
    @Test
    void chaqueContrainteDeclareeEstEteignableSousExactementLeMemeNom() throws IOException {
        for (Path famille : families()) {
            String source = Files.readString(famille);

            assertThat(nomsEnveloppes(source).stream().sorted().toList())
                    .as("noms passés à ConstraintToggleSupport.actif dans %s", famille.getFileName())
                    .containsExactlyElementsOf(nomsDeclares(source));
        }
    }

    /**
     * And the count matches: the constraints found in the sources are exactly
     * the ones the UI offers to switch off.
     */
    @Test
    void lesContraintesEteignablesSontExactementCellesDuCatalogue() throws IOException {
        List<String> eteignables = new ArrayList<>();
        for (Path famille : families()) {
            eteignables.addAll(nomsEnveloppes(Files.readString(famille)));
        }

        assertThat(eteignables.stream().sorted().toList())
                .containsExactlyElementsOf(ConstraintCatalog.definitions().stream()
                        .map(ConstraintCatalog.ConstraintDefinition::name)
                        .sorted()
                        .toList());
    }
}
