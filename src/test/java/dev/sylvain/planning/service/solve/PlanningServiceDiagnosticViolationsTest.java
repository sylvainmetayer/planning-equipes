package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.solver.ConstraintFloorRules;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * {@code diagnose()} must turn a hard-constraint match into a
 * human-readable line (so the Contraintes page can show "who/what/when" to a
 * non-technical user), but must not bother doing that for medium/soft
 * constraints, whose match counts can run into the thousands.
 *
 * <p>It must also say <em>which hand-entered exception</em> a violated ad hoc
 * rule is about (issue #84): "affectationForcee: 12" is where a reader stops,
 * and the exceptions are the only thing anyone can act on.</p>
 *
 * <p>And it must read a rule that penalises everything for lack of data as a
 * <em>floor</em> (issue #495): on a real edition two such rules made 68 % of
 * the medium score, and nothing said those points would never move.</p>
 */
class PlanningServiceDiagnosticViolationsTest {

    @Test
    void aViolatedAdHocRuleNamesTheExceptionItIsAbout() {
        PlanningService planningService = planningService();

        Stand stand = new Stand("STAND-1", "Stand tir à l'arc", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(12, 30), LocalTime.of(15, 30));
        Animateur animateur = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        // The seat is staffed by somebody else, so the forced assignment of A1
        // has nowhere to land: affectationForcee matches, on C1.
        Animateur occupant = new Animateur("A2", "Bob", "Durand", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(occupant);
        ContrainteAdHoc forcee = new ContrainteAdHoc("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        forcee.setAnimateursConcernes(List.of(animateur));
        forcee.setCreneau(creneau);
        forcee.setRaison("Promesse faite en juin");

        PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnose(new PlanningEvenement(
                creneau.getDate(), List.of(animateur, occupant), List.of(poste), List.of(forcee)));

        assertThat(diagnostic.contraintesAdHocEnCause()).singleElement().satisfies(contribution -> {
            assertThat(contribution.contrainteId()).isEqualTo("C1");
            assertThat(contribution.type()).isEqualTo("AFFECTATION_FORCEE");
            assertThat(contribution.raison()).isEqualTo("Promesse faite en juin");
            assertThat(contribution.violations()).isEqualTo(1);
            assertThat(contribution.contraintes()).containsExactly("affectationForcee");
        });

        // The per-match line names it too: the id is what the ad hoc screen shows.
        assertThat(diagnostic.contraintes())
                .filteredOn(c -> c.name().equals("affectationForcee"))
                .singleElement()
                .satisfies(c ->
                        assertThat(c.violations()).containsExactly("AFFECTATION_FORCEE C1 (Promesse faite en juin)"));
    }

    @Test
    void aPlanHonouringItsExceptionsBlamesNone() {
        PlanningService planningService = planningService();

        Stand stand = new Stand("STAND-1", "Stand tir à l'arc", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(12, 30), LocalTime.of(15, 30));
        Animateur animateur = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(animateur);
        ContrainteAdHoc forcee = new ContrainteAdHoc("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        forcee.setAnimateursConcernes(List.of(animateur));
        forcee.setCreneau(creneau);

        PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnose(
                new PlanningEvenement(creneau.getDate(), List.of(animateur), List.of(poste), List.of(forcee)));

        assertThat(diagnostic.contraintesAdHocEnCause()).isEmpty();
    }

    @Test
    void posteNonPourvuProduitUneLigneLisibleDeViolation() {
        PlanningService planningService = planningService();

        Stand stand = new Stand("STAND-1", "Stand tir à l'arc", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(12, 30), LocalTime.of(15, 30));
        PosteAffectation posteNonPourvu = new PosteAffectation("P1", stand, creneau);

        PlanningEvenement evenement = new PlanningEvenement(creneau.getDate(), List.of(), List.of(posteNonPourvu));

        PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnose(evenement);

        ConstraintDiagnostic posteDoitEtrePourvu = diagnostic.contraintes().stream()
                .filter(c -> c.name().equals("posteDoitEtrePourvu"))
                .findFirst()
                .orElseThrow();
        assertThat(posteDoitEtrePourvu.matchCount()).isEqualTo(1);
        assertThat(posteDoitEtrePourvu.violations()).containsExactly("Stand tir à l'arc — 2026-07-16 12:30-15:30");

        // A soft/medium constraint, if it matches at all here, must not carry a
        // per-match dump — that's reserved for hard constraints (see
        // ConstraintCatalog.NOMS_DURS).
        assertThat(diagnostic.contraintes())
                .filteredOn(c -> !c.name().equals("posteDoitEtrePourvu"))
                .allSatisfy(c -> assertThat(c.violations()).isEmpty());
    }

    // --- Floors (issue #495) --------------------------------------------------

    /** Two seats on one stand and one timeslot, both filled: the smallest plan every seat rule evaluates twice. */
    private static PlanningEvenement twoFilledSeats(Animateur first, Animateur second) {
        Stand stand = new Stand("STAND-1", "Stand stratégie", Set.of("STRAT"), 2, 2, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(9, 0), LocalTime.of(12, 0));
        PosteAffectation p1 = new PosteAffectation("P1", stand, creneau);
        p1.setAnimateur(first);
        PosteAffectation p2 = new PosteAffectation("P2", stand, creneau);
        p2.setAnimateur(second);
        return new PlanningEvenement(creneau.getDate(), List.of(first, second), List.of(p1, p2));
    }

    private static Animateur animateur(String id, NiveauCompetence niveau) {
        Animateur animateur = new Animateur(id, "Prénom " + id, "Nom", LocalDate.of(1990, 1, 1), false);
        animateur.setCompetences(Map.of("STRAT", niveau));
        return animateur;
    }

    private static ConstraintDiagnostic constraint(
            PlanningDiagnosticService.PlanningDiagnostic diagnostic, String name) {
        return diagnostic.contraintes().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aRuleMatchingEverySeatForLackOfDataIsReportedAsAFloorNamingThatData() {
        // A referent and a beginner: appreciation, referent and level mixing are
        // all satisfied. Nobody declared a wish, so souhaitsIncompatibles
        // matches both seats — a floor no solve will ever move.
        PlanningEvenement plan =
                twoFilledSeats(animateur("A1", NiveauCompetence.REFERENT), animateur("A2", NiveauCompetence.DEBUTANT));

        PlanningDiagnosticService.PlanningDiagnostic diagnostic =
                planningService().diagnose(plan);

        ConstraintDiagnostic souhaits = constraint(diagnostic, "souhaitsIncompatibles");
        assertThat(souhaits.matchCount()).isEqualTo(2);
        assertThat(souhaits.postesEvalues()).isEqualTo(2);
        assertThat(souhaits.plancher()).isNotNull().satisfies(plancher -> {
            assertThat(plancher.ratio()).isEqualTo(1.0);
            assertThat(plancher.motif()).isEqualTo("SOUHAITS");
            assertThat(plancher.libelle()).contains("souhait");
            assertThat(plancher.lien()).isEqualTo(ConstraintFloorRules.ROUTE_ANIMATEURS);
        });
        // Only that rule is a floor here — and the score net of it is what the solve can move.
        assertThat(diagnostic.contraintes())
                .filteredOn(c -> !c.name().equals("souhaitsIncompatibles"))
                .allSatisfy(c -> assertThat(c.plancher()).as(c.name()).isNull());
        assertThat(diagnostic.plancherMedium()).isNegative();
        assertThat(diagnostic.plancherSoft()).isZero();
        assertThat(diagnostic.scoreHorsPlancher()).isEqualTo("0hard/0medium/0soft");
        assertThat(diagnostic.score()).isNotEqualTo(diagnostic.scoreHorsPlancher());
    }

    @Test
    void aRuleWhoseDataIsEnteredIsNotAFloor() {
        Animateur alice = animateur("A1", NiveauCompetence.REFERENT);
        alice.setSouhaits(Set.of("STRAT"));
        PlanningEvenement plan = twoFilledSeats(alice, animateur("A2", NiveauCompetence.DEBUTANT));

        PlanningDiagnosticService.PlanningDiagnostic diagnostic =
                planningService().diagnose(plan);

        // One wish honoured out of two seats: 50 %, well under the threshold.
        ConstraintDiagnostic souhaits = constraint(diagnostic, "souhaitsIncompatibles");
        assertThat(souhaits.matchCount()).isEqualTo(1);
        assertThat(souhaits.postesEvalues()).isEqualTo(2);
        assertThat(souhaits.plancher()).isNull();
        assertThat(diagnostic.plancherMedium()).isZero();
        assertThat(diagnostic.scoreHorsPlancher()).isEqualTo(diagnostic.score());
    }

    /** A hard rule is respected or the plan is invalid: it is never read as a floor. */
    @Test
    void aHardRuleCarriesNoFloorReading() {
        PlanningEvenement plan =
                twoFilledSeats(animateur("A1", NiveauCompetence.REFERENT), animateur("A2", NiveauCompetence.DEBUTANT));

        PlanningDiagnosticService.PlanningDiagnostic diagnostic =
                planningService().diagnose(plan);

        ConstraintDiagnostic pourvu = constraint(diagnostic, "posteDoitEtrePourvu");
        assertThat(pourvu.postesEvalues()).isNull();
        assertThat(pourvu.plancher()).isNull();
        // And a rule whose single match is an aggregate has no per-item reading either.
        assertThat(constraint(diagnostic, "equilibrerCharge").postesEvalues()).isNull();
    }

    /**
     * Nothing evaluated, nothing to divide by: a plan whose seats are all empty
     * reports every per-seat rule at zero items, and no floor at all.
     */
    @Test
    void aPlanWithNoFilledSeatReportsNoFloorAndDividesByNothing() {
        Stand stand = new Stand("STAND-1", "Stand stratégie", Set.of("STRAT"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(9, 0), LocalTime.of(12, 0));
        PlanningEvenement plan = new PlanningEvenement(
                creneau.getDate(),
                List.of(animateur("A1", NiveauCompetence.DEBUTANT)),
                List.of(new PosteAffectation("P1", stand, creneau)));

        PlanningDiagnosticService.PlanningDiagnostic diagnostic =
                planningService().diagnose(plan);

        ConstraintDiagnostic souhaits = constraint(diagnostic, "souhaitsIncompatibles");
        assertThat(souhaits.matchCount()).isZero();
        assertThat(souhaits.postesEvalues()).isZero();
        assertThat(diagnostic.contraintes())
                .allSatisfy(c -> assertThat(c.plancher()).as(c.name()).isNull());
        assertThat(diagnostic.plancherMedium()).isZero();
        assertThat(diagnostic.scoreHorsPlancher()).isEqualTo(diagnostic.score());
    }

    /**
     * A floor no missing data explains is still a floor: reported bare, with
     * the ratio and without a screen to send the user to. Here the single
     * staffed group has a referent and no beginner, which is all
     * {@code favoriserMixiteDesNiveaux} penalises — 100 % of what it saw.
     */
    @Test
    void aFloorNoMissingDataExplainsIsReportedWithoutACause() {
        Animateur alice = animateur("A1", NiveauCompetence.REFERENT);
        alice.setSouhaits(Set.of("STRAT"));
        Animateur bob = animateur("A2", NiveauCompetence.AUTONOME);
        bob.setSouhaits(Set.of("STRAT"));
        PlanningEvenement plan = twoFilledSeats(alice, bob);

        PlanningDiagnosticService.PlanningDiagnostic diagnostic =
                planningService().diagnose(plan);

        ConstraintDiagnostic mixite = constraint(diagnostic, "favoriserMixiteDesNiveaux");
        assertThat(mixite.matchCount()).isEqualTo(1);
        assertThat(mixite.postesEvalues()).isEqualTo(1);
        assertThat(mixite.plancher()).isNotNull().satisfies(plancher -> {
            assertThat(plancher.ratio()).isEqualTo(1.0);
            assertThat(plancher.motif()).isNull();
            assertThat(plancher.libelle()).isEqualTo(PlanningDiagnosticService.LIBELLE_PLANCHER_SANS_MOTIF);
            assertThat(plancher.lien()).isNull();
        });
        assertThat(diagnostic.plancherSoft()).isNegative();
    }

    private static PlanningService planningService() {
        return new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());
    }
}
