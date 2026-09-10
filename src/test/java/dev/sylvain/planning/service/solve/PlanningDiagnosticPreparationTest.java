package dev.sylvain.planning.service.solve;

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

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.referentiel.ReferenceData;

/**
 * A diagnostic of the <em>persisted</em> plan is scored under the same rules a
 * solve runs — the edition's toggles and its weights — because
 * {@link PlanningService} hands {@link PlanningDiagnosticService} the very
 * preparation {@link SolveRunner} applies.
 *
 * <p>That wiring is a lambda in the façade's constructor, and nothing pinned
 * it: a plan reloaded from the database carries no toggle and no weight of its
 * own, so replacing {@code solveRunner::prepareProblem} with {@code planning ->
 * {}} left the whole suite green while the Contraintes screen started
 * reporting violations of a rule the edition had switched off. What follows is
 * that one seam, from both ends — the weights and the toggles.</p>
 *
 * <p>The solve side of the same preparation is pinned by
 * {@link PlanningServiceConstraintWeightOverridesTest}.</p>
 */
class PlanningDiagnosticPreparationTest {

    /** A weight no default is: {@code standComplexeAvecReferent} is worth 1 out of the box. */
    private static final int TELLTALE_WEIGHT = 9;

    private static final String CONTRAINTE = "standComplexeAvecReferent";

    /**
     * The configured weight reaches the diagnostic: the single violation below
     * is worth {@value #TELLTALE_WEIGHT}, not the catalogue's 1.
     */
    @Test
    void theDiagnosticOfThePersistedPlanUsesTheConfiguredWeights() {
        PlanningService service = serviceUnderTest(new EmptyReferenceData());

        PlanningDiagnosticService.PlanningDiagnostic diagnostic = service.diagnosePersistedPlan();

        assertThat(diagnostic.contraintes())
                .filteredOn(contrainte -> contrainte.name().equals(CONTRAINTE))
                .singleElement()
                .satisfies(contrainte -> {
                    assertThat(contrainte.matchCount()).isEqualTo(1);
                    assertThat(contrainte.score()).isEqualTo("0hard/-" + TELLTALE_WEIGHT + "medium/0soft");
                });
    }

    /**
     * And so do the edition's toggles: a rule this edition switched off scores
     * nothing at all, rather than the plan being judged under a rule its
     * organisers have disabled.
     */
    @Test
    void theDiagnosticOfThePersistedPlanHonoursTheEditionsDisabledConstraints() {
        PlanningService service = serviceUnderTest(new EmptyReferenceData() {
            @Override
            public Set<String> getContraintesDesactivees() {
                return Set.of(CONTRAINTE);
            }
        });

        PlanningDiagnosticService.PlanningDiagnostic diagnostic = service.diagnosePersistedPlan();

        assertThat(diagnostic.contraintes())
                .filteredOn(contrainte -> contrainte.name().equals(CONTRAINTE))
                .allSatisfy(contrainte -> assertThat(contrainte.matchCount()).isZero());
    }

    /**
     * The service a live application builds, with the database replaced by a
     * plan holding exactly one violation of {@link #CONTRAINTE}. Nothing else
     * is stubbed: the diagnostic runs the real score director.
     */
    private static PlanningService serviceUnderTest(ReferenceData edition) {
        Config config = ConfigProviderResolver.instance().getBuilder()
                .withSources(mapConfigSource(Map.of(
                        "planning.constraint-weights." + CONTRAINTE, String.valueOf(TELLTALE_WEIGHT))))
                .build();
        PlanningPersistenceService persistence = new PlanningPersistenceService() {
            @Override
            public PlanningEvenement loadPersistedPlanning() {
                return planWithOneViolation();
            }
        };
        return new PlanningService(2L, 1L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, edition,
                new FeasibilityAnalyzer(), persistence, null, config);
    }

    /**
     * One beginner alone on a complex stand: {@code standComplexeAvecReferent}
     * fires once, and nothing else does — the animateur is skilled for and
     * wishes the typology, so neither {@code appreciationIncompatible} nor
     * {@code souhaitsIncompatibles} joins in.
     */
    private static PlanningEvenement planWithOneViolation() {
        Stand stand = new Stand("S1", "S1", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 8), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur debutant = new Animateur("D1", "D1", "D1", LocalDate.of(2000, 1, 1), false);
        debutant.setCompetences(Map.of("STRATEGIE", NiveauCompetence.DEBUTANT));
        debutant.setSouhaits(Set.of("STRATEGIE"));
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(debutant);
        return new PlanningEvenement(creneau.getDate(), List.of(debutant), List.of(poste));
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
                return "test-diagnostic-preparation";
            }
        };
    }
}
