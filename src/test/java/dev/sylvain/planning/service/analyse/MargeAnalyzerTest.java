package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.CelluleMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.JourMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Plain-Java test, no Quarkus and no solve: the margin is read from the seats
 * and the roster alone, which is the whole point of the analyzer.
 */
class MargeAnalyzerTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);
    private static final LocalDate LENDEMAIN = JOUR.plusDays(1);

    private final MargeAnalyzer analyzer = new MargeAnalyzer();

    @Test
    void anEditionWithoutAnySeatYieldsNoGridAndSaysWhy() {
        RapportMarge rapport = beforeSolve(List.of(), List.of(animateur("alice")));

        assertThat(rapport.jours()).isEmpty();
        assertThat(rapport.tranches()).isEmpty();
        assertThat(rapport.pireCellule()).isNull();
        assertThat(rapport.message()).contains("Aucun siège à couvrir");
    }

    @Test
    void anEditionWithoutAnyAnimateurSaysSoRatherThanShowingEveryCellAtItsNeed() {
        // The grid would read « -2 everywhere », which is true and useless: the
        // capacity has not been entered yet (issue #416).
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));

        RapportMarge rapport = beforeSolve(seats(stand("A", 2), matin, null, null), List.of());

        assertThat(rapport.animateursTotal()).isZero();
        assertThat(rapport.message()).contains("Aucun animateur n'est saisi");
    }

    @Test
    void beforeSolvingTheMarginIsTheRosterMinusEverySeatOfTheCell() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        List<Animateur> roster = List.of(animateur("alice"), animateur("bob"), animateur("carol"));

        RapportMarge rapport = beforeSolve(seats(stand("A", 2), matin, null, null), roster);

        CelluleMarge cellule = cellule(rapport, JOUR, LocalTime.of(9, 0));
        assertThat(cellule.sieges()).isEqualTo(2);
        assertThat(cellule.besoin()).isEqualTo(2);
        assertThat(cellule.siegesPourvus()).isZero();
        assertThat(cellule.disponibles()).isEqualTo(3);
        assertThat(cellule.marge()).isEqualTo(1);
        assertThat(rapport.cellulesDeficitaires()).isZero();
    }

    @Test
    void beforeSolvingAnAnimateurUnavailableThatDayIsOutOfTheCapacity() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau lendemain = creneau(2, LENDEMAIN, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Animateur bob = animateur("bob");
        bob.getJoursIndisponibles().add(JOUR);
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), matin, (Animateur) null));
        postes.addAll(seats(stand("A", 1), lendemain, (Animateur) null));

        RapportMarge rapport = beforeSolve(postes, List.of(animateur("alice"), bob));

        assertThat(cellule(rapport, JOUR, LocalTime.of(9, 0)).disponibles()).isEqualTo(1);
        assertThat(cellule(rapport, LENDEMAIN, LocalTime.of(9, 0)).disponibles())
                .isEqualTo(2);
    }

    @Test
    void beforeSolvingTheAssignmentsAPlanAlreadyHoldsAreIgnored() {
        // The « avant » mode answers on the referential: it must read the same
        // number whether a plan is persisted or not, otherwise the recruitment
        // question changes answer after a solve.
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice");

        RapportMarge rapport = beforeSolve(seats(stand("A", 2), matin, alice, null), List.of(alice, animateur("bob")));

        CelluleMarge cellule = cellule(rapport, JOUR, LocalTime.of(9, 0));
        assertThat(cellule.siegesPourvus()).isZero();
        assertThat(cellule.besoin()).isEqualTo(2);
        assertThat(cellule.disponibles()).isEqualTo(2);
        assertThat(cellule.marge()).isZero();
    }

    @Test
    void theColumnsAreTheTimeslotsOfTheGridSortedByStart() {
        Creneau soir = creneau(1, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0));
        Creneau matin = creneau(2, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        // The same hours on the next day are the same column, not a second one.
        Creneau matinLendemain = creneau(3, LENDEMAIN, LocalTime.of(9, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), soir, (Animateur) null));
        postes.addAll(seats(stand("A", 1), matin, (Animateur) null));
        postes.addAll(seats(stand("A", 1), matinLendemain, (Animateur) null));

        RapportMarge rapport = beforeSolve(postes, List.of(animateur("alice")));

        assertThat(rapport.tranches())
                .extracting(tranche -> tranche.debut().toString())
                .containsExactly("09:00", "20:00");
        assertThat(rapport.jours()).extracting(JourMarge::date).containsExactly(JOUR, LENDEMAIN);
        assertThat(rapport.jours().getFirst().cellules()).hasSize(2);
    }

    @Test
    void aSeatWhoseTimeslotCarriesNoHoursHasNoColumnAndIsDropped() {
        Creneau sansHeures = new Creneau(9L, 1, JOUR, null, null);
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), sansHeures, (Animateur) null));
        postes.addAll(seats(stand("A", 1), matin, (Animateur) null));

        RapportMarge rapport = beforeSolve(postes, List.of(animateur("alice")));

        assertThat(rapport.tranches()).hasSize(1);
        assertThat(cellule(rapport, JOUR, LocalTime.of(9, 0)).sieges()).isEqualTo(1);
    }

    @Test
    void twoTimeslotsSharingTheSameHoursOnOneDayAreOneCellNamedByTheSmallestId() {
        Creneau premier = creneau(7, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau second = creneau(3, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), premier, (Animateur) null));
        postes.addAll(seats(stand("B", 1), second, (Animateur) null));

        RapportMarge rapport = beforeSolve(postes, List.of(animateur("alice")));

        CelluleMarge cellule = cellule(rapport, JOUR, LocalTime.of(9, 0));
        assertThat(cellule.sieges()).isEqualTo(2);
        assertThat(cellule.creneauId()).isEqualTo(3);
    }

    @Test
    void afterSolvingTheNeedIsWhatThePlanLeftEmpty() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice");
        Animateur bob = animateur("bob");
        Animateur carol = animateur("carol");

        RapportMarge rapport = afterSolve(seats(stand("A", 2), matin, alice, null), List.of(alice, bob, carol));

        CelluleMarge cellule = cellule(rapport, JOUR, LocalTime.of(9, 0));
        assertThat(cellule.siegesPourvus()).isEqualTo(1);
        assertThat(cellule.besoin()).isEqualTo(1);
        // Alice holds a seat of the cell, so she is not on the bench.
        assertThat(cellule.disponibles()).isEqualTo(2);
        assertThat(cellule.marge()).isEqualTo(1);
    }

    @Test
    void afterSolvingSomebodyOnAnOverlappingSeatElsewhereIsNotFree() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau chevauchant = creneau(2, JOUR, LocalTime.of(10, 0), LocalTime.of(14, 0));
        Animateur alice = animateur("alice");
        Animateur bob = animateur("bob");
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), matin, (Animateur) null));
        postes.addAll(seats(stand("B", 1), chevauchant, bob));

        RapportMarge rapport = afterSolve(postes, List.of(alice, bob));

        assertThat(cellule(rapport, JOUR, LocalTime.of(9, 0)).disponibles()).isEqualTo(1);
    }

    /**
     * Busy means busy, with no buffer either side (ADR 0048). Bob finishes at
     * 08:45 and the cell starts at 09:00: he is free to take it, and so is
     * somebody whose shift ends exactly at 09:00. This screen predicts the
     * solver, and the solver says the same — the fifteen minutes count as
     * worked if he takes the cell, which the caps judge, not this count.
     *
     * <p>It used to hold a buffer of « pause minimale entre vacations », a rule
     * of its own; that rule is retired, so the buffer with it.</p>
     */
    @Test
    void afterSolvingAShiftEndingJustBeforeTheCellLeavesItsHolderFree() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau veille = creneau(2, JOUR, LocalTime.of(6, 0), LocalTime.of(8, 45));
        Animateur alice = animateur("alice");
        Animateur bob = animateur("bob");
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), matin, (Animateur) null));
        postes.addAll(seats(stand("B", 1), veille, bob));

        RapportMarge rapport = afterSolve(postes, List.of(alice, bob));

        assertThat(cellule(rapport, JOUR, LocalTime.of(9, 0)).disponibles()).isEqualTo(2);
    }

    @Test
    void afterSolvingAShiftEndingBeforeTheBreakStillLeavesTheAnimateurFree() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau veille = creneau(2, JOUR, LocalTime.of(5, 0), LocalTime.of(8, 30));
        Animateur alice = animateur("alice");
        Animateur bob = animateur("bob");
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), matin, (Animateur) null));
        postes.addAll(seats(stand("B", 1), veille, bob));

        RapportMarge rapport = afterSolve(postes, List.of(alice, bob));

        assertThat(cellule(rapport, JOUR, LocalTime.of(9, 0)).disponibles()).isEqualTo(2);
    }

    @Test
    void afterSolvingAnAnimateurNoHardRuleWouldLetTakeTheSeatIsNotCountedFree() {
        // The bench refuses a minor on an adults-only stand, and so does the
        // move filter: this screen must not count them as slack.
        Stand reserve = stand("A", 1);
        reserve.setReserveMajeurs(true);
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Animateur alice = animateur("alice");
        Animateur mineur = animateur("mineur");
        mineur.setDateNaissance(JOUR.minusYears(16));

        RapportMarge rapport = afterSolve(seats(reserve, matin, (Animateur) null), List.of(alice, mineur));

        assertThat(cellule(rapport, JOUR, LocalTime.of(9, 0)).disponibles()).isEqualTo(1);
    }

    @Test
    void eachDayCarriesItsWorstCellAndTheReportTheWorstOfThemAll() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau soir = creneau(2, JOUR, LocalTime.of(20, 0), LocalTime.of(23, 0));
        Creneau matinLendemain = creneau(3, LENDEMAIN, LocalTime.of(9, 0), LocalTime.of(12, 0));
        List<PosteAffectation> postes = new ArrayList<>(seats(stand("A", 1), matin, (Animateur) null));
        postes.addAll(seats(stand("A", 4), soir, null, null, null, null));
        postes.addAll(seats(stand("A", 2), matinLendemain, null, null));

        RapportMarge rapport = beforeSolve(postes, List.of(animateur("alice"), animateur("bob")));

        JourMarge premier = rapport.jours().getFirst();
        assertThat(premier.pireCellule().debut()).isEqualTo(LocalTime.of(20, 0));
        assertThat(premier.pireCellule().marge()).isEqualTo(-2);
        assertThat(rapport.jours().get(1).pireCellule().marge()).isZero();
        assertThat(rapport.pireCellule().date()).isEqualTo(JOUR);
        assertThat(rapport.pireCellule().marge()).isEqualTo(-2);
        assertThat(rapport.cellulesDeficitaires()).isEqualTo(1);
        assertThat(rapport.message()).contains("1 tranche(s) en déficit").contains("-2");
    }

    @Test
    void theReportCarriesWhatTheEditionHasNotFilledInYet() {
        Creneau matin = creneau(1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));

        RapportMarge rapport = analyzer.analyze(
                Mode.AVANT,
                seats(stand("A", 1), matin, (Animateur) null),
                List.of(animateur("alice")),
                new ParametresLegaux(),
                List.of(ReferentielManquant.CRENEAUX));

        assertThat(rapport.referentielsManquants()).containsExactly(ReferentielManquant.CRENEAUX);
        assertThat(rapport.mode()).isEqualTo(Mode.AVANT);
    }

    private RapportMarge beforeSolve(List<PosteAffectation> postes, List<Animateur> animateurs) {
        return analyzer.analyze(Mode.AVANT, postes, animateurs, new ParametresLegaux(), List.of());
    }

    private RapportMarge afterSolve(List<PosteAffectation> postes, List<Animateur> animateurs) {
        return analyzer.analyze(Mode.APRES, postes, animateurs, new ParametresLegaux(), List.of());
    }

    private static CelluleMarge cellule(RapportMarge rapport, LocalDate date, LocalTime debut) {
        return rapport.jours().stream()
                .filter(jour -> jour.date().equals(date))
                .flatMap(jour -> jour.cellules().stream())
                .filter(cellule -> cellule.debut().equals(debut))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell on " + date + " at " + debut));
    }

    private static Stand stand(String id, int effectifMin) {
        return new Stand(id, id, Set.of("JEUX"), effectifMin, effectifMin, false);
    }

    private static Creneau creneau(long id, LocalDate date, LocalTime debut, LocalTime fin) {
        return new Creneau(id, (int) (date.toEpochDay() - JOUR.toEpochDay()) + 1, date, debut, fin);
    }

    private static Animateur animateur(String id) {
        Animateur animateur = new Animateur(id, id, id.toUpperCase(Locale.ROOT), LocalDate.of(1990, 1, 1), false);
        animateur.getCompetences().put("JEUX", NiveauCompetence.AUTONOME);
        return animateur;
    }

    /** One poste per seat, {@code null} standing for a seat nobody holds. */
    private static List<PosteAffectation> seats(Stand stand, Creneau creneau, Animateur... titulaires) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (int seat = 0; seat < titulaires.length; seat++) {
            PosteAffectation poste =
                    new PosteAffectation(stand.getId() + "-" + creneau.getId() + "-" + seat, stand, creneau);
            poste.setAnimateur(titulaires[seat]);
            postes.add(poste);
        }
        return postes;
    }
}
