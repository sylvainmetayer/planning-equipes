package dev.sylvain.planning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AffectationExplanationResourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Every test here solves and persists {@code scenario.yml}, whose
     * créneaux carry small, explicit synthetic ids (1, 2, ...) — see
     * {@code PlanningService.chargerScenarioYaml}. Left in place, they can
     * collide with the {@code creneau} table's own id sequence in a later
     * test class sharing this same dev-services database (a {@code POST
     * /api/creneaux} relies on that sequence, which explicit-id inserts never
     * advance). Resetting after every test keeps this class's persisted state
     * from leaking into whichever test runs next.
     */
    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /**
     * Small, fast-solving scenario shared by every test below, with the
     * server-computed {@code score} stripped out before it is reposted.
     *
     * <p>Reposting a {@code PlanningEvenement} whose {@code score} is
     * populated crashes Quarkus's build-time-generated Jackson deserializer
     * for {@code HardMediumSoftScore} (it calls a private no-arg
     * constructor and throws {@code IllegalAccessError}) — a pre-existing
     * issue that every other endpoint accepting a {@code PlanningEvenement}
     * body has so far avoided simply because no caller ever reposted an
     * already-scored planning. These are the first endpoints designed to be
     * handed one (the client's just-solved or persisted planning), so the
     * client is expected to omit {@code score} from the request body, same
     * as here.</p>
     */
    private String solveScenario() {
        String sample = given()
                .when().get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract().asString();

        String solved = given()
                .contentType("application/json")
                .body(sample)
                .when().post("/api/solve?seconds=3")
                .then()
                .statusCode(200)
                .extract().asString();
        return withoutScore(solved);
    }

    private static String withoutScore(String planningJson) {
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(planningJson);
            node.remove("score");
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void explicationRetourneLesContraintesPourUnPosteAssigne() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        assertThat(posteId).isNotNull();

        JsonPath explication = given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/explication")
                .then()
                .statusCode(200)
                .body("posteId", equalTo(posteId))
                .body("animateurId", notNullValue())
                .body("score.hardScore", notNullValue())
                .extract().jsonPath();

        // Every catalogued constraint must show up on exactly one side.
        int total = explication.getList("contraintesViolees").size() + explication.getList("contraintesRespectees").size();
        int catalogueSize = given()
                .when().get("/api/constraints")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("contraintes").size();
        assertThat(total).isEqualTo(catalogueSize);
    }

    @Test
    void explicationRefuseUnPosteInconnu() {
        String solvedJson = solveScenario();

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/POSTE-INEXISTANT/explication")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    void simulationSwapCalculeUnDeltaDeScore() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String animateurActuelId = solved.getString("postes.find { it.id == '" + posteId + "' }.animateur.id");
        String autreAnimateurId = solved.getString("animateurs.find { it.id != '" + animateurActuelId + "' }.id");
        assertThat(autreAnimateurId).isNotNull();

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/simulation-swap?animateurId=" + autreAnimateurId)
                .then()
                .statusCode(200)
                .body("posteId", equalTo(posteId))
                .body("animateurActuelId", equalTo(animateurActuelId))
                .body("animateurCandidatId", equalTo(autreAnimateurId))
                .body("scoreAvant.hardScore", notNullValue())
                .body("scoreApres.hardScore", notNullValue())
                .body("delta.hardScore", notNullValue())
                .body("contraintesVioleesAvant", notNullValue())
                .body("contraintesVioleesApres", notNullValue());
    }

    @Test
    void simulationSwapAvecSoiMemeNeChangeRienAuScore() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String animateurActuelId = solved.getString("postes.find { it.id == '" + posteId + "' }.animateur.id");

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/simulation-swap?animateurId=" + animateurActuelId)
                .then()
                .statusCode(200)
                .body("delta.hardScore", equalTo(0))
                .body("delta.mediumScore", equalTo(0))
                .body("delta.softScore", equalTo(0));
    }

    @Test
    void simulationSwapRefuseUnAnimateurInconnu() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/simulation-swap?animateurId=ANIMATEUR-INEXISTANT")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }
}
