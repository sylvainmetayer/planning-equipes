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
 * The freeze through MCP (ADR 0052): {@code figer_referentiel} and
 * {@code lever_gel} work in the edition their argument names, {@code etat_edition}
 * reads the freeze back, and a write tool of a frozen family comes back as a
 * refusal carrying the sentence — the same guard as over HTTP, since both reach
 * the same services.
 */
@QuarkusTest
class GelReferentielMcpToolsTest {

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
        edition = given().contentType("application/json")
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
        for (ReferentialFamily famille : ReferentialFamily.values()) {
            editionTools.liftFreeze(famille.name(), edition);
        }
        given().when().delete("/api/editions/" + edition);
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
    void freezingIsReadBackByEtatEditionAndLiftedAgain() {
        GelReferentielService.EtatGel fige = editionTools.freezeReferential("stands", edition);
        assertThat(fige.fige()).isTrue();
        assertThat(fige.figeLe()).isNotNull();

        EtatEditionView etat = editionTools.editionState(edition);
        assertThat(etat.gel().familles())
                .filteredOn(GelReferentielService.EtatGel::fige)
                .extracting(GelReferentielService.EtatGel::famille)
                .containsExactly(ReferentialFamily.STANDS);
        // Another edition is not frozen by this one.
        assertThat(editionTools.editionState(null).gel().familles()).noneMatch(GelReferentielService.EtatGel::fige);

        assertThat(editionTools.liftFreeze("STANDS", edition).fige()).isFalse();
    }

    @Test
    void anUnknownFamilyIsRefusedWithTheListOfFamilies() {
        assertThatThrownBy(() -> editionTools.freezeReferential("HORAIRES", edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("TYPOLOGIES_EMPLACEMENTS");
    }

    @Test
    void theWriteToolsOfAFrozenFamilyAreRefused() {
        editionTools.freezeReferential("STANDS", edition);
        editionTools.freezeReferential("CRENEAUX", edition);
        editionTools.freezeReferential("TYPOLOGIES_EMPLACEMENTS", edition);
        editionTools.freezeReferential("COMPETENCES", edition);

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
        assertThatThrownBy(() -> animateurTools.updateAnimateur(
                        a1, null, Map.of(strategie, "DEBUTANT"), null, null, null, edition))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Frozen.class);

        // The name of a stand and the days off of a person stay open.
        standTools.updateStand(stand, "Stratégie", null, null, null, null, null, null, null, null, null, edition);
        animateurTools.updateAnimateur(a1, null, null, null, List.of("2026-07-08"), null, edition);
    }
}
