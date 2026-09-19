package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.RenfortAnalyzer.RapportRenforts;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Where the bonus hours sit, on a problem built by hand: no container, no
 * database, no solve.
 */
class RenfortAnalyzerTest {

    private static final LocalDate J1 = LocalDate.of(2027, 7, 8);
    private static final LocalDate J2 = LocalDate.of(2027, 7, 9);

    private final RenfortAnalyzer analyzer = new RenfortAnalyzer();

    private final Stand bar = new Stand("BAR", "Le bar", Set.of("STRATEGIE"), 1, 3, false);
    private final Stand cirque = new Stand("CIRQUE", "Le cirque", Set.of("STRATEGIE"), 2, 2, false);
    private final Creneau matinJ1 = new Creneau(1L, 1, J1, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Creneau matinJ2 = new Creneau(2L, 2, J2, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Animateur alice = new Animateur("A1", "Alice", "A", LocalDate.of(2000, 1, 1), false);

    private static PosteAffectation renfort(String id, Stand stand, Creneau creneau) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setOptionnel(true);
        return poste;
    }

    @Test
    void theHoursAreCountedPerStandAndPerDay() {
        List<PosteAffectation> seats = List.of(
                new PosteAffectation("du-1", bar, matinJ1),
                renfort("renfort-1", bar, matinJ1),
                renfort("renfort-2", bar, matinJ2),
                new PosteAffectation("du-2", cirque, matinJ1));

        RapportRenforts rapport = analyzer.analyze(seats, List.of());

        assertThat(rapport.jours()).containsExactly(J1, J2);
        // Only the stand declaring a margin is a row: the cirque, whose min
        // equals its max, has nothing to give and nothing to say.
        assertThat(rapport.stands())
                .extracting(RenfortAnalyzer.LigneRenfort::standId)
                .containsExactly("BAR");
        assertThat(rapport.stands().get(0).heuresOuvertes()).isCloseTo(7.0, within(0.001));
        assertThat(rapport.stands().get(0).jours())
                .extracting(RenfortAnalyzer.CelluleRenfort::date, RenfortAnalyzer.CelluleRenfort::heuresOuvertes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(J1, 4.0), org.assertj.core.groups.Tuple.tuple(J2, 3.0));
        assertThat(rapport.heuresOuvertes()).isCloseTo(7.0, within(0.001));
        // Four hours owed on the bar, four on the cirque.
        assertThat(rapport.heuresDues()).isCloseTo(8.0, within(0.001));
        assertThat(rapport.planEnregistre()).isFalse();
    }

    @Test
    void whatWasStaffedIsReadFromThePersistedPlanAndCountedApart() {
        List<PosteAffectation> seats = List.of(renfort("renfort-1", bar, matinJ1), renfort("renfort-2", bar, matinJ2));
        PosteAffectation tenu = renfort("persiste-1", bar, matinJ1);
        tenu.setAnimateur(alice);
        List<PosteAffectation> persiste = List.of(tenu, renfort("persiste-2", bar, matinJ2));

        RapportRenforts rapport = analyzer.analyze(seats, persiste);

        assertThat(rapport.heuresOuvertes()).isCloseTo(7.0, within(0.001));
        // Only the seat somebody holds: an empty renfort costs nothing.
        assertThat(rapport.heuresPourvues()).isCloseTo(4.0, within(0.001));
        assertThat(rapport.planEnregistre()).isTrue();
        assertThat(rapport.stands().get(0).jours())
                .extracting(RenfortAnalyzer.CelluleRenfort::heuresPourvues)
                .containsExactly(4.0, 0.0);
    }

    @Test
    void anOwedSeatNeverCountsWhateverItHolds() {
        PosteAffectation du = new PosteAffectation("du-1", bar, matinJ1);
        du.setAnimateur(alice);

        RapportRenforts rapport = analyzer.analyze(List.of(du), List.of(du));

        assertThat(rapport.stands()).isEmpty();
        assertThat(rapport.heuresOuvertes()).isZero();
        assertThat(rapport.heuresPourvues()).isZero();
        assertThat(rapport.heuresDues()).isCloseTo(4.0, within(0.001));
    }

    @Test
    void aRenfortNarrowedByAClosureCountsOnlyTheTimeItCovers() {
        PosteAffectation reduit = renfort("renfort-1", bar, matinJ1);
        reduit.setHeureFinEffective(LocalTime.of(11, 0));

        RapportRenforts rapport = analyzer.analyze(List.of(reduit), List.of());

        assertThat(rapport.heuresOuvertes()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void theStandWithTheMostToGiveComesFirstAndCarriesItsLocation() {
        bar.setEmplacement(new Emplacement("PLACE", "La place", null, null));
        List<PosteAffectation> seats = List.of(
                renfort("renfort-1", cirque, matinJ2),
                renfort("renfort-2", bar, matinJ1),
                renfort("renfort-3", bar, matinJ2));

        RapportRenforts rapport = analyzer.analyze(seats, List.of());

        assertThat(rapport.stands())
                .extracting(RenfortAnalyzer.LigneRenfort::standId)
                .containsExactly("BAR", "CIRQUE");
        assertThat(rapport.stands().get(0).emplacementNom()).isEqualTo("La place");
        assertThat(rapport.stands().get(1).emplacementNom()).isNull();
    }

    @Test
    void anEditionWithoutSeatSaysSoRatherThanReportingNoRenfort() {
        assertThat(analyzer.analyze(List.of(), List.of()).message()).contains("pas encore de stand");
        assertThat(analyzer.analyze(List.of(new PosteAffectation("du-1", cirque, matinJ1)), List.of())
                        .message())
                .contains("Aucun renfort");
    }
}
