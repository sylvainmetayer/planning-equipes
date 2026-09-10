package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService;
import dev.sylvain.planning.service.EmptyReferenceData;

/**
 * {@code diagnose()} must turn a hard-constraint match into a
 * human-readable line (so the Contraintes page can show "who/what/when" to a
 * non-technical user), but must not bother doing that for medium/soft
 * constraints, whose match counts can run into the thousands.
 *
 * <p>It must also say <em>which hand-entered exception</em> a violated ad hoc
 * rule is about (issue #84): "affectationForcee: 12" is where a reader stops,
 * and the exceptions are the only thing anyone can act on.</p>
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

        assertThat(diagnostic.contraintesAdHocEnCause())
                .singleElement()
                .satisfies(contribution -> {
                    assertThat(contribution.contrainteId()).isEqualTo("C1");
                    assertThat(contribution.type()).isEqualTo("AFFECTATION_FORCEE");
                    assertThat(contribution.raison()).isEqualTo("Promesse faite en juin");
                    assertThat(contribution.violations()).isEqualTo(1);
                    assertThat(contribution.contraintes()).containsExactly("affectationForcee");
                });

        // The per-match line names it too: the id is what the ad hoc screen shows.
        assertThat(diagnostic.contraintes()).filteredOn(c -> c.name().equals("affectationForcee"))
                .singleElement()
                .satisfies(c -> assertThat(c.violations())
                        .containsExactly("AFFECTATION_FORCEE C1 (Promesse faite en juin)"));
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

        PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnose(new PlanningEvenement(
                creneau.getDate(), List.of(animateur), List.of(poste), List.of(forcee)));

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

    private static PlanningService planningService() {
        return new PlanningService(3L, 2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, new EmptyReferenceData(),
                new FeasibilityAnalyzer(), null, null, ConfigProvider.getConfig());
    }
}
