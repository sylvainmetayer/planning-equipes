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
 * read once at construction time by {@code readConfiguredWeights} and
 * attached to every problem by {@code prepareProblem} — and that an edition
 * that stored its own weight wins over that configuration.
 */
class PlanningServiceConstraintWeightOverridesTest {

    @Test
    void overriddenConstraintWeightChangesTheScore() {
        ReferenceData referenceDataService = new EmptyReferenceData();

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

        PlanningFestival solved = planningService.solve(festival);

        assertThat(solved.getScore().mediumScore()).isEqualTo(-5);
    }

    /**
     * The edition's own weight beats the configured one. Same problem as
     * above, so the only thing that can move the medium score from -5 to -7 is
     * {@code ponderation_contrainte} being read at solve time rather than the
     * property at startup.
     */
    @Test
    void editionWeightOverridesTheConfiguredOne() {
        ReferenceData referenceDataService = new EmptyReferenceData() {
            @Override
            public Map<String, Integer> getConstraintWeights() {
                return Map.of("standComplexeAvecReferent", 7);
            }
        };

        Config config = ConfigProviderResolver.instance().getBuilder()
                .withSources(mapConfigSource(Map.of(
                        "planning.constraint-weights.standComplexeAvecReferent", "5")))
                .build();
        PlanningService planningService = new PlanningService(2L, 1L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService,
                new FeasibilityAnalyzer(), config);

        assertThat(planningService.effectiveConstraintWeights())
                .containsEntry("standComplexeAvecReferent", 7)
                // Untouched by the edition: still the configured default.
                .containsEntry("equilibrerCharge", 1);

        PlanningFestival solved = planningService.solve(problemWithoutReferent());

        assertThat(solved.getScore().mediumScore()).isEqualTo(-7);
    }

    /**
     * A single animateur, débutant, alone on a stand: he is also the only
     * value in the planning variable's range, so the solver has no alternative
     * assignment to try and the "no referent on this stand/créneau" match
     * (standComplexeAvecReferent) is guaranteed to survive into the final
     * solution. Appreciated and wished on STRATEGIE, so neither
     * appreciationIncompatible nor souhaitsIncompatibles fires alongside it.
     */
    private static PlanningFestival problemWithoutReferent() {
        Stand stand = new Stand("S1", "S1", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 8), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur debutant = new Animateur("D1", "D1", "D1", LocalDate.of(2000, 1, 1), false);
        debutant.setCompetences(Map.of("STRATEGIE", NiveauCompetence.DEBUTANT));
        debutant.setSouhaits(Set.of("STRATEGIE"));
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(debutant);
        return new PlanningFestival(creneau.getDate(), List.of(debutant), List.of(poste));
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
