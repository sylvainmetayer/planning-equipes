package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.mcp.CreneauMcpTools.WrittenCreneauView;
import dev.sylvain.planning.mcp.VerrouillageMcpTools.WrittenVerrouillageView;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The MCP writes accepted during a solve say so, tool by tool, through the
 * real interceptor chain — and say nothing when no solve holds their edition.
 *
 * <p>The solve is the probe's answer, not a real one: holding the solver for
 * real takes a solve per test, and what decides « this edition, running, not
 * queued » is {@code SolverJobService.activeJobForCurrentEdition}, the guard's
 * own rule, pinned where the guard is tested.</p>
 */
@QuarkusTest
class McpWarnsWhileSolvingTest {

    private static final String CODE = "RESOLUTION_EN_COURS";

    @Inject
    StandMcpTools standTools;

    @Inject
    ParametresMcpTools parametresTools;

    @Inject
    ContrainteMcpTools contrainteTools;

    @Inject
    VerrouillageMcpTools verrouillageTools;

    @Inject
    AnimateurMcpTools animateurTools;

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    ValidationJourneeMcpTools validationTools;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    EditionService editions;

    /** A solve holds the edition of the call, for the length of one test. */
    private static void solveHoldsTheEdition() {
        QuarkusMock.installMockForType(
                new RunningSolveProbe() {
                    @Override
                    public boolean holdsCurrentEdition() {
                        return true;
                    }
                },
                RunningSolveProbe.class);
    }

    @Test
    void typologieWritesWarnDuringASolve() {
        solveHoldsTheEdition();
        try {
            assertWarned(() -> standTools
                    .creer_typologie("RES-T1", "Pendant un calcul", null)
                    .avertissements());
            assertWarned(() -> standTools
                    .modifier_typologie("RES-T1", "Renommée pendant un calcul", null, null)
                    .avertissements());
        } finally {
            assertWarned(() -> standTools.supprimer_typologie("RES-T1", null).avertissements());
        }
    }

    @Test
    void emplacementWritesWarnDuringASolve() {
        solveHoldsTheEdition();
        try {
            assertWarned(() -> standTools
                    .creer_emplacement("RES-E1", "Préau", 45.0, 4.0, null)
                    .avertissements());
            assertWarned(() -> standTools
                    .modifier_emplacement("RES-E1", "Préau nord", 45.0, 4.0, null, null)
                    .avertissements());
        } finally {
            assertWarned(() -> standTools.supprimer_emplacement("RES-E1", null).avertissements());
        }
    }

    /** A creation is not refused during a solve (its landing does not touch it): it warns instead. */
    @Test
    void aStandCreationWarnsDuringASolve() {
        standTools.creer_typologie("RES-T2", "Stratégie", null);
        solveHoldsTheEdition();
        try {
            assertWarned(() -> standTools
                    .creer_stand("RES-S1", "Stand", List.of("RES-T2"), 1, 1, false, false, null, null, null)
                    .avertissements());
        } finally {
            referenceData.deleteStand("RES-S1");
            referenceData.deleteTypologie("RES-T2");
        }
    }

    @Test
    void settingsAndWeightsWarnDuringASolve() {
        int duree = referenceData.getParametresSolveur().dureeResolutionSecondes();
        String contrainte = ConstraintCatalog.PAR_NOM.keySet().stream()
                .filter(nom -> !ConstraintCatalog.NOMS_DURS.contains(nom))
                .sorted()
                .findFirst()
                .orElseThrow();
        solveHoldsTheEdition();
        try {
            assertWarned(() ->
                    parametresTools.modifier_parametres_solveur(duree, null).avertissements());
            assertWarned(() -> contrainteTools
                    .modifier_poids_contrainte(contrainte, 3, null)
                    .avertissements());
            assertWarned(() ->
                    contrainteTools.desactiver_contrainte(contrainte, null).avertissements());
        } finally {
            assertWarned(
                    () -> contrainteTools.activer_contrainte(contrainte, null).avertissements());
            assertWarned(() -> contrainteTools
                    .modifier_poids_contrainte(contrainte, null, null)
                    .avertissements());
        }
    }

    @Test
    void locksWarnDuringASolve() {
        solveHoldsTheEdition();
        String id = verrouillageTools
                .verrouiller("JOUR", null, null, null, "2031-07-14", null, null)
                .verrouillage()
                .id();
        assertWarned(() -> verrouillageTools.deverrouiller(id, null).avertissements());
    }

    /**
     * The annotated tools the tests above leave out, one assertion each, in an
     * edition of their own so the warnings each write raises are predictable:
     * the code is added next to them, never in their place.
     */
    @Test
    void everyOtherAnnotatedToolWarnsAndKeepsItsOwnWarnings() {
        String ed = "RES-WARN-ED";
        editions.create(new Edition(ed, "Écritures pendant un calcul", false, null));
        try {
            solveHoldsTheEdition();
            // No stand yet: the timeslot raises nothing of its own.
            WrittenCreneauView matin = creneauTools.creer_creneau("2031-08-01", "10:00", "12:00", null, ed);
            assertThat(matin.avertissements()).containsExactly(CODE);
            Long creneauId = matin.creneau().id();

            // Open 14:00-16:00 only, over a grid with nothing there.
            assertThat(standTools
                            .creer_stand_complet(
                                    "RES-SC",
                                    "Stand complet",
                                    List.of("RES-WTY"),
                                    true,
                                    1,
                                    1,
                                    false,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    "14:00-16:00",
                                    "TOUS",
                                    null,
                                    null,
                                    null,
                                    null,
                                    ed)
                            .avertissements())
                    .contains(CODE)
                    .hasSizeGreaterThan(1);
            assertThat(creneauTools
                            .creer_creneau("2031-08-01", "20:00", "21:00", null, ed)
                            .avertissements())
                    .contains("CRENEAU_HORS_OUVERTURE_STANDS", CODE);
            assertThat(animateurTools
                            .creer_animateur(
                                    "RES-AN",
                                    "2020-01-01",
                                    "Prénom",
                                    "Nom",
                                    null,
                                    null,
                                    null,
                                    List.of("2031-08-01"),
                                    ed)
                            .avertissements())
                    .contains("MINEUR_PENDANT_EVENEMENT", CODE);
            // Forced onto a day the animateur is off: a warning, not a refusal.
            assertThat(parametresTools
                            .creer_contrainte_ad_hoc(
                                    "RES-CA", "AFFECTATION_FORCEE", List.of("RES-AN"), creneauId, "RES-SC", null, ed)
                            .avertissements())
                    .contains("AFFECTATION_FORCEE_JOUR_INDISPONIBLE", CODE);
            assertWarned(() ->
                    parametresTools.supprimer_contrainte_ad_hoc("RES-CA", ed).avertissements());
            assertWarned(() -> parametresTools
                    .modifier_parametres_legaux(null, null, null, null, null, null, null, null, null, null, null, ed)
                    .avertissements());
            WrittenVerrouillageView verrou =
                    verrouillageTools.verrouiller("JOUR", null, null, null, "2031-08-01", null, ed);
            assertThat(verrou.avertissements()).contains(CODE);
            assertWarned(() -> verrouillageTools
                    .deverrouiller(verrou.verrouillage().id(), ed)
                    .avertissements());
            assertWarned(() -> validationTools
                    .ajouter_validation_journee("2031-08-01", null, false, ed)
                    .avertissements());
        } finally {
            editions.delete(ed);
        }
    }

    /** The other half: with no solve holding the edition, the same writes carry nothing about one. */
    @Test
    void withoutASolveNothingIsSaid() {
        try {
            assertThat(standTools.creer_typologie("RES-T3", "Au calme", null).avertissements())
                    .doesNotContain(CODE);
            assertThat(standTools
                            .creer_emplacement("RES-E3", "Cour", null, null, null)
                            .avertissements())
                    .doesNotContain(CODE);
            assertThat(standTools.supprimer_emplacement("RES-E3", null).avertissements())
                    .doesNotContain(CODE);
        } finally {
            assertThat(standTools.supprimer_typologie("RES-T3", null).avertissements())
                    .doesNotContain(CODE);
        }
    }

    private static void assertWarned(Supplier<List<String>> write) {
        assertThat(write.get()).contains(CODE);
    }
}
