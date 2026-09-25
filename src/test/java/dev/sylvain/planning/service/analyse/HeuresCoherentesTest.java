package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.EffectiveWork;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PlanningKpiService.AffectationKpi;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Criterion 7 of issue #32: every screen that puts a number of hours beside an
 * animateur's name puts the <b>same</b> number, and that number is the
 * amplitude less the legal breaks the days owe (ADR 0048).
 *
 * <p>Four read-outs used to disagree on one person. The daily and weekly caps
 * deducted the break; the Heures screen, the Équité screen and the KPI report
 * counted the whole span. Half an hour a day is small enough to look like a
 * rounding error and large enough to make an organiser distrust all four, and
 * nothing in the application said which was which. This test is the lock: a new
 * read-out that counts amplitude fails here, in one place, with the four
 * numbers printed side by side.</p>
 */
class HeuresCoherentesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 8);
    private static final LocalDate LENDEMAIN = JOUR.plusDays(1);

    /**
     * One animateur over two days: 08:00-18:00 unbroken (ten hours of
     * amplitude, one break owed past the sixth hour, 9 h 30 worked) and then
     * 09:00-13:00 (four hours, nothing owed). 13 h 30 in all, where the
     * amplitude is 14 h.
     */
    @Test
    void theFourReadingsGiveTheSameTotalForOneAnimateur() {
        Stand stand = new Stand("STAND", "Stand", Set.of(), 1, 1, false);
        Animateur alice = new Animateur("A-ALICE", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        List<PosteAffectation> postes = List.of(
                poste("P1", stand, new Creneau(1L, 1, JOUR, LocalTime.of(8, 0), LocalTime.of(18, 0)), alice),
                poste("P2", stand, new Creneau(2L, 2, LENDEMAIN, LocalTime.of(9, 0), LocalTime.of(13, 0)), alice));
        PlanningEvenement planning = new PlanningEvenement(JOUR, List.of(alice), new ArrayList<>(postes));
        ParametresLegaux parametres = planning.parametresLegaux();

        double attendu = 13.5;

        assertThat(EffectiveWork.minutes(postes, parametres) / 60.0)
                .as("le domaine")
                .isCloseTo(attendu, within(0.001));

        assertThat(new PlanningHoursService()
                        .compute(planning)
                        .animateurs()
                        .getFirst()
                        .total())
                .as("écran Heures et heures_travaillees")
                .isCloseTo(attendu, within(0.001));

        assertThat(EquiteService.compute(planning, parametres, Set.of())
                        .lignes()
                        .getFirst()
                        .heuresTotal())
                .as("écran Équité")
                .isCloseTo(attendu, within(0.001));

        assertThat(PlanningKpiService.compute(new PlanningKpiService.KpiInputs(
                                affectationsKpi(postes),
                                null,
                                Map.of(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                EffectiveWork.breakMinutesPerAnimateur(postes, parametres)))
                        .heuresTotal())
                .as("KPI")
                .isCloseTo(attendu, within(0.001));
    }

    /** The same day split by a real hole owes nothing, and every read-out says 14 h. */
    @Test
    void unTrouDansLaGrilleNeFaitRienDeduireNonPlus() {
        Stand stand = new Stand("STAND", "Stand", Set.of(), 1, 1, false);
        Animateur alice = new Animateur("A-ALICE", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        List<PosteAffectation> postes = List.of(
                poste("P1", stand, new Creneau(1L, 1, JOUR, LocalTime.of(8, 0), LocalTime.of(13, 0)), alice),
                poste("P2", stand, new Creneau(2L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(19, 0)), alice),
                poste("P3", stand, new Creneau(3L, 2, LENDEMAIN, LocalTime.of(9, 0), LocalTime.of(13, 0)), alice));
        PlanningEvenement planning = new PlanningEvenement(JOUR, List.of(alice), new ArrayList<>(postes));

        assertThat(EffectiveWork.minutes(postes, planning.parametresLegaux()) / 60.0)
                .isCloseTo(14.0, within(0.001));
        assertThat(new PlanningHoursService()
                        .compute(planning)
                        .animateurs()
                        .getFirst()
                        .total())
                .isCloseTo(14.0, within(0.001));
    }

    /**
     * The three premium counters of the Heures screen stay at amplitude, and
     * say so: a Sunday premium is paid on presence, and a break cannot be put
     * on one side of a window without inventing when it was taken. The total
     * is the one figure the other screens have to match.
     */
    @Test
    void lesColonnesDePrimeComptentLAmplitude() {
        Stand stand = new Stand("STAND", "Stand", Set.of(), 1, 1, false);
        Animateur alice = new Animateur("A-ALICE", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        LocalDate dimanche = LocalDate.of(2026, 7, 12);
        List<PosteAffectation> postes = List.of(
                poste("P1", stand, new Creneau(1L, 1, dimanche, LocalTime.of(8, 0), LocalTime.of(18, 0)), alice));
        PlanningEvenement planning = new PlanningEvenement(dimanche, List.of(alice), new ArrayList<>(postes));

        PlanningHoursService.HeuresAnimateur ligne =
                new PlanningHoursService().compute(planning).animateurs().getFirst();

        assertThat(ligne.total()).as("travail effectif").isCloseTo(9.5, within(0.001));
        assertThat(ligne.heuresDimanche()).as("amplitude, pour la prime").isCloseTo(10.0, within(0.001));
    }

    private static List<AffectationKpi> affectationsKpi(List<PosteAffectation> postes) {
        List<AffectationKpi> affectations = new ArrayList<>();
        for (PosteAffectation poste : postes) {
            affectations.add(new AffectationKpi(
                    poste.getStand().getId(),
                    String.valueOf(poste.getCreneau().getId()),
                    poste.getAnimateur().getId(),
                    poste.getDureeEffectiveMinutes()));
        }
        return affectations;
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
