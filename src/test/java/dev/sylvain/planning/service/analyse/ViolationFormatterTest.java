package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The ids a violation hands to the screen, so a symptom links to the fiche that
 * fixes it (issue #489). What matters here is when it hands over <b>nothing</b>:
 * a link to the wrong fiche is worse than no link, since the reader opens it,
 * finds nothing wrong, and stops trusting the others.
 */
class ViolationFormatterTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 16);

    private static Animateur animateur(String id) {
        return new Animateur(id, "Prénom " + id, "Nom", LocalDate.of(1990, 1, 1), false);
    }

    private static Creneau creneau() {
        return new Creneau(1L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    @Test
    void aSeatNamesItsThreeObjects() {
        Stand tir = new Stand("STAND-1", "Tir", Set.of("STRAT"), 1, 2, false);
        Animateur alice = animateur("A1");

        ViolationFormatter.ViolationReference reference =
                ViolationFormatter.references(List.of(poste("P1", tir, creneau(), alice)));

        assertThat(reference.animateurId()).isEqualTo("A1");
        assertThat(reference.standId()).isEqualTo("STAND-1");
        assertThat(reference.creneauId()).isEqualTo(1L);
    }

    /**
     * Two animateurs — {@code incompatibiliteAdHoc} names exactly that — and
     * the first of them is not « the » one. « The first of each kind » sent the
     * reader to a fiche picked by the order of a justification.
     */
    @Test
    void aMatchNamingTwoPeopleNamesNeither() {
        ViolationFormatter.ViolationReference reference =
                ViolationFormatter.references(List.of(animateur("A1"), animateur("A2")));

        assertThat(reference.animateurId()).isNull();
        assertThat(reference.texte()).contains("A1").contains("A2");
    }

    /**
     * A rule grouping the seats of one person hands over the whole list: the
     * person is still worth a link, the stand is not.
     */
    @Test
    void anAggregatedMatchKeepsWhatItDesignatesAndDropsTheRest() {
        Stand tir = new Stand("STAND-1", "Tir", Set.of("STRAT"), 1, 2, false);
        Stand quiz = new Stand("STAND-2", "Quiz", Set.of("STRAT"), 1, 2, false);
        Creneau matin = creneau();
        Animateur alice = animateur("A1");

        ViolationFormatter.ViolationReference reference = ViolationFormatter.references(
                List.of(List.of(poste("P1", tir, matin, alice), poste("P2", quiz, matin, alice))));

        assertThat(reference.animateurId()).isEqualTo("A1");
        assertThat(reference.standId()).isNull();
        assertThat(reference.creneauId()).isEqualTo(1L);
    }

    /** A cap names its game category by its label: its id is a number drawn by the edition (ADR 0050). */
    @Test
    void aCapNamesItsGameCategoryByItsLabel() {
        assertThat(ViolationFormatter.describe(List.of(new QuotaTypologie("T3", "Hommes jeu", 4))))
                .contains("« Hommes jeu »")
                .doesNotContain("T3");
        assertThat(ViolationFormatter.describe(List.of(new QuotaTypologie("T3", 4))))
                .contains("« T3 »");
    }
}
