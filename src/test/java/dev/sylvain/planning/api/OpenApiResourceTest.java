package dev.sylvain.planning.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class OpenApiResourceTest {

    @Test
    void openApiSpecIsExposed() {
        given()
                .when().get("/q/openapi")
                .then()
                .statusCode(200)
                .body(containsString("openapi"));
    }

    @Test
    void swaggerUiIsExposed() {
        given()
                .when().get("/q/swagger-ui")
                .then()
                .statusCode(200)
                .body(containsString("swagger-ui"));
    }
}
