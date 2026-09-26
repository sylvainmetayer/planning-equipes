package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.solve.ProblemBuilder.ProblemeReamorce;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A cold start ({@code reamorcage=AUCUN}) ignores the persisted plan for the
 * future — and for nothing else: the locks and the past still read it. The
 * build used to receive an empty map on a cold start with the freeze off,
 * and {@code seedFromAffectations} returns early on an empty map, so a lock
 * was silently applied to nothing. It now receives no map at all and reads
 * the plan itself, once, when a lock or the freeze needs it — pinned under
 * both positions of the switch.
 */
class ProblemBuilderColdStartLockTest {

    private static final LocalDate JOUR = LocalDate.of(2027, 7, 14);

    /** One stand, one timeslot, two animateurs, and a lock on Alice. */
    private static final class LockedEdition extends EmptyReferenceData {

        @Override
        public List<Animateur> listAnimateurs() {
            return List.of(
                    new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false),
                    new Animateur("A2", "Bob", "Durand", LocalDate.of(1990, 1, 1), false));
        }

        @Override
        public List<Stand> listSolvedStands() {
            return List.of(new Stand("S1", "Stand", java.util.Set.of("JEU"), 1, 1, false));
        }

        @Override
        public List<Creneau> listCreneaux() {
            return List.of(new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(18, 0)));
        }

        @Override
        public List<VerrouillagePlanning> listVerrouillages() {
            VerrouillagePlanning verrou = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
            verrou.setAnimateurId("A1");
            return List.of(verrou);
        }
    }

    /** The persisted plan: Alice on the one seat. Nothing else of the bean is reached. */
    private static PlanningPersistenceService planWithAlice() {
        return new PlanningPersistenceService(null, null, null, null) {
            @Override
            public Map<String, List<String>> loadAnimateursByStandCreneau() {
                return Map.of(PlanningPersistenceService.standCreneauKey("S1", 1L), List.of("A1"));
            }

            @Override
            public Map<String, List<PlanningPersistenceService.Siege>> loadSplitCells() {
                return Map.of();
            }
        };
    }

    private static ProblemeReamorce coldStart(Supplier<PastHorizon> horizon) {
        return new ProblemBuilder(new LockedEdition(), planWithAlice(), horizon)
                .buildFromReferenceData(Reamorcage.AUCUN);
    }

    @Test
    void aLockIsAppliedOnAColdStartWithTheFreezeOff() {
        ProblemeReamorce probleme = coldStart(() -> null);

        PosteAffectation seat = probleme.planning().getPostes().get(0);
        assertThat(seat.getAnimateur().getId()).isEqualTo("A1");
        assertThat(seat.isVerrouille()).isTrue();
        assertThat(seat.isPasse()).isFalse();
        assertThat(probleme.reamorcage()).isEqualTo(Reamorcage.AUCUN);
        assertThat(probleme.postesReamorces()).isZero();
        assertThat(probleme.postesPasses()).isZero();
    }

    @Test
    void aLockIsAppliedOnAColdStartWithTheFreezeOnAndTheEventAhead() {
        ProblemeReamorce probleme = coldStart(() -> new PastHorizon(JOUR.minusDays(7), LocalTime.NOON));

        PosteAffectation seat = probleme.planning().getPostes().get(0);
        assertThat(seat.getAnimateur().getId()).isEqualTo("A1");
        assertThat(seat.isVerrouille()).isTrue();
        assertThat(seat.isPasse()).isFalse();
        assertThat(probleme.postesPasses()).isZero();
    }

    /** With every seat already started there is nothing left to plan, cold start or not. */
    @Test
    void aColdStartWithEverySeatPastIsRefused() {
        assertThatThrownBy(() -> coldStart(() -> new PastHorizon(JOUR.plusDays(1), LocalTime.NOON)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.NOTHING_AHEAD_REFUSAL);
    }
}
