package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.LigneTypologie;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.RapportTypologies;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The plan read by typologie of jeu (issue #590). */
class TypologieAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 8, 14);

    private static Animateur animateur(String id, String prenom, Map<String, NiveauCompetence> competences) {
        Animateur animateur = new Animateur(id, prenom, "Martin", LocalDate.of(1990, 1, 1), false);
        animateur.setCompetences(competences);
        return animateur;
    }

    private static PosteAffectation poste(String id, Stand stand, Animateur qui, int debut, int fin) {
        Creneau creneau = new Creneau((long) id.hashCode(), 1, JOUR, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(qui);
        return poste;
    }

    private static LigneTypologie ligne(RapportTypologies rapport, String typologie) {
        return rapport.typologies().stream()
                .filter(candidate -> candidate.typologie().equals(typologie))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + typologie));
    }

    @Test
    void compteLesAnimateursAffectesLesPostesEtLesHeuresParTypologie() {
        Stand strategie = new Stand("S1", "Stand stratégie", Set.of("STRATEGIE"), 1, 2, false);
        Animateur ada = animateur("A1", "Ada", Map.of("STRATEGIE", NiveauCompetence.AUTONOME));
        Animateur bob = animateur("A2", "Bob", Map.of("STRATEGIE", NiveauCompetence.AUTONOME));

        RapportTypologies rapport = TypologieAnalyzer.compute(
                new PlanningEvenement(
                        JOUR,
                        List.of(ada, bob),
                        List.of(poste("P1", strategie, ada, 9, 13), poste("P2", strategie, bob, 14, 18))),
                List.of(new TypologieItem("STRATEGIE", "Stratégie")),
                List.of(ada, bob));

        LigneTypologie ligne = ligne(rapport, "STRATEGIE");
        assertThat(noms(ligne.animateursAffectes())).containsExactly("Ada Martin", "Bob Martin");
        assertThat(ligne.postes()).isEqualTo(2);
        assertThat(ligne.heures()).isCloseTo(8.0, within(0.01));
    }

    /**
     * A stand proposing several typologies makes its seat count for each of
     * them — the same reading as the quota rule (ADR 0042): somebody holds the
     * game they are sat at, whether or not their fiche mentions it.
     */
    @Test
    void unPosteComptePourChaqueTypologieDeSonStand() {
        Stand mixte = new Stand("S2", "Stand mixte", Set.of("STRATEGIE", "AMBIANCE"), 1, 1, false);
        Animateur ada = animateur("A1", "Ada", Map.of("STRATEGIE", NiveauCompetence.AUTONOME));

        RapportTypologies rapport = TypologieAnalyzer.compute(
                new PlanningEvenement(JOUR, List.of(ada), List.of(poste("P1", mixte, ada, 9, 12))),
                List.of(new TypologieItem("STRATEGIE", "Stratégie"), new TypologieItem("AMBIANCE", "Ambiance")),
                List.of(ada));

        assertThat(ligne(rapport, "STRATEGIE").postes()).isEqualTo(1);
        assertThat(ligne(rapport, "AMBIANCE").postes()).isEqualTo(1);
        assertThat(ligne(rapport, "AMBIANCE").heures()).isCloseTo(3.0, within(0.01));
    }

    /** The gap between vetted and used is the information this view is opened for. */
    @Test
    void separeLesCompetentsDesAffectesEtNommeLEcart() {
        Stand mixte = new Stand("S2", "Stand mixte", Set.of("STRATEGIE", "AMBIANCE"), 1, 1, false);
        Animateur ada = animateur("A1", "Ada", Map.of("STRATEGIE", NiveauCompetence.AUTONOME));
        Animateur bob = animateur("A2", "Bob", Map.of("AMBIANCE", NiveauCompetence.AUTONOME));

        RapportTypologies rapport = TypologieAnalyzer.compute(
                new PlanningEvenement(JOUR, List.of(ada, bob), List.of(poste("P1", mixte, ada, 9, 12))),
                List.of(new TypologieItem("STRATEGIE", "Stratégie"), new TypologieItem("AMBIANCE", "Ambiance")),
                List.of(ada, bob));

        LigneTypologie ambiance = ligne(rapport, "AMBIANCE");
        assertThat(noms(ambiance.animateursCompetents())).containsExactly("Bob Martin");
        assertThat(noms(ambiance.animateursAffectes())).containsExactly("Ada Martin");
        assertThat(noms(ambiance.competentsJamaisAffectes())).containsExactly("Bob Martin");
        assertThat(noms(ambiance.affectesSansCompetence())).containsExactly("Ada Martin");
        // The id travels with the name: the screen links each one to that
        // person's timeline, which it cannot do from a name alone.
        assertThat(ambiance.animateursAffectes().getFirst().animateurId()).isEqualTo("A1");
    }

    /** Every typologie gets a line, held or not: « nobody holds it » is the answer wanted. */
    @Test
    void uneTypologieQuePersonneNeTientALigneQuandMeme() {
        Animateur ada = animateur("A1", "Ada", Map.of());

        RapportTypologies rapport = TypologieAnalyzer.compute(
                new PlanningEvenement(JOUR, List.of(ada), List.of()),
                List.of(new TypologieItem("ENIGME", "Énigme")),
                List.of(ada));

        LigneTypologie ligne = ligne(rapport, "ENIGME");
        assertThat(ligne.animateursAffectes()).isEmpty();
        assertThat(ligne.postes()).isZero();
        assertThat(ligne.heures()).isZero();
    }

    /** No plan at all is a legitimate state, not an error: every line answers zero. */
    @Test
    void sansPlanningResoluChaqueTypologieRepondZero() {
        RapportTypologies rapport =
                TypologieAnalyzer.compute(null, List.of(new TypologieItem("STRATEGIE", "Stratégie")), List.of());

        assertThat(rapport.typologies()).hasSize(1);
        assertThat(ligne(rapport, "STRATEGIE").postes()).isZero();
    }

    /** The quota of issue #594 is shown here: a ceiling nobody can see is a ceiling nobody can set. */
    @Test
    void lePlafondDeLaTypologieEstReporte() {
        RapportTypologies rapport = TypologieAnalyzer.compute(
                null,
                List.of(new TypologieItem("HOMMES-JEU", "Hommes jeu", false, 4, "45 jeux à apprendre", null)),
                List.of());

        assertThat(ligne(rapport, "HOMMES-JEU").maxCreneauxParAnimateur()).isEqualTo(4);
        assertThat(ligne(rapport, "HOMMES-JEU").description()).isEqualTo("45 jeux à apprendre");
    }

    /**
     * The heatmap of the screen: « quand mes jeux de stratégie tournent-ils »
     * is a question the edition totals cannot answer. A day the typologie was
     * not held has no entry, rather than a zero nobody asked for.
     */
    @Test
    void lesHeuresSontAussiVentileesJourParJour() {
        Stand strategie = new Stand("S1", "Stand stratégie", Set.of("STRATEGIE"), 1, 2, false);
        Animateur ada = animateur("A1", "Ada", Map.of("STRATEGIE", NiveauCompetence.AUTONOME));
        PosteAffectation premier = poste("P1", strategie, ada, 9, 13);
        PosteAffectation second = poste("P2", strategie, ada, 14, 18);
        second.getCreneau().setDate(JOUR.plusDays(1));

        RapportTypologies rapport = TypologieAnalyzer.compute(
                new PlanningEvenement(JOUR, List.of(ada), List.of(premier, second)),
                List.of(new TypologieItem("STRATEGIE", "Stratégie")),
                List.of(ada));

        assertThat(rapport.jours())
                .containsExactly(JOUR.toString(), JOUR.plusDays(1).toString());
        assertThat(ligne(rapport, "STRATEGIE").heuresParJour())
                .containsOnlyKeys(JOUR.toString(), JOUR.plusDays(1).toString());
        assertThat(ligne(rapport, "STRATEGIE").heuresParJour().get(JOUR.toString()))
                .isCloseTo(4.0, within(0.01));
    }

    /** Names to read, ids to link on: the assertions above only care about the names. */
    private static List<String> noms(List<TypologieAnalyzer.AnimateurTypologie> animateurs) {
        return animateurs.stream()
                .map(TypologieAnalyzer.AnimateurTypologie::nom)
                .toList();
    }
}
