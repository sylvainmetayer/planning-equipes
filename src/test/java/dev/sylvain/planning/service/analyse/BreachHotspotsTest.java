package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.BreachHotspots.Hotspot;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** « Où » on a card of the Problèmes screen: the stands and timeslots gathering most of a rule's breaches. */
class BreachHotspotsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 7);

    @Test
    void ranksThePlacesByBreachesAndKeepsThree() {
        Stand jeux = stand("JEUX");
        Stand quiz = stand("QUIZ");
        Creneau morning = creneau(1, MONDAY);
        Creneau evening = creneau(2, MONDAY);
        Creneau tuesday = creneau(3, TUESDAY);
        List<MatchFacts> matches = List.of(
                seat(jeux, evening),
                seat(jeux, evening),
                seat(jeux, evening),
                seat(quiz, morning),
                seat(quiz, morning),
                seat(jeux, tuesday),
                seat(quiz, tuesday));

        assertThat(BreachHotspots.of(matches))
                .containsExactly(
                        new Hotspot("JEUX", MONDAY, 2L, 3),
                        new Hotspot("QUIZ", MONDAY, 1L, 2),
                        new Hotspot("JEUX", TUESDAY, 3L, 1));
    }

    @Test
    void aSeatNamesItsOwnStandAndTimeslotNeverItsNeighbours() {
        // A consecutive pair on two stands: each seat is a place of its own,
        // never the first stand crossed with the second timeslot.
        List<Object> pair =
                List.of(poste(stand("BLEU"), creneau(1, MONDAY)), poste(stand("ROUGE"), creneau(2, MONDAY)));

        assertThat(BreachHotspots.of(List.of(new MatchFacts(pair))))
                .containsExactlyInAnyOrder(new Hotspot("BLEU", MONDAY, 1L, 1), new Hotspot("ROUGE", MONDAY, 2L, 1));
    }

    @Test
    void aStandAndItsTimeslotNamedApartAreCrossed() {
        Stand jeux = stand("JEUX");
        Creneau creneau = creneau(4, TUESDAY);

        assertThat(BreachHotspots.of(List.of(new MatchFacts(List.of(jeux, creneau)))))
                .containsExactly(new Hotspot("JEUX", TUESDAY, 4L, 1));
    }

    @Test
    void aMatchNamingADayOnlyCountsOnThatDay() {
        assertThat(BreachHotspots.of(List.of(new MatchFacts(List.of(animateur("a"), MONDAY)))))
                .containsExactly(new Hotspot(null, MONDAY, null, 1));
    }

    @Test
    void anAggregateNamingNobodysPlaceHasNoHotspot() {
        assertThat(BreachHotspots.of(List.of(new MatchFacts(List.of(animateur("a"), animateur("b"))))))
                .isEmpty();
        assertThat(BreachHotspots.of(List.of())).isEmpty();
    }

    private static MatchFacts seat(Stand stand, Creneau creneau) {
        return new MatchFacts(List.of(poste(stand, creneau)));
    }

    private static Stand stand(String id) {
        return new Stand(id, id, Set.of("JEUX"), 1, 2, false);
    }

    private static Creneau creneau(long id, LocalDate date) {
        return new Creneau(id, 1, date, LocalTime.of(9, 0), LocalTime.of(13, 0));
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
    }

    private static PosteAffectation poste(Stand stand, Creneau creneau) {
        PosteAffectation poste = new PosteAffectation("P-" + stand.getId() + creneau.getId(), stand, creneau);
        poste.setAnimateur(animateur("x"));
        return poste;
    }
}
