package dev.sylvain.planning.solver.constraints;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.solver.ConstraintCatalog;

/**
 * Le nom d'une contrainte est écrit trois fois sans qu'aucun compilateur ne
 * relie les trois : {@code ConstraintToggleSupport.actif(stream, "X")} qui
 * l'éteint, {@code .asConstraint("X")} qui la déclare, et
 * {@code ConstraintCatalog} qui la décrit à l'IHM. {@code ConstraintCatalogTest}
 * ne compare que les deux dernières ; une faute de frappe — ou un oubli — du
 * côté {@code actif} n'était donc détectée par rien, et l'interrupteur proposé
 * par l'IHM ne faisait simplement rien.
 *
 * <p>Ce n'est pas théorique : {@code eviterChangementEmplacementEloigne} a été
 * livrée sans son enveloppe {@code actif} (voir {@link ConstraintToggleTest}).
 * Ce test-ci couvre les 39 contraintes, là où {@link ConstraintToggleTest}
 * vérifie le mécanisme lui-même sur un représentant par famille — les deux se
 * complètent, aucun ne remplace l'autre.</p>
 */
class ConstraintToggleStructurelleTest {

    private static final Path FAMILLES =
            Path.of("src/main/java/dev/sylvain/planning/solver/constraints");

    /** {@code asConstraint("nom")} — la déclaration de la contrainte. */
    private static final Pattern DECLARATION = Pattern.compile("asConstraint\\(\"([A-Za-z0-9_]+)\"\\)");

    /** Début d'un appel à {@code actif(…)} ; l'argument fermant est extrait à la main. */
    private static final Pattern ENVELOPPE = Pattern.compile("\\bactif\\(");

    /** Dernier littéral chaîne d'un appel, c'est-à-dire le nom passé à {@code actif}. */
    private static final Pattern DERNIER_LITTERAL = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*$");

    private static List<Path> familles() throws IOException {
        try (Stream<Path> fichiers = Files.list(FAMILLES)) {
            return fichiers.filter(f -> f.getFileName().toString().endsWith("Constraints.java")).sorted().toList();
        }
    }

    /** Les noms passés à {@code actif(…)}, en équilibrant les parenthèses de l'appel. */
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
            Matcher nom = DERNIER_LITTERAL.matcher(source.substring(debut.end(), i - 1).stripTrailing());
            if (nom.find()) {
                noms.add(nom.group(1));
            }
        }
        return noms;
    }

    private static List<String> nomsDeclares(String source) {
        return DECLARATION.matcher(source).results().map(r -> r.group(1)).sorted().toList();
    }

    /**
     * Toute contrainte déclarée est éteignable, et rien n'est éteignable qui ne
     * soit déclaré — sinon l'interrupteur pointe dans le vide.
     */
    @Test
    void chaqueContrainteDeclareeEstEteignableSousExactementLeMemeNom() throws IOException {
        for (Path famille : familles()) {
            String source = Files.readString(famille);

            assertThat(nomsEnveloppes(source).stream().sorted().toList())
                    .as("noms passés à ConstraintToggleSupport.actif dans %s", famille.getFileName())
                    .containsExactlyElementsOf(nomsDeclares(source));
        }
    }

    /**
     * Et le compte y est : les contraintes trouvées dans les sources sont
     * exactement celles que l'IHM propose d'éteindre.
     */
    @Test
    void lesContraintesEteignablesSontExactementCellesDuCatalogue() throws IOException {
        List<String> eteignables = new ArrayList<>();
        for (Path famille : familles()) {
            eteignables.addAll(nomsEnveloppes(Files.readString(famille)));
        }

        assertThat(eteignables.stream().sorted().toList())
                .containsExactlyElementsOf(ConstraintCatalog.definitions().stream()
                        .map(ConstraintCatalog.ConstraintDefinition::name)
                        .sorted()
                        .toList());
    }
}
