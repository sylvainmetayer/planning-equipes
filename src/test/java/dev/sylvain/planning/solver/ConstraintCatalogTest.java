package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Guards the invariant stated in AGENTS.md: every constraint defined by
 * {@link PlanningConstraintProvider} is described in {@link ConstraintCatalog},
 * and nothing else is. The catalogue feeds {@code GET /api/constraints} and is
 * joined to a score analysis by constraint name, so a name present on one side
 * only means either an undocumented constraint, or a UI card (and its
 * enable/disable switch) matching no rule at all.
 */
class ConstraintCatalogTest {

    /**
     * Names of the constraints the provider actually defines.
     *
     * <p>{@code verifyThat(BiFunction)} is the only public entry point handing
     * out a live {@code ConstraintFactory}; it applies the function lazily, when
     * the scoring session is built, so a throwaway verification is run to force
     * it.</p>
     */
    private static List<String> nomsDesContraintesDefinies() {
        ConstraintVerifier<PlanningConstraintProvider, PlanningFestival> verifier =
                ConstraintVerifier.build(new PlanningConstraintProvider(), PlanningFestival.class,
                        PosteAffectation.class);
        List<String> noms = new ArrayList<>();
        verifier.verifyThat((provider, factory) -> {
            Constraint[] constraints = provider.defineConstraints(factory);
            for (Constraint constraint : constraints) {
                noms.add(constraint.getConstraintName());
            }
            return constraints[0];
        }).given().penalizesBy(0);
        assertThat(noms).as("the provider's constraints were never built").isNotEmpty();
        return noms;
    }

    @Test
    void chaqueContrainteDefinieEstDecriteDansLeCatalogue() {
        List<String> catalogue = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(nomsDesContraintesDefinies()).isSubsetOf(catalogue);
    }

    @Test
    void leCatalogueNeDecritAucuneContrainteInexistante() {
        List<String> definies = nomsDesContraintesDefinies();

        assertThat(ConstraintCatalog.definitions())
                .allSatisfy(definition -> assertThat(definies).contains(definition.name()));
    }

    @Test
    void lesNomsDuCatalogueSontUniques() {
        List<String> noms = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(noms).doesNotHaveDuplicates();
    }
}
