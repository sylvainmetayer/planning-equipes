package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The day templates end to end (ADR 0032): a template, a calendar, the
 * preview that writes nothing, the application that materialises créneaux
 * and keeps the ids of the ones already there, the drift once the grid moves
 * under the calendar, and the recognition that reads the templates back.
 */
@QuarkusTest
class JourneeTypeResourceTest {

    private static final String JOUR_NORMAL = """
            {
              "nom": "Jour normal (test)",
              "vacations": [
                {"heureDebut":"09:00:00","heureFin":"12:00:00"},
                {"heureDebut":"12:00:00","heureFin":"13:00:00","couverturePause":true},
                {"heureDebut":"13:00:00","heureFin":"14:00:00","couverturePause":true},
                {"heureDebut":"14:00:00","heureFin":"20:00:00"}
              ]
            }
            """;

    private static int countCreneaux() {
        return given().when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .path("size()");
    }

    private static List<Integer> creneauxOf(String prefixeDate) {
        return given().when()
                .get("/api/creneaux")
                .then()
                .extract()
                .jsonPath()
                .getList("findAll { it.date.startsWith('" + prefixeDate + "') }.id", Integer.class);
    }

    @Test
    void unCalendrierAppliqueMaterialiseLesCreneauxEtGardeLeursIds() {
        String modeAvant =
                given().when().get("/api/parametres-decoupage").then().extract().path("modeGrille");
        int avant = countCreneaux();
        Integer id = given().contentType("application/json")
                .body(JOUR_NORMAL)
                .when()
                .post("/api/journees-types")
                .then()
                .statusCode(200)
                .body("nom", equalTo("Jour normal (test)"))
                .body("vacations", hasSize(4))
                .extract()
                .path("id");
        try {
            given().contentType("application/json")
                    .body("[{\"date\":\"2033-05-02\",\"journeeTypeId\":" + id + "},"
                            + "{\"date\":\"2033-05-03\",\"journeeTypeId\":" + id + "}]")
                    .when()
                    .put("/api/journees-types/calendrier")
                    .then()
                    .statusCode(200)
                    .body("calendrier", hasSize(2))
                    .body("datesEnEcart", hasSize(2));

            // The preview says what would happen and writes nothing.
            given().when()
                    .post("/api/journees-types/application/apercu")
                    .then()
                    .statusCode(200)
                    .body("crees", equalTo(8))
                    .body("supprimes", equalTo(0))
                    .body("aucunChangement", equalTo(false))
                    .body("controle.mode", equalTo("VACATIONS"));
            assertThat(countCreneaux()).isEqualTo(avant);

            given().when()
                    .post("/api/journees-types/application")
                    .then()
                    .statusCode(200)
                    .body("crees", equalTo(8))
                    .body("controle.mode", equalTo("VACATIONS"));
            assertThat(countCreneaux()).isEqualTo(avant + 8);
            given().when().get("/api/parametres-decoupage").then().body("modeGrille", equalTo("VACATIONS"));
            given().when()
                    .get("/api/creneaux")
                    .then()
                    .body("findAll { it.date == '2033-05-02' && it.couverturePause }.size()", equalTo(2));

            // Applied twice: nothing moves, and the ids are the same rows.
            List<Integer> ids = creneauxOf("2033-05");
            given().when()
                    .post("/api/journees-types/application")
                    .then()
                    .statusCode(200)
                    .body("aucunChangement", equalTo(true))
                    .body("conserves", equalTo(8));
            assertThat(creneauxOf("2033-05")).containsExactlyElementsOf(ids);
            given().when().get("/api/journees-types").then().body("datesEnEcart", hasSize(0));

            // The grid moves under the calendar: the date is in drift, and only what is missing is created.
            given().when().delete("/api/creneaux/" + ids.get(0)).then().statusCode(204);
            given().when().get("/api/journees-types").then().body("datesEnEcart", hasItem("2033-05-02"));
            given().when()
                    .post("/api/journees-types/application")
                    .then()
                    .statusCode(200)
                    .body("crees", equalTo(1))
                    .body("conserves", equalTo(7));

            // Recognition reads the templates back: the two dates share one shape.
            given().when()
                    .post("/api/journees-types/reconnaissance/apercu")
                    .then()
                    .statusCode(200)
                    .body("journeesTypes.find { it.vacations.size() == 4 }", org.hamcrest.Matchers.notNullValue())
                    .body("calendrier.date", hasItem("2033-05-02"))
                    .body("calendrier.date", hasItem("2033-05-03"));
        } finally {
            for (Integer creneauId : creneauxOf("2033-05")) {
                given().when().delete("/api/creneaux/" + creneauId).then().statusCode(204);
            }
            given().when().delete("/api/journees-types/" + id).then().statusCode(204);
            given().contentType("application/json")
                    .body("{\"modeGrille\":\"" + modeAvant + "\"}")
                    .when()
                    .put("/api/parametres-decoupage/mode-grille")
                    .then()
                    .statusCode(200);
        }
    }

    @Test
    void unNomDejaPrisOuUneJourneeSansVacationEstRefuse() {
        Integer id = given().contentType("application/json")
                .body(JOUR_NORMAL)
                .when()
                .post("/api/journees-types")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        try {
            given().contentType("application/json")
                    .body(JOUR_NORMAL.replace("Jour normal (test)", "jour NORMAL (test)"))
                    .when()
                    .post("/api/journees-types")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("s'appelle déjà"));
            given().contentType("application/json")
                    .body("{\"nom\":\"Vide (test)\",\"vacations\":[]}")
                    .when()
                    .post("/api/journees-types")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("au moins une vacation"));
        } finally {
            given().when().delete("/api/journees-types/" + id).then().statusCode(204);
        }
    }

    /**
     * The edition may already hold a calendar — a scenario import recognises
     * the templates of the grid it lands — so the test empties it first, and
     * puts it back.
     */
    @Test
    void unCalendrierSansDateNAPasDApplication() {
        String calendrierAvant = given().when()
                .get("/api/journees-types")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("calendrier");
        List<Map<String, Object>> sauvegarde = given().when()
                .get("/api/journees-types")
                .then()
                .extract()
                .jsonPath()
                .getList("calendrier");
        given().contentType("application/json")
                .body("[]")
                .when()
                .put("/api/journees-types/calendrier")
                .then()
                .statusCode(200);
        try {
            given().when()
                    .post("/api/journees-types/application/apercu")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("Aucune date"));
        } finally {
            assertThat(calendrierAvant).isNotNull();
            given().contentType("application/json")
                    .body(sauvegarde)
                    .when()
                    .put("/api/journees-types/calendrier")
                    .then()
                    .statusCode(200);
        }
    }
}
