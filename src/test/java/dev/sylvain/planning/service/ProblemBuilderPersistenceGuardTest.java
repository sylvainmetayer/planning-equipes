package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;

/**
 * Building a problem when there is no persistence to read the locks from.
 *
 * <p>{@code ProblemBuilder} reaches the persisted plan through a
 * {@link java.util.function.Supplier} rather than a field, because the bean is
 * injected into {@link PlanningService} <em>after</em> its constructor has run —
 * capturing the field there would pin {@code null} in production too, on every
 * réamorçage. The guard that makes this safe is
 * {@code applyVerrouillages}' {@code persistenceService == null} branch.</p>
 *
 * <p>That branch was unreachable in the whole suite: the only plain-Java double
 * lists no lock, so {@code verrouillages.isEmpty()} short-circuits first and the
 * second operand is never evaluated. The guard the design leans on was therefore
 * never exercised — which is what this pins, with a locked edition and no
 * persistence at all.</p>
 */
class ProblemBuilderPersistenceGuardTest {

    /** An edition with one stand, one créneau, one animateur — and one lock. */
    private static final class LockedEdition extends EmptyReferenceData {

        @Override
        public List<Animateur> listAnimateurs() {
            Animateur animateur = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
            return List.of(animateur);
        }

        @Override
        public List<Stand> listSolvedStands() {
            Stand stand = new Stand("S1", "Stand", Set.of("JEU"), 1, 1, false);
            return List.of(stand);
        }

        @Override
        public List<Creneau> listCreneaux() {
            Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(10, 0), LocalTime.of(18, 0));
            return List.of(creneau);
        }

        @Override
        public List<VerrouillagePlanning> listVerrouillages() {
            return List.of(new VerrouillagePlanning("V1", TypeVerrouillage.STAND));
        }
    }

    /**
     * With locks to apply and nothing to read them against, the build returns a
     * problem rather than throwing: a plain-Java harness has no persistence, and
     * a lock recorded before any solve freezes nothing anyway.
     */
    @Test
    void aLockedEditionWithoutPersistenceStillBuildsAProblem() {
        ProblemBuilder builder = new ProblemBuilder(new LockedEdition(), () -> null);

        assertThatCode(builder::buildFromReferenceData).doesNotThrowAnyException();

        PlanningEvenement probleme = builder.buildFromReferenceData();
        assertThat(probleme.getPostes()).isNotEmpty();
        assertThat(probleme.getPostes()).allSatisfy(poste -> assertThat(poste.getAnimateur()).isNull());
        // The locks still travel with the problem — they are simply not applied
        // to the seats, since there is no persisted plan to read them from.
        assertThat(probleme.getVerrouillages()).hasSize(1);
    }
}
