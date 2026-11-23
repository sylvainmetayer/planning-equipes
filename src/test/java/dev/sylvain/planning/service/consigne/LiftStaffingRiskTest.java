package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LiftStaffingRiskTest {

    /** Monday of an ISO week; the lifted day is its Friday. */
    private static final LocalDate MONDAY = LocalDate.of(2027, 2, 1);

    private static final LocalDate FRIDAY = MONDAY.plusDays(4);
    private static final PastHorizon LONG_AGO = new PastHorizon(LocalDate.of(2026, 1, 1), LocalTime.MIDNIGHT);

    private final Stand stand = new Stand("S", "Stand", Set.of(), 1, 1, false);
    private final Animateur fullWeek = animateur("A1");
    private final Animateur threeDays = animateur("A2");
    private final List<PosteAffectation> plan = new ArrayList<>();

    LiftStaffingRiskTest() {
        for (int d = 0; d < 7; d++) {
            LocalDate day = MONDAY.plusDays(d);
            if (!day.equals(FRIDAY)) {
                seat(fullWeek, day);
                if (d < 3) {
                    seat(threeDays, day);
                }
            }
        }
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, "P", "N", LocalDate.of(1990, 1, 1), false);
    }

    private PosteAffectation seat(Animateur animateur, LocalDate day) {
        PosteAffectation poste = new PosteAffectation(
                "P" + plan.size(),
                stand,
                new Creneau((long) plan.size(), 0, day, LocalTime.of(10, 0), LocalTime.of(14, 0)));
        poste.setAnimateur(animateur);
        plan.add(poste);
        return poste;
    }

    private static List<VerrouillagePlanning> lockEveryDayBut(LocalDate free) {
        List<VerrouillagePlanning> locks = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            LocalDate day = MONDAY.plusDays(d);
            if (!day.equals(free)) {
                VerrouillagePlanning lock = new VerrouillagePlanning("J" + d, TypeVerrouillage.JOUR);
                lock.setJour(day);
                locks.add(lock);
            }
        }
        return locks;
    }

    @Test
    void somebodyWhoseFrozenDaysFillTheWeekIsCountedAndTheNearestLockedDayIsNamed() {
        assertThat(LiftStaffingRisk.assess(
                        FRIDAY, 100, 400, List.of(fullWeek, threeDays), plan, lockEveryDayBut(FRIDAY), LONG_AGO))
                .contains(new LiftStaffingRisk.Risk(1, 1, List.of(MONDAY.plusDays(3))));
    }

    @Test
    void daysAlreadyStartedFreezeTooButNameNothingToUnlock() {
        PastHorizon nextMonday = new PastHorizon(MONDAY.plusDays(7), LocalTime.NOON);
        assertThat(LiftStaffingRisk.assess(FRIDAY, 100, 400, List.of(fullWeek), plan, List.of(), nextMonday))
                .contains(new LiftStaffingRisk.Risk(1, 0, List.of()));
    }

    @Test
    void aLockedDayAlreadyStartedIsNotOneToReopen() {
        // Thursday noon: Monday to Thursday are started — Thursday, the nearest, is not offered
        PastHorizon thursdayNoon = new PastHorizon(MONDAY.plusDays(3), LocalTime.NOON);
        assertThat(LiftStaffingRisk.assess(
                        FRIDAY, 100, 400, List.of(fullWeek), plan, lockEveryDayBut(FRIDAY), thursdayNoon))
                .contains(new LiftStaffingRisk.Risk(1, 1, List.of(MONDAY.plusDays(5))));
    }

    @Test
    void somebodyHeldByAnAnimateurLockIsCountedButNoDayIsNamedForThem() {
        List<VerrouillagePlanning> locks = new ArrayList<>(lockEveryDayBut(FRIDAY));
        VerrouillagePlanning person = new VerrouillagePlanning("A", TypeVerrouillage.ANIMATEUR);
        person.setAnimateurId(fullWeek.getId());
        locks.add(person);
        assertThat(LiftStaffingRisk.assess(FRIDAY, 100, 400, List.of(fullWeek), plan, locks, LONG_AGO))
                .contains(new LiftStaffingRisk.Risk(1, 0, List.of()));
    }

    @Test
    void oneDayFreeingBothComesFirstWhenTheirReopenableDaysOverlap() {
        Animateur other = animateur("A3");
        List<VerrouillagePlanning> locks = new ArrayList<>(lockEveryDayBut(FRIDAY));
        for (int d : new int[] {0, 1, 2, 3, 5, 6}) {
            PosteAffectation poste = seat(other, MONDAY.plusDays(d));
            if (d != 0 && d != 5) {
                // a lock on that very seat: reopening the day would not free it
                VerrouillagePlanning lock = new VerrouillagePlanning("C" + d, TypeVerrouillage.ANIMATEUR_CRENEAU);
                lock.setAnimateurId(other.getId());
                lock.setCreneauId(poste.getCreneau().getId());
                locks.add(lock);
            }
        }
        // A1 reopens on any day, A3 on Monday or Saturday only: Saturday, nearer than Monday, frees both
        assertThat(LiftStaffingRisk.assess(FRIDAY, 100, 400, List.of(fullWeek, other), plan, locks, LONG_AGO))
                .contains(new LiftStaffingRisk.Risk(2, 2, List.of(MONDAY.plusDays(5))));
    }

    @Test
    void silentWhenTheWeekIsNotFrozenTheLiftAddsNothingOrThePersonIsOffThatDay() {
        assertThat(LiftStaffingRisk.assess(FRIDAY, 100, 400, List.of(fullWeek), plan, List.of(), LONG_AGO))
                .isEmpty();
        assertThat(LiftStaffingRisk.assess(
                        FRIDAY, 400, 400, List.of(fullWeek), plan, lockEveryDayBut(FRIDAY), LONG_AGO))
                .isEmpty();
        fullWeek.setJoursIndisponibles(Set.of(FRIDAY));
        assertThat(LiftStaffingRisk.assess(
                        FRIDAY, 100, 400, List.of(fullWeek, threeDays), plan, lockEveryDayBut(FRIDAY), LONG_AGO))
                .isEmpty();
    }

    @Test
    void withTheFreezeOffOnlyLocksFreeze() {
        assertThat(LiftStaffingRisk.assess(FRIDAY, 100, 400, List.of(fullWeek), plan, List.of(), null))
                .isEmpty();
    }
}
