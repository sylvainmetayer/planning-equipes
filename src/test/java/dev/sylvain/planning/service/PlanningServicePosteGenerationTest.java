package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Exercises {@link PlanningService#construirePostes} directly (package-private,
 * no database needed) to check the per-timeslot stand availability rule: a
 * timeslot with no restriction stays open to every stand, one with a
 * restriction list only generates seats for the listed stands.
 */
class PlanningServicePosteGenerationTest {

    private final Stand standA = new Stand("STAND-A", "A", Set.of(), 1, 1, false);
    private final Stand standB = new Stand("STAND-B", "B", Set.of(), 1, 1, false);
    private final Creneau creneauOuvert = new Creneau("C1", 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));

    @Test
    void creneauSansRestrictionResteOuvertATousLesStands() {
        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(standA, standB), List.of(creneauOuvert));

        assertThat(postes).hasSize(2);
        assertThat(postes).extracting(poste -> poste.getStand().getId())
                .containsExactlyInAnyOrder("STAND-A", "STAND-B");
    }

    @Test
    void creneauRestreintNeGenereQueLesStandsListes() {
        Creneau creneauRestreint = new Creneau("C2", 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        creneauRestreint.setStandsOuvertsIds(Set.of("STAND-A"));

        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(standA, standB), List.of(creneauRestreint));

        assertThat(postes).hasSize(1);
        assertThat(postes.get(0).getStand().getId()).isEqualTo("STAND-A");
    }

    @Test
    void genereEffectifMinSeatsPasEffectifMax() {
        // effectifMin != effectifMax here on purpose: standA/standB above use
        // identical values and would silently pass even if this regressed back
        // to effectifMax, which is exactly the bug that made solving from
        // reference data generate 2736 mandatory seats instead of the 2088 the
        // scenario actually needs (effectifMax is the capacity ceiling, not the
        // number of seats that must be staffed).
        Stand standMinMax = new Stand("STAND-C", "C", Set.of(), 2, 5, false);

        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(standMinMax), List.of(creneauOuvert));

        assertThat(postes).hasSize(2);
    }
}
