package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.StaffingAnalyzer.BorneRetenue;
import dev.sylvain.planning.service.StaffingAnalyzer.StaffingSummary;

class StaffingAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    private final StaffingAnalyzer analyzer = new StaffingAnalyzer();

    @Test
    void emptyProblemNeedsNobody() {
        StaffingSummary summary = analyzer.analyze(List.of(), 48 * 60, 30);

        assertThat(summary.minimumTotal()).isZero();
        assertThat(summary.parJour()).isEmpty();
        assertThat(summary.jourCritique()).isNull();
    }

    @Test
    void peakCountsSimultaneousSeatsOnly() {
        // Two stands of 2 seats open at the same time: 4 people at once. A
        // third stand opens only once they have closed, and needs nobody more.
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Creneau afternoon = creneau(2, LocalTime.of(14, 0), LocalTime.of(16, 0));
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand("A", 2), matin, 2));
        postes.addAll(postes(stand("B", 2), matin, 2));
        postes.addAll(postes(stand("C", 3), afternoon, 3));

        StaffingSummary summary = analyzer.analyze(postes, 48 * 60, 0);

        assertThat(summary.picSimultane()).isEqualTo(4);
        assertThat(summary.parJour()).hasSize(1);
        assertThat(summary.parJour().get(0).standsOuverts()).isEqualTo(3);
        assertThat(summary.parJour().get(0).sieges()).isEqualTo(7);
        assertThat(summary.parJour().get(0).heures()).isEqualTo(14.0);
    }

    @Test
    void overlappingRelayVacationsOfTheSameStandAreNotCountedTwice() {
        // The regression this class exists for: the browser-side estimate
        // summed effectifMin over every créneau a stand was open on, so a day
        // sliced into overlapping relay vacations counted the same stand
        // several times over. Two consecutive vacations of the same single-seat
        // stand need one person at a time (two over the day), never four.
        Stand stand = stand("A", 1);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand, creneau(1, LocalTime.of(10, 0), LocalTime.of(15, 15)), 1));
        postes.addAll(postes(stand, creneau(2, LocalTime.of(15, 0), LocalTime.of(20, 0)), 1));

        StaffingSummary summary = analyzer.analyze(postes, 48 * 60, 0);

        // 2 only during the 15 min handover the relay pattern is built on.
        assertThat(summary.picSimultane()).isEqualTo(2);
        assertThat(summary.parJour().get(0).sieges()).isEqualTo(2);
    }

    @Test
    void breakBetweenVacationsRaisesThePeakToTheExactHeadcount() {
        // Two vacations of the same stand, back to back with no overlap: one
        // seat at a time, but the 30-minute legal break between two vacations
        // of the same person means the relay needs two distinct people.
        Stand stand = stand("A", 1);
        List<PosteAffectation> postes = new ArrayList<>();
        postes.addAll(postes(stand, creneau(1, LocalTime.of(10, 0), LocalTime.of(15, 0)), 1));
        postes.addAll(postes(stand, creneau(2, LocalTime.of(15, 0), LocalTime.of(20, 0)), 1));

        StaffingSummary summary = analyzer.analyze(postes, 48 * 60, 30);

        assertThat(summary.picSimultane()).isEqualTo(1);
        assertThat(summary.picAvecPause()).isEqualTo(2);
        assertThat(summary.minimumTotal()).isEqualTo(2);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.PIC_AVEC_PAUSE);
    }

    @Test
    void workloadBoundWinsWhenTheFestivalIsLongEnough() {
        // One seat open 10 hours a day for the 7 days of one ISO week = 70
        // person-hours, against a 20 h weekly cap: 4 people, way above the
        // peak of 1.
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 7; jour++) {
            Creneau creneau = new Creneau((long) jour, jour + 1, LocalDate.of(2026, 7, 6).plusDays(jour),
                    LocalTime.of(10, 0), LocalTime.of(20, 0));
            postes.addAll(postes(stand("A", 1), creneau, 1));
        }

        StaffingSummary summary = analyzer.analyze(postes, 20 * 60, 30);

        assertThat(summary.nombreSemaines()).isEqualTo(1);
        assertThat(summary.totalDemandeHeures()).isEqualTo(70.0);
        assertThat(summary.chargeTotal()).isEqualTo(4);
        assertThat(summary.minimumTotal()).isEqualTo(4);
        assertThat(summary.borneRetenue()).isEqualTo(BorneRetenue.CHARGE_HORAIRE);
    }

    @Test
    void aWindowCrossingMidnightStaysOnTheEveningItStartedOn() {
        Creneau soiree = creneau(1, LocalTime.of(22, 0), LocalTime.of(2, 0));
        StaffingSummary summary = analyzer.analyze(postes(stand("A", 1), soiree, 1), 48 * 60, 30);

        assertThat(summary.parJour()).hasSize(1);
        assertThat(summary.parJour().get(0).date()).isEqualTo(JOUR);
        assertThat(summary.parJour().get(0).heures()).isEqualTo(4.0);
    }

    @Test
    void reserveMajeursStandsPushTheAdultShareUp() {
        Creneau matin = creneau(1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        Stand majeurs = stand("A", 2);
        majeurs.setReserveMajeurs(true);
        List<PosteAffectation> postes = new ArrayList<>(postes(majeurs, matin, 2));
        postes.addAll(postes(stand("B", 2), matin, 2));

        StaffingSummary summary = analyzer.analyze(postes, 48 * 60, 0);

        // 2 adult-only seats + 1 of the 2 remaining ones = 3 of 4.
        assertThat(summary.minimumTotal()).isEqualTo(4);
        assertThat(summary.minimumMajeurs()).isEqualTo(3);
        assertThat(summary.minimumMineurs()).isEqualTo(1);
    }

    private static Stand stand(String id, int effectifMin) {
        return new Stand(id, id, Set.of("JEUX"), effectifMin, effectifMin, false);
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, JOUR, debut, fin);
    }

    /** One poste per seat, exactly like {@code PlanningService#buildPostes} generates them. */
    private static List<PosteAffectation> postes(Stand stand, Creneau creneau, int seats) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (int seat = 0; seat < seats; seat++) {
            postes.add(new PosteAffectation(stand.getId() + "-" + creneau.getId() + "-" + seat, stand, creneau));
        }
        return postes;
    }
}
