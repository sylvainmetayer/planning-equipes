package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PlanningHoursReader.HoursReading;
import dev.sylvain.planning.service.analyse.PlanningHoursReader.Source;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The persisted plan's hours are dated by the solve that wrote them: the date
 * and the seats are two reads, and a solve landing between them must not put
 * one plan's date on the other's seats.
 */
class PlanningHoursReaderTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 11);
    private static final Instant FIRST_SOLVE = Instant.parse("2026-07-10T08:00:00Z");
    private static final Instant SECOND_SOLVE = Instant.parse("2026-07-10T09:00:00Z");

    private static final Animateur ALICE = new Animateur("A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
    private static final Animateur BRUNO = new Animateur("B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);

    @Test
    void aSolveLandingDuringTheReadIsReadAgainAndDatedByItsOwnDate() {
        // The date moves between the first read and the second: the plan read
        // in between may be either one, so it is read again.
        Persisted persisted =
                new Persisted(List.of(FIRST_SOLVE, SECOND_SOLVE, SECOND_SOLVE), List.of(heldBy(ALICE), heldBy(BRUNO)));

        HoursReading reading = reader(persisted).read(Source.PERSISTE);

        assertThat(persisted.plansRead).isEqualTo(2);
        assertThat(reading.planDate()).isEqualTo(SECOND_SOLVE);
        assertThat(total(reading, "B")).isEqualTo(3.0);
        assertThat(total(reading, "A")).isZero();
    }

    @Test
    void aDateThatDidNotMoveDatesThePlanReadOnce() {
        Persisted persisted = new Persisted(List.of(FIRST_SOLVE, FIRST_SOLVE), List.of(heldBy(ALICE)));

        HoursReading reading = reader(persisted).read(null);

        assertThat(persisted.plansRead).isEqualTo(1);
        assertThat(reading.source()).isEqualTo("persiste");
        assertThat(reading.planDate()).isEqualTo(FIRST_SOLVE);
        assertThat(total(reading, "A")).isEqualTo(3.0);
    }

    private static double total(HoursReading reading, String animateurId) {
        return reading.report().animateurs().stream()
                .filter(ligne -> ligne.animateurId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .total();
    }

    private static PlanningHoursReader reader(Persisted persisted) {
        PlanPublieService nothingPublished = new PlanPublieService(null, null, null) {
            @Override
            public Optional<PublishedPlan> publicationInForce() {
                return Optional.empty();
            }

            @Override
            public boolean jamaisPublie() {
                return true;
            }
        };
        return new PlanningHoursReader(new PlanningHoursService(), persisted, nothingPublished);
    }

    private static PlanningEvenement heldBy(Animateur holder) {
        Stand stand = new Stand("S", "Stand", Set.of(), 1, 1, false);
        PosteAffectation poste =
                new PosteAffectation("P", stand, new Creneau(1L, 1, SATURDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        poste.setAnimateur(holder);
        return new PlanningEvenement(SATURDAY, List.of(ALICE, BRUNO), new ArrayList<>(List.of(poste)));
    }

    /** A persisted plan whose resolution date and seats are handed out in the order the reads ask for them. */
    private static final class Persisted extends PlanningPersistenceService {

        private final Iterator<Instant> dates;
        private final Iterator<PlanningEvenement> plans;
        private int plansRead;

        Persisted(List<Instant> dates, List<PlanningEvenement> plans) {
            super(null, null, null, null);
            this.dates = dates.iterator();
            this.plans = plans.iterator();
        }

        @Override
        public PlanningResolution loadResolution() {
            return new PlanningResolution(dates.next(), null);
        }

        @Override
        public PlanningEvenement loadPersistedPlanning() {
            plansRead++;
            return plans.next();
        }
    }
}
