package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
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
    private static final LocalDate LONG_AGO = LocalDate.of(2026, 1, 1);

    private final Stand stand = new Stand("S", "Stand", Set.of(), 1, 1, false);
    private final Animateur fullWeek = new Animateur("A1", "P", "N", LocalDate.of(1990, 1, 1), false);
    private final Animateur threeDays = new Animateur("A2", "P", "N", LocalDate.of(1990, 1, 1), false);
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

    private void seat(Animateur animateur, LocalDate day) {
        PosteAffectation poste = new PosteAffectation(
                "P" + plan.size(),
                stand,
                new Creneau((long) plan.size(), 0, day, LocalTime.of(10, 0), LocalTime.of(14, 0)));
        poste.setAnimateur(animateur);
        plan.add(poste);
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
                .contains(new LiftStaffingRisk.Risk(1, List.of(MONDAY.plusDays(3))));
    }

    @Test
    void daysAlreadyPastFreezeTooButNameNothingToUnlock() {
        assertThat(LiftStaffingRisk.assess(FRIDAY, 100, 400, List.of(fullWeek), plan, List.of(), MONDAY.plusDays(7)))
                .contains(new LiftStaffingRisk.Risk(1, List.of()));
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
}
