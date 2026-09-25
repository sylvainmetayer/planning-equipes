package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter.Motif;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The filter now answers two questions with one computation: « may this
 * animateur take this poste » for the solver, and « why not » for the banc de
 * touche screen (issue #303). This is where the two are held to being the same
 * judgement — a screen listing a reason the solver does not act on, or a solver
 * refusing something the screen calls fine, is exactly the divergence that
 * issue exists to prevent.
 *
 * <p>Its sibling {@code solver.constraints.EligibleAnimateurMoveFilterTest}
 * owns the other half of the contract: that nothing the filter rejects is
 * something the constraints would have accepted. This class owns the reasons.
 * Kept apart because that one needs a {@code ConstraintVerifier} and this one
 * needs nothing at all.</p>
 */
class EligibleAnimateurMotifsTest {

    /** The edition's legal parameters, left at the domain's defaults. */
    private static final ParametresLegaux DEFAUTS = new ParametresLegaux();

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 16);
    private static final LocalDate JOUR_FERIE = LocalDate.of(2026, 7, 14);

    @Test
    void everyReasonNamesAConstraintTheCatalogueDescribesAsHard() {
        List<String> catalogueesEnDur = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() == ConstraintCatalog.Niveau.HARD)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(Motif.values()).extracting(Motif::contrainte).isSubsetOf(catalogueesEnDur);
    }

    @Test
    void anAvailableAdultIsEligibleAndHasNoReasonAgainstThem() {
        PosteAffectation poste = poste(openStand(), creneauJournee());

        assertThat(EligibleAnimateurMoveFilter.isEligible(poste, majeur(), DEFAUTS))
                .isTrue();
        assertThat(EligibleAnimateurMoveFilter.motifs(poste, majeur(), DEFAUTS)).isEmpty();
    }

    /**
     * The value range holds a {@code null} for the unassigned seat. The
     * sibling test already covers that the filter accepts it; what matters here
     * is that a screen asking for reasons gets none rather than a spurious
     * refusal to display.
     */
    @Test
    void theEmptySeatCarriesNoReason() {
        PosteAffectation poste = poste(openStand(), creneauJournee());

        assertThat(EligibleAnimateurMoveFilter.motifs(poste, null, DEFAUTS)).isEmpty();
    }

    @Test
    void anUnavailableAnimateurIsRefusedAndSaysSo() {
        PosteAffectation poste = poste(openStand(), creneauJournee());
        Animateur indisponible = majeur();
        indisponible.setJoursIndisponibles(Set.of(JOUR));

        assertThat(EligibleAnimateurMoveFilter.isEligible(poste, indisponible, DEFAUTS))
                .isFalse();
        assertThat(EligibleAnimateurMoveFilter.motifs(poste, indisponible, DEFAUTS))
                .containsExactly(Motif.INDISPONIBLE);
    }

    /**
     * The point of listing reasons rather than returning the first one: an
     * animateur can be refused four ways at once, and knowing that lifting one
     * of them would leave three behind is the whole information.
     */
    @Test
    void everyApplicableReasonIsListedNotJustTheFirst() {
        // 16:00 → 00:00 is 8 h, 7 h 30 once the break comes off, still past the 7 h
        // cap of an under-16 — the deduction must not make this seat eligible.
        Creneau nuitDeFete = new Creneau(1L, 1, JOUR_FERIE, LocalTime.of(16, 0), LocalTime.of(0, 0));
        PosteAffectation poste = poste(adultsOnlyStand(), nuitDeFete);
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR_FERIE.minusYears(15), false);
        mineur.setJoursIndisponibles(Set.of(JOUR_FERIE));

        assertThat(EligibleAnimateurMoveFilter.motifs(poste, mineur, DEFAUTS))
                .containsExactly(
                        Motif.INDISPONIBLE,
                        Motif.STAND_RESERVE_AUX_MAJEURS,
                        Motif.JOUR_FERIE_MINEUR,
                        Motif.TRAVAIL_DE_NUIT_MINEUR,
                        Motif.DUREE_QUOTIDIENNE_MINEUR);
    }

    /**
     * A long créneau no longer caps a minor's stretch here: the break it owes
     * may be relayed by a colleague, which is a fact about the rest of the plan
     * and so the score's business (ADR 0048). Six hours count 5 h 30 of work
     * once the break is deducted, inside the 8 h day; nine hours exceed it even
     * then.
     *
     * <p>And the deduction is the edition's, not a default. Manon is fifteen,
     * so her day is capped at seven hours (art. D4153-3): a nine-hour créneau
     * is refused whatever the break, while one of 7 h 40 counts 7 h 10 at
     * thirty minutes — refused — and 6 h 55 at forty-five — accepted. The
     * filter used to read the default whatever the edition granted, which made
     * it stricter than the rule it mirrors: exactly the drift
     * {@code PlafondsLegauxMineurs} exists to prevent.
     */
    @Test
    void theBreakDeductedHereIsTheOneTheEditionGrants() {
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR.minusYears(15), false);
        Creneau sixHeures = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(16, 0));
        Creneau neufHeures = new Creneau(2L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(18, 0));
        Creneau septHeuresQuarante = new Creneau(3L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(16, 40));

        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), sixHeures), mineur, DEFAUTS))
                .isEmpty();
        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), neufHeures), mineur, DEFAUTS))
                .containsExactly(Motif.DUREE_QUOTIDIENNE_MINEUR);

        ParametresLegaux genereux = new ParametresLegaux();
        genereux.setDureePauseMinutes(45);
        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), neufHeures), mineur, genereux))
                .containsExactly(Motif.DUREE_QUOTIDIENNE_MINEUR);
        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), septHeuresQuarante), mineur, DEFAUTS))
                .containsExactly(Motif.DUREE_QUOTIDIENNE_MINEUR);
        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), septHeuresQuarante), mineur, genereux))
                .isEmpty();
    }

    /**
     * A minor is not refused on principle: on a short, daytime, non-holiday
     * créneau of an unrestricted stand, nothing here stands in their way — the
     * rules that could still stop them (adult supervision, daily totals) depend
     * on the rest of the plan and are the score's business, not this filter's.
     */
    @Test
    void aMinorOnAnOrdinarySlotIsNotRefusedHere() {
        Creneau courtEtDeJour = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0));
        PosteAffectation poste = poste(openStand(), courtEtDeJour);
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR.minusYears(15), false);

        assertThat(EligibleAnimateurMoveFilter.motifs(poste, mineur, DEFAUTS)).isEmpty();
    }

    /** {@code isEligible} is exactly « aucun motif », on every case above and their variations. */
    @Test
    void theBooleanVerdictIsExactlyTheAbsenceOfReasons() {
        List<Creneau> creneaux = List.of(
                new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0)),
                new Creneau(2L, 1, JOUR, LocalTime.of(16, 0), LocalTime.of(23, 0)),
                new Creneau(3L, 1, JOUR_FERIE, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        List<Stand> stands = List.of(openStand(), adultsOnlyStand());
        Animateur indisponible = majeur();
        indisponible.setJoursIndisponibles(Set.of(JOUR, JOUR_FERIE));
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR.minusYears(15), false);
        Animateur jeune = new Animateur("A-JEUNE", "Jules", "Fabre", JOUR.minusYears(17), false);
        List<Animateur> animateurs = List.of(majeur(), indisponible, mineur, jeune);

        for (Creneau creneau : creneaux) {
            for (Stand stand : stands) {
                PosteAffectation poste = poste(stand, creneau);
                for (Animateur animateur : animateurs) {
                    assertThat(EligibleAnimateurMoveFilter.isEligible(poste, animateur, DEFAUTS))
                            .describedAs("%s sur %s / %s", animateur.getId(), stand.getId(), creneau.getId())
                            .isEqualTo(EligibleAnimateurMoveFilter.motifs(poste, animateur, DEFAUTS)
                                    .isEmpty());
                }
            }
        }
    }

    private static Animateur majeur() {
        return new Animateur("A-MAJEUR", "Marie", "Martin", LocalDate.of(1990, 1, 1), false);
    }

    private static Creneau creneauJournee() {
        return new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0));
    }

    private static Stand openStand() {
        return new Stand("S1", "Chamboule-tout", Set.of(), 1, 2, false);
    }

    private static Stand adultsOnlyStand() {
        return new Stand("S2", "Bar", Set.of(), 1, 1, true);
    }

    private static PosteAffectation poste(Stand stand, Creneau creneau) {
        return new PosteAffectation("P1", stand, creneau);
    }
}
