package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/** The four sections under {@code /parametres}, and the 3.x roots that stay as aliases of them. */
@QuarkusTest
class ParametresSectionsResourceTest {

    @Test
    void theWholeAnswerCarriesTheFourSections() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/parametres")
                .then()
                .statusCode(200)
                .body("legal.dureePauseMinutes", notNullValue())
                .body("organisation.joursConsecutifsMax", notNullValue())
                .body("notifications.delaiRelanceHeures", notNullValue())
                .body("instance.instance", notNullValue());
    }

    @Test
    void eachFormerRootAnswersWhatItsSectionAnswers() {
        String[][] roots = {
            {"/api/parametres-legaux", "/api/parametres/legal"},
            {"/api/parametres-qualite", "/api/parametres/organisation"},
            {"/api/parametres-notifications", "/api/parametres/notifications"},
            {"/api/parametres-solveur", "/api/parametres/instance"},
        };
        for (String[] pair : roots) {
            String old = given().header("X-Edition-Id", "E1")
                    .get(pair[0])
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            String section = given().header("X-Edition-Id", "E1")
                    .get(pair[1])
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            org.assertj.core.api.Assertions.assertThat(section).as(pair[1]).isEqualTo(old);
        }
    }

    @Test
    void aWriteThroughTheSectionIsReadBackThroughTheFormerRoot() {
        String courant = given().header("X-Edition-Id", "E1")
                .get("/api/parametres/notifications")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(courant.replaceAll("\"anciennete[A-Za-z]*\":\\d+", "\"ancienneteEchangeJours\":4"))
                .put("/api/parametres/notifications")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "E1")
                .get("/api/parametres-notifications")
                .then()
                .statusCode(200)
                .body("ancienneteEchangeJours", equalTo(4));
    }

    @Test
    void aFieldOfAnotherSectionIsRefusedBySectionButStillIgnoredByTheFormerRoot() {
        String courant = given().header("X-Edition-Id", "E1")
                .get("/api/parametres/notifications")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        String avecIntrus = courant.replaceFirst("\\{", "{\"dureePauseMinutes\":25,");
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(avecIntrus)
                .put("/api/parametres/notifications")
                .then()
                .statusCode(400)
                .body("message", containsString("dureePauseMinutes"));
        given().header("X-Edition-Id", "E1")
                .contentType(ContentType.JSON)
                .body(avecIntrus)
                .put("/api/parametres-notifications")
                .then()
                .statusCode(200);
    }
}
