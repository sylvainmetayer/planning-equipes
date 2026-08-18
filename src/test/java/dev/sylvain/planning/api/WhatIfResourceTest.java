package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What-if simulation (issue #73): the variant must change the verdict without
 * changing a single row of the referential.
 */
@QuarkusTest
class WhatIfResourceTest {

    @BeforeEach
    void chargerScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(204);
    }

    private io.restassured.path.json.JsonPath simuler(String mutations) {
        return given()
                .contentType(ContentType.JSON)
                .body(mutations)
                .when().post("/api/what-if")
                .then()
                .statusCode(200)
                .extract().jsonPath();
    }

    @Test
    void unePlaceVideEstUneSimulationNeutre() {
        var resultat = simuler("{}");
        assertThat(resultat.getInt("animateurs")).isEqualTo(resultat.getInt("animateursReference"));
        assertThat(resultat.getInt("standsOuverts")).isEqualTo(resultat.getInt("standsOuvertsReference"));
        assertThat(resultat.getBoolean("simulation.feasible")).isEqualTo(resultat.getBoolean("reference.feasible"));
    }

    @Test
    void ajouterDesAnimateursAugmenteLaCapacite() {
        var resultat = simuler("{\"animateursAjoutes\":5}");
        assertThat(resultat.getInt("animateurs")).isEqualTo(resultat.getInt("animateursReference") + 5);
    }

    @Test
    void retirerTousLesAnimateursRendLePlanningInfaisable() {
        List<String> ids = given()
                .when().get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("id", String.class);
        assertThat(ids).isNotEmpty();

        String retires = ids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
        var resultat = simuler("{\"animateursRetires\":[" + retires + "]}");

        assertThat(resultat.getInt("animateurs")).isZero();
        assertThat(resultat.getBoolean("simulation.feasible")).isFalse();
        // And nothing was written: the referential still holds every animateur.
        given()
                .when().get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("size()", equalTo(ids.size()));
    }

    @Test
    void fermerUnStandNeTouchePasAuReferentiel() {
        List<String> stands = given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("id", String.class);
        assertThat(stands).isNotEmpty();

        var resultat = simuler("{\"standsFermes\":[\"" + stands.getFirst() + "\"]}");
        assertThat(resultat.getInt("standsOuverts")).isEqualTo(resultat.getInt("standsOuvertsReference") - 1);

        given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .body("size()", equalTo(stands.size()));
    }

    @Test
    void augmenterLEffectifRequisNeTouchePasAuReferentiel() {
        var stand = given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .extract().jsonPath();
        String id = stand.getString("[0].id");
        int effectifMin = stand.getInt("[0].effectifMin");

        simuler("{\"effectifsMin\":{\"" + id + "\":" + (effectifMin + 10) + "}}");

        given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + id + "' }.effectifMin", equalTo(effectifMin));
    }
}
