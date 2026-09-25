package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkiverse.mcp.server.Tool;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every MCP write tool answers the question « and if a solve is running? » —
 * and the answer is written down, never left to omission.
 *
 * <p>Three answers exist. The write is <b>refused</b> in {@code 409} while a
 * solve holds its edition ({@code SolverJobService.refuseIfSolving}, on the
 * writes whose data the landing would bring back); it is <b>accepted and
 * warned about</b> ({@link WarnsWhileSolving}, the answer then carries
 * {@link WarningCodes#RESOLUTION_EN_COURS}); or it has <b>nothing to do with a
 * solve</b>, and says why. A write tool in none of the three fails the build:
 * an assistant must never write during a solve without being told the result
 * will not reflect it merely because nobody thought of the case.</p>
 *
 * <p>The refused list names the service methods holding the guard, and that
 * statement is read back in their source; the three lists are disjoint and
 * exhaustive, so moving a tool from one to another is a line somebody
 * reviews.</p>
 */
class McpWarnsWhileSolvingStructuralTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    /**
     * Refused in {@code 409} while a solve holds the edition, with the service
     * methods the tool goes through that hold the guard — checked in their
     * source by {@link #eachRefusedToolNamesMethodsThatHoldTheGuard}.
     */
    private static final Map<String, String> REFUSED = Map.ofEntries(
            Map.entry("modifier_animateur", "AnimateurService.update"),
            Map.entry("supprimer_animateur", "AnimateurService.delete"),
            Map.entry("appliquer_declaration_disponibilite", "AnimateurService.update"),
            Map.entry("modifier_stand", "StandService.update"),
            Map.entry("ajouter_fermeture_stand", "StandService.update"),
            Map.entry("ajouter_ouverture_stand", "StandService.update"),
            Map.entry("effacer_plages_stand", "StandService.update"),
            Map.entry("ajouter_horaire_stand", "StandService.update"),
            Map.entry("effacer_horaires_stand", "StandService.update"),
            Map.entry("supprimer_stand", "StandService.delete"),
            Map.entry("compacter_horaires_stands", "StandService.compactHoraires"),
            Map.entry("supprimer_creneau", "CreneauService.delete"),
            Map.entry("supprimer_creneaux", "CreneauService.deleteInBulk"),
            Map.entry("creer_creneaux_recurrents", "CreneauService.createInBulk"),
            Map.entry("generer_creneaux_depuis_stands", "CreneauService.replace, CreneauService.createInBulk"),
            Map.entry("modifier_creneau", "CreneauService.update"),
            Map.entry("affecter_poste", "PlanningPersistenceService.reaffecterPoste"),
            Map.entry(
                    "accepter_demande_echange",
                    "DemandeEchangeService.accept, PlanningPersistenceService.applyEchange,"
                            + " PlanningPersistenceService.applyDirectedEchange"),
            Map.entry("materialiser_journees_types", "JourneeTypeService.apply"),
            Map.entry("appliquer_consigne", "ConsigneService.poser"),
            Map.entry("lever_consigne", "ConsigneService.lever"),
            Map.entry("deplacer_affectation", "DeplacementService.apply"),
            Map.entry("restaurer_instantane", "PlanSnapshotService.restaurer"),
            Map.entry("importer_scenario", "ReferenceDataService.importFromPlanning"),
            Map.entry("importer_scenario_yaml", "ReferenceDataService.importFromPlanning"),
            Map.entry("reinitialiser_donnees", "PlanningPersistenceService.clearDatabase"));

    /** Writes a solve never reads, each with the reason it has nothing to warn about. */
    private static final Map<String, String> UNRELATED = Map.ofEntries(
            Map.entry("lancer_solveur", "le solveur lui-même : mis en file quand un calcul tient déjà le solveur"),
            Map.entry(
                    "resoudre_incremental", "le solveur lui-même : mis en file quand un calcul tient déjà le solveur"),
            Map.entry("arreter_solveur", "arrête le calcul en cours, il ne l'ignore pas"),
            Map.entry("supprimer_job", "retire une résolution de la file ou de l'historique"),
            Map.entry("definir_prereglage_consigne", "un préréglage n'est lu que par le formulaire de consigne"),
            Map.entry("supprimer_prereglage_consigne", "un préréglage n'est lu que par le formulaire de consigne"),
            Map.entry(
                    "retirer_validation_journee",
                    "l'atterrissage ne fait que retirer des validations : il ne peut ni défaire ce retrait, ni"
                            + " rendre la journée moins à relire"),
            Map.entry(
                    "definir_journee_type",
                    "une journée type n'atteint la grille que par materialiser_journees_types, refusé pendant un calcul"),
            Map.entry(
                    "supprimer_journee_type",
                    "une journée type n'atteint la grille que par materialiser_journees_types, refusé pendant un calcul"),
            Map.entry(
                    "affecter_journee_type",
                    "le calendrier des journées types n'atteint la grille que par materialiser_journees_types"),
            Map.entry(
                    "retirer_dates_journee_type",
                    "le calendrier des journées types n'atteint la grille que par materialiser_journees_types"),
            Map.entry(
                    "reconnaitre_journees_types",
                    "réécrit les journées types d'après la grille, sans toucher la grille"),
            Map.entry("configurer_collecte_disponibilites", "la fenêtre de collecte n'est pas lue par le calcul"),
            Map.entry("refuser_declaration_disponibilite", "un refus ne modifie aucune fiche"),
            Map.entry("configurer_foire_echanges", "la fenêtre de la foire n'est pas lue par le calcul"),
            Map.entry("refuser_demande_echange", "un refus ne modifie ni le plan ni le référentiel"),
            Map.entry("capturer_instantane", "copie le plan enregistré, que le calcul ne lit pas en retour"),
            Map.entry("supprimer_instantane", "un instantané n'est pas lu par le calcul"),
            Map.entry("supprimer_kpi_historique", "l'historique des indicateurs n'est pas lu par le calcul"),
            Map.entry("creer_edition", "agit sur les éditions entières, pas dans celle qu'un calcul tient"),
            Map.entry("dupliquer_edition", "agit sur les éditions entières, pas dans celle qu'un calcul tient"),
            Map.entry("renommer_edition", "un nom d'édition n'est pas lu par le calcul"),
            Map.entry("definir_edition_par_defaut", "désigne une édition, n'en modifie aucune"),
            Map.entry("supprimer_edition", "agit sur les éditions entières, pas dans celle qu'un calcul tient"),
            Map.entry("publier_planning", "publie le plan enregistré, n'écrit rien que le calcul lise"),
            Map.entry("envoyer_planning_animateur", "envoie le plan enregistré, n'écrit rien que le calcul lise"),
            Map.entry("relancer_animateurs", "envoie des relances, n'écrit rien que le calcul lise"),
            Map.entry("modifier_parametres_notifications", "lus par les envois de nuit, jamais par le calcul"),
            Map.entry("modifier_sauvegardes", "réglage de la base entière, jamais lu par le calcul"));

    @Test
    void everyWriteToolSaysWhatHappensDuringASolve() throws Exception {
        List<String> unclassified = new ArrayList<>();
        for (Method tool : writeTools()) {
            String name = FeatureNames.of(tool);
            int answers = (tool.isAnnotationPresent(WarnsWhileSolving.class) ? 1 : 0)
                    + (REFUSED.containsKey(name) ? 1 : 0)
                    + (UNRELATED.containsKey(name) ? 1 : 0);
            if (answers != 1) {
                unclassified.add(name + " (" + answers + " réponses)");
            }
        }
        assertThat(unclassified)
                .as("chaque outil d'écriture est soit refusé pendant un calcul (REFUSED), soit annoté"
                        + " @WarnsWhileSolving, soit exclu avec son motif (UNRELATED) — et un seul des trois")
                .isEmpty();
    }

    /**
     * The refused list is a statement about the services; it is read back in
     * their source, so a guard removed from a method named here fails the
     * build instead of turning a refused write into a silent one.
     */
    @Test
    void eachRefusedToolNamesMethodsThatHoldTheGuard() throws IOException {
        List<String> unguarded = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(REFUSED).entrySet()) {
            for (String method : entry.getValue().split(",\\s*")) {
                String[] parts = method.trim().split("\\.");
                if (!methodBodies(parts[0], parts[1]).stream().anyMatch(body -> body.contains("refuseIfSolving("))) {
                    unguarded.add(entry.getKey() + " → " + method.trim());
                }
            }
        }
        assertThat(unguarded)
                .as("chaque méthode nommée dans REFUSED appelle solverJobs.refuseIfSolving()")
                .isEmpty();
    }

    /** The bodies of every method {@code method} of {@code className}, overloads included. */
    private static List<String> methodBodies(String className, String method) throws IOException {
        Path file;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            file = files.filter(path -> path.getFileName().toString().equals(className + ".java"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("classe introuvable : " + className));
        }
        String source = Files.readString(file);
        List<String> bodies = new ArrayList<>();
        Matcher declaration = Pattern.compile("\\n    public [\\w<>, .?]+ " + Pattern.quote(method) + "\\(")
                .matcher(source);
        while (declaration.find()) {
            int index = declaration.end();
            int depth = 1;
            while (depth > 0) {
                char c = source.charAt(index++);
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            }
            int open = source.indexOf('{', index);
            int end = open + 1;
            depth = 1;
            while (depth > 0) {
                char c = source.charAt(end++);
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            }
            bodies.add(source.substring(open, end));
        }
        assertThat(bodies).as("méthode introuvable : %s.%s", className, method).isNotEmpty();
        return bodies;
    }

    /**
     * {@code creer_typologie} and {@code modifier_typologie} answer a view
     * instead of the service's {@code TypologieItem}: it must say the same
     * thing under the same keys, the warnings aside — left out when empty.
     */
    @Test
    void theTypologieViewHasTheKeysOfTheTypologieItem() {
        List<String> item = Arrays.stream(TypologieItem.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> view = Arrays.stream(StandMcpTools.TypologieView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> !name.equals("avertissements"))
                .toList();
        assertThat(view).isEqualTo(item);
        JsonInclude include = StandMcpTools.TypologieView.class
                .getRecordComponents()[item.size()]
                .getAccessor()
                .getAnnotation(JsonInclude.class);
        assertThat(include).as("avertissements absent quand vide").isNotNull();
        assertThat(include.value()).isEqualTo(JsonInclude.Include.NON_EMPTY);
    }

    /** An annotation on an answer that cannot carry the code would warn nobody, silently. */
    @Test
    void anAnnotatedToolAnswersWithAWarningCarrier() throws Exception {
        List<String> mute = OutilsMcp.all().stream()
                .filter(tool -> tool.isAnnotationPresent(WarnsWhileSolving.class))
                .filter(tool -> !WarningCarrier.class.isAssignableFrom(tool.getReturnType()))
                .map(tool ->
                        FeatureNames.of(tool) + " → " + tool.getReturnType().getSimpleName())
                .toList();
        assertThat(mute)
                .as("un outil @WarnsWhileSolving renvoie un WarningCarrier, sinon l'avertissement se perd")
                .isEmpty();
    }

    /** Reads never warn: the annotation on a read tool would be a statement nobody can check. */
    @Test
    void onlyWriteToolsWarn() throws Exception {
        Set<String> writes = writeTools().stream().map(FeatureNames::of).collect(Collectors.toSet());
        List<String> reads = OutilsMcp.all().stream()
                .filter(tool -> tool.isAnnotationPresent(WarnsWhileSolving.class))
                .map(FeatureNames::of)
                .filter(name -> !writes.contains(name))
                .toList();
        assertThat(reads).isEmpty();
    }

    /** A classification naming a tool that no longer exists reads as a decision; it is stale. */
    @Test
    void theListsNameExistingTools() throws Exception {
        Set<String> tools = OutilsMcp.all().stream().map(FeatureNames::of).collect(Collectors.toSet());
        Set<String> stale = new TreeSet<>(REFUSED.keySet());
        stale.addAll(UNRELATED.keySet());
        stale.removeAll(tools);
        assertThat(stale).isEmpty();
    }

    private static List<Method> writeTools() throws Exception {
        return OutilsMcp.all().stream()
                .filter(tool -> !tool.getAnnotation(Tool.class).annotations().readOnlyHint())
                .toList();
    }
}
