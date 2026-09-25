package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Restoring a snapshot while a solve holds the solver (issue #313): the guard
 * lives in {@link PlanSnapshotService#restaurer}, so the REST resource and the
 * MCP tool are both covered by these tests without being exercised here.
 *
 * <p>The scenario the issue describes: a snapshot is captured, a solve starts,
 * a restore is asked during the run. Without the guard the restore answers
 * 200, and the landing solve overwrites it without a word.</p>
 */
@QuarkusTest
class PlanSnapshotServiceSolveGuardTest {

    private static final LocalDate JOUR = LocalDate.of(2030, 8, 3);

    private static final String EDITION_VOISINE = "SNAP-EDITION-B";

    @Inject
    PlanSnapshotService snapshots;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    SolverJobService solverJobs;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Test
    void restoreIsRefusedWhileASolveHoldsThisEdition() {
        Creneau creneau = creneau(9601L);
        Stand stand = stand("SNAP-S1");
        Animateur animateur = animateur("SNAP-A1");
        PlanningEvenement probleme =
                new PlanningEvenement(JOUR, List.of(animateur), List.of(poste("SNAP-P1", stand, creneau, animateur)));
        String jobId = null;
        try {
            persistence.persist(probleme);
            attendreSolveurLibre();
            long snapshotId = snapshots.capture("Avant le solve", false).id();

            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            assertThat(solverJobs.findActive()).isPresent();

            assertThatThrownBy(() -> snapshots.restaurer(snapshotId, false))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);

            // Refused means nothing was written: the persisted seat is the one the
            // fixture put there, its resolution timestamp untouched by a restore.
            assertThat(persistence.countPersistedAssignments()).isEqualTo(1);
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            nettoyer();
        }
    }

    /**
     * A solve on edition A must not freeze edition B: the guard compares the
     * job's edition to the caller's, like every other write path. In B the
     * call reaches the snapshot lookup — an unknown id answers {@code null}
     * there, where the guard would have thrown before looking.
     */
    @Test
    void aSolveOnAnotherEditionRefusesNothing() {
        Creneau creneau = creneau(9602L);
        Stand stand = stand("SNAP-S2");
        Animateur animateur = animateur("SNAP-A2");
        PlanningEvenement probleme =
                new PlanningEvenement(JOUR, List.of(animateur), List.of(poste("SNAP-P2", stand, creneau, animateur)));
        String jobId = null;
        try {
            editions.create(new Edition(EDITION_VOISINE, "Édition voisine des instantanés", false, null));
            persistence.persist(probleme);
            attendreSolveurLibre();

            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            assertThat(solverJobs.findActive().orElseThrow().getEditionId())
                    .isEqualTo(editionContext.editionIdCourant());

            editionContext.executeIn(EDITION_VOISINE, () -> {
                assertThat(snapshots.restaurer(Long.MAX_VALUE, false)).isNull();
            });
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            nettoyer();
            editions.delete(EDITION_VOISINE);
        }
    }

    /** Once the solver is free again, the same restore goes through. */
    @Test
    void restoreGoesThroughOnceTheSolveIsOver() {
        Creneau creneau = creneau(9603L);
        Stand stand = stand("SNAP-S3");
        Animateur animateur = animateur("SNAP-A3");
        try {
            persistence.persist(new PlanningEvenement(
                    JOUR, List.of(animateur), List.of(poste("SNAP-P3", stand, creneau, animateur))));
            attendreSolveurLibre();
            long snapshotId = snapshots.capture("Plan à remettre", false).id();
            persistence.persist(new PlanningEvenement(JOUR, List.of(animateur), List.of()));
            assertThat(persistence.countPersistedAssignments()).isZero();

            PlanSnapshotService.RestaurationResult result = snapshots.restaurer(snapshotId, false);

            assertThat(result.restaure()).isTrue();
            assertThat(persistence.countPersistedAssignments()).isEqualTo(1);
        } finally {
            nettoyer();
        }
    }

    private void nettoyer() {
        attendreSolveurLibre();
        persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
        for (PlanSnapshotService.SnapshotMeta meta : snapshots.list()) {
            if (meta.libelle().startsWith("Avant le solve") || meta.libelle().startsWith("Plan à remettre")) {
                snapshots.delete(meta.id());
            }
        }
        for (String id : List.of("SNAP-S1", "SNAP-S2", "SNAP-S3")) {
            referenceData.deleteStand(id);
        }
        for (String id : List.of("SNAP-A1", "SNAP-A2", "SNAP-A3")) {
            referenceData.deleteAnimateur(id);
        }
        referenceData.deleteCreneaux(List.of(9601L, 9602L, 9603L));
    }

    /** Asserts nothing on purpose: called first in every {@code finally}, it must not mask the real failure. */
    private void attendreSolveurLibre() {
        try {
            await().atMost(Duration.ofSeconds(120))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> solverJobs.findActive().isEmpty());
        } catch (ConditionTimeoutException _) {
            // Deliberately silent: a solver that never frees up shows as the refusal it causes.
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
