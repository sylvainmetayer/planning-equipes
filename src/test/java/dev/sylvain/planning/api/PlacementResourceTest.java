package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * « Placer » from the Siège panel: somebody off duty seated on a free seat of
 * a timeslot still ahead, over the endpoint the panel calls. Same checks as a
 * direct write — a hard rule, a lock — plus the two a move makes: the seat
 * must still be free, and the person receiving it must not be locked.
 */
@QuarkusTest
class PlacementResourceTest {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistence;

    @AfterEach
    void resetDatabase() {
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    /** Solves the sample scenario, then frees one seat: its former occupant is off duty on that timeslot. */
    private FreeSeat freeSeat() {
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
        Map<String, String> occupants = occupants(persisted());
        String siege = occupants.keySet().iterator().next();
        given().when().post("/api/postes/" + siege + "/affectation").then().statusCode(204);
        JsonPath plan = persisted();
        String creneau = plan.getString("postes.find { it.id == '" + siege + "' }.creneau.id");
        return new FreeSeat(siege, occupants.get(siege), creneau, occupants(plan));
    }

    private record FreeSeat(String posteId, String libre, String creneauId, Map<String, String> occupants) {}

    private static JsonPath persisted() {
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
                .collect(Collectors.toMap(poste -> (String) poste.get("id"), poste ->
                        (String) ((Map<?, ?>) poste.get("animateur")).get("id")));
    }

    @Test
    void placingSomebodyOffDutyFillsTheSeatAndAnswersTheScores() {
        FreeSeat siege = freeSeat();

        given().when()
                .post("/api/postes/" + siege.posteId() + "/placement?animateur=" + siege.libre())
                .then()
                .statusCode(200)
                .body("posteSourceId", equalTo(siege.posteId()))
                .body("posteCibleId", nullValue())
                .body("animateurSourceId", nullValue())
                .body("animateurCibleId", equalTo(siege.libre()))
                .body("casseContrainteDure", equalTo(false))
                .body("scoreAvant", notNullValue())
                .body("scoreApres", notNullValue())
                .body("delta", notNullValue());

        Map<String, String> apres = occupants(persisted());
        assertThat(apres).containsEntry(siege.posteId(), siege.libre());
        // Nothing else moved.
        siege.occupants().forEach((poste, animateur) -> assertThat(apres).containsEntry(poste, animateur));
    }

    /** The panel shows a plan loaded earlier: a seat filled since is not silently taken from its new holder. */
    @Test
    void aSeatNoLongerFreeIsRefusedWithAConflict() {
        FreeSeat siege = freeSeat();
        given().when()
                .post("/api/postes/" + siege.posteId() + "/placement?animateur=" + siege.libre())
                .then()
                .statusCode(200);

        given().when()
                .post("/api/postes/" + siege.posteId() + "/placement?animateur=" + siege.libre())
                .then()
                .statusCode(409)
                .body("message", containsString("n'est plus libre"));
    }

    /** A forced unavailability is a hard rule: the placement is refused, named, and writes nothing. */
    @Test
    void aPlacementThatBreaksAHardRuleIsRefusedAndWritesNothing() {
        FreeSeat siege = freeSeat();
        String indispo = given().contentType("application/json")
                .body("""
                        {"type":"INDISPONIBILITE_FORCEE",
                         "animateursConcernes":[{"id":"%s"}],"creneau":{"id":%s}}""".formatted(siege.libre(), siege.creneauId()))
                .when()
                .post("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .path("contrainte.id");
        try {
            given().when()
                    .post("/api/postes/" + siege.posteId() + "/placement?animateur=" + siege.libre())
                    .then()
                    .statusCode(400)
                    .body("message", containsString("Affectation refusée"));
            assertThat(occupants(persisted())).isEqualTo(siege.occupants());
        } finally {
            given().when().delete("/api/contraintes-ad-hoc/" + indispo);
        }
    }

    /** A lock on the person receiving the seat forbids them a new one, as it does for a move. */
    @Test
    void placingALockedAnimateurIsRefused() {
        FreeSeat siege = freeSeat();
        given().contentType("application/json")
                .body("{\"id\":\"PLAC-VERROU\",\"type\":\"ANIMATEUR\",\"animateurId\":\"" + siege.libre() + "\"}")
                .when()
                .post("/api/verrouillages")
                .then()
                .statusCode(200);
        try {
            given().when()
                    .post("/api/postes/" + siege.posteId() + "/placement?animateur=" + siege.libre())
                    .then()
                    .statusCode(400)
                    .body("message", containsString("verrouillé"));
            assertThat(occupants(persisted())).isEqualTo(siege.occupants());
        } finally {
            given().when().delete("/api/verrouillages/PLAC-VERROU");
        }
    }

    @Test
    void anUnknownSeatIs404AndNobodyToPlaceIs400() {
        FreeSeat siege = freeSeat();

        given().when()
                .post("/api/postes/POSTE-INEXISTANT/placement?animateur=" + siege.libre())
                .then()
                .statusCode(404);
        given().when()
                .post("/api/postes/" + siege.posteId() + "/placement")
                .then()
                .statusCode(400);
    }

    /**
     * The read is not the guard: a seat filled between the plan the check
     * read and the write is kept by the {@code UPDATE} itself, which only
     * lands on a seat still empty.
     */
    @Test
    void aSeatFilledAfterTheCheckReadThePlanIsNotOverwritten() {
        FreeSeat siege = freeSeat();
        PlanningEvenement lu = persistence.loadPersistedPlanning();
        String autre = siege.occupants().values().stream()
                .filter(animateur -> !animateur.equals(siege.libre()))
                .findFirst()
                .orElseThrow();
        // Somebody else takes the seat after the plan was read.
        persistence.reaffecterPoste(siege.posteId(), autre);

        assertThatThrownBy(() -> planningService.placeOnFreeSeat(lu, siege.posteId(), siege.libre()))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("n'est plus libre");
        assertThat(occupants(persisted())).containsEntry(siege.posteId(), autre);
    }

    /** « Libérer » names who it frees: a seat somebody else holds by then is a 409, and stays theirs. */
    @Test
    void freeingWithAnOccupantPreconditionOnlyFreesThatPerson() {
        FreeSeat siege = freeSeat();
        Map.Entry<String, String> tenu = siege.occupants().entrySet().iterator().next();
        String autre = siege.occupants().values().stream()
                .filter(animateur -> !animateur.equals(tenu.getValue()))
                .findFirst()
                .orElseThrow();

        given().when()
                .post("/api/postes/" + tenu.getKey() + "/affectation?occupant=" + autre)
                .then()
                .statusCode(409)
                .body("message", containsString("n'est plus tenu par la personne affichée"));
        assertThat(occupants(persisted())).containsEntry(tenu.getKey(), tenu.getValue());

        given().when()
                .post("/api/postes/" + tenu.getKey() + "/affectation?occupant=" + tenu.getValue())
                .then()
                .statusCode(204);
        assertThat(occupants(persisted())).doesNotContainKey(tenu.getKey());
    }

    /** « Remplacer › Appliquer » carries the same precondition as « Libérer ». */
    @Test
    void replacingWithAStaleOccupantIsRefusedAndWritesNothing() {
        FreeSeat siege = freeSeat();

        given().when()
                .post("/api/postes/" + siege.posteId() + "/affectation?animateurId=" + siege.libre() + "&occupant="
                        + siege.libre())
                .then()
                .statusCode(409);
        assertThat(occupants(persisted())).isEqualTo(siege.occupants());
    }
}
