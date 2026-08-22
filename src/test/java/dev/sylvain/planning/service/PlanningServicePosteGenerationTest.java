package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Exercises {@link PlanningService#buildPostes} directly (package-private,
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
        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(standA, standB), List.of(creneauOuvert));

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

        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(standA, standFerme), List.of(creneauOuvert));

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

        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(standPartiel), List.of(creneau));

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

        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(standMinMax), List.of(creneauOuvert));

        assertThat(postes).hasSize(2);
    }

    /**
     * When the créneau list spans more than one "famille" (staggered
     * relay-grid variant, see {@link VacationGeneratorService}), each stand
     * must be paired only against its own famille's créneaux — never both —
     * so the cross product stays {@code stands × 1 famille}, not
     * {@code stands × nombreFamilles}.
     */
    @Test
    void standNestPaireQuAvecLaFamilleDeCreneauxQuiLuiEstAssignee() {
        Creneau creneauFamille0 = new Creneau(10L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau creneauFamille1 = new Creneau(11L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        creneauFamille1.setFamille(1);

        List<PosteAffectation> postes = PlanningService.buildPostes(
                List.of(standA, standB), List.of(creneauFamille0, creneauFamille1));

        // Every stand shows up on ONE of the two families only, never on both
        // (otherwise there would be 4 seats, not 2: the full cartesian product
        // of the days before the offset was introduced).
        assertThat(postes).hasSize(2);
        assertThat(postes).extracting(p -> p.getStand().getId() + "->" + p.getCreneau().getFamille())
                .containsExactlyInAnyOrder("STAND-A->0", "STAND-B->1");
    }

    /**
     * Familles must be <b>balanced</b>, not merely deterministic: the
     * staggering only breaks the simultaneity peak if each grid variant
     * carries a comparable share of the demand. The hash this replaces put
     * 36 of 91 seats on one famille out of four on the reference scenario —
     * see {@code PlanningService#spreadStandsByFamily}.
     */
    @Test
    void lesStandsSontRepartisEquitablementEntreLesFamilles() {
        List<Stand> stands = new ArrayList<>();
        for (int i = 0; i < 63; i++) {
            stands.add(new Stand("STAND-" + i, "S" + i, Set.of(), 1, 1, false));
        }
        List<Creneau> creneaux = new ArrayList<>();
        for (int famille = 0; famille < 4; famille++) {
            Creneau creneau = new Creneau(100L + famille, 1, LocalDate.of(2026, 8, 14),
                    LocalTime.of(9, 0), LocalTime.of(13, 0));
            creneau.setFamille(famille);
            creneaux.add(creneau);
        }

        Map<Integer, Long> parFamille = PlanningService.buildPostes(stands, creneaux).stream()
                .collect(Collectors.groupingBy(p -> p.getCreneau().getFamille(), Collectors.counting()));

        assertThat(parFamille).hasSize(4);
        long min = parFamille.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = parFamille.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(max - min).as("écart entre la plus grosse et la plus petite famille").isLessThanOrEqualTo(1);
    }

    /** Same stands in a different order must land on the same families. */
    @Test
    void laRepartitionParFamilleEstStableQuelQueSoitLOrdreDesStands() {
        Creneau famille0 = new Creneau(10L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau famille1 = new Creneau(11L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        famille1.setFamille(1);
        List<Creneau> creneaux = List.of(famille0, famille1);

        Map<String, Integer> ordreDirect = PlanningService.buildPostes(List.of(standA, standB), creneaux).stream()
                .collect(Collectors.toMap(p -> p.getStand().getId(), p -> p.getCreneau().getFamille()));
        Map<String, Integer> ordreInverse = PlanningService.buildPostes(List.of(standB, standA), creneaux).stream()
                .collect(Collectors.toMap(p -> p.getStand().getId(), p -> p.getCreneau().getFamille()));

        assertThat(ordreInverse).isEqualTo(ordreDirect);
    }

    /**
     * EFFECTIF_REDUIT strategy: on the shift covering the meal break, the stand
     * calls up half of its staffing only, rounded up.
     */
    @Test
    void vacationDeCouverturePauseNeGenereQueLaMoitieDesSieges() {
        Stand quatre = new Stand("STAND-4", "Quatre", Set.of(), 4, 4, false);
        Stand trois = new Stand("STAND-3", "Trois", Set.of(), 3, 3, false);
        Creneau pause = new Creneau(20L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(12, 0), LocalTime.of(13, 0));
        pause.setCouverturePause(true);

        Map<String, Long> parStand = PlanningService.buildPostes(List.of(quatre, trois), List.of(pause)).stream()
                .collect(Collectors.groupingBy(p -> p.getStand().getId(), Collectors.counting()));

        assertThat(parStand).containsEntry("STAND-4", 2L);   // 4 / 2
        assertThat(parStand).containsEntry("STAND-3", 2L);   // ceil(3 / 2)
    }

    /**
     * The edge case that motivates rounding up: a stand held by a single person
     * keeps that person during the break. Rounding down it would close, which
     * would amount to FERMETURE without anybody having asked for it.
     */
    @Test
    void standAUnSeulSiegeResteOuvertPendantLaPause() {
        Creneau pause = new Creneau(21L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(12, 0), LocalTime.of(13, 0));
        pause.setCouverturePause(true);

        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(standA), List.of(pause));

        assertThat(postes).hasSize(1);
    }

    /** Outside the break-covering shift the staffing stays full — no regression. */
    @Test
    void creneauOrdinaireGardeLEffectifPlein() {
        Stand quatre = new Stand("STAND-4", "Quatre", Set.of(), 4, 4, false);

        List<PosteAffectation> postes = PlanningService.buildPostes(List.of(quatre), List.of(creneauOuvert));

        assertThat(postes).hasSize(4);
    }
}
