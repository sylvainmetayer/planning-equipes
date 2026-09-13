package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Test;

/**
 * A restart empties {@link ConstraintAnalysisStore} while the plan it describes
 * stays in the database — and the screens reading it must not pretend the plan
 * was never analysed.
 *
 * <p>That is the whole defect this class pins: the Problèmes tab of the
 * Diagnostic screen merges the feasibility causes, which are recomputed on
 * every read, with the violations of the analysis, which lived in memory only.
 * The morning after a deployment, « Actualiser » brought back the first half
 * and silently dropped the second — the rules in default disappeared from a
 * plan that still broke them, and no amount of refreshing brought them back
 * before the next solve.</p>
 */
class ConstraintAnalysisStoreRestartTest {

    /** The rule the plan below breaks exactly once — see {@link #planWithOneViolation()}. */
    private static final String CONTRAINTE = "standComplexeAvecReferent";

    /**
     * Nothing recorded, a plan in the database: the analysis is derived from
     * that plan instead of coming back as "never analysed".
     */
    @Test
    void aRestartedProcessStillReportsTheAnalysisOfThePersistedPlan() {
        PersistenceStub persistence = new PersistenceStub();
        ConstraintAnalysisStore store = storeAfterRestart(persistence);

        ConstraintAnalysisStore.StoredAnalysis analysis = store.latest();

        assertThat(analysis).isNotNull();
        assertThat(analysis.analysedAt()).isNotNull();
        assertThat(analysis.diagnostic().contraintes())
                .filteredOn(contrainte -> contrainte.name().equals(CONTRAINTE))
                .singleElement()
                .satisfies(contrainte -> assertThat(contrainte.matchCount()).isEqualTo(1));
    }

    /** Derived once and kept: refreshing a screen must not re-score the plan every time. */
    @Test
    void theDerivedAnalysisIsKeptRatherThanRecomputedOnEveryRead() {
        PersistenceStub persistence = new PersistenceStub();
        ConstraintAnalysisStore store = storeAfterRestart(persistence);

        store.latest();
        store.latest();

        assertThat(persistence.loads.get()).isEqualTo(1);
    }

    /**
     * The ordinary state before the first solve: no plan, no analysis — and the
     * referential is not loaded to establish it, the seats are counted.
     */
    @Test
    void anEditionWithoutAPersistedPlanIsAnsweredWithoutLoadingIt() {
        PersistenceStub persistence = new PersistenceStub();
        persistence.sieges = 0;
        ConstraintAnalysisStore store = storeAfterRestart(persistence);

        assertThat(store.latest()).isNull();
        assertThat(persistence.loads.get()).isZero();
    }

    /**
     * Best-effort: a plan that cannot be read leaves the readers with the
     * "never analysed" state they already handle, rather than turning every
     * screen that shows a diagnostic into an error.
     */
    @Test
    void anAnalysisThatCannotBeProducedIsNotAnErrorForItsReaders() {
        PersistenceStub persistence = new PersistenceStub() {
            @Override
            public PlanningEvenement loadPersistedPlanning() {
                throw new IllegalStateException("database down");
            }
        };
        ConstraintAnalysisStore store = storeAfterRestart(persistence);

        assertThat(store.latest()).isNull();
    }

    /** A recorded analysis stands: the derivation is a fallback, not a second opinion. */
    @Test
    void aRecordedAnalysisIsServedWithoutTouchingTheDatabase() {
        PersistenceStub persistence = new PersistenceStub();
        ConstraintAnalysisStore store = storeAfterRestart(persistence);

        store.record(store.planningService.diagnosePersistedPlan());
        int loadsAfterRecording = persistence.loads.get();
        store.latest();

        assertThat(persistence.loads.get()).isEqualTo(loadsAfterRecording);
    }

    /** The store as a freshly started process holds it: empty, over a database that is not. */
    private static ConstraintAnalysisStore storeAfterRestart(PersistenceStub persistence) {
        ConstraintAnalysisStore store = new ConstraintAnalysisStore();
        store.planningService = new PlanningService(
                2L,
                1L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                persistence,
                null,
                ConfigProviderResolver.instance().getBuilder().build());
        store.persistenceService = persistence;
        return store;
    }

    /** The database, reduced to the two questions this store asks it. */
    private static class PersistenceStub extends PlanningPersistenceService {

        private final AtomicInteger loads = new AtomicInteger();
        private int sieges = 1;

        @Override
        public int countPersistedAssignments() {
            return sieges;
        }

        @Override
        public PlanningEvenement loadPersistedPlanning() {
            loads.incrementAndGet();
            return planWithOneViolation();
        }
    }

    /**
     * One beginner alone on a complex stand: {@code standComplexeAvecReferent}
     * fires once, and nothing else does — the animateur is skilled for and
     * wishes the typology, so neither {@code appreciationIncompatible} nor
     * {@code souhaitsIncompatibles} joins in.
     */
    private static PlanningEvenement planWithOneViolation() {
        Stand stand = new Stand("S1", "S1", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 8), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur debutant = new Animateur("D1", "D1", "D1", LocalDate.of(2000, 1, 1), false);
        debutant.setCompetences(Map.of("STRATEGIE", NiveauCompetence.DEBUTANT));
        debutant.setSouhaits(Set.of("STRATEGIE"));
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(debutant);
        return new PlanningEvenement(creneau.getDate(), List.of(debutant), List.of(poste));
    }
}
