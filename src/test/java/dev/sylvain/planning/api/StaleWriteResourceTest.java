package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * The 409 of a stale write, as the frontend reads it (issue #362): a body
 * carrying the {@code MODIFICATION_CONCURRENTE} code — what tells this 409
 * apart from a busy solver's — and the row's current {@code modifieLe}, so
 * sending it back overwrites knowingly.
 */
@QuarkusTest
class StaleWriteResourceTest {

    @Test
    void putWithAnOutdatedModifieLeAnswers409WithTheCodeAndTheCurrentStamp() {
        String stand = """
                {"nom":"Stand","typologiesProposees":["STRATEGIE"],"effectifMin":1,"effectifMax":1,
                 "reserveMajeurs":false,"premium":false,"niveauEffort":"NORMAL"}""";
        // The id is generated on creation (ADR 0050): the response names the row.
        JsonPath cree = given().contentType(ContentType.JSON)
                .body(stand)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .body("stand.modifieLe", notNullValue())
                .extract()
                .jsonPath();
        String id = cree.getString("stand.id");
        String modifieLe = cree.getString("stand.modifieLe");
        try {
            given().contentType(ContentType.JSON)
                    .body(stand.replace(
                            "\"nom\":\"Stand\"", "\"nom\":\"Périmé\",\"modifieLe\":\"2020-01-01T00:00:00Z\""))
                    .when()
                    .put("/api/stands/" + id)
                    .then()
                    .statusCode(409)
                    .body("code", equalTo(StaleWriteError.STALE_WRITE_CODE))
                    .body("message", containsString("par une autre session"))
                    .body("modifieLe", notNullValue());

            // The stamp the client loaded, or none: both go through.
            given().contentType(ContentType.JSON)
                    .body(stand.replace("\"nom\":\"Stand\"", "\"nom\":\"À jour\",\"modifieLe\":\"" + modifieLe + "\""))
                    .when()
                    .put("/api/stands/" + id)
                    .then()
                    .statusCode(200)
                    .body("stand.nom", equalTo("À jour"));
            given().contentType(ContentType.JSON)
                    .body(stand.replace("\"nom\":\"Stand\"", "\"nom\":\"Sans précondition\""))
                    .when()
                    .put("/api/stands/" + id)
                    .then()
                    .statusCode(200)
                    .body("stand.nom", equalTo("Sans précondition"));
        } finally {
            given().when().delete("/api/stands/" + id);
        }
    }
}
