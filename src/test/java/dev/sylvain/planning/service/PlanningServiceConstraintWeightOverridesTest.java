package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Confirms a {@code planning.constraint-weights.<name>} property reaches the
 * solver end to end through {@link PlanningService} (not just Timefold's
 * {@code ConstraintWeightOverrides} mechanism in isolation): the config is
 * read once at construction time by {@code buildConstraintWeightOverrides}
 * and attached to every problem by {@code prepareProblem}.
 */
class PlanningServiceConstraintWeightOverridesTest {

    @Test
    void overriddenConstraintWeightChangesTheScore() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();

        Config config = ConfigProviderResolver.instance().getBuilder()
                .withSources(mapConfigSource(Map.of(
                        "planning.constraint-weights.standComplexeAvecReferent", "5")))
                .build();
        PlanningService planningService = new PlanningService(2L, 1L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService,
                new FeasibilityAnalyzer(), config);

        Stand stand = new Stand("S1", "S1", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 8), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur debutant = new Animateur("D1", "D1", "D1", LocalDate.of(2000, 1, 1), false);
        debutant.setCompetences(Map.of("STRATEGIE", NiveauCompetence.DEBUTANT));
        // Appreciated and wished on STRATEGIE, so neither appreciationIncompatible
        // nor souhaitsIncompatibles fires here: this test isolates
        // standComplexeAvecReferent's weight override alone.
        debutant.setSouhaits(Set.of("STRATEGIE"));
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(debutant);

        // A single animateur is also the only value in the planning variable's
        // range, so the solver has no alternative assignment to try: the
        // "no referent on this stand/créneau" match (standComplexeAvecReferent)
        // is guaranteed to survive into the final solution.
        PlanningFestival festival = new PlanningFestival(creneau.getDate(), List.of(debutant), List.of(poste));

        PlanningFestival solved = planningService.resoudre(festival);

        assertThat(solved.getScore().mediumScore()).isEqualTo(-5);
    }

    private static ConfigSource mapConfigSource(Map<String, String> properties) {
        return new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return properties;
            }

            @Override
            public Set<String> getPropertyNames() {
                return properties.keySet();
            }

            @Override
            public String getValue(String propertyName) {
                return properties.get(propertyName);
            }

            @Override
            public String getName() {
                return "test-constraint-weight-overrides";
            }
        };
    }
}
