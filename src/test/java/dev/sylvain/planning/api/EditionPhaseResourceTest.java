package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The phase of the edition over HTTP (ADR 0115): what each phase freezes, the
 * structure of a published edition unblocked and frozen again, the journal,
 * and a duplicate starting in preparation. The publication's own move to
 * {@code PUBLIEE} is {@code PublicationResourceTest}.
 *
 * <p>Everything happens in an edition of its own, created and deleted around
 * each test.</p>
 */
@QuarkusTest
class EditionPhaseResourceTest {

    private static final String FIGE = "REFERENTIEL_FIGE";

    private static String edition;

    @BeforeEach
    void anEditionOfItsOwnHoldingTheScenario() {
        edition = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(Map.of("nom", "Phase de l'édition"))
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        edition()
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void dropTheEdition() {
        given().header("X-Edition-Id", "E1").when().delete("/api/editions/" + edition);
    }

    private static RequestSpecification edition() {
        return given().header("X-Edition-Id", edition).contentType("application/json");
    }

    private static ValidatableResponse phase(String phase) {
        return edition()
                .body(Map.of("phase", phase))
                .when()
                .put("/api/editions/courant/phase")
                .then();
    }

    private static ValidatableResponse createStand() {
        return edition().body("""
                        {"nom":"Nouveau","typologiesProposees":["STRATEGIE"],"effectifMin":1,"effectifMax":1,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"10:00:00"}]}]}
                        """).when().post("/api/stands").then();
    }

    /** Moves A1's competence on STRATEGIE: a retouch of the COMPETENCES family. */
    @SuppressWarnings("unchecked")
    private static ValidatableResponse retouchCompetences(String niveau) {
        List<Map<String, Object>> typologies = edition()
                .when()
                .get("/api/typologies")
                .then()
                .extract()
                .jsonPath()
                .getList("$");
        String strategie = (String) typologies.stream()
                .filter(row -> "STRATEGIE".equals(row.get("code")))
                .findFirst()
                .orElseThrow()
                .get("id");
        List<Map<String, Object>> animateurs = edition()
                .when()
                .get("/api/animateurs")
                .then()
                .extract()
                .jsonPath()
                .getList("$");
        Map<String, Object> a1 = new HashMap<>(animateurs.stream()
                .filter(row -> "A1@example.org".equals(row.get("email")))
                .findFirst()
                .orElseThrow());
        a1.put("competences", Map.of(strategie, niveau));
        return edition().body(a1).when().put("/api/animateurs/" + a1.get("id")).then();
    }

    @Test
    void anEditionIsBornInPreparationAndTheCalculPhaseFreezesTheReferential() {
        edition()
                .when()
                .get("/api/editions/courant")
                .then()
                .statusCode(200)
                .body("phase", equalTo("PREPARATION"))
                .body("phaseDepuis", notNullValue())
                .body("structureDebloquee", equalTo(false));

        phase("CALCUL").statusCode(200).body("phase", equalTo("CALCUL"));
        createStand().statusCode(409).body("code", equalTo(FIGE)).body("familles", equalTo(List.of("STANDS")));
        createStand().body("message", containsString("phase « Calcul »"));
        retouchCompetences("DEBUTANT").statusCode(409).body("familles", equalTo(List.of("COMPETENCES")));

        phase("PREPARATION").statusCode(200);
        createStand().statusCode(200);

        edition()
                .when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .body("findAll { it.action == 'PHASE_CHANGEE' }.champs.flatten()", hasItem("CALCUL"))
                .body("find { it.action == 'PHASE_CHANGEE' }.libelle", equalTo("Phase de l'édition changée"));
    }

    @Test
    void aPublishedEditionFreezesItsStructureButNotThePeople() {
        phase("PUBLIEE").statusCode(200).body("structureDebloquee", equalTo(false));

        createStand().statusCode(409).body("familles", equalTo(List.of("STANDS")));
        createStand().body("message", containsString("Débloquez la structure"));
        edition()
                .body(Map.of("nom", "Parc"))
                .when()
                .post("/api/emplacements")
                .then()
                .statusCode(409)
                .body("familles", equalTo(List.of("TYPOLOGIES_EMPLACEMENTS")));
        retouchCompetences("DEBUTANT").statusCode(200);

        edition()
                .when()
                .get("/api/editions/courant/gel")
                .then()
                .body("fige", equalTo(List.of(true, true, true, false)));
    }

    @Test
    void theStructureOfAPublishedEditionIsUnblockedThenFrozenAgain() {
        phase("PUBLIEE").statusCode(200);

        edition()
                .when()
                .put("/api/editions/courant/deblocage-structure")
                .then()
                .statusCode(200)
                .body("phase", equalTo("PUBLIEE"))
                .body("structureDebloquee", equalTo(true));
        createStand().statusCode(200);

        edition()
                .when()
                .delete("/api/editions/courant/deblocage-structure")
                .then()
                .statusCode(200)
                .body("structureDebloquee", equalTo(false));
        createStand().statusCode(409);

        // Entering a phase refreezes an unblocked structure.
        edition().when().put("/api/editions/courant/deblocage-structure").then().statusCode(200);
        phase("PUBLIEE").statusCode(200).body("structureDebloquee", equalTo(false));

        edition()
                .when()
                .get("/api/historique")
                .then()
                .body("find { it.action == 'STRUCTURE_DEBLOQUEE' }", notNullValue())
                .body("find { it.action == 'STRUCTURE_REFIGEE' }", notNullValue());
    }

    @Test
    void onlyAPublishedEditionUnblocksItsStructure() {
        for (String autre : List.of("PREPARATION", "CALCUL", "TERMINEE")) {
            phase(autre).statusCode(200);
            edition()
                    .when()
                    .put("/api/editions/courant/deblocage-structure")
                    .then()
                    .statusCode(409)
                    .body("message", containsString("seule une édition publiée"));
        }
    }

    /** The 3.x routes are aliases of the phase until 5.0. */
    @Test
    void theFreezeRoutesMoveThePhase() {
        edition().when().put("/api/editions/courant/gel/STANDS").then().statusCode(200);
        edition().when().get("/api/editions/courant").then().body("phase", equalTo("CALCUL"));
        edition().when().delete("/api/editions/courant/gel/COMPETENCES").then().statusCode(200);
        edition().when().get("/api/editions/courant").then().body("phase", equalTo("PREPARATION"));

        phase("PUBLIEE").statusCode(200);
        edition().when().delete("/api/editions/courant/gel/STANDS").then().statusCode(200);
        edition().when().get("/api/editions/courant").then().body("structureDebloquee", equalTo(true));
        edition().when().put("/api/editions/courant/gel/CRENEAUX").then().statusCode(200);
        edition().when().get("/api/editions/courant").then().body("structureDebloquee", equalTo(false));

        phase("TERMINEE").statusCode(200);
        createStand().statusCode(409).body("message", containsString("terminée"));
        edition().when().delete("/api/editions/courant/gel/STANDS").then().statusCode(409);
    }

    @Test
    void anUnknownOrMissingPhaseIsRefused() {
        phase("OUVERTE").statusCode(400);
        edition().body("{}").when().put("/api/editions/courant/phase").then().statusCode(400);
    }

    @Test
    void aPhaseStaysInItsEditionAndADuplicateStartsInPreparation() {
        phase("PUBLIEE").statusCode(200);
        String copie = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(Map.of("nom", "Copie"))
                .when()
                .post("/api/editions/" + edition + "/dupliquer")
                .then()
                .statusCode(200)
                .body("phase", equalTo("PREPARATION"))
                .extract()
                .path("id");
        try {
            given().header("X-Edition-Id", copie)
                    .when()
                    .get("/api/editions/courant")
                    .then()
                    .body("phase", equalTo("PREPARATION"))
                    .body("structureDebloquee", equalTo(false));
        } finally {
            given().header("X-Edition-Id", "E1").when().delete("/api/editions/" + copie);
        }
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions/courant")
                .then()
                .body("phase", equalTo("PREPARATION"));
    }
}
