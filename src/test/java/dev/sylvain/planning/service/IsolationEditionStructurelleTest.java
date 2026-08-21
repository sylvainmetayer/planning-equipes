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
 * Tout est cloisonné par édition (voir {@code docs/editions.md}), et une
 * requête qui oublie son prédicat {@code edition_id} ne se voit pas : elle ne
 * lève rien, elle lit ou écrit simplement chez les voisins.
 *
 * <p>C'est arrivé. La rétrogradation de la typologie ninja s'écrivait
 * {@code UPDATE typologie SET ninja = FALSE WHERE ninja AND id <> ?}, sans
 * édition, alors que l'index unique qu'elle protège est scopé par édition
 * depuis {@code V33} : marquer un ninja sur une édition effaçait celui de
 * toutes les autres, et comme le drapeau alimente l'ordonnancement de la
 * construction heuristique, le solve suivant de l'édition voisine partait
 * ailleurs sans que rien ne le signale.</p>
 *
 * <p>Ce test lit le SQL de tout le backend et refuse une requête visant une
 * table métier sans prédicat d'édition. La liste d'exceptions est courte et
 * chacune est justifiée ici : c'est le seul endroit où « cette requête est
 * volontairement inter-éditions » est une affirmation vérifiée plutôt qu'un
 * commentaire.</p>
 */
class IsolationEditionStructurelleTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    /** Toute table portant une colonne {@code edition_id} (migrations V33/V36). */
    private static final List<String> TABLES_METIER = List.of(
            "stand", "animateur", "creneau", "emplacement", "typologie",
            "contrainte_ad_hoc", "contrainte_animateur", "constraint_toggle", "verrouillage_planning",
            "parametres_legaux", "parametres_decoupage", "parametres_solveur",
            "poste_affectation", "planning_resolution", "stand_typologie",
            "animateur_competence", "animateur_souhait", "animateur_indisponibilite",
            "stand_indisponibilite", "stand_ouverture", "stand_horaire", "stand_horaire_fenetre",
            "demande_echange", "espace_session", "espace_code", "plan_snapshot", "creneau_remap");

    /**
     * Les deux requêtes délibérément inter-éditions, et pourquoi.
     *
     * <ul>
     *   <li>Le jeton d'espace animateur arrive sur une URL publique, sans
     *       {@code X-Edition-Id} à croire : il est globalement unique
     *       précisément pour désigner l'édition tout seul, l'appelant
     *       enchaînant dans {@code EditionContext.executeDans}.</li>
     *   <li>La collision d'adresse e-mail est vérifiée au démarrage du mode
     *       « en-tête de confiance », qui n'a aucune édition à considérer et
     *       veut savoir si la collision existe où que ce soit.</li>
     * </ul>
     */
    private static final List<String> EXCEPTIONS_ASSUMEES = List.of(
            "SELECT edition_id, id, email FROM animateur WHERE jeton_acces = ?",
            "SELECT 1 FROM animateur WHERE lower(email) = lower(?) LIMIT 1");

    private static final Pattern APPEL = Pattern.compile("prepare(?:Statement|Scoped)\\(");
    private static final Pattern LITTERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern COLONNES_INSERT =
            Pattern.compile("insert\\s+into\\s+\\w+\\s*\\(([^)]*)\\)");

    /** Le SQL de chaque appel, littéraux concaténés recollés. */
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
                sql.append(litteral.group(1)).append(' ');
            }
            if (!sql.isEmpty()) {
                enonces.add(sql.toString().replaceAll("\\s+", " ").trim());
            }
        }
        return enonces;
    }

    /**
     * L'édition doit être <b>filtrée</b>, pas seulement lue : {@code SELECT
     * edition_id … WHERE jeton = ?} ramène la colonne sans cloisonner quoi que
     * ce soit.
     */
    private static boolean estScopee(String sql) {
        String bas = sql.toLowerCase(Locale.ROOT);
        if (bas.contains("edition_id = ?")) {
            return true;
        }
        Matcher colonnes = COLONNES_INSERT.matcher(bas);
        return colonnes.find() && colonnes.group(1).contains("edition_id");
    }

    private static boolean viseUneTableMetier(String sql) {
        String bas = sql.toLowerCase(Locale.ROOT);
        return TABLES_METIER.stream().anyMatch(table -> bas.matches(".*\\b" + table + "\\b.*"));
    }

    @Test
    void touteRequeteSurUneTableMetierPorteSonPredicatDEdition() throws IOException {
        List<String> manquants = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String sql : enonces(Files.readString(fichier))) {
                    if (estScopee(sql) || !viseUneTableMetier(sql) || EXCEPTIONS_ASSUMEES.contains(sql)) {
                        continue;
                    }
                    manquants.add(fichier.getFileName() + " : " + sql);
                }
            }
        }

        assertThat(manquants)
                .as("requêtes visant une table métier sans prédicat edition_id — soit il manque, "
                        + "soit l'omission est délibérée et sa raison doit rejoindre EXCEPTIONS_ASSUMEES")
                .isEmpty();
    }

    /**
     * Le test ci-dessus ne vaut que s'il regarde vraiment du SQL : une
     * expression rationnelle qui ne trouverait plus rien passerait au vert
     * pour la pire des raisons.
     */
    @Test
    void leScanTrouveBienLeSqlDuBackend() throws IOException {
        int enonces = 0;
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                enonces += enonces(Files.readString(fichier)).size();
            }
        }

        assertThat(enonces)
                .as("nombre de requêtes préparées trouvées dans le backend")
                .isGreaterThan(80);
    }

    /** Une exception qui n'existe plus dans le code doit sortir de la liste. */
    @Test
    void chaqueExceptionAssumeeCorrespondAUneRequeteReelle() throws IOException {
        List<String> toutes = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                toutes.addAll(enonces(Files.readString(fichier)));
            }
        }

        assertThat(toutes).containsAll(EXCEPTIONS_ASSUMEES);
    }
}
