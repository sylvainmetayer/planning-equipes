package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.JourJService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.SolverJobService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A real solve holding one edition: what an MCP write answers there and next
 * door, and the seat, timeslot and absence writes its landing would undo,
 * which are refused rather than warned about.
 *
 * <p>No probe replaced here, unlike {@link McpWarnsWhileSolvingTest}: the
 * edition the warning is decided on is the one the tool's {@code edition}
 * argument names, compared by {@code SolverJobService} itself with the job's —
 * the rule this test pins.</p>
 */
@QuarkusTest
class McpRunningSolveEditionTest {

    private static final LocalDate JOUR = LocalDate.of(2030, 9, 3);

    private static final String NEIGHBOUR = "RUN-EDITION-B";

    private static final String CODE = WarningCodes.RESOLUTION_EN_COURS;

    @Inject
    StandMcpTools standTools;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanningService planningService;

    @Inject
    DemandeEchangeService echanges;

    @Inject
    JourJService jourJ;

    @Inject
    SolverJobService solverJobs;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Test
    void onlyTheEditionTheSolveHoldsWarnsAndRefuses() {
        Creneau creneau = creneau(9701L);
        Stand stand = stand("RUN-S1");
        Animateur animateur = animateur("RUN-A1");
        PlanningEvenement probleme =
                new PlanningEvenement(JOUR, List.of(animateur), List.of(poste("RUN-P1", stand, creneau, animateur)));
        String held = editionContext.editionIdCourant();
        String jobId = null;
        try {
            editions.create(new Edition(NEIGHBOUR, "Édition voisine du calcul", false, null));
            persistence.persist(probleme);
            referenceData.createAnimateur(animateur("RUN-A2"));
            waitForFreeSolver();

            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            assertThat(solverJobs.findActive().orElseThrow().getEditionId()).isEqualTo(held);

            // The warning: named edition held → warned; the neighbour → nothing.
            assertThat(standTools
                            .creer_typologie("RUN-T1", "Pendant le calcul", held)
                            .avertissements())
                    .contains(CODE);
            assertThat(standTools
                            .creer_typologie("RUN-T2", "À côté du calcul", NEIGHBOUR)
                            .avertissements())
                    .doesNotContain(CODE);

            // The refusals: what the landing rewrites is not written meanwhile.
            assertThatThrownBy(() -> referenceData.updateCreneau(9701L, creneau(9701L)))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
            assertThatThrownBy(() -> planningService.applyReparation("RUN-P1", null))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
            assertThatThrownBy(() -> echanges.accept("RUN-D-INCONNUE", null))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
            // The repair is refused before any of its own checks: not even the
            // seat is looked up while the landing would undo the write.
            assertThatThrownBy(() -> planningService.applyReparation("RUN-P-INCONNU", "RUN-A1"))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
            // An absence with no seat to free is refused all the same: the
            // solve never read its exceptions, and could seat the person there.
            int exceptions = referenceData.listContraintesAdHoc().size();
            assertThatThrownBy(() -> jourJ.recordAbsence("RUN-A2", null, JOUR, JOUR.atTime(LocalTime.of(8, 0))))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
            assertThat(referenceData.listContraintesAdHoc()).hasSize(exceptions);

            // Refused means untouched: the seat still holds its animateur.
            assertThat(persistence.loadPersistedPlanning().getPostes())
                    .filteredOn(poste -> "RUN-P1".equals(poste.getId()))
                    .singleElement()
                    .satisfies(poste -> assertThat(poste.getAnimateur()).isNotNull());
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            waitForFreeSolver();
            persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
            referenceData.deleteStand("RUN-S1");
            referenceData.deleteAnimateur("RUN-A1");
            referenceData.deleteAnimateur("RUN-A2");
            referenceData.deleteCreneaux(List.of(9701L));
            referenceData.deleteTypologie("RUN-T1");
            editions.delete(NEIGHBOUR);
        }
    }

    /** Asserts nothing on purpose: called in the {@code finally}, it must not mask the real failure. */
    private void waitForFreeSolver() {
        for (int attempt = 0; attempt < 240 && solverJobs.findActive().isPresent(); attempt++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Creneau creneau(long id) {
        return new Creneau(id, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private static Stand stand(String id) {
        return new Stand(id, "Stand " + id, Set.of(), 1, 1, false);
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, "Prenom", "Nom " + id, LocalDate.of(1990, 1, 1), false);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
