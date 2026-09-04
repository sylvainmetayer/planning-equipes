package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter.Motif;

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

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 16);
    private static final LocalDate JOUR_FERIE = LocalDate.of(2026, 7, 14);

    @Test
    void everyReasonNamesAConstraintTheCatalogueDescribesAsHard() {
        List<String> catalogueesEnDur = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() == ConstraintCatalog.Niveau.HARD)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(Motif.values())
                .extracting(Motif::contrainte)
                .isSubsetOf(catalogueesEnDur);
    }

    @Test
    void anAvailableAdultIsEligibleAndHasNoReasonAgainstThem() {
        PosteAffectation poste = poste(openStand(), creneauJournee());

        assertThat(EligibleAnimateurMoveFilter.isEligible(poste, majeur(), false)).isTrue();
        assertThat(EligibleAnimateurMoveFilter.motifs(poste, majeur(), false)).isEmpty();
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

        assertThat(EligibleAnimateurMoveFilter.motifs(poste, null, false)).isEmpty();
    }

    @Test
    void anUnavailableAnimateurIsRefusedAndSaysSo() {
        PosteAffectation poste = poste(openStand(), creneauJournee());
        Animateur indisponible = majeur();
        indisponible.setJoursIndisponibles(Set.of(JOUR));

        assertThat(EligibleAnimateurMoveFilter.isEligible(poste, indisponible, false)).isFalse();
        assertThat(EligibleAnimateurMoveFilter.motifs(poste, indisponible, false))
                .containsExactly(Motif.INDISPONIBLE);
    }

    /**
     * The point of listing reasons rather than returning the first one: an
     * animateur can be refused four ways at once, and knowing that lifting one
     * of them would leave three behind is the whole information.
     */
    @Test
    void everyApplicableReasonIsListedNotJustTheFirst() {
        Creneau nuitDeFete = new Creneau(1L, 1, JOUR_FERIE, LocalTime.of(16, 0), LocalTime.of(23, 30));
        PosteAffectation poste = poste(adultsOnlyStand(), nuitDeFete);
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR_FERIE.minusYears(15), false);
        mineur.setJoursIndisponibles(Set.of(JOUR_FERIE));

        assertThat(EligibleAnimateurMoveFilter.motifs(poste, mineur, false))
                .containsExactly(Motif.INDISPONIBLE, Motif.STAND_RESERVE_AUX_MAJEURS, Motif.JOUR_FERIE_MINEUR,
                        Motif.TRAVAIL_DE_NUIT_MINEUR, Motif.DUREE_QUOTIDIENNE_MINEUR, Motif.TRAVAIL_CONTINU_MINEUR);
    }

    /**
     * A minor is not refused on principle: on a short, daytime, non-holiday
     * créneau of an unrestricted stand, nothing here stands in their way — the
     * rules that could still stop them (adult supervision, daily totals) depend
     * on the rest of the plan and are the score's business, not this filter's.
     */
    /**
     * With the organiser's declaration that breaks are taken on the post, a
     * six-hour créneau no longer caps a minor's stretch and counts 5 h 30 of
     * work — exactly what {@code travailContinuMaxMineur} and
     * {@code dureeQuotidienneMaxMineur} then accept. A nine-hour one still
     * exceeds the 8 h day even once its break is deducted.
     */
    @Test
    void aDeclaredOnPostBreakIsReadHereAsTheConstraintsReadIt() {
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR.minusYears(15), false);
        Creneau sixHeures = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(16, 0));
        Creneau neufHeures = new Creneau(2L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(18, 0));

        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), sixHeures), mineur, false))
                .containsExactly(Motif.TRAVAIL_CONTINU_MINEUR);
        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), sixHeures), mineur, true)).isEmpty();
        assertThat(EligibleAnimateurMoveFilter.motifs(poste(openStand(), neufHeures), mineur, true))
                .containsExactly(Motif.DUREE_QUOTIDIENNE_MINEUR);
    }

    @Test
    void aMinorOnAnOrdinarySlotIsNotRefusedHere() {
        Creneau courtEtDeJour = new Creneau(1L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0));
        PosteAffectation poste = poste(openStand(), courtEtDeJour);
        Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", JOUR.minusYears(15), false);

        assertThat(EligibleAnimateurMoveFilter.motifs(poste, mineur, false)).isEmpty();
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
                    assertThat(EligibleAnimateurMoveFilter.isEligible(poste, animateur, false))
                            .describedAs("%s sur %s / %s", animateur.getId(), stand.getId(), creneau.getId())
                            .isEqualTo(EligibleAnimateurMoveFilter.motifs(poste, animateur, false).isEmpty());
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
