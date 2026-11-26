package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The refusals of the measures' routes, on the new path and on the deprecated
 * alias that still answers until 5.0: both go through the same checks, so a
 * missing or unreadable parameter is a 400 with its sentence, never a 500.
 */
@QuarkusTest
class MesuresEditionResourceTest {

    private static final String[] DETAIL_PATHS = {"/api/mesures-edition/realise/detail", "/api/planning/realise/detail"
    };

    private static String body(String path, String query) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get(path + query)
                .then()
                .statusCode(400)
                .extract()
                .asString();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void detailWithoutStandIsRefused(int path) {
        assertThat(body(DETAIL_PATHS[path], "?jour=2026-07-01")).contains("Le stand est obligatoire");
        assertThat(body(DETAIL_PATHS[path], "?jour=2026-07-01&stand=")).contains("Le stand est obligatoire");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void detailWithoutOrWithUnreadableDayIsRefused(int path) {
        assertThat(body(DETAIL_PATHS[path], "?stand=S1")).contains("La journée est obligatoire");
        assertThat(body(DETAIL_PATHS[path], "?stand=S1&jour=demain")).contains("Journée illisible : demain");
    }

    @Test
    void deletingAnUnknownIndicatorAnswers404OnBothPaths() {
        for (String path : new String[] {"/api/mesures-edition/indicateurs/", "/api/kpi/historique/"}) {
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete(path + "999999999")
                    .then()
                    .statusCode(404);
        }
    }

    @Test
    void theGridAndItsCsvAnswerOnBothPaths() {
        for (String path : new String[] {"/api/mesures-edition/realise", "/api/planning/realise"}) {
            given().header("X-Edition-Id", "E1").when().get(path).then().statusCode(200);
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get(path + "/export")
                    .then()
                    .statusCode(200)
                    .contentType("text/csv");
        }
    }
}
