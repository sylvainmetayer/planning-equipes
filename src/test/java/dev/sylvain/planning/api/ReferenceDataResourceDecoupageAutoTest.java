package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The optional {@code decoupageAuto:} scenario section (issue #110): a
 * scenario written straight in "amplitudes" (one long opening window per day)
 * can ask its import to auto-slice itself into vacations, sparing the
 * operator the manual "Découpage" screen round-trip. Since issue #172 the
 * découpage runs <b>in place</b>: the imported amplitudes are replaced by the
 * generated vacations, the edition only ever holds one grid.
 * {@code scenario-decoupage-auto.yaml} (test fixture) carries a single 14h
 * amplitude — same shape as {@code VacationGeneratorServiceTest}'s "continu"
 * case — which {@code VacationGeneratorService} always relay-splits into
 * exactly 5 vacations under default {@code parametresDecoupage} (two real
 * meal breaks, each splitting an otherwise-3-vacation relay chain in two).
 */
@QuarkusTest
class ReferenceDataResourceDecoupageAutoTest {

    @Test
    void importScenarioAvecDecoupageAutoRemplaceLesAmplitudesParLesVacations() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario-decoupage-auto.yaml")
                .then()
                .statusCode(200)
                .body("decoupageAuto", equalTo(true));

        // The edition's créneaux ARE the generated vacations: the 14h
        // amplitude the file carried was consumed by the in-place découpage.
        List<Map<String, Object>> creneaux = given()
                .when().get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(creneaux).hasSize(5);
        assertThat(creneaux).noneMatch(c -> "09:00:00".equals(c.get("heureDebut"))
                && "23:00:00".equals(c.get("heureFin")));
    }

    @Test
    void importScenarioSansDecoupageAutoImporteLesCreneauxTelsQuels() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);

        List<Map<String, Object>> creneaux = given()
                .when().get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("$");
        assertThat(creneaux).isNotEmpty();
    }
}
