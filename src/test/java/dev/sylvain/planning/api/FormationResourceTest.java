package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/formation} and its CSV: a read-out opened before any solve
 * as well as after, so an empty edition answers rather than failing.
 */
@QuarkusTest
class FormationResourceTest {

    @Inject
    PlanningPersistenceService persistence;

    @BeforeEach
    void clear() {
        persistence.clearDatabase();
    }

    @Test
    void anEmptyEditionAnswersWithAnEmptyPlanThatSaysWhy() {
        given().when()
                .get("/api/formation")
                .then()
                .statusCode(200)
                .body("typologies.size()", equalTo(0))
                .body("planPersiste", equalTo(false))
                .body("aucunAnimateur", equalTo(true))
                .body("aucuneCompetence", equalTo(true));
    }

    @Test
    void theExportIsACsvWithItsHeaderEvenWhenNothingIsListed() {
        given().when()
                .get("/api/formation/export")
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("plan-formation.csv"))
                .body(startsWith("﻿typologie;manque besoin;"));
    }
}
