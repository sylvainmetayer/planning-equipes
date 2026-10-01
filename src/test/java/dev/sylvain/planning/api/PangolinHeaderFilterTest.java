package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PangolinHeaderFilterTest {

    @Test
    void mirroreLEnTetePangolinRecuSurLaReponse() {
        given().header("X-Edition-Id", "E1")
                .header(PangolinHeaderFilter.EN_TETE, "true")
                .when()
                .get("/api/config")
                .then()
                .statusCode(200)
                .header(PangolinHeaderFilter.EN_TETE, "true");
    }

    @Test
    void nAjouteRienSansEnTeteEntrant() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/config")
                .then()
                .statusCode(200)
                .header(PangolinHeaderFilter.EN_TETE, nullValue());
    }
}
