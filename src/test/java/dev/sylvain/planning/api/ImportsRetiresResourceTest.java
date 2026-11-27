package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The CSV imports withdrawn in 4.0 — every referential but the animateurs, and
 * the stand matrix — still own their routes, so a script written against 3.x
 * reads where to go instead of a bare {@code 404}. Each one answers {@code 410}
 * with the YAML import as the way out, whatever it is sent.
 */
@QuarkusTest
class ImportsRetiresResourceTest {

    static Stream<Arguments> routesRetirees() {
        return Stream.of(
                        "/api/typologies/import-csv",
                        "/api/emplacements/import-csv",
                        "/api/stands/import-csv",
                        "/api/creneaux/import-csv",
                        "/api/journees-types/import-csv",
                        "/api/stands/import-grille")
                .flatMap(base -> Stream.of(
                        Arguments.of("POST", base),
                        Arguments.of("POST", base + "/analyse"),
                        Arguments.of("GET", base + "/exemple")));
    }

    @ParameterizedTest(name = "{0} {1} répond 410")
    @MethodSource("routesRetirees")
    void aRetiredImportRouteAnswers410AndPointsAtTheYamlImport(String verbe, String chemin) {
        var requete = given().header("X-Edition-Id", "E1");
        if ("POST".equals(verbe)) {
            requete = requete.contentType("application/json").body("{\"fileName\":\"f.csv\",\"content\":\"a;b\"}");
        }
        requete.when()
                .request(verbe, chemin)
                .then()
                .statusCode(410)
                .body("message", containsString("retiré depuis la 4.0"))
                .body("message", containsString("YAML"));
    }

    @ParameterizedTest(name = "POST {0} sans corps répond 410")
    @MethodSource("postsWithoutBody")
    void aRetiredImportAnswers410EvenWithoutABody(String chemin) {
        given().header("X-Edition-Id", "E1").when().post(chemin).then().statusCode(410);
    }

    static Stream<String> postsWithoutBody() {
        return routesRetirees().filter(a -> "POST".equals(a.get()[0])).map(a -> (String) a.get()[1]);
    }
}
