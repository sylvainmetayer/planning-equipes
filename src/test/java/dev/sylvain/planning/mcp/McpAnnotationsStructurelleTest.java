package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.Tool;

/**
 * Every tool must say what it does to the data, and say it consistently with
 * its own name.
 *
 * <p>The MCP defaults are the reason this matters: an undeclared tool is
 * advertised as {@code destructiveHint = true} and {@code openWorldHint =
 * true}. Left alone, {@code lister_stands} claimed to be a destructive call
 * onto the open internet — so a client that gates destructive tools gated
 * every single read, and the hint stopped carrying information.</p>
 *
 * <h2>How a missing declaration is caught</h2>
 *
 * <p>An annotation left out is indistinguishable from one written with the
 * default values, so there is nothing to detect directly.
 * {@code openWorldHint = false} is what makes it detectable: it is true of
 * every tool here — none of them reaches beyond this application's own
 * database — and it differs from the default, so a tool that forgot the block
 * keeps {@code true} and fails.</p>
 *
 * <h2>How a wrong declaration is caught</h2>
 *
 * <p>The expected hints are derived from the tool's <b>name</b>, and compared
 * with what it declares. That is not a restatement of the annotation: it is
 * the tool's own name disagreeing with it — {@code supprimer_stand} declaring
 * itself read-only, or a new {@code lister_*} copy-pasted from a write. A name
 * this test cannot classify fails too, so a new tool cannot slip through by
 * being unlike anything already there.</p>
 */
class McpAnnotationsStructurelleTest {

    /**
     * Tools that read and never write. Refreshing the in-memory analysis a
     * diagnostic reads back is not a write: it is a pure function of the plan
     * the tool just read, and no business data changes.
     */
    private static final List<String> LECTURE = List.of(
            "lister_", "consulter_", "previsualiser_", "diagnostiquer_", "valider_", "expliquer_",
            "analyser_", "comparer_", "simuler_", "statut_", "resultats_", "heures_", "etat_",
            "synthese_", "suggerer_", "volumes", "edition_courante");

    /**
     * Tools that overwrite or drop something the user cannot get back by
     * calling the tool again. The solves are in: each one replaces the
     * persisted plan, which is the very risk snapshots exist for.
     */
    private static final List<String> DESTRUCTION = List.of(
            "supprimer_", "effacer_", "reinitialiser_", "importer_", "restaurer_",
            "deverrouiller", "generer_decoupage", "lancer_solveur", "resoudre_incremental");

    /** Tools that write without destroying: creations, edits, toggles, locks. */
    private static final List<String> ECRITURE = List.of(
            "creer_", "modifier_", "ajouter_", "activer_", "desactiver_", "dupliquer_",
            "renommer_", "definir_", "verrouiller", "capturer_", "arreter_", "affecter_",
            "configurer_", "appliquer_", "accepter_", "refuser_", "publier_", "envoyer_",
            "compacter_");

    /**
     * The tools that leave the building, and the only ones allowed to declare
     * {@code openWorldHint = true}: they send mail. Everything else answers
     * out of this application's own database.
     *
     * <p>Listing them rather than deriving them from the name is the point:
     * an outgoing mail is a decision somebody has to take deliberately, and
     * adding a tool to this list is that decision, reviewed. A tool that
     * forgot its annotation block is still caught, since the same block also
     * carries {@code destructiveHint}, which its name is checked against
     * above.</p>
     */
    private static final List<String> SORTIE_EXTERIEURE = List.of(
            "publier_planning", "envoyer_planning_animateur", "configurer_collecte_disponibilites");

    @Test
    void chaqueOutilDeclareSesAnnotations() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (correspond(outil.getName(), SORTIE_EXTERIEURE)) {
                continue;
            }
            assertThat(annotations(outil).openWorldHint())
                    .as("l'outil %s doit déclarer @Tool.Annotations : sans le bloc, il est annoncé destructif"
                            + " et ouvert sur le monde extérieur", outil.getName())
                    .isFalse();
        }
    }

    /**
     * Sending mail is the one thing a tool here can do outside the database,
     * and it is claimed from both ends: a mail-sending tool must say so, and
     * nothing else may.
     */
    @Test
    void seulsLesOutilsQuiEnvoientDuCourrielSortentDeLApplication() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            assertThat(annotations(outil).openWorldHint())
                    .as("openWorldHint de %s : vrai pour les seuls outils qui envoient du courriel"
                            + " (voir SORTIE_EXTERIEURE)", outil.getName())
                    .isEqualTo(correspond(outil.getName(), SORTIE_EXTERIEURE));
        }
    }

    @Test
    void lesAnnotationsSaccordentAvecLeNomDeLoutil() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            String nom = outil.getName();
            Tool.Annotations declarees = annotations(outil);
            if (correspond(nom, LECTURE)) {
                assertThat(declarees.readOnlyHint())
                        .as("%s lit et n'écrit rien : readOnlyHint attendu vrai", nom)
                        .isTrue();
                assertThat(declarees.destructiveHint())
                        .as("%s est en lecture seule : destructiveHint attendu faux", nom)
                        .isFalse();
            } else if (correspond(nom, DESTRUCTION)) {
                assertThat(declarees.readOnlyHint()).as("%s écrit : readOnlyHint attendu faux", nom).isFalse();
                assertThat(declarees.destructiveHint())
                        .as("%s écrase ou supprime : destructiveHint attendu vrai", nom)
                        .isTrue();
            } else if (correspond(nom, ECRITURE)) {
                assertThat(declarees.readOnlyHint()).as("%s écrit : readOnlyHint attendu faux", nom).isFalse();
                assertThat(declarees.destructiveHint())
                        .as("%s écrit sans rien détruire : destructiveHint attendu faux", nom)
                        .isFalse();
            } else {
                throw new AssertionError("Outil non classé : " + nom + ". Ajouter son nom (ou son préfixe) à "
                        + "LECTURE, ECRITURE ou DESTRUCTION dans " + getClass().getSimpleName()
                        + ", puis déclarer les mêmes hints dans son @Tool.");
            }
        }
    }

    /**
     * Read-only is the claim a client trusts to skip a confirmation, so it is
     * checked from both ends: nothing outside {@link #LECTURE} may claim it,
     * whatever its name looks like.
     */
    @Test
    void aucunOutilDEcritureNeSeDeclareEnLectureSeule() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (annotations(outil).readOnlyHint()) {
                assertThat(correspond(outil.getName(), LECTURE))
                        .as("%s se déclare en lecture seule sans être reconnu comme un outil de lecture",
                                outil.getName())
                        .isTrue();
            }
        }
    }

    private static Tool.Annotations annotations(Method outil) {
        return outil.getAnnotation(Tool.class).annotations();
    }

    private static boolean correspond(String nom, List<String> motifs) {
        return motifs.stream().anyMatch(motif -> motif.endsWith("_") ? nom.startsWith(motif) : nom.equals(motif));
    }
}
