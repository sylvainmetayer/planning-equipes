package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A seat moved by hand (issue #308), over the API the day views use: a drop
 * on a held seat swaps, a drop on a person hands the seat over, and a drop
 * that would break a hard rule is refused before anything is written —
 * server-side, whatever the browser checked.
 */
@QuarkusTest
class DeplacementResourceTest {

    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /** Solves the sample scenario, which persists its plan: three seats, three animateurs. */
    private JsonPath persistedPlan() {
        String sample = given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().contentType("application/json")
                .body(sample)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
        return given().when()
                .get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static Map<String, String> occupants(JsonPath plan) {
        List<Map<String, Object>> postes = plan.getList("postes");
        return postes.stream()
                .filter(poste -> poste.get("animateur") != null)
                .collect(java.util.stream.Collectors.toMap(poste -> (String) poste.get("id"), poste ->
                        (String) ((Map<?, ?>) poste.get("animateur")).get("id")));
    }

    /** Two held seats on the same créneau, whatever the solver decided. */
    private static String[] twoSeatsOnOneCreneau(JsonPath plan) {
        List<Map<String, Object>> postes = plan.getList("postes.findAll { it.animateur != null }");
        for (Map<String, Object> premier : postes) {
            for (Map<String, Object> second : postes) {
                if (premier == second) {
                    continue;
                }
                Object creneauPremier = ((Map<?, ?>) premier.get("creneau")).get("id");
                Object creneauSecond = ((Map<?, ?>) second.get("creneau")).get("id");
                if (creneauPremier.equals(creneauSecond)) {
                    return new String[] {(String) premier.get("id"), (String) second.get("id")};
                }
            }
        }
        throw new AssertionError("the sample plan should hold two seats on one créneau");
    }

    @Test
    void droppingOnAHeldSeatSwapsTheTwoAnimateurs() {
        JsonPath plan = persistedPlan();
        Map<String, String> avant = occupants(plan);
        String[] sieges = twoSeatsOnOneCreneau(plan);

        // Simulated with an empty body: the persisted plan is what the drop is about.
        given().contentType("application/json")
                .when()
                .post("/api/postes/" + sieges[0] + "/deplacement/simulation?cible=" + sieges[1])
                .then()
                .statusCode(200)
                .body("posteSourceId", equalTo(sieges[0]))
                .body("posteCibleId", equalTo(sieges[1]))
                .body("animateurSourceId", equalTo(avant.get(sieges[0])))
                .body("animateurCibleId", equalTo(avant.get(sieges[1])))
                .body("scoreAvant", notNullValue());
        assertThat(occupants(given().when()
                        .get("/api/planning/persisted")
                        .then()
                        .extract()
                        .jsonPath()))
                .as("a simulation writes nothing")
                .isEqualTo(avant);

        given().when()
                .post("/api/postes/" + sieges[0] + "/deplacement?cible=" + sieges[1])
                .then()
                .statusCode(200)
                .body("casseContrainteDure", equalTo(false));

        Map<String, String> apres = occupants(
                given().when().get("/api/planning/persisted").then().extract().jsonPath());
        assertThat(apres).containsEntry(sieges[0], avant.get(sieges[1])).containsEntry(sieges[1], avant.get(sieges[0]));
        // Nothing else moved: the rest of the plan is exactly what it was.
        avant.keySet().stream()
                .filter(id -> !id.equals(sieges[0]) && !id.equals(sieges[1]))
                .forEach(id -> assertThat(apres).containsEntry(id, avant.get(id)));
    }

    @Test
    void droppingOnAFreePersonHandsThemTheSeat() {
        JsonPath plan = persistedPlan();
        Map<String, String> avant = occupants(plan);
        String siege = avant.keySet().iterator().next();
        String creneau = plan.getString("postes.find { it.id == '" + siege + "' }.creneau.id");
        List<String> occupesCeCreneau = plan.getList(
                "postes.findAll { it.creneau.id == " + creneau + " && it.animateur != null }.animateur.id");
        String libre = plan.<String>getList("animateurs.id").stream()
                .filter(id -> !occupesCeCreneau.contains(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("somebody should be free on that créneau"));

        given().when()
                .post("/api/postes/" + siege + "/deplacement?animateur=" + libre)
                .then()
                .statusCode(200)
                .body("posteCibleId", nullValue())
                .body("animateurCibleId", equalTo(libre));

        Map<String, String> apres = occupants(
                given().when().get("/api/planning/persisted").then().extract().jsonPath());
        assertThat(apres).containsEntry(siege, libre);
    }

    /** A forced unavailability is a hard rule: handing that seat to that person is refused, and named. */
    @Test
    void aDropThatBreaksAHardRuleIsRefusedAndWritesNothing() {
        JsonPath plan = persistedPlan();
        Map<String, String> avant = occupants(plan);
        String siege = avant.keySet().iterator().next();
        String creneau = plan.getString("postes.find { it.id == '" + siege + "' }.creneau.id");
        List<String> occupesCeCreneau = plan.getList(
                "postes.findAll { it.creneau.id == " + creneau + " && it.animateur != null }.animateur.id");
        String libre = plan.<String>getList("animateurs.id").stream()
                .filter(id -> !occupesCeCreneau.contains(id))
                .findFirst()
                .orElseThrow();
        // The ad hoc constraint's id is generated (ADR 0050): the response names it.
        String indispo = given().contentType("application/json")
                .body("""
                        {"type":"INDISPONIBILITE_FORCEE",
                         "animateursConcernes":[{"id":"%s"}],"creneau":{"id":%s}}""".formatted(libre, creneau))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .path("contrainte.id");
        try {
            given().contentType("application/json")
                    .when()
                    .post("/api/postes/" + siege + "/deplacement/simulation?animateur=" + libre)
                    .then()
                    .statusCode(200)
                    .body("casseContrainteDure", equalTo(true))
                    .body("nouvellesViolationsDures.size()", org.hamcrest.Matchers.greaterThan(0));

            given().when()
                    .post("/api/postes/" + siege + "/deplacement?animateur=" + libre)
                    .then()
                    .statusCode(400)
                    .body("message", containsString("Déplacement refusé"));

            assertThat(occupants(given().when()
                            .get("/api/planning/persisted")
                            .then()
                            .extract()
                            .jsonPath()))
                    .isEqualTo(avant);
        } finally {
            given().when().delete("/api/contraintes-ad-hoc/" + indispo);
        }
    }

    /**
     * The gesture names a seat, and a day view left open shows a plan somebody
     * else may have moved since. Without the precondition the server moves
     * whoever sits there now — the wrong person, with a 200.
     */
    @Test
    void movingASeatSomebodyElseAlreadyMovedIsRefused() {
        JsonPath plan = persistedPlan();
        Map<String, String> avant = occupants(plan);
        String siege = avant.keySet().iterator().next();
        String occupantReel = avant.get(siege);
        String autre = avant.values().stream()
                .filter(id -> !id.equals(occupantReel))
                .findFirst()
                .orElseThrow();
        String creneau = plan.getString("postes.find { it.id == '" + siege + "' }.creneau.id");
        List<String> occupesCeCreneau = plan.getList(
                "postes.findAll { it.creneau.id == " + creneau + " && it.animateur != null }.animateur.id");
        String libre = plan.<String>getList("animateurs.id").stream()
                .filter(id -> !occupesCeCreneau.contains(id))
                .findFirst()
                .orElseThrow();

        // The view still shows somebody else on that seat.
        given().when()
                .post("/api/postes/" + siege + "/deplacement?animateur=" + libre + "&occupant=" + autre)
                .then()
                .statusCode(409)
                .body("message", containsString("n'est plus tenu par la personne affichée"));
        assertThat(occupants(given().when()
                        .get("/api/planning/persisted")
                        .then()
                        .extract()
                        .jsonPath()))
                .isEqualTo(avant);

        // Naming the real occupant goes through, and so does naming nobody.
        given().when()
                .post("/api/postes/" + siege + "/deplacement?animateur=" + libre + "&occupant=" + occupantReel)
                .then()
                .statusCode(200);
    }

    /**
     * A lock on the person <em>receiving</em> the seat is read on nothing when
     * they hold none on that créneau — the rail gesture's main case — so it
     * has to be checked by id, not through the seats.
     */
    @Test
    void handingASeatToALockedAnimateurIsRefused() {
        JsonPath plan = persistedPlan();
        Map<String, String> avant = occupants(plan);
        String siege = avant.keySet().iterator().next();
        String creneau = plan.getString("postes.find { it.id == '" + siege + "' }.creneau.id");
        List<String> occupesCeCreneau = plan.getList(
                "postes.findAll { it.creneau.id == " + creneau + " && it.animateur != null }.animateur.id");
        String libre = plan.<String>getList("animateurs.id").stream()
                .filter(id -> !occupesCeCreneau.contains(id))
                .findFirst()
                .orElseThrow();

        given().contentType("application/json")
                .body("{\"id\":\"DEPL-VERROU\",\"type\":\"ANIMATEUR\",\"animateurId\":\"" + libre + "\"}")
                .when()
                .post("/api/verrouillages")
                .then()
                .statusCode(200);
        try {
            given().when()
                    .post("/api/postes/" + siege + "/deplacement?animateur=" + libre)
                    .then()
                    .statusCode(400)
                    .body("message", containsString("verrouillé"));
            assertThat(occupants(given().when()
                            .get("/api/planning/persisted")
                            .then()
                            .extract()
                            .jsonPath()))
                    .isEqualTo(avant);
        } finally {
            given().when().delete("/api/verrouillages/DEPL-VERROU");
        }
    }

    /**
     * The verdict is read on the rules this edition runs under: a hard rule the
     * operator switched off must not refuse a drop. Proves the plan is prepared
     * server-side before it is scored.
     */
    @Test
    void aDisabledHardRuleNoLongerRefusesADrop() {
        JsonPath plan = persistedPlan();
        Map<String, String> avant = occupants(plan);
        String siege = avant.keySet().iterator().next();
        String creneau = plan.getString("postes.find { it.id == '" + siege + "' }.creneau.id");
        List<String> occupesCeCreneau = plan.getList(
                "postes.findAll { it.creneau.id == " + creneau + " && it.animateur != null }.animateur.id");
        String libre = plan.<String>getList("animateurs.id").stream()
                .filter(id -> !occupesCeCreneau.contains(id))
                .findFirst()
                .orElseThrow();
        // The ad hoc constraint's id is generated (ADR 0050): the response names it.
        String indispo = given().contentType("application/json")
                .body("""
                        {"type":"INDISPONIBILITE_FORCEE",
                         "animateursConcernes":[{"id":"%s"}],"creneau":{"id":%s}}""".formatted(libre, creneau))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .path("contrainte.id");
        try {
            given().when()
                    .post("/api/postes/" + siege + "/deplacement?animateur=" + libre)
                    .then()
                    .statusCode(400);

            // Same gesture, with that rule switched off on the Contraintes screen.
            given().contentType("application/json")
                    .body("{\"actif\":false}")
                    .when()
                    .put("/api/constraints/indisponibiliteForcee")
                    .then()
                    .statusCode(200);
            try {
                given().when()
                        .post("/api/postes/" + siege + "/deplacement?animateur=" + libre)
                        .then()
                        .statusCode(200);
            } finally {
                given().contentType("application/json")
                        .body("{\"actif\":true}")
                        .when()
                        .put("/api/constraints/indisponibiliteForcee")
                        .then()
                        .statusCode(200);
            }
        } finally {
            given().when().delete("/api/contraintes-ad-hoc/" + indispo);
        }
    }

    @Test
    void anUnknownSeatIs404AndASeatDroppedOnItselfIs400() {
        JsonPath plan = persistedPlan();
        String siege = occupants(plan).keySet().iterator().next();

        given().when()
                .post("/api/postes/POSTE-INEXISTANT/deplacement?cible=" + siege)
                .then()
                .statusCode(404);
        given().when()
                .post("/api/postes/" + siege + "/deplacement?cible=" + siege)
                .then()
                .statusCode(400);
        given().when().post("/api/postes/" + siege + "/deplacement").then().statusCode(400);
    }
}
