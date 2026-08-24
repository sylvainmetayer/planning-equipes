package dev.sylvain.planning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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

    // --- Repair assistant (issue #71) ---------------------------------------

    /**
     * The scenario holds three animateurs, so a poste has at most two eligible
     * candidates: enough to observe the ranking, the plafond and the hard
     * filter, small enough to keep each of these tests a couple of seconds.
     */
    @Test
    void suggestionsClassentLesCandidatsViablesParImpactDecroissant() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String occupantId = solved.getString("postes.find { it.id == '" + posteId + "' }.animateur.id");

        JsonPath suggestions = given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/suggestions-reparation")
                .then()
                .statusCode(200)
                .body("posteId", equalTo(posteId))
                .body("animateurActuelId", equalTo(occupantId))
                .body("scoreAvant.hardScore", notNullValue())
                .extract().jsonPath();

        assertThat(suggestions.getInt("candidatsEligibles")).isPositive();
        assertThat(suggestions.getInt("candidatsEvalues")).isLessThanOrEqualTo(suggestions.getInt("candidatsEligibles"));
        assertThat(suggestions.getInt("plafond")).isEqualTo(20);

        List<String> proposes = suggestions.getList("suggestions.animateurId");
        assertThat(proposes).doesNotContain(occupantId);

        // Best first, lexicographic hard > medium > soft — the very order the
        // UI relies on to show "the N best".
        List<List<Integer>> deltas = proposes.stream()
                .map(id -> List.of(
                        suggestions.getInt("suggestions.find { it.animateurId == '" + id + "' }.delta.hardScore"),
                        suggestions.getInt("suggestions.find { it.animateurId == '" + id + "' }.delta.mediumScore"),
                        suggestions.getInt("suggestions.find { it.animateurId == '" + id + "' }.delta.softScore")))
                .toList();
        assertThat(deltas).isSortedAccordingTo(Comparator
                .<List<Integer>>comparingInt(delta -> delta.get(0))
                .thenComparingInt(delta -> delta.get(1))
                .thenComparingInt(delta -> delta.get(2))
                .reversed());
    }

    /**
     * The acceptance criterion of the issue, cross-checked against the
     * <em>other</em> endpoint rather than against the same computation: every
     * suggestion re-simulated through {@code simulation-swap} must leave the
     * hard score untouched, and any candidate that endpoint reports as
     * hard-breaking must be absent from the list.
     */
    @Test
    void aucuneSuggestionNIntroduitDeViolationDure() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String occupantId = solved.getString("postes.find { it.id == '" + posteId + "' }.animateur.id");

        JsonPath suggestions = given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/suggestions-reparation?plafond=100")
                .then()
                .statusCode(200)
                .extract().jsonPath();
        List<String> proposes = suggestions.getList("suggestions.animateurId");

        // No suggestion carries a hard violation it would introduce...
        for (int i = 0; i < proposes.size(); i++) {
            assertThat(suggestions.getList("suggestions[" + i + "].violationsIntroduites.niveau", String.class))
                    .doesNotContain("HARD");
        }

        // ...and the split between kept and dropped matches what the
        // simulation endpoint says of each candidate, one by one.
        List<String> autres = solved.getList("animateurs.findAll { it.id != '" + occupantId + "' }.id", String.class);
        for (String candidatId : autres) {
            int deltaDur = given()
                    .contentType("application/json")
                    .body(solvedJson)
                    .when().post("/api/postes/" + posteId + "/simulation-swap?animateurId=" + candidatId)
                    .then()
                    .statusCode(200)
                    .extract().jsonPath().getInt("delta.hardScore");
            if (deltaDur < 0) {
                assertThat(proposes).as("candidat cassant une contrainte dure").doesNotContain(candidatId);
            } else {
                assertThat(proposes).as("candidat viable").contains(candidatId);
            }
        }
    }

    @Test
    void suggestionsSArretentAuPlafondDemande() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");

        JsonPath suggestions = given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/suggestions-reparation?plafond=1")
                .then()
                .statusCode(200)
                .body("plafond", equalTo(1))
                .body("candidatsEvalues", equalTo(1))
                .extract().jsonPath();

        assertThat(suggestions.getList("suggestions")).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void unPlafondNonPositifRetombeSurLeDefautEtUnPlafondEnormeEstBride() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/suggestions-reparation?plafond=0")
                .then()
                .statusCode(200)
                .body("plafond", equalTo(20));

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/" + posteId + "/suggestions-reparation?plafond=100000")
                .then()
                .statusCode(200)
                .body("plafond", equalTo(100));
    }

    @Test
    void suggestionsRefusentUnPosteInconnu() {
        String solvedJson = solveScenario();

        given()
                .contentType("application/json")
                .body(solvedJson)
                .when().post("/api/postes/POSTE-INEXISTANT/suggestions-reparation")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    /**
     * A seat nobody holds is the archetypal thing to repair (the poste a
     * forcing left uncovered), so the assistant has to answer on it — with the
     * whole referential eligible, since there is no occupant to exclude.
     */
    @Test
    void suggestionsRepondentSurUnPosteVide() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String videJson = withoutAnimateur(solvedJson, posteId);
        int animateurs = solved.getList("animateurs").size();

        given()
                .contentType("application/json")
                .body(videJson)
                .when().post("/api/postes/" + posteId + "/suggestions-reparation")
                .then()
                .statusCode(200)
                .body("animateurActuelId", nullValue())
                .body("candidatsEligibles", equalTo(animateurs));
    }

    @Test
    void appliquerUneSuggestionNeChangeQueLePosteVise() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String occupantId = solved.getString("postes.find { it.id == '" + posteId + "' }.animateur.id");
        String remplacantId = solved.getString("animateurs.find { it.id != '" + occupantId + "' }.id");
        Map<String, String> avant = occupantsPersistes();

        given()
                .when().post("/api/postes/" + posteId + "/affectation?animateurId=" + remplacantId)
                .then()
                .statusCode(204);

        Map<String, String> apres = occupantsPersistes();
        assertThat(apres).containsEntry(posteId, remplacantId);
        assertThat(apres).hasSameSizeAs(avant);
        apres.forEach((id, animateurId) -> {
            if (!id.equals(posteId)) {
                assertThat(animateurId).as("poste %s inchangé", id).isEqualTo(avant.get(id));
            }
        });
    }

    @Test
    void appliquerSansAnimateurLibereLePoste() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");

        given()
                .when().post("/api/postes/" + posteId + "/affectation")
                .then()
                .statusCode(204);

        assertThat(occupantsPersistes()).doesNotContainKey(posteId);
    }

    @Test
    void appliquerRefuseUnPosteInconnu() {
        solveScenario();

        given()
                .when().post("/api/postes/POSTE-INEXISTANT/affectation?animateurId=A1")
                .then()
                .statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    void appliquerRefuseUnAnimateurInconnu() {
        String solvedJson = solveScenario();
        String posteId = JsonPath.from(solvedJson).getString("postes.find { it.animateur != null }.id");
        Map<String, String> avant = occupantsPersistes();

        given()
                .when().post("/api/postes/" + posteId + "/affectation?animateurId=ANIMATEUR-INEXISTANT")
                .then()
                .statusCode(404)
                .body("message", notNullValue());

        assertThat(occupantsPersistes()).isEqualTo(avant);
    }

    /** A lock is the operator saying "this one does not move" — the assistant obeys it. */
    @Test
    void appliquerRefuseUnPosteVerrouille() {
        String solvedJson = solveScenario();
        JsonPath solved = JsonPath.from(solvedJson);
        String posteId = solved.getString("postes.find { it.animateur != null }.id");
        String standId = solved.getString("postes.find { it.id == '" + posteId + "' }.stand.id");
        String occupantId = solved.getString("postes.find { it.id == '" + posteId + "' }.animateur.id");
        String remplacantId = solved.getString("animateurs.find { it.id != '" + occupantId + "' }.id");

        given()
                .contentType("application/json")
                .body("{\"type\":\"STAND\",\"standId\":\"" + standId + "\"}")
                .when().post("/api/verrouillages")
                .then()
                .statusCode(200);

        given()
                .when().post("/api/postes/" + posteId + "/affectation?animateurId=" + remplacantId)
                .then()
                .statusCode(400)
                .body("message", notNullValue());

        assertThat(occupantsPersistes()).containsEntry(posteId, occupantId);
    }

    /** Poste id → occupant of the persisted plan; unstaffed seats are simply absent. */
    private static Map<String, String> occupantsPersistes() {
        JsonPath persiste = given()
                .when().get("/api/planning/persisted")
                .then()
                .statusCode(200)
                .extract().jsonPath();
        Map<String, String> occupants = new LinkedHashMap<>();
        List<String> ids = persiste.getList("postes.id", String.class);
        for (int i = 0; i < ids.size(); i++) {
            String animateurId = persiste.getString("postes[" + i + "].animateur.id");
            if (animateurId != null) {
                occupants.put(ids.get(i), animateurId);
            }
        }
        return occupants;
    }

    /** The posted planning with one seat emptied, to exercise the unstaffed case. */
    private static String withoutAnimateur(String planningJson, String posteId) {
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(planningJson);
            for (com.fasterxml.jackson.databind.JsonNode poste : node.withArray("postes")) {
                if (posteId.equals(poste.path("id").asText())) {
                    ((ObjectNode) poste).putNull("animateur");
                }
            }
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
