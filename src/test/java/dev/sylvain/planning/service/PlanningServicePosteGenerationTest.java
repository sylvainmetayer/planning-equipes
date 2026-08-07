package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Exercises {@link PlanningService#construirePostes} directly (package-private,
 * no database needed) to check the stand-availability rule: a stand with no
 * closure stays open on every timeslot, a stand closed for a whole créneau
 * generates no poste on it, and a stand closed for only part of a créneau
 * (issue #60) generates one poste per still-open segment, each carrying the
 * narrowed effective time window.
 */
class PlanningServicePosteGenerationTest {

    private final Stand standA = new Stand("STAND-A", "A", Set.of(), 1, 1, false);
    private final Stand standB = new Stand("STAND-B", "B", Set.of(), 1, 1, false);
    private final Creneau creneauOuvert = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));

    @Test
    void creneauSansRestrictionResteOuvertATousLesStands() {
        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(standA, standB), List.of(creneauOuvert));

        assertThat(postes).hasSize(2);
        assertThat(postes).extracting(poste -> poste.getStand().getId())
                .containsExactlyInAnyOrder("STAND-A", "STAND-B");
        assertThat(postes).allSatisfy(poste -> assertThat(poste.getHeureDebutEffective()).isNull());
    }

    @Test
    void standFermeIntegralementNeGenereAucunPoste() {
        Stand standFerme = new Stand("STAND-B", "B", Set.of(), 1, 1, false);
        standFerme.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0), null)));

        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(standA, standFerme), List.of(creneauOuvert));

        assertThat(postes).hasSize(1);
        assertThat(postes.get(0).getStand().getId()).isEqualTo("STAND-A");
    }

    /** Issue #60: a stand closed for only part of a créneau still needs staffing for the open remainder. */
    @Test
    void fermeturePartielleGenereUnPostePourChaqueSegmentOuvert() {
        Creneau creneau = new Creneau(3L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand standPartiel = new Stand("STAND-A", "A", Set.of(), 1, 1, false);
        standPartiel.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, LocalDate.of(2026, 8, 14), LocalTime.of(11, 0), LocalTime.of(13, 0), "Pause")));

        List<PosteAffectation> postes = PlanningService.construirePostes(List.of(standPartiel), List.of(creneau));

        assertThat(postes).hasSize(2);
        assertThat(postes).extracting(PosteAffectation::getHeureDebutEffective, PosteAffectation::getHeureFinEffective)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(9, 0), LocalTime.of(11, 0)),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(13, 0), LocalTime.of(14, 0)));
        assertThat(postes).allSatisfy(poste -> assertThat(poste.getCreneau()).isSameAs(creneau));
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
