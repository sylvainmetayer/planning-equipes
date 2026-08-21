package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class PangolinHeaderFilterTest {

    @Test
    void mirroreLEnTetePangolinRecuSurLaReponse() {
        given()
                .header(PangolinHeaderFilter.EN_TETE, "true")
                .when().get("/api/config")
                .then()
                .statusCode(200)
                .header(PangolinHeaderFilter.EN_TETE, "true");
    }

    @Test
    void nAjouteRienSansEnTeteEntrant() {
        given()
                .when().get("/api/config")
                .then()
                .statusCode(200)
                .header(PangolinHeaderFilter.EN_TETE, nullValue());
    }
}
