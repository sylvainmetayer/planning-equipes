package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a solve says about the people it would disturb (feature « stabilité »):
 * nothing before any publication, and afterwards the count of animateurs whose
 * seats differ from the published plan — the figure the recap shows so the
 * operator knows before publishing.
 *
 * <p>Runs in its own edition: other test classes publish in the default one
 * and a reset does not forget a publication, so "nothing published yet" is
 * only true where nobody else has been.</p>
 */
@QuarkusTest
class ImpactPublicationTest {

    private static final int MAX_POLLS = 120;
    private static final String EDITION = "IMPACT-EDITION";

    @BeforeEach
    void createEdition() {
        given().contentType("application/json")
                .body("{\"id\":\"" + EDITION + "\",\"nom\":\"Édition de l'impact\"}")
                .when().post("/api/editions");
        edition().when().post("/api/planning/reset").then().statusCode(200);
    }

    @AfterEach
    void resetDatabase() {
        edition().when().post("/api/planning/reset").then().statusCode(200);
    }

    private static RequestSpecification edition() {
        return given().header("X-Edition-Id", EDITION);
    }

    @Test
    void theImpactIsAbsentBeforeAnyPublicationAndCountedAfter() throws InterruptedException {
        edition().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);

        JsonPath premier = solve();
        assertThat((Object) premier.get("result.impactPublication")).as("nothing published yet").isNull();

        edition().when().post("/api/planning/publication").then().statusCode(200);

        // Same referential, re-solved from the published plan: nobody moves.
        JsonPath second = solve();
        assertThat(second.getInt("result.impactPublication.personnes")).isZero();
        assertThat(second.getString("result.impactPublication.publieLe")).isNotNull();
        // The stability rule is now in the analysis, at zero: nothing to keep in place moved.
        assertThat(edition().when().get("/api/constraints").then().statusCode(200)
                .extract().jsonPath().<String>getList("contraintes.name")).contains("stabiliteDuPlanPublie");
    }

    /**
     * The rule must see the published seats in the real wiring, not only under
     * the constraint verifier: two seats swapped by hand after publication are
     * two people no longer where the published plan put them.
     */
    @Test
    void theRuleSeesThePublishedSeatsOfTheEdition() throws InterruptedException {
        edition().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
        solve();
        edition().when().post("/api/planning/publication").then().statusCode(200);

        List<Map<String, Object>> postes = edition().when().get("/api/planning/persisted").then().statusCode(200)
                .extract().jsonPath().getList("postes.findAll { it.animateur != null }");
        Map<String, Object> premier = postes.get(0);
        Map<String, Object> second = postes.stream()
                .filter(poste -> !standId(poste).equals(standId(premier)))
                .findFirst().orElseThrow();
        String sql = String.join("\n",
                deleteSeat(premier), deleteSeat(second),
                insertSeat(premier, animateurId(second)), insertSeat(second, animateurId(premier)));
        edition().contentType("text/plain").body(sql).when().post("/api/database/import").then().statusCode(200);

        edition().when().post("/api/constraints/diagnostic").then().statusCode(200);
        assertThat(edition().when().get("/api/constraints").then().statusCode(200)
                .extract().jsonPath().getInt("contraintes.find { it.name == 'stabiliteDuPlanPublie' }.matchCount"))
                .as("two seats no longer held by the people the publication named").isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static String standId(Map<String, Object> poste) {
        return (String) ((Map<String, Object>) poste.get("stand")).get("id");
    }

    @SuppressWarnings("unchecked")
    private static String animateurId(Map<String, Object> poste) {
        return (String) ((Map<String, Object>) poste.get("animateur")).get("id");
    }

    @SuppressWarnings("unchecked")
    private static Object creneauId(Map<String, Object> poste) {
        return ((Map<String, Object>) poste.get("creneau")).get("id");
    }

    private static String deleteSeat(Map<String, Object> poste) {
        return "DELETE FROM poste_affectation WHERE edition_id = '" + EDITION + "' AND id = '" + poste.get("id") + "';";
    }

    private static String insertSeat(Map<String, Object> poste, String animateurId) {
        return "INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) VALUES ('"
                + EDITION + "', '" + poste.get("id") + "', '" + standId(poste) + "', " + creneauId(poste)
                + ", '" + animateurId + "');";
    }

    /**
     * Two seats of one line held by the same person — a créneau cut into
     * segments gives one seat per segment, and a plan published with a double
     * booking gives the same shape — used to abort the next solve:
     * « The fact (AffectationPubliee[…]) was already inserted ». Timefold
     * indexes problem facts by equality, so the published seats have to reach
     * it as a set.
     */
    @Test
    void twoPublishedSeatsOfOneLineHeldByTheSamePersonDoNotBreakTheNextSolve() throws InterruptedException {
        edition().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
        solve();

        List<Map<String, Object>> postes = edition().when().get("/api/planning/persisted").then().statusCode(200)
                .extract().jsonPath().getList("postes.findAll { it.animateur != null }");
        Map<String, Object> premier = postes.get(0);
        String standId = standId(premier);
        Object creneauId = creneauId(premier);
        String tenant = animateurId(premier);
        // A second seat on the very same line, same person, other half of the
        // slot: what a segmented créneau produces.
        edition().contentType("text/plain")
                .body("INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id, "
                        + "heure_debut_effective, heure_fin_effective) VALUES ('" + EDITION + "', 'DOUBLON', '"
                        + standId + "', " + creneauId + ", '" + tenant + "', '14:00', '16:00');")
                .when().post("/api/database/import").then().statusCode(200);

        edition().when().post("/api/planning/publication").then().statusCode(200);

        JsonPath apres = solve();
        assertThat(apres.getString("status")).isEqualTo("COMPLETED");
        assertThat(apres.getString("error")).isNull();
    }

    private JsonPath solve() throws InterruptedException {
        attendreSolveurLibre();
        String jobId = edition().when().post("/api/solve/async/reference-data?seconds=2")
                .then().statusCode(202).extract().path("id");
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = edition().when().get("/api/jobs/" + jobId).then().statusCode(200).extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                assertThat(job.getString("status")).isEqualTo("COMPLETED");
                return job;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(250);
        }
    }
}
