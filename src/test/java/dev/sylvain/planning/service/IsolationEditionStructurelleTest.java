package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Everything is partitioned by edition (see {@code docs/editions.md}), and a
 * statement that forgets its {@code edition_id} predicate does not show: it
 * throws nothing, it simply reads or writes at the neighbours'.
 *
 * <p>It happened. Demoting the ninja typologie was written
 * {@code UPDATE typologie SET ninja = FALSE WHERE ninja AND id <> ?}, with no
 * edition, while the unique index it protects has been scoped per edition since
 * {@code V33}: marking a ninja on one edition erased the ninja of every other
 * one, and since that flag feeds the ordering of the construction heuristic,
 * the next solve of the neighbouring edition started somewhere else without
 * anything reporting it.</p>
 *
 * <p>This test reads the SQL of the whole backend and refuses a statement
 * aiming at a business table without an edition predicate. The list of
 * exceptions is short and each one is justified here: it is the only place
 * where "this statement is deliberately cross-edition" is a verified claim
 * rather than a comment.</p>
 */
class IsolationEditionStructurelleTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    /** Every table carrying an {@code edition_id} column (migrations V33/V36). */
    private static final List<String> TABLES_METIER = List.of(
            "stand", "animateur", "creneau", "emplacement", "typologie",
            "contrainte_ad_hoc", "contrainte_animateur", "constraint_toggle", "verrouillage_planning",
            "parametres_legaux", "parametres_decoupage", "parametres_solveur",
            "poste_affectation", "planning_resolution", "stand_typologie",
            "animateur_competence", "animateur_souhait", "animateur_indisponibilite",
            "stand_indisponibilite", "stand_ouverture", "stand_horaire", "stand_horaire_fenetre",
            "demande_echange", "espace_session", "espace_code", "plan_snapshot", "creneau_remap");

    /**
     * The two deliberately cross-edition statements, and why.
     *
     * <ul>
     *   <li>The espace animateur token arrives on a public URL, with no
     *       {@code X-Edition-Id} to believe: it is globally unique precisely so
     *       it can name the edition on its own, the caller carrying on inside
     *       {@code EditionContext.executeIn}.</li>
     *   <li>The e-mail address collision is checked when the "trusted header"
     *       mode boots, which has no edition to consider and wants to know
     *       whether the collision exists anywhere at all.</li>
     * </ul>
     */
    private static final List<String> EXCEPTIONS_ASSUMEES = List.of(
            "SELECT edition_id, id, email FROM animateur WHERE jeton_acces = ?",
            "SELECT 1 FROM animateur WHERE lower(email) = lower(?) LIMIT 1");

    private static final Pattern APPEL = Pattern.compile("prepare(?:Statement|Scoped)\\(");

    /**
     * A text block <b>or</b> a plain literal — both forms live side by side:
     * long SQL is passed as a text block, one-line statements stay literals.
     */
    private static final Pattern LITTERAL =
            Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern COLONNES_INSERT =
            Pattern.compile("insert\\s+into\\s+\\w+\\s*\\(([^)]*)\\)");

    /** The SQL of every call, concatenated literals glued back together. */
    private static List<String> enonces(String source) {
        List<String> enonces = new ArrayList<>();
        Matcher appel = APPEL.matcher(source);
        while (appel.find()) {
            int i = appel.end();
            int profondeur = 1;
            while (profondeur > 0 && i < source.length()) {
                char c = source.charAt(i++);
                if (c == '(') {
                    profondeur++;
                } else if (c == ')') {
                    profondeur--;
                }
            }
            StringBuilder sql = new StringBuilder();
            Matcher litteral = LITTERAL.matcher(source.substring(appel.end(), i - 1));
            while (litteral.find()) {
                String morceau = litteral.group(1) != null ? litteral.group(1) : litteral.group(2);
                sql.append(morceau).append(' ');
            }
            if (!sql.isEmpty()) {
                enonces.add(sql.toString().replaceAll("\\s+", " ").trim());
            }
        }
        return enonces;
    }

    /**
     * The edition must be <b>filtered on</b>, not merely read: {@code SELECT
     * edition_id … WHERE token = ?} brings the column back without partitioning
     * anything at all.
     */
    private static boolean isScoped(String sql) {
        String bas = sql.toLowerCase(Locale.ROOT);
        if (bas.contains("edition_id = ?")) {
            return true;
        }
        Matcher colonnes = COLONNES_INSERT.matcher(bas);
        return colonnes.find() && colonnes.group(1).contains("edition_id");
    }

    private static boolean targetsBusinessTable(String sql) {
        String bas = sql.toLowerCase(Locale.ROOT);
        return TABLES_METIER.stream().anyMatch(table -> bas.matches(".*\\b" + table + "\\b.*"));
    }

    @Test
    void touteRequeteSurUneTableMetierPorteSonPredicatDEdition() throws IOException {
        List<String> manquants = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String sql : enonces(Files.readString(file))) {
                    if (isScoped(sql) || !targetsBusinessTable(sql) || EXCEPTIONS_ASSUMEES.contains(sql)) {
                        continue;
                    }
                    manquants.add(file.getFileName() + " : " + sql);
                }
            }
        }

        assertThat(manquants)
                .as("requêtes visant une table métier sans prédicat edition_id — soit il manque, "
                        + "soit l'omission est délibérée et sa raison doit rejoindre EXCEPTIONS_ASSUMEES")
                .isEmpty();
    }

    /**
     * The test above is only worth what its scan is worth: a regular expression
     * that stopped matching anything would turn green for the worst possible
     * reason.
     */
    @Test
    void leScanTrouveBienLeSqlDuBackend() throws IOException {
        int enonces = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                enonces += enonces(Files.readString(file)).size();
            }
        }

        assertThat(enonces)
                .as("nombre de requêtes préparées trouvées dans le backend")
                .isGreaterThan(80);
    }

    /** An exception that no longer exists in the code must leave the list. */
    @Test
    void chaqueExceptionAssumeeCorrespondAUneRequeteReelle() throws IOException {
        List<String> toutes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                toutes.addAll(enonces(Files.readString(file)));
            }
        }

        assertThat(toutes).containsAll(EXCEPTIONS_ASSUMEES);
    }
}
