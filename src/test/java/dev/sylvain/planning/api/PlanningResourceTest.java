package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class PlanningResourceTest {

    @Test
    void sampleEndpointRetourneUnPlanning() {
        given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .body("animateurs.size()", greaterThan(0))
                .body("postes.size()", greaterThan(0))
                .body("dateDebutFestival", notNullValue());
    }

    @Test
    void standCrudWorks() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"STAND-TEST",
                          "nom":"Test Stand",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":1,
                          "effectifMax":1,
                          "reserveMajeurs":false
                        }
                        """)
                .when().post("/api/stands")
                .then()
                .statusCode(200)
                .body("id", equalTo("STAND-TEST"));

        given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .body("find { it.id == 'STAND-TEST' }.nom", equalTo("Test Stand"));

        given()
                .when().delete("/api/stands/STAND-TEST")
                .then()
                .statusCode(204);
    }

    @Test
    void creneauCrudWorks() {
        String futureTestDate = "2030-01-02";

        Object createdId = given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"%s",
                          "heureDebut":"18:00:00",
                          "heureFin":"22:00:00"
                        }
                        """.formatted(futureTestDate))
                .when().post("/api/creneaux")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .extract().path("id");

        given()
                .when().get("/api/creneaux")
                .then()
                .statusCode(200)
                .body("find { it.id == " + createdId + " }.date", equalTo(futureTestDate));

        given()
                .when().delete("/api/creneaux/" + createdId)
                .then()
                .statusCode(204);
    }

    @Test
    void persistedPlanningEndpointIsReadOnly() {
        int before = given()
                .when().get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .extract().path("assignments");

        given()
                .when().get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(before));

        // Reading the planning must never trigger a solve, so the stored
        // assignments are left untouched.
        given()
                .when().get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .body("assignments", equalTo(before));
    }

    @Test
    void constraintsCatalogueExposesEveryRuleWithADescription() {
        given()
                .when().get("/api/constraints")
                .then()
                .statusCode(200)
                .body("contraintes.size()", greaterThan(0))
                .body("contraintes.findAll { it.description == null || it.description.isEmpty() }.size()",
                        equalTo(0))
                .body("contraintes.findAll { !(it.niveau in ['HARD', 'MEDIUM', 'SOFT']) }.size()", equalTo(0));
    }

    @Test
    void analyzeFeedsTheConstraintsScreen() {
        String planningJson = given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract().asString();

        List<String> analysedNames = given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/solve/analyze?seconds=1")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("contraintes.name");

        JsonPath view = given()
                .when().get("/api/constraints")
                .then()
                .statusCode(200)
                .body("analysedAt", notNullValue())
                .body("scoreGlobal", notNullValue())
                .extract().jsonPath();

        // The catalogue ids must match the solver constraint ids, otherwise the
        // screen would silently show rules without any result.
        List<String> catalogueNames = view.getList("contraintes.name");
        assertThat(catalogueNames).containsAll(analysedNames);
        List<String> scoredNames = view.getList("contraintes.findAll { it.score != null }.name");
        assertThat(scoredNames).containsExactlyInAnyOrderElementsOf(analysedNames);
    }

    @Test
    void resetEmptiesTheDatabase() {
        // Seed some data first so the reset has something to wipe. Uses the tiny
        // scenario so the solve (which persists the assignments) stays fast.
        String sample = given()
                .when().get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract().asString();
        given()
                .contentType("application/json")
                .body(sample)
                .when().post("/api/solve?seconds=1")
                .then()
                .statusCode(200);

        // Reset now empties the database instead of reloading a scenario: the
        // summary is all zeros and nothing remains persisted.
        given()
                .when().post("/api/planning/reset")
                .then()
                .statusCode(200)
                .body("animateurs", equalTo(0))
                .body("stands", equalTo(0))
                .body("creneaux", equalTo(0))
                .body("postes", equalTo(0));

        given()
                .when().get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(0));
    }

    @Test
    void exportScenarioReturnsTheCurrentReferenceDataAsYaml() {
        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);

        String yaml = given()
                .when().get("/api/planning/export-scenario")
                .then()
                .statusCode(200)
                .contentType("application/x-yaml")
                .header("Content-Disposition", notNullValue())
                .extract().asString();

        assertThat(yaml).contains("festival:", "creneaux:", "stands:", "animateurs:", "postes:")
                .contains("STAND-STRAT", "A1");
    }

    @Test
    void pdfExportBundlesOneFilePerAnimateur() throws IOException {
        String planningJson = given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract().asString();
        int animateurs = JsonPath.from(planningJson).getList("animateurs").size();

        byte[] zip = given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/planning/export/pdf/all")
                .then()
                .statusCode(200)
                .contentType("application/zip")
                .extract().asByteArray();

        List<String> entries = new ArrayList<>();
        try (ZipInputStream stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).hasSize(animateurs);
        assertThat(entries).allMatch(name -> name.endsWith(".pdf"));
    }

    @Test
    void exportEndpointsReturnFiles() {
        String planningJson = given()
                .when().get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract().asString();

        given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/planning/export/pdf/all")
                .then()
                .statusCode(200)
                .contentType("application/zip");

        given()
                .contentType("application/json")
                .body(planningJson)
                .when().post("/api/planning/export/ics/animateur/A1")
                .then()
                .statusCode(200)
                .contentType("text/calendar;charset=UTF-8");
    }
}
