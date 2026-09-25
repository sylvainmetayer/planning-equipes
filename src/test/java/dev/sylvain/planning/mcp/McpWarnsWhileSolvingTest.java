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
                new RunningSolveProbe(null) {
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
        var creee = standTools.createTypologie("Pendant un calcul", null, null);
        String id = creee.id();
        try {
            assertThat(creee.avertissements()).contains(CODE);
            assertWarned(() -> standTools
                    .updateTypologie(id, "Renommée pendant un calcul", null, null)
                    .avertissements());
        } finally {
            assertWarned(() -> standTools.deleteTypologie(id, null).avertissements());
        }
    }

    @Test
    void emplacementWritesWarnDuringASolve() {
        solveHoldsTheEdition();
        var cree = standTools.createEmplacement("Préau", null, 45.0, 4.0, null);
        String id = cree.id();
        try {
            assertThat(cree.avertissements()).contains(CODE);
            assertWarned(() -> standTools
                    .updateEmplacement(id, "Préau nord", 45.0, 4.0, null, null)
                    .avertissements());
        } finally {
            assertWarned(() -> standTools.deleteEmplacement(id, null).avertissements());
        }
    }

    /** A creation is not refused during a solve (its landing does not touch it): it warns instead. */
    @Test
    void aStandCreationWarnsDuringASolve() {
        String typologie = standTools.createTypologie("Stratégie", null, null).id();
        solveHoldsTheEdition();
        String stand = null;
        try {
            var cree = standTools.createStand("Stand", null, List.of(typologie), 1, 1, false, false, null, null, null);
            stand = cree.stand().id();
            assertThat(cree.avertissements()).contains(CODE);
        } finally {
            if (stand != null) {
                referenceData.deleteStand(stand);
            }
            referenceData.deleteTypologie(typologie);
        }
    }

    @Test
    void settingsAndWeightsWarnDuringASolve() {
        Integer duree = referenceData.getParametresSolveur().dureeResolutionSecondes();
        String contrainte = ConstraintCatalog.PAR_NOM.keySet().stream()
                .filter(nom -> !ConstraintCatalog.NOMS_DURS.contains(nom))
                .sorted()
                .findFirst()
                .orElseThrow();
        solveHoldsTheEdition();
        try {
            assertWarned(() -> parametresTools
                    .updateParametresSolveur(duree, null, null, null)
                    .avertissements());
            assertWarned(() ->
                    contrainteTools.updateContrainteWeight(contrainte, 3, null).avertissements());
            assertWarned(
                    () -> contrainteTools.disableContrainte(contrainte, null).avertissements());
        } finally {
            assertWarned(
                    () -> contrainteTools.enableContrainte(contrainte, null).avertissements());
            assertWarned(() -> contrainteTools
                    .updateContrainteWeight(contrainte, null, null)
                    .avertissements());
        }
    }

    @Test
    void locksWarnDuringASolve() {
        solveHoldsTheEdition();
        String id = verrouillageTools
                .lock("JOUR", null, null, null, "2031-07-14", null, null)
                .verrouillage()
                .id();
        assertWarned(() -> verrouillageTools.unlock(id, null).avertissements());
    }

    /**
     * The annotated tools the tests above leave out, one assertion each, in an
     * edition of their own so the warnings each write raises are predictable:
     * the code is added next to them, never in their place.
     */
    @Test
    void everyOtherAnnotatedToolWarnsAndKeepsItsOwnWarnings() {
        String ed = editions.create(new Edition(null, "Écritures pendant un calcul", false, null))
                .getId();
        try {
            solveHoldsTheEdition();
            // No stand yet: the timeslot raises nothing of its own.
            WrittenCreneauView matin = creneauTools.createCreneau("2031-08-01", "10:00", "12:00", null, ed);
            assertThat(matin.avertissements()).containsExactly(CODE);
            Long creneauId = matin.creneau().id();

            // Open 14:00-16:00 only, over a grid with nothing there.
            var complet = standTools.createCompleteStand(
                    "Stand complet",
                    null,
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
                    ed);
            String standComplet = complet.stand().id();
            assertThat(complet.avertissements()).contains(CODE).hasSizeGreaterThan(1);
            assertThat(creneauTools
                            .createCreneau("2031-08-01", "20:00", "21:00", null, ed)
                            .avertissements())
                    .contains("CRENEAU_HORS_OUVERTURE_STANDS", CODE);
            var animateur = animateurTools.createAnimateur(
                    "2020-01-01", "Prénom", "Nom", null, null, null, List.of("2031-08-01"), ed);
            assertThat(animateur.avertissements()).contains("MINEUR_PENDANT_EVENEMENT", CODE);
            String animateurId = animateur.animateur().id();
            // Forced onto a day the animateur is off: a warning, not a refusal.
            var contrainte = parametresTools.createContrainteAdHoc(
                    "AFFECTATION_FORCEE", List.of(animateurId), creneauId, standComplet, null, ed);
            assertThat(contrainte.avertissements()).contains("AFFECTATION_FORCEE_JOUR_INDISPONIBLE", CODE);
            String contrainteId = contrainte.contrainte().id();
            assertWarned(() ->
                    parametresTools.deleteContrainteAdHoc(contrainteId, ed).avertissements());
            assertWarned(() -> parametresTools
                    .updateParametresLegaux(null, null, null, null, null, null, null, null, null, null, null, ed)
                    .avertissements());
            WrittenVerrouillageView verrou = verrouillageTools.lock("JOUR", null, null, null, "2031-08-01", null, ed);
            assertThat(verrou.avertissements()).contains(CODE);
            assertWarned(() ->
                    verrouillageTools.unlock(verrou.verrouillage().id(), ed).avertissements());
            assertWarned(() -> validationTools
                    .addJourneeValidation("2031-08-01", null, false, ed)
                    .avertissements());
        } finally {
            editions.delete(ed);
        }
    }

    /** The other half: with no solve holding the edition, the same writes carry nothing about one. */
    @Test
    void withoutASolveNothingIsSaid() {
        var typologie = standTools.createTypologie("Au calme", null, null);
        try {
            assertThat(typologie.avertissements()).doesNotContain(CODE);
            var emplacement = standTools.createEmplacement("Cour", null, null, null, null);
            assertThat(emplacement.avertissements()).doesNotContain(CODE);
            assertThat(standTools.deleteEmplacement(emplacement.id(), null).avertissements())
                    .doesNotContain(CODE);
        } finally {
            assertThat(standTools.deleteTypologie(typologie.id(), null).avertissements())
                    .doesNotContain(CODE);
        }
    }

    private static void assertWarned(Supplier<List<String>> write) {
        assertThat(write.get()).contains(CODE);
    }
}
