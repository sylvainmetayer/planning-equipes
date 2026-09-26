package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The repair assistant looks further when asked (« Chercher plus loin » on
 * Aujourd'hui): the eligible candidates are ranked the same way on every
 * call, most likely to pass first, and {@code depuis} asks for the next batch
 * of them.
 */
class RepairSuggestionsPagingTest {

    private static final LocalDate SAMEDI = LocalDate.of(2027, 9, 4);

    private final Stand plateau = new Stand("PLATEAU", "Plateau", Set.of("JEUX"), 1, 1, false);
    private final Stand atelier = new Stand("ATELIER", "Atelier", Set.of("JEUX"), 1, 1, false);
    private final Creneau matin = new Creneau(1L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(13, 0));
    private final Creneau apresMidi = new Creneau(2L, 1, SAMEDI, LocalTime.of(14, 0), LocalTime.of(17, 0));
    private final Creneau veille = new Creneau(3L, 1, SAMEDI.minusDays(1), LocalTime.of(10, 0), LocalTime.of(13, 0));

    private final Animateur occupe = adulte("A1");
    private final Animateur plusTard = adulte("A2");
    private final Animateur hier = adulte("A3");
    private final Animateur libre = adulte("A4");
    private final Animateur libreAussi = adulte("A10");

    /** The empty seat to fill, and what everybody else already holds. */
    private PlanningEvenement plan() {
        PosteAffectation cible = new PosteAffectation("p0", plateau, matin);
        PosteAffectation pendant = seat("p1", atelier, matin, occupe);
        PosteAffectation ensuite = seat("p2", atelier, apresMidi, plusTard);
        PosteAffectation laVeille = seat("p3", atelier, veille, hier);
        PlanningEvenement plan = new PlanningEvenement(
                SAMEDI,
                new ArrayList<>(List.of(occupe, plusTard, hier, libre, libreAussi)),
                new ArrayList<>(List.of(cible, pendant, ensuite, laVeille)));
        plan.setParametresLegaux(List.of(new ParametresLegaux()));
        return plan;
    }

    /**
     * Free at that moment first, then nobody holding a seat on the day — the
     * rest and daily rules have nothing to break —, then the fewest minutes
     * worked, then the natural id order.
     */
    @Test
    void theCandidatesMostLikelyToPassComeFirst() {
        PlanningEvenement plan = plan();

        List<Animateur> ranges =
                PlanningWhatIf.candidatsEligibles(plan, plan.getPostes().getFirst());

        assertThat(ranges).extracting(Animateur::getId).containsExactly("A4", "A10", "A3", "A2", "A1");
    }

    /** Each call simulates the batch it names, and says from where. */
    @Test
    void depuisAsksForTheNextBatch() {
        PlanningWhatIf.SuggestionsReparation premiers = whatIf().suggererReparations(plan(), "p0", 2, null);
        PlanningWhatIf.SuggestionsReparation suivants = whatIf().suggererReparations(plan(), "p0", 2, 2);

        assertThat(premiers.candidatsEligibles()).isEqualTo(5);
        assertThat(premiers.depuis()).isZero();
        assertThat(premiers.candidatsEvalues()).isEqualTo(2);
        assertThat(premiers.suggestions())
                .extracting(PlanningWhatIf.SuggestionReparation::animateurId)
                .containsExactlyInAnyOrder("A4", "A10");
        assertThat(suivants.depuis()).isEqualTo(2);
        assertThat(suivants.candidatsEvalues()).isEqualTo(2);
        assertThat(suivants.suggestions())
                .extracting(PlanningWhatIf.SuggestionReparation::animateurId)
                .doesNotContain("A4", "A10");
    }

    /** Past the end, nothing is simulated; a negative rank reads as the first. */
    @Test
    void aRankOutOfTheListIsClamped() {
        PlanningWhatIf.SuggestionsReparation audela = whatIf().suggererReparations(plan(), "p0", 20, 99);
        PlanningWhatIf.SuggestionsReparation negatif = whatIf().suggererReparations(plan(), "p0", 1, -4);

        assertThat(audela.depuis()).isEqualTo(5);
        assertThat(audela.candidatsEvalues()).isZero();
        assertThat(audela.suggestions()).isEmpty();
        assertThat(negatif.depuis()).isZero();
        assertThat(negatif.candidatsEvalues()).isEqualTo(1);
    }

    private static PosteAffectation seat(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    private static Animateur adulte(String id) {
        return new Animateur(id, "Prénom " + id, "Nom " + id, LocalDate.of(1990, 1, 1), false);
    }

    private static PlanningWhatIf whatIf() {
        SolverConfiguration configuration = new SolverConfiguration(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                ConfigProvider.getConfig());
        return new PlanningWhatIf(configuration.diagnosticService(), new EmptyReferenceData(), null, planning -> {});
    }
}
