package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/marge/tension}: the rules are {@code TensionAnalyzerTest}'s;
 * this pins the route and its answer on an edition with nothing to read —
 * a sentence, never a calm grid.
 */
@QuarkusTest
class MargeTensionResourceTest {

    @Inject
    PlanningPersistenceService persistence;

    @Test
    void anEditionWithoutAnimateurAnswersWithASentenceRatherThanACalmGrid() {
        persistence.clearDatabase();

        given().when()
                .get("/api/marge/tension")
                .then()
                .statusCode(200)
                .body("jours.size()", equalTo(0))
                .body("cellulesCritiques", equalTo(0))
                .body("message", containsString("Aucun animateur"));
    }
}
