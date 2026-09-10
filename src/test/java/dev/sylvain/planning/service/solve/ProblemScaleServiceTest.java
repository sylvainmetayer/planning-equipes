package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.ProblemScaleService.ProblemScale;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two hour figures of the volumétrie card, on a problem built by hand: no
 * container, no solver — {@link ProblemScale#of} is pure.
 */
class ProblemScaleServiceTest {

    /** A Monday: the event below stays inside one ISO week unless said otherwise. */
    private static final LocalDate LUNDI = LocalDate.of(2030, 9, 2);

    private static final Stand STAND = new Stand("S1", "Stand", Set.of("STRATEGIE"), 1, 1, false);

    @Test
    void hoursToFillSumTheEffectiveDurationOfEverySeatWhetherStaffedOrNot() {
        Creneau matin = creneau(1, LUNDI, 9, 12);
        Creneau apresMidi = creneau(2, LUNDI, 14, 18);
        PosteAffectation entier = new PosteAffectation("P1", STAND, matin);
        PosteAffectation reduit = new PosteAffectation("P2", STAND, apresMidi);
        // A stand closing at 16h narrows the seat: two hours staffed, not four.
        reduit.setHeureFinEffective(LocalTime.of(16, 0));

        ProblemScale volumetrie =
                ProblemScale.of(planning(List.of(matin, apresMidi), List.of(), List.of(entier, reduit)));

        assertThat(volumetrie.posteCount()).isEqualTo(2);
        assertThat(volumetrie.hoursToFill()).isCloseTo(5.0, within(0.001));
        assertThat(volumetrie.hoursAvailable()).isZero();
    }

    @Test
    void hoursAvailableAreTheDailyCeilingOfEachDayTheAnimateurCanCome() {
        List<Creneau> troisJours = List.of(
                creneau(1, LUNDI, 9, 12), creneau(2, LUNDI.plusDays(1), 9, 12), creneau(3, LUNDI.plusDays(2), 9, 12));
        Animateur majeur = animateur("A1", LocalDate.of(1990, 1, 1));
        majeur.setJoursIndisponibles(Set.of(LUNDI.plusDays(1)));

        ProblemScale volumetrie = ProblemScale.of(planning(troisJours, List.of(majeur), List.of()));

        // Two days available out of three, ten hours each; the 48 h week is far away.
        assertThat(volumetrie.hoursAvailable()).isCloseTo(20.0, within(0.001));
    }

    @Test
    void aMinorGetsTheCeilingOfTheirAgeAndTheirOwnWeeklyLimit() {
        List<Creneau> septJours = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            septJours.add(creneau(i + 1, LUNDI.plusDays(i), 9, 12));
        }
        // Seventeen during the event, so 8 h a day; under 16 would be 7 h.
        Animateur mineur = animateur("M1", LUNDI.minusYears(17));
        Animateur majeur = animateur("A1", LUNDI.minusYears(30));

        ProblemScale volumetrie = ProblemScale.of(
                planning(septJours, List.of(mineur, majeur), List.of(), new ParametresLegaux(48 * 60, 35 * 60)));

        // Seven days but six worked at most: 6 × 8 h = 48 h, capped at 35 h
        // for the minor; 6 × 10 h = 60 h, capped at 48 h for the adult.
        assertThat(volumetrie.hoursAvailable()).isCloseTo(35.0 + 48.0, within(0.001));
    }

    @Test
    void theWeeklyCeilingAppliesPerIsoWeek() {
        // Saturday and Sunday, then Monday: two ISO weeks, each under the ceiling.
        LocalDate samedi = LUNDI.plusDays(5);
        List<Creneau> aCheval = List.of(
                creneau(1, samedi, 9, 12),
                creneau(2, samedi.plusDays(1), 9, 12),
                creneau(3, samedi.plusDays(2), 9, 12));
        Animateur majeur = animateur("A1", LocalDate.of(1990, 1, 1));

        ProblemScale volumetrie =
                ProblemScale.of(planning(aCheval, List.of(majeur), List.of(), new ParametresLegaux(15 * 60, 35 * 60)));

        // 20 h capped at 15 h in the first week, 10 h in the second.
        assertThat(volumetrie.hoursAvailable()).isCloseTo(25.0, within(0.001));
    }

    private static Creneau creneau(long id, LocalDate date, int debut, int fin) {
        return new Creneau(id, 1, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0));
    }

    private static Animateur animateur(String id, LocalDate naissance) {
        return new Animateur(id, "Prenom", "Nom", naissance, false);
    }

    private static PlanningEvenement planning(
            List<Creneau> creneaux, List<Animateur> animateurs, List<PosteAffectation> postes) {
        return planning(creneaux, animateurs, postes, new ParametresLegaux());
    }

    /**
     * A planning carries no timeslot list of its own — the event's days are
     * read off its seats — so every timeslot without a seat gets one here.
     */
    private static PlanningEvenement planning(
            List<Creneau> creneaux,
            List<Animateur> animateurs,
            List<PosteAffectation> postes,
            ParametresLegaux legaux) {
        List<PosteAffectation> sieges = new java.util.ArrayList<>(postes);
        for (Creneau creneau : creneaux) {
            if (postes.stream().noneMatch(poste -> poste.getCreneau() == creneau)) {
                sieges.add(new PosteAffectation("P-" + creneau.getId(), STAND, creneau));
            }
        }
        PlanningEvenement planning = new PlanningEvenement(LUNDI, animateurs, sieges);
        planning.setParametresLegaux(List.of(legaux));
        return planning;
    }
}
