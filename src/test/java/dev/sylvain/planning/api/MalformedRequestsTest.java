package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The requests an API fuzzer (Schemathesis, on the published contract) turned
 * into a 500: a missing body, an empty element in a list, a U+0000 the
 * database refuses, a scenario name that names no file, an export of an empty
 * edition. Each is the caller's error and must say so — 400, 404 or 409 —
 * instead of the generic « Internal server error » and its Sentry alert.
 */
@QuarkusTest
class MalformedRequestsTest {

    private static final String EDITION = "E1";

    static Stream<Arguments> refusals() {
        return Stream.of(
                // No body, or a JSON null: the first read of it was an NPE.
                Arguments.of("POST", "/api/animateurs", "", 400),
                Arguments.of("POST", "/api/stands", "", 400),
                Arguments.of("POST", "/api/emplacements", "", 400),
                Arguments.of("POST", "/api/typologies", "null", 400),
                Arguments.of("POST", "/api/contraintes-ad-hoc", "", 400),
                Arguments.of("POST", "/api/verrouillages", "", 400),
                Arguments.of("POST", "/api/editions", "", 400),
                Arguments.of("POST", "/api/journees-types", "", 400),
                Arguments.of("POST", "/api/generateur-creneaux/gabarits", "", 400),
                Arguments.of("POST", "/api/creneaux/recurrence/apercu", "", 400),
                Arguments.of("POST", "/api/creneaux/derivation/apercu", "", 400),
                Arguments.of("PUT", "/api/constraints/posteDoitEtrePourvu", "", 400),
                Arguments.of("PUT", "/api/constraints/equilibrerCharge/poids", "null", 400),
                Arguments.of("PUT", "/api/parametres/legal", "", 400),
                Arguments.of("PUT", "/api/parametres-legaux", "", 400),
                Arguments.of("PUT", "/api/parametres-contact", "null", 400),
                Arguments.of("PUT", "/api/generateur-creneaux/calendrier", "", 400),
                Arguments.of("PUT", "/api/journees-types/calendrier", "", 400),
                Arguments.of("POST", "/api/postes/inconnu/simulation-swap?animateurId=A1", "", 400),
                // A field the database requires, absent.
                Arguments.of("POST", "/api/emplacements", "{}", 400),
                Arguments.of("POST", "/api/contraintes-ad-hoc", "{}", 400),
                // An empty element in a list: List.copyOf and the loops threw a bare NPE.
                Arguments.of("PUT", "/api/ouvertures-stands/grille", "{\"stands\": [null]}", 400),
                Arguments.of(
                        "POST", "/api/consignes/apercu", "{\"dates\": [\"2027-07-01\"], \"ouvertures\": [null]}", 400),
                Arguments.of(
                        "POST",
                        "/api/generateur-creneaux/apercu",
                        "{\"source\": \"RECURRENCE\", \"recurrence\": {\"fenetres\": [null]}}",
                        400),
                // U+0000 reaches PostgreSQL, which refuses it in any text.
                Arguments.of("POST", "/api/typologies", "{\"label\": \"a\\u0000b\"}", 400),
                // A seat that does not exist in a plan sent without seats.
                Arguments.of("POST", "/api/postes/inconnu/simulation-swap?animateurId=A1", "{}", 404));
    }

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @MethodSource("refusals")
    void malformedRequestIsRefusedAsTheCallersError(String method, String path, String body, int status) {
        RequestSpecification request = given().header("X-Edition-Id", EDITION)
                .contentType(ContentType.JSON)
                .body(body);
        request.when().request(method, path).then().statusCode(status);
    }

    @Test
    void missingBodySaysSo() {
        given().header("X-Edition-Id", EDITION)
                .contentType(ContentType.JSON)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(400)
                .body("message", equalTo(RequiredBody.MESSAGE));
    }

    @Test
    void nulCharacterInAQueryIsABadRequest() {
        given().header("X-Edition-Id", EDITION)
                .queryParam("id", "\u0000")
                .when()
                .get("/api/stands/usages")
                .then()
                .statusCode(400);
    }

    @Test
    void unknownScenarioNameIsNotFound() {
        given().header("X-Edition-Id", EDITION)
                .queryParam("name", "nexiste-pas")
                .when()
                .get("/api/planning/sample")
                .then()
                .statusCode(404);
        given().header("X-Edition-Id", EDITION)
                .queryParam("name", "nexiste-pas")
                .when()
                .get("/api/reference-data/cible-scenario")
                .then()
                .statusCode(404);
    }

    @Test
    void exportingAnEmptyEditionIsAConflict() {
        String empty = given().header("X-Edition-Id", EDITION)
                .contentType(ContentType.JSON)
                .body("{\"nom\": \"Vide pour l'export\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        try {
            given().header("X-Edition-Id", empty)
                    .when()
                    .get("/api/planning/export-scenario")
                    .then()
                    .statusCode(409);
        } finally {
            given().header("X-Edition-Id", EDITION).when().delete("/api/editions/" + empty);
        }
    }
}
