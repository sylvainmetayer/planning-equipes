package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanningResourceTest {

    @Test
    void sampleEndpointRetourneUnPlanning() {
        given().when()
                .get("/api/planning/sample")
                .then()
                .statusCode(200)
                .body("animateurs.size()", greaterThan(0))
                .body("postes.size()", greaterThan(0))
                .body("dateDebutFestival", notNullValue());
    }

    @Test
    void standCrudWorks() {
        // The id sent is ignored: the stand gets a generated one (ADR 0046).
        String id = given().contentType("application/json")
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
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .body("stand.id", notNullValue())
                .extract()
                .path("stand.id");

        given().when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + id + "' }.nom", equalTo("Test Stand"));

        given().when().delete("/api/stands/" + id).then().statusCode(204);
    }

    @Test
    void referenceDataMutationUpdatesTheStaleDataMarker() {
        String before = given().when()
                .get("/api/planning/persisted/resolution")
                .then()
                .statusCode(200)
                .extract()
                .path("derniereModificationDonnees");

        String standId = given().contentType("application/json")
                .body("""
                        {
                          "id":"STAND-STALE-MARKER",
                          "nom":"Test Stand",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":1,
                          "effectifMax":1,
                          "reserveMajeurs":false
                        }
                        """)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");

        try {
            String after = given().when()
                    .get("/api/planning/persisted/resolution")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("derniereModificationDonnees");

            assertThat(after).isNotNull();
            if (before != null) {
                assertThat(java.time.Instant.parse(after)).isAfterOrEqualTo(java.time.Instant.parse(before));
            }
        } finally {
            given().when().delete("/api/stands/" + standId).then().statusCode(204);
        }
    }

    @Test
    void creneauCrudWorks() {
        String futureTestDate = "2030-01-02";

        Object createdId = given().contentType("application/json")
                .body("""
                        {
                          "date":"%s",
                          "heureDebut":"18:00:00",
                          "heureFin":"22:00:00"
                        }
                        """.formatted(futureTestDate))
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200)
                .body("creneau.id", notNullValue())
                .extract()
                .path("creneau.id");

        given().when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .body("find { it.id == " + createdId + " }.date", equalTo(futureTestDate));

        given().when().delete("/api/creneaux/" + createdId).then().statusCode(204);
    }

    @Test
    void persistedPlanningEndpointIsReadOnly() {
        int before = given().when()
                .get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .extract()
                .path("assignments");

        given().when().get("/api/planning/persisted").then().statusCode(200).body("postes.size()", equalTo(before));

        // Reading the planning must never trigger a solve, so the stored
        // assignments are left untouched.
        given().when()
                .get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .body("assignments", equalTo(before));
    }

    @Test
    void constraintsCatalogueExposesEveryRuleWithADescription() {
        given().when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .body("contraintes.size()", greaterThan(0))
                .body("contraintes.findAll { it.description == null || it.description.isEmpty() }.size()", equalTo(0))
                .body("contraintes.findAll { !(it.niveau in ['HARD', 'MEDIUM', 'SOFT']) }.size()", equalTo(0));
    }

    @Test
    void volumetrieMatchesThePlanningActuallyBuiltForASolve() {
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        JsonPath sample = JsonPath.from(given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString());

        // Not stands.size() x créneaux.size(): entity count is one poste per
        // required seat, so it must match what a real solve builds.
        given().when()
                .get("/api/planning/volumetrie")
                .then()
                .statusCode(200)
                .body("animateurCount", equalTo(sample.getList("animateurs").size()))
                .body("posteCount", equalTo(sample.getList("postes").size()))
                // The scenario's créneaux are whole hours, so the seats add up
                // to a whole number of hours; the ceiling is a handful of full
                // days per animateur, well above what the seats need.
                .body("hoursToFill", greaterThan(0f))
                .body("hoursAvailable", greaterThan(0f));
    }

    @Test
    void volumetrieIsAllZeroWithoutReferenceData() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .get("/api/planning/volumetrie")
                .then()
                .statusCode(200)
                .body("animateurCount", equalTo(0))
                .body("posteCount", equalTo(0))
                .body("contrainteAdHocCount", equalTo(0))
                .body("hoursToFill", equalTo(0f))
                .body("hoursAvailable", equalTo(0f));
    }

    @Test
    void volumetrieCountsTheSeatsBeforeAnyAnimateurIsEntered() {
        // Seats depend on the stands and the créneaux only (issue #416): the
        // card must not read « nothing loaded » on an edition whose roster is
        // simply not typed in yet.
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().contentType("application/json")
                .body("""
                        {
                          "id":"STAND-VOLUMETRIE",
                          "nom":"Stand sans animateur",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":2,
                          "effectifMax":3,
                          "reserveMajeurs":false
                        }
                        """)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200);
        given().contentType("application/json")
                .body("""
                        {
                          "jour":1,
                          "date":"2026-08-01",
                          "heureDebut":"10:00:00",
                          "heureFin":"12:00:00"
                        }
                        """)
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/planning/volumetrie")
                .then()
                .statusCode(200)
                .body("animateurCount", equalTo(0))
                .body("posteCount", equalTo(2))
                .body("hoursToFill", equalTo(4f))
                .body("hoursAvailable", equalTo(0f));

        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    @Test
    void resetEmptiesTheDatabase() {
        // Seed some data first so the reset has something to wipe. Uses the tiny
        // scenario so the solve (which persists the assignments) stays fast.
        String sample = given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().contentType("application/json")
                .body(sample)
                .when()
                .post("/api/solve?seconds=1")
                .then()
                .statusCode(200);

        // Reset now empties the database instead of reloading a scenario: the
        // summary is all zeros and nothing remains persisted.
        given().when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200)
                .body("animateurs", equalTo(0))
                .body("stands", equalTo(0))
                .body("creneaux", equalTo(0))
                .body("postes", equalTo(0));

        given().when().get("/api/planning/persisted").then().statusCode(200).body("postes.size()", equalTo(0));
    }

    @Test
    void exportScenarioReturnsTheCurrentReferenceDataAsYaml() {
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);

        String yaml = given().when()
                .get("/api/planning/export-scenario")
                .then()
                .statusCode(200)
                .contentType("application/x-yaml")
                .header("Content-Disposition", notNullValue())
                .extract()
                .asString();

        assertThat(yaml)
                .contains("festival:", "creneaux:", "stands:", "animateurs:")
                // No seat list: the import rebuilds it from the stands and créneaux.
                .doesNotContain("postes:")
                .contains("STAND-STRAT", "A1");
    }

    @Test
    void pdfExportBundlesOneFilePerAnimateur() throws IOException {
        String planningJson = given().when()
                .get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        int animateurs = JsonPath.from(planningJson).getList("animateurs").size();

        byte[] zip = given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/planning/export/pdf/all")
                .then()
                .statusCode(200)
                .contentType("application/zip")
                .extract()
                .asByteArray();

        List<String> entries = new ArrayList<>();
        try (ZipInputStream stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).hasSize(animateurs).allMatch(name -> name.endsWith(".pdf"));
    }

    @Test
    void exportEndpointsReturnFiles() {
        String planningJson = given().when()
                .get("/api/planning/sample")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/planning/export/pdf/all")
                .then()
                .statusCode(200)
                .contentType("application/zip");

        given().contentType("application/json")
                .body(planningJson)
                .when()
                .post("/api/planning/export/ics/animateur/A1")
                .then()
                .statusCode(200)
                .contentType("text/calendar;charset=UTF-8");
    }
}
