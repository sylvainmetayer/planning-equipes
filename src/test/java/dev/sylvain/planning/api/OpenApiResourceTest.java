package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OpenApiResourceTest {

    @Test
    void openApiSpecIsExposed() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body(containsString("openapi"));
    }

    @Test
    void swaggerUiIsExposed() {
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/q/swagger-ui")
                .then()
                .statusCode(200)
                .body(containsString("swagger-ui"));
    }
}
