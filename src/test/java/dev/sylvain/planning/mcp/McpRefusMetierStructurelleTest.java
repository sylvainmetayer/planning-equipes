package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The two halves of issue #529, each held where it can be forgotten.
 *
 * <p>A refusal from the domain used to leave over MCP as
 * {@code {"code": -32603, "message": "Internal error"}}: the sentence saying
 * what was refused, and often how to fix it, never reached the assistant.
 * {@code RefusMetierInterceptor} now carries it — <b>as is</b>, which is the
 * arbitration the issue asked for, and which only holds on two conditions.
 * This test is those two conditions:</p>
 *
 * <ul>
 *   <li>every class declaring a {@code @Tool} carries {@code @RefusMetier},
 *       so a tool family added later does not go back to « Internal error »
 *       by omission — the failure mode {@code @Journalise} already had to be
 *       split out of {@code @EditionCiblee} to close;</li>
 *   <li>no {@code BusinessError} is built from an animateur's nom, prénom,
 *       date de naissance or adresse, so relaying its message cannot leak a
 *       name (issue #107). {@code PlanningDeliveryService} was the one place
 *       that did — « <i>Prénom Nom</i> n'a pas d'adresse e-mail sur sa fiche »,
 *       worded for a screen showing the fiche — and
 *       {@code envoyer_planning_animateur} replaced that sentence by hand,
 *       tool by tool. Wording it by id at the source is what makes the
 *       transverse relay safe and that workaround unnecessary.</li>
 * </ul>
 *
 * <p>Deliberately a source scan rather than a list of exemptions: the refusals
 * number in the hundreds and are written in passing, which is exactly when the
 * rule gets forgotten. It under-detects rather than crying wolf — a floor, not
 * a proof, like {@code LanguagePolicyStructuralTest}.</p>
 */
class McpRefusMetierStructurelleTest {

    private static final Path BACKEND = Path.of("src/main/java/dev/sylvain/planning");

    /**
     * Accessors that read a personal field. {@code getNom()} alone is not one
     * of them — a stand, a typologie and a journée type all have a nom, and
     * naming those in a refusal is the point — so it counts only when the
     * receiver says animateur.
     */
    private static final List<String> ACCESSEURS_PERSONNELS =
            List.of("nomAffiche(", "getPrenom(", "getNom(", "getDateNaissance(", "getEmail(");

    private static final Pattern NOM_D_ANIMATEUR = Pattern.compile("(?i)animateur\\w*\\s*\\.\\s*getNom\\s*\\(");

    @Test
    void chaqueClasseDOutilsPorteLeBindingDuRefus() throws Exception {
        Set<String> sansBinding = new LinkedHashSet<>();
        for (var outil : OutilsMcp.all()) {
            if (!outil.getDeclaringClass().isAnnotationPresent(RefusMetier.class)) {
                sansBinding.add(outil.getDeclaringClass().getSimpleName());
            }
        }
        assertThat(sansBinding)
                .as("classes d'outils dont les refus repartiraient en « Internal error » — il leur manque @RefusMetier")
                .isEmpty();
    }

    @Test
    void aucunRefusMetierNeNommeUnePersonne() throws IOException {
        List<String> fautifs = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(BACKEND)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                for (String refus : refusals(Files.readString(fichier))) {
                    if (namesSomebody(refus)) {
                        fautifs.add(fichier.getFileName() + " : " + refus.strip());
                    }
                }
            }
        }
        assertThat(fautifs)
                .as("refus qui porteraient un nom d'animateur jusqu'à l'assistant : les désigner par leur id")
                .isEmpty();
    }

    /** Every {@code new BusinessError.…(…)} expression, read up to the end of its statement. */
    private static List<String> refusals(String source) {
        List<String> expressions = new ArrayList<>();
        int index = source.indexOf(CONSTRUCTEUR);
        while (index >= 0) {
            int fin = source.indexOf(';', index);
            expressions.add(source.substring(index, fin < 0 ? source.length() : fin));
            index = source.indexOf(CONSTRUCTEUR, index + CONSTRUCTEUR.length());
        }
        return expressions;
    }

    private static boolean namesSomebody(String expression) {
        return ACCESSEURS_PERSONNELS.stream()
                .anyMatch(accesseur -> accesseur.equals("getNom(")
                        ? NOM_D_ANIMATEUR.matcher(expression).find()
                        : expression.contains(accesseur));
    }

    private static final String CONSTRUCTEUR = "new BusinessError.";
}
