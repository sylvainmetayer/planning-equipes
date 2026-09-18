package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which dates the « journées aux horaires modifiés » lines of a publication
 * mail are read for: the days the person holds a seat in the plan being
 * published, and the days they held one in the plan it replaces. A band that
 * closes somebody's whole day leaves them a seat in the second only — and
 * the motif is what turns their bare « vacation retirée » into a decision.
 */
class PlanPublicationDatesConcerneesTest {

    private static final LocalDate LUNDI = LocalDate.of(2027, 2, 1);
    private static final LocalDate MARDI = LUNDI.plusDays(1);
    private static final LocalDate MERCREDI = LUNDI.plusDays(2);
    private static final Stand STAND = new Stand("S", "Stand", Set.of(), 1, 2, false);
    private static final Animateur ADA = new Animateur("ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
    private static final Animateur ALAN = new Animateur("ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);

    @Test
    void aDayEmptiedByTheBandStillCountsThroughTheReferencePlan() {
        PlanningEvenement reference = planning(poste("p1", LUNDI, ADA), poste("p2", MARDI, ADA));
        PlanningEvenement publie = planning(poste("p3", LUNDI, ADA), poste("p4", MERCREDI, ADA));

        assertThat(PlanPublicationService.datesConcernees(publie, reference, "ADA"))
                .containsExactly(LUNDI, MARDI, MERCREDI);
    }

    @Test
    void onlyThePersonsOwnSeatsCount() {
        PlanningEvenement reference = planning(poste("p1", MARDI, ALAN));
        PlanningEvenement publie = planning(poste("p2", LUNDI, ADA), poste("p3", MERCREDI, ALAN));

        assertThat(PlanPublicationService.datesConcernees(publie, reference, "ADA"))
                .containsExactly(LUNDI);
    }

    @Test
    void aFirstPublicationHasNoReferencePlan() {
        PlanningEvenement publie = planning(poste("p1", LUNDI, ADA));

        assertThat(PlanPublicationService.datesConcernees(publie, null, "ADA")).containsExactly(LUNDI);
        assertThat(PlanPublicationService.datesConcernees(publie, planning(), "ADA"))
                .containsExactly(LUNDI);
    }

    private static PlanningEvenement planning(PosteAffectation... postes) {
        return new PlanningEvenement(LUNDI, new ArrayList<>(List.of(ADA, ALAN)), new ArrayList<>(List.of(postes)));
    }

    private static PosteAffectation poste(String id, LocalDate date, Animateur animateur) {
        PosteAffectation poste =
                new PosteAffectation(id, STAND, new Creneau(null, 1, date, LocalTime.of(9, 0), LocalTime.of(13, 0)));
        poste.setAnimateur(animateur);
        return poste;
    }
}
