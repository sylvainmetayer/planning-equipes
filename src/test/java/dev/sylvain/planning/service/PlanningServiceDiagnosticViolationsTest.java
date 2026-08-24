package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningService.ConstraintDiagnostic;

/**
 * {@code diagnose()} must turn a hard-constraint match into a
 * human-readable line (so the Contraintes page can show "who/what/when" to a
 * non-technical user), but must not bother doing that for medium/soft
 * constraints, whose match counts can run into the thousands.
 */
class PlanningServiceDiagnosticViolationsTest {

    @Test
    void posteNonPourvuProduitUneLigneLisibleDeViolation() {
        ReferenceData referenceDataService = new EmptyReferenceData();
        PlanningService planningService = new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());

        Stand stand = new Stand("STAND-1", "Stand tir à l'arc", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(12, 30), LocalTime.of(15, 30));
        PosteAffectation posteNonPourvu = new PosteAffectation("P1", stand, creneau);

        PlanningEvenement evenement = new PlanningEvenement(creneau.getDate(), List.of(), List.of(posteNonPourvu));

        PlanningService.PlanningDiagnostic diagnostic = planningService.diagnose(evenement);

        ConstraintDiagnostic posteDoitEtrePourvu = diagnostic.contraintes().stream()
                .filter(c -> c.name().equals("posteDoitEtrePourvu"))
                .findFirst()
                .orElseThrow();
        assertThat(posteDoitEtrePourvu.matchCount()).isEqualTo(1);
        assertThat(posteDoitEtrePourvu.violations()).containsExactly("Stand tir à l'arc — 2026-07-16 12:30-15:30");

        // A soft/medium constraint, if it matches at all here, must not carry a
        // per-match dump — that's reserved for hard constraints (see
        // PlanningService.HARD_CONSTRAINT_NAMES).
        assertThat(diagnostic.contraintes())
                .filteredOn(c -> !c.name().equals("posteDoitEtrePourvu"))
                .allSatisfy(c -> assertThat(c.violations()).isEmpty());
    }
}
