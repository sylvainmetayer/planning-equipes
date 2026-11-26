package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.edition.EtatEditionView;
import dev.sylvain.planning.service.referentiel.GelReferentielService;
import dev.sylvain.planning.service.referentiel.ReferentialFamily;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The phase of the edition through MCP (ADR 0115): {@code modifier_phase_edition}
 * works in the edition its argument names, declares a phase or unblocks and
 * freezes the structure of a published one, {@code etat_edition} reads the
 * freeze back, and a write tool of a frozen family comes back as a refusal
 * carrying the sentence — the same guard as over HTTP, since both reach the
 * same services.
 */
@QuarkusTest
class PhaseEditionMcpToolsTest {

    /** The edition each test works in, drawn by the server when created (ids are generated). */
    private static String edition;

    @Inject
    EditionMcpTools editionTools;

    @Inject
    StandMcpTools standTools;

    @Inject
    CreneauMcpTools creneauTools;

    @Inject
    AnimateurMcpTools animateurTools;

    @BeforeEach
    void anEditionOfItsOwnHoldingTheScenario() {
        edition = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(Map.of("nom", "Gel MCP"))
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        given().header("X-Edition-Id", edition)
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void liftEverythingAndDropTheEdition() {
        given().header("X-Edition-Id", "E1").when().delete("/api/editions/" + edition);
    }

    /** The id the edition drew for the row the scenario named by {@code code} (ids are generated per edition). */
    private static String idOfCode(String path, String code) {
        return given().header("X-Edition-Id", edition)
                .when()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("find { it.code == '" + code + "' }.id");
    }

    @Test
    void declaringCalculFreezesTheFourFamiliesAndPreparationOpensThemAgain() {
        EditionMcpTools.PhaseView calcul = editionTools.changeEditionPhase("CALCUL", null, edition);
        assertThat(calcul.phase()).isEqualTo("CALCUL");
        assertThat(calcul.edition()).isEqualTo(edition);

        EtatEditionView etat = editionTools.editionState(edition);
        assertThat(etat.gel().familles())
                .filteredOn(GelReferentielService.EtatGel::fige)
                .extracting(GelReferentielService.EtatGel::famille)
                .containsExactly(ReferentialFamily.values());
        // Another edition is not frozen by this one.
        assertThat(editionTools.editionState("E1").gel().familles()).noneMatch(GelReferentielService.EtatGel::fige);

        assertThat(editionTools.changeEditionPhase("PREPARATION", null, edition).phase())
                .isEqualTo("PREPARATION");
        assertThat(editionTools.editionState(edition).gel().familles()).noneMatch(GelReferentielService.EtatGel::fige);
    }

    @Test
    void theStructureUnblocksAndFreezesAgainInPublishedOnly() {
        assertThatThrownBy(() -> editionTools.changeEditionPhase(null, "DEBLOQUER", edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class);

        editionTools.changeEditionPhase("PUBLIEE", null, edition);
        assertThat(editionTools.changeEditionPhase(null, "DEBLOQUER", edition).structureDebloquee())
                .isTrue();
        assertThat(editionTools.changeEditionPhase(null, "REFIGER", edition).structureDebloquee())
                .isFalse();
    }

    @Test
    void aPhaseOrAStructureGestureIsRequiredButNotBoth() {
        assertThatThrownBy(() -> editionTools.changeEditionPhase(null, null, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> editionTools.changeEditionPhase("CALCUL", "DEBLOQUER", edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void anUnknownPhaseIsRefusedWithTheListOfPhases() {
        assertThatThrownBy(() -> editionTools.changeEditionPhase("FINIE", null, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("PUBLIEE");
    }

    @Test
    void theWriteToolsOfAFrozenFamilyAreRefused() {
        editionTools.changeEditionPhase("CALCUL", null, edition);

        String stand = idOfCode("/api/stands", "STAND-STRAT");
        String strategie = idOfCode("/api/typologies", "STRATEGIE");
        String a1 = given().header("X-Edition-Id", edition)
                .when()
                .get("/api/animateurs")
                .then()
                .extract()
                .jsonPath()
                .getString("find { it.email == 'A1@example.org' }.id");
        assertThatThrownBy(() ->
                        standTools.updateStand(stand, null, null, null, null, 3, null, null, null, null, null, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Frozen.class)
                .hasMessageContaining("« Stands »");
        long creneau = creneauTools.listCreneaux(edition).get(0).id();
        assertThatThrownBy(() -> creneauTools.deleteCreneau(creneau, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Frozen.class);
        assertThatThrownBy(() -> standTools.createTypologie("Nouvelle", "NOUVELLE", edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Frozen.class);
        Map<String, String> competences = Map.of(strategie, "DEBUTANT");
        assertThatThrownBy(() -> animateurTools.updateAnimateur(a1, null, competences, null, null, null, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Frozen.class);

        // The name of a stand and the days off of a person stay open.
        standTools.updateStand(stand, "Stratégie", null, null, null, null, null, null, null, null, null, edition);
        animateurTools.updateAnimateur(a1, null, null, null, List.of("2026-07-08"), null, edition);
    }
}
