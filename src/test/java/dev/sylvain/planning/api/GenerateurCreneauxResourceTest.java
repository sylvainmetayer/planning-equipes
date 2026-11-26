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
 * The generator end to end (ADR 0126): a template, a calendar, the preview
 * that writes nothing, the application that materialises timeslots and keeps
 * the ids of the ones already there, the drift once the grid moves under the
 * calendar, the recognition that reads the templates back, the withdrawal of
 * dates — and the 3.x routes, which answer the same under their old keys.
 */
@QuarkusTest
class GenerateurCreneauxResourceTest {

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

    private static final String GABARITS = "{\"source\":\"GABARITS\"}";

    private static int countCreneaux() {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .path("size()");
    }

    private static List<Integer> creneauxOf(String prefixeDate) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/creneaux")
                .then()
                .extract()
                .jsonPath()
                .getList("findAll { it.date.startsWith('" + prefixeDate + "') }.id", Integer.class);
    }

    @Test
    void unCalendrierAppliqueMaterialiseLesCreneauxEtGardeLeursIds() {
        int avant = countCreneaux();
        Integer id = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(JOUR_NORMAL)
                .when()
                .post("/api/generateur-creneaux/gabarits")
                .then()
                .statusCode(200)
                .body("nom", equalTo("Jour normal (test)"))
                .body("vacations", hasSize(4))
                .extract()
                .path("id");
        try {
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body("[{\"date\":\"2033-05-02\",\"gabaritId\":" + id + "},"
                            + "{\"date\":\"2033-05-03\",\"gabaritId\":" + id + "}]")
                    .when()
                    .put("/api/generateur-creneaux/calendrier")
                    .then()
                    .statusCode(200)
                    .body("calendrier", hasSize(2))
                    .body("datesEnEcart", hasSize(2));

            // The preview says what would happen and writes nothing.
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(GABARITS)
                    .when()
                    .post("/api/generateur-creneaux/apercu")
                    .then()
                    .statusCode(200)
                    .body("application.crees", equalTo(8))
                    .body("application.supprimes", equalTo(0))
                    .body("application.aucunChangement", equalTo(false));
            assertThat(countCreneaux()).isEqualTo(avant);

            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(GABARITS)
                    .when()
                    .post("/api/generateur-creneaux/application")
                    .then()
                    .statusCode(200)
                    .body("application.crees", equalTo(8))
                    .body("controle.nombreCreneaux", equalTo(avant + 8));
            assertThat(countCreneaux()).isEqualTo(avant + 8);
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get("/api/creneaux")
                    .then()
                    .body("findAll { it.date == '2033-05-02' && it.couverturePause }.size()", equalTo(2));

            // Applied twice: nothing moves, and the ids are the same rows.
            List<Integer> ids = creneauxOf("2033-05");
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(GABARITS)
                    .when()
                    .post("/api/generateur-creneaux/application")
                    .then()
                    .statusCode(200)
                    .body("application.aucunChangement", equalTo(true))
                    .body("application.conserves", equalTo(8));
            assertThat(creneauxOf("2033-05")).containsExactlyElementsOf(ids);
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get("/api/generateur-creneaux")
                    .then()
                    .body("datesEnEcart", hasSize(0));

            // The grid moves under the calendar: the date is in drift, and only what is missing is created.
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete("/api/creneaux/" + ids.get(0))
                    .then()
                    .statusCode(204);
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get("/api/generateur-creneaux")
                    .then()
                    .body("datesEnEcart", hasItem("2033-05-02"));
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(GABARITS)
                    .when()
                    .post("/api/generateur-creneaux/application")
                    .then()
                    .statusCode(200)
                    .body("application.crees", equalTo(1))
                    .body("application.conserves", equalTo(7));

            // Recognition reads the templates back: the two dates share one shape.
            given().header("X-Edition-Id", "E1")
                    .when()
                    .post("/api/generateur-creneaux/reconnaissance/apercu")
                    .then()
                    .statusCode(200)
                    .body("gabarits.find { it.vacations.size() == 4 }", org.hamcrest.Matchers.notNullValue())
                    .body("calendrier.date", hasItem("2033-05-02"))
                    .body("calendrier.date", hasItem("2033-05-03"));
        } finally {
            for (Integer creneauId : creneauxOf("2033-05")) {
                given().header("X-Edition-Id", "E1")
                        .when()
                        .delete("/api/creneaux/" + creneauId)
                        .then()
                        .statusCode(204);
            }
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete("/api/generateur-creneaux/gabarits/" + id)
                    .then()
                    .statusCode(204);
        }
    }

    @Test
    void unNomDejaPrisOuUneJourneeSansVacationEstRefuse() {
        Integer id = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(JOUR_NORMAL)
                .when()
                .post("/api/generateur-creneaux/gabarits")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        try {
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(JOUR_NORMAL.replace("Jour normal (test)", "jour NORMAL (test)"))
                    .when()
                    .post("/api/generateur-creneaux/gabarits")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("s'appelle déjà"));
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body("{\"nom\":\"Vide (test)\",\"vacations\":[]}")
                    .when()
                    .post("/api/generateur-creneaux/gabarits")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("au moins une vacation"));
        } finally {
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete("/api/generateur-creneaux/gabarits/" + id)
                    .then()
                    .statusCode(204);
        }
    }

    /**
     * The edition may already hold a calendar — a scenario import recognises
     * the templates of the grid it lands — so the test empties it first, and
     * puts it back.
     */
    @Test
    void unCalendrierSansDateNAPasDApplication() {
        String calendrierAvant = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/generateur-creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("calendrier");
        List<Map<String, Object>> sauvegarde = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/generateur-creneaux")
                .then()
                .extract()
                .jsonPath()
                .getList("calendrier");
        given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("[]")
                .when()
                .put("/api/generateur-creneaux/calendrier")
                .then()
                .statusCode(200);
        try {
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(GABARITS)
                    .when()
                    .post("/api/generateur-creneaux/apercu")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("Aucune date"));
        } finally {
            assertThat(calendrierAvant).isNotNull();
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(sauvegarde)
                    .when()
                    .put("/api/generateur-creneaux/calendrier")
                    .then()
                    .statusCode(200);
        }
    }

    /**
     * The 3.x routes are aliases of the generator until 5.0: the same template
     * and calendar read under their old keys, {@code journeesTypes} and
     * {@code journeeTypeId}, and the generator's withdrawal leaves the
     * timeslots where they are.
     */
    @Test
    void theOldRoutesAnswerUnderTheirOldKeysAndAWithdrawnDateKeepsItsTimeslots() {
        Integer id = given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body(JOUR_NORMAL)
                .when()
                .post("/api/journees-types")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        try {
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body("[{\"date\":\"2033-06-02\",\"journeeTypeId\":" + id + "}]")
                    .when()
                    .put("/api/journees-types/calendrier")
                    .then()
                    .statusCode(200)
                    .body("journeesTypes.find { it.id == " + id + " }.nom", equalTo("Jour normal (test)"))
                    .body("calendrier.find { it.date == '2033-06-02' }.journeeTypeId", equalTo(id));
            given().header("X-Edition-Id", "E1")
                    .when()
                    .get("/api/generateur-creneaux")
                    .then()
                    .statusCode(200)
                    .body("calendrier.find { it.date == '2033-06-02' }.gabaritId", equalTo(id));
            given().header("X-Edition-Id", "E1")
                    .when()
                    .post("/api/journees-types/application")
                    .then()
                    .statusCode(200)
                    .body("crees", equalTo(4));
            int avant = countCreneaux();

            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body("{\"dates\":[\"2033-06-02\"]}")
                    .when()
                    .post("/api/generateur-creneaux/retrait")
                    .then()
                    .statusCode(200)
                    .body("calendrier.date", org.hamcrest.Matchers.not(hasItem("2033-06-02")));
            assertThat(countCreneaux()).isEqualTo(avant);
        } finally {
            for (Integer creneauId : creneauxOf("2033-06")) {
                given().header("X-Edition-Id", "E1")
                        .when()
                        .delete("/api/creneaux/" + creneauId)
                        .then()
                        .statusCode(204);
            }
            given().header("X-Edition-Id", "E1")
                    .when()
                    .delete("/api/journees-types/" + id)
                    .then()
                    .statusCode(204);
        }
    }

    /** A recurrence goes through the same door: previewed without a write, then added. */
    @Test
    void aRecurrenceIsPreviewedThenAddedThroughTheGenerator() {
        String demande = """
                {"source":"RECURRENCE","recurrence":{"jours":"PLAGE","dateDebut":"2033-07-04",
                "dateFin":"2033-07-05","fenetres":[{"heureDebut":"10:00:00","heureFin":"12:00:00"}]}}""";
        int avant = countCreneaux();
        try {
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(demande)
                    .when()
                    .post("/api/generateur-creneaux/apercu")
                    .then()
                    .statusCode(200)
                    .body("source", equalTo("RECURRENCE"))
                    .body("nombreGeneres", equalTo(2));
            assertThat(countCreneaux()).isEqualTo(avant);
            given().header("X-Edition-Id", "E1")
                    .contentType("application/json")
                    .body(demande)
                    .when()
                    .post("/api/generateur-creneaux/application")
                    .then()
                    .statusCode(200)
                    .body("nombreGeneres", equalTo(2))
                    .body("creneaux.id", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.notNullValue()));
            assertThat(countCreneaux()).isEqualTo(avant + 2);
        } finally {
            for (Integer creneauId : creneauxOf("2033-07")) {
                given().header("X-Edition-Id", "E1")
                        .when()
                        .delete("/api/creneaux/" + creneauId)
                        .then()
                        .statusCode(204);
            }
        }
    }
}
