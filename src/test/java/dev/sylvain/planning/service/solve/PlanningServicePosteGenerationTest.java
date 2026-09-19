package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ProblemBuilder#buildPostes} directly (package-private,
 * no database needed) to check the stand-availability rule: a stand with no
 * closure stays open on every timeslot, a stand closed for a whole créneau
 * generates no poste on it, and a stand closed for only part of a créneau
 * (issue #60) generates one poste per still-open segment, each carrying the
 * narrowed effective time window.
 */
class PlanningServicePosteGenerationTest {

    private final Stand standA = new Stand("STAND-A", "A", Set.of(), 1, 1, false);
    private final Stand standB = new Stand("STAND-B", "B", Set.of(), 1, 1, false);
    private final Creneau creneauOuvert =
            new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));

    @Test
    void creneauSansRestrictionResteOuvertATousLesStands() {
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(standA, standB), List.of(creneauOuvert));

        assertThat(postes).hasSize(2);
        assertThat(postes)
                .extracting(poste -> poste.getStand().getId())
                .containsExactlyInAnyOrder("STAND-A", "STAND-B");
        assertThat(postes)
                .allSatisfy(poste -> assertThat(poste.getHeureDebutEffective()).isNull());
    }

    /**
     * The persisted plan is read back with {@code ORDER BY id} on a
     * {@code VARCHAR} column, and re-seeded positionally: a seat id that does
     * not sort the way it was generated puts people back on a neighbouring
     * seat — another effective window, on a day that may be locked.
     */
    @Test
    void seatIdsSortInTheOrderTheyWereGenerated() {
        List<Creneau> creneaux = new java.util.ArrayList<>();
        for (int jour = 1; jour <= 120; jour++) {
            creneaux.add(new Creneau(
                    (long) jour,
                    jour,
                    LocalDate.of(2026, 8, 14).plusDays(jour),
                    LocalTime.of(9, 0),
                    LocalTime.of(13, 0)));
        }

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(standA, standB), creneaux);

        assertThat(postes).hasSize(240);
        assertThat(postes).extracting(PosteAffectation::getId).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test
    void standFermeIntegralementNeGenereAucunPoste() {
        Stand standFerme = new Stand("STAND-B", "B", Set.of(), 1, 1, false);
        standFerme.setIndisponibilites(List.of(new IndisponibiliteStand(
                null, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0), null)));

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(standA, standFerme), List.of(creneauOuvert));

        assertThat(postes).hasSize(1);
        assertThat(postes.get(0).getStand().getId()).isEqualTo("STAND-A");
    }

    /** Issue #60: a stand closed for only part of a créneau still needs staffing for the open remainder. */
    @Test
    void fermeturePartielleGenereUnPostePourChaqueSegmentOuvert() {
        Creneau creneau = new Creneau(3L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(14, 0));
        Stand standPartiel = new Stand("STAND-A", "A", Set.of(), 1, 1, false);
        standPartiel.setIndisponibilites(List.of(new IndisponibiliteStand(
                null, LocalDate.of(2026, 8, 14), LocalTime.of(11, 0), LocalTime.of(13, 0), "Pause")));

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(standPartiel), List.of(creneau));

        assertThat(postes).hasSize(2);
        assertThat(postes)
                .extracting(PosteAffectation::getHeureDebutEffective, PosteAffectation::getHeureFinEffective)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(9, 0), LocalTime.of(11, 0)),
                        org.assertj.core.groups.Tuple.tuple(LocalTime.of(13, 0), LocalTime.of(14, 0)));
        assertThat(postes).allSatisfy(poste -> assertThat(poste.getCreneau()).isSameAs(creneau));
    }

    /** The seats {@code posteDoitEtrePourvu} will demand — renforts excluded. */
    private static List<PosteAffectation> mandatory(List<PosteAffectation> postes) {
        return postes.stream().filter(poste -> !poste.isOptionnel()).toList();
    }

    private static List<PosteAffectation> optional(List<PosteAffectation> postes) {
        return postes.stream().filter(PosteAffectation::isOptionnel).toList();
    }

    @Test
    void effectifMaxGeneratesRenfortsAndNeverAMandatorySeat() {
        // effectifMin != effectifMax here on purpose: standA/standB above use
        // identical values and would silently pass even if this regressed back
        // to effectifMax, which is exactly the bug that made solving from
        // reference data generate 2736 mandatory seats instead of the 2088 the
        // scenario actually needs (effectifMax is the capacity ceiling, not the
        // number of seats that must be staffed). Since issue #505 the ceiling
        // does generate seats — optional ones, which nobody is owed.
        Stand standMinMax = new Stand("STAND-C", "C", Set.of(), 2, 5, false);

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(standMinMax), List.of(creneauOuvert));

        assertThat(mandatory(postes)).hasSize(2);
        assertThat(optional(postes)).hasSize(3);
        // And the renforts come last, which is what makes a re-seeding of the
        // persisted plan land its people on the seats that are owed first.
        assertThat(postes.subList(0, 2))
                .allSatisfy(poste -> assertThat(poste.isOptionnel()).isFalse());
    }

    /**
     * The reason {@code FenetreHoraire.effectif} exists: a stand needing 4
     * people until 19:00 and 2 after is one stand, and one créneau must yield
     * both groups — each narrowed to its own window. Applying
     * {@code effectifMin} to the whole slot is what made a real event's
     * planning cover a third fewer hours than its source workbook needed.
     */
    @Test
    void theWindowEffectifDrivesTheSeatCountOfEachSegment() {
        LocalDate jour = LocalDate.of(2026, 8, 14);
        Creneau apresMidi = new Creneau(4L, 1, jour, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Stand stand = new Stand("STAND-D", "D", Set.of(), 1, 6, false);
        stand.setOuvertures(List.of(
                new OuvertureStand(null, jour, LocalTime.of(14, 0), LocalTime.of(19, 0), null, 4),
                new OuvertureStand(null, jour, LocalTime.of(19, 0), LocalTime.of(20, 0), null, 2)));

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), List.of(apresMidi));

        assertThat(mandatory(postes)).hasSize(6);
        assertThat(mandatory(postes))
                .extracting(PosteAffectation::getHeureDebutEffective, PosteAffectation::getHeureFinEffective)
                .containsExactly(
                        tuple(LocalTime.of(14, 0), LocalTime.of(19, 0)),
                        tuple(LocalTime.of(14, 0), LocalTime.of(19, 0)),
                        tuple(LocalTime.of(14, 0), LocalTime.of(19, 0)),
                        tuple(LocalTime.of(14, 0), LocalTime.of(19, 0)),
                        tuple(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                        tuple(LocalTime.of(19, 0), LocalTime.of(20, 0)));
        // The renfort band is per segment too: the ceiling is 6, so the window
        // needing 4 has room for 2 more and the one needing 2 for 4 more.
        assertThat(optional(postes))
                .extracting(PosteAffectation::getHeureDebutEffective, PosteAffectation::getHeureFinEffective)
                .containsExactly(
                        tuple(LocalTime.of(14, 0), LocalTime.of(19, 0)),
                        tuple(LocalTime.of(14, 0), LocalTime.of(19, 0)),
                        tuple(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                        tuple(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                        tuple(LocalTime.of(19, 0), LocalTime.of(20, 0)),
                        tuple(LocalTime.of(19, 0), LocalTime.of(20, 0)));
    }

    /**
     * A window naming no effectif must generate exactly what it generated
     * before the field existed — otherwise adding the column would silently
     * change the volume of every edition already in the database.
     */
    @Test
    void aWindowNamingNoEffectifStillGeneratesEffectifMinSeats() {
        LocalDate jour = LocalDate.of(2026, 8, 14);
        Creneau apresMidi = new Creneau(5L, 1, jour, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Stand stand = new Stand("STAND-E", "E", Set.of(), 3, 6, false);
        stand.setOuvertures(List.of(new OuvertureStand(null, jour, LocalTime.of(14, 0), LocalTime.of(20, 0), null)));

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), List.of(apresMidi));

        assertThat(mandatory(postes)).hasSize(3);
        assertThat(postes)
                .allSatisfy(poste -> assertThat(poste.getHeureDebutEffective()).isNull());
    }

    /**
     * On a break-covering slot (EFFECTIF_REDUIT) the halving applies to the
     * <em>segment's</em> effectif, not the stand's: the relay is half of
     * whoever was actually on duty at that hour.
     */
    @Test
    void laCouverturePauseHalveLEffectifDeLaFenetrePasCeluiDuStand() {
        LocalDate jour = LocalDate.of(2026, 8, 14);
        Creneau releve = new Creneau(6L, 1, jour, LocalTime.of(12, 0), LocalTime.of(13, 0));
        releve.setCouverturePause(true);
        Stand stand = new Stand("STAND-F", "F", Set.of(), 1, 6, false);
        stand.setOuvertures(List.of(new OuvertureStand(null, jour, LocalTime.of(12, 0), LocalTime.of(13, 0), null, 5)));

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(stand), List.of(releve));

        // ceil(5 / 2) = 3, not ceil(effectifMin=1 / 2) = 1.
        assertThat(postes).hasSize(3);
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

        Map<String, Long> parStand = ProblemBuilder.buildPostes(List.of(quatre, trois), List.of(pause)).stream()
                .collect(Collectors.groupingBy(p -> p.getStand().getId(), Collectors.counting()));

        assertThat(parStand).containsEntry("STAND-4", 2L); // 4 / 2
        assertThat(parStand).containsEntry("STAND-3", 2L); // ceil(3 / 2)
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

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(standA), List.of(pause));

        assertThat(postes).hasSize(1);
    }

    /** Outside the break-covering shift the staffing stays full — no regression. */
    @Test
    void creneauOrdinaireGardeLEffectifPlein() {
        Stand quatre = new Stand("STAND-4", "Quatre", Set.of(), 4, 4, false);

        List<PosteAffectation> postes = ProblemBuilder.buildPostes(List.of(quatre), List.of(creneauOuvert));

        assertThat(postes).hasSize(4);
    }
}
