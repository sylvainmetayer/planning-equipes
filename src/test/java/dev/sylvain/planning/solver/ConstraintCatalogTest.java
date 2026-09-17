package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    private static List<String> declaredConstraintNames() {
        ConstraintVerifier<PlanningConstraintProvider, PlanningEvenement> check = ConstraintVerifier.build(
                new PlanningConstraintProvider(), PlanningEvenement.class, PosteAffectation.class);
        List<String> noms = new ArrayList<>();
        check.verifyThat((provider, factory) -> {
                    Constraint[] constraints = provider.defineConstraints(factory);
                    for (Constraint constraint : constraints) {
                        noms.add(constraint.getConstraintRef().id());
                    }
                    return constraints[0];
                })
                .given()
                .penalizesBy(0);
        assertThat(noms).as("the provider's constraints were never built").isNotEmpty();
        return noms;
    }

    @Test
    void chaqueContrainteDefinieEstDecriteDansLeCatalogue() {
        List<String> catalogue = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(declaredConstraintNames()).isSubsetOf(catalogue);
    }

    @Test
    void leCatalogueNeDecritAucuneContrainteInexistante() {
        List<String> definies = declaredConstraintNames();

        assertThat(ConstraintCatalog.definitions())
                .allSatisfy(definition -> assertThat(definies).contains(definition.name()));
    }

    /**
     * The rules a disabling confirmation must protect. Named one by one rather
     * than derived from the category, so moving a legal rule to another
     * category — or adding one and forgetting its category — fails here
     * instead of silently dropping the confirmation that stands between an
     * organiser and a plan contrary to the Code du travail.
     */
    @Test
    void lesReglesLegalesEtDeSecuriteSontProtegees() {
        List<String> protegees = ConstraintCatalog.definitions().stream()
                .filter(ConstraintCatalog.ConstraintDefinition::protegee)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(protegees)
                .containsExactlyInAnyOrder(
                        "standReserveAuxMajeurs",
                        "travailDeNuitInterditPourMineur",
                        "dureeQuotidienneMaxMineur",
                        "travailInterditJourFerieMineur",
                        "reposHebdomadaireMineur",
                        "travailContinuMaxMineur",
                        "dureeHebdomadaireMaxMineur",
                        "mineurNecessiteEncadrementMajeur",
                        "dureeHebdomadaireMax",
                        "dureeHebdomadaireMaxDeuxSemaines",
                        "dureeQuotidienneMaxMajeur",
                        "reposQuotidienMinimal",
                        "maxJoursTravaillesParSemaine",
                        "reposHebdomadaireMinimal",
                        "travailContinuMaxMajeur",
                        "pauseMinimaleEntreVacations",
                        "coupureRepasObligatoire");
    }

    /**
     * Which of the protected rules an article of the Code du travail actually
     * founds. « Sécurité (mineurs) » and « Organisation (repas) » are the
     * organiser's own rules: no less binding on them, but the confirmation
     * asked before switching one off must not claim the plan becomes unlawful.
     */
    @Test
    void seulesLesReglesLegalesSontFondeesEnDroit() {
        List<String> fondees = ConstraintCatalog.definitions().stream()
                .filter(ConstraintCatalog.ConstraintDefinition::legale)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(fondees)
                .doesNotContain("mineurNecessiteEncadrementMajeur", "coupureRepasObligatoire")
                .contains("dureeQuotidienneMaxMajeur", "travailDeNuitInterditPourMineur");
        assertThat(ConstraintCatalog.definitions())
                .filteredOn(ConstraintCatalog.ConstraintDefinition::legale)
                .allMatch(ConstraintCatalog.ConstraintDefinition::protegee);
    }

    /** What is dosed rather than switched off: the MEDIUM rules of « Qualité d'organisation ». */
    @Test
    void lesReglesDeQualiteSontDosables() {
        assertThat(ConstraintCatalog.definitions())
                .filteredOn(ConstraintCatalog.ConstraintDefinition::dosable)
                .allSatisfy(definition -> {
                    assertThat(definition.niveau()).isEqualTo(ConstraintCatalog.Niveau.MEDIUM);
                    assertThat(definition.categorie()).isEqualTo(ConstraintCatalog.CATEGORIE_QUALITE);
                })
                .hasSize(15);

        // Nothing protected is presented as a dial.
        assertThat(ConstraintCatalog.definitions())
                .noneMatch(definition -> definition.dosable() && definition.protegee());
    }

    @Test
    void lesNomsDuCatalogueSontUniques() {
        List<String> noms = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(noms).doesNotHaveDuplicates();
    }

    /**
     * Every rule says what to do about it (review of issue #496). The pivot
     * answers « où » ; a screen that stops at « 210 écarts ici » is one nobody
     * opens twice. A rule without a lever of its own falls back on its
     * category's, and every category has one — so the answer is never empty
     * and never the generic sentence for a rule founded in law.
     */
    @Test
    void chaqueContraintePorteUneConsigneDeCorrection() {
        for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            assertThat(definition.remediation())
                    .as("consigne de correction de %s", definition.name())
                    .isNotBlank();
        }
    }

    @Test
    void uneRegleLegaleNeConseilleJamaisDeBaisserUnPoids() {
        for (ConstraintCatalog.ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            if (!definition.protegee()) {
                continue;
            }
            assertThat(definition.remediation())
                    .as("consigne de %s : une règle protégée ne se dose pas", definition.name())
                    .doesNotContain("baissez son poids");
        }
    }
}
