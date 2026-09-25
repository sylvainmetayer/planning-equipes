package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import dev.sylvain.planning.config.DevMode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The wall display end to end: the admin creates, lists and revokes links, and
 * a link opens the view of the day on the server's clock — a frozen one here,
 * so that « the shift under way » means the same thing on every run.
 */
@QuarkusTest
class AffichageMuralResourceTest {

    private static final String JOUR = "2026-07-08";

    /** A server launched with {@code quarkus:dev}, as far as the clock's guard can tell. */
    private static final class DevModeActif extends DevMode {
        @Override
        public boolean isActive() {
            return true;
        }
    }

    @AfterEach
    void handEverythingBack() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType(ContentType.JSON)
                .body("{\"dateDuJour\":null}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
        for (Object id : given().when().get("/api/affichage-mural").jsonPath().getList("id")) {
            given().when().delete("/api/affichage-mural/" + id);
        }
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    @Test
    void theAdminCreatesListsAndRevokesALink() {
        JsonPath cree = create("{\"libelle\":\"TV PC sécurité\"}");
        String token = cree.getString("token");
        long id = cree.getLong("link.id");

        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        assertThat(cree.getBoolean("link.fullNames")).isFalse();
        given().when()
                .get("/api/affichage-mural")
                .then()
                .statusCode(200)
                .body("find { it.id == " + id + " }.libelle", equalTo("TV PC sécurité"))
                // Only the hash is stored: the list has no token to give back.
                .body("find { it.id == " + id + " }", not(hasKey("token")));

        given().when().delete("/api/affichage-mural/" + id).then().statusCode(204);

        given().when().get("/api/affichage-mural").then().statusCode(200).body("id", not(hasItem((int) id)));
        given().when().delete("/api/affichage-mural/" + id).then().statusCode(404);
    }

    @Test
    void creationAndRevocationAreJournalledButReadsAreNot() {
        JsonPath cree = create("{\"libelle\":\"Accueil\"}");
        String id = String.valueOf(cree.getLong("link.id"));
        given().when().get("/api/mural/" + cree.getString("token")).then().statusCode(200);
        given().when().delete("/api/affichage-mural/" + id).then().statusCode(204);

        JsonPath historique = given().when()
                .get("/api/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(historique.getString("find { it.action == 'AFFICHAGE_MURAL_CREE' }.entiteId"))
                .isEqualTo(id);
        assertThat(historique.getString("find { it.action == 'AFFICHAGE_MURAL_REVOQUE' }.entiteId"))
                .isEqualTo(id);
        // The token never reaches the history, in any column.
        assertThat(historique.prettify()).doesNotContain(cree.getString("token"));
    }

    @Test
    void aLinkNeedsALabelAndKnownEmplacements() {
        given().contentType(ContentType.JSON)
                .body("{\"libelle\":\"  \"}")
                .when()
                .post("/api/affichage-mural")
                .then()
                .statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"libelle\":\"Zone\",\"emplacements\":[\"nulle-part\"]}")
                .when()
                .post("/api/affichage-mural")
                .then()
                .statusCode(400)
                .body("message", containsString("nulle-part"));
    }

    /**
     * A zone's link keeps its restriction when its emplacement is deleted: the
     * foreign key drops the emplacement from the filter, and the screen then
     * shows nothing rather than, silently, the whole edition.
     */
    @Test
    void aRestrictedLinkOutlivesItsEmplacementWithoutShowingEverything() {
        solveScenario();
        String emplacement = given().contentType(ContentType.JSON)
                .body("{\"nom\":\"Zone mural\"}")
                .when()
                .post("/api/emplacements")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        JsonPath cree = create("{\"libelle\":\"Zone\",\"emplacements\":[\"" + emplacement + "\"]}");
        assertThat(cree.getBoolean("link.restricted")).isTrue();
        assertThat(cree.getList("link.emplacements", String.class)).containsExactly(emplacement);

        given().when().delete("/api/emplacements/" + emplacement).then().statusCode(anyOf(equalTo(200), equalTo(204)));

        long id = cree.getLong("link.id");
        given().when()
                .get("/api/affichage-mural")
                .then()
                .body("find { it.id == " + id + " }.restricted", equalTo(true))
                .body("find { it.id == " + id + " }.emplacements", org.hamcrest.Matchers.empty());
        assertThat(view(cree.getString("token")).getList("stands")).isEmpty();
    }

    /** Unknown and revoked answer the same thing: nothing tells a dead link from an invented one. */
    @Test
    void unknownAndRevokedTokensGetTheSameAnswer() {
        JsonPath cree = create("{\"libelle\":\"TV\"}");
        given().when()
                .delete("/api/affichage-mural/" + cree.getLong("link.id"))
                .then()
                .statusCode(204);

        Response revoque = given().when().get("/api/mural/" + cree.getString("token"));
        Response inconnu = given().when().get("/api/mural/jeton-invente-de-toutes-pieces");

        assertThat(revoque.statusCode()).isEqualTo(404);
        assertThat(inconnu.statusCode()).isEqualTo(404);
        assertThat(revoque.body().asString()).isEqualTo(inconnu.body().asString());
    }

    @Test
    void theViewIsNeverCachedNorIndexed() {
        String token = create("{\"libelle\":\"TV\"}").getString("token");

        given().when()
                .get("/api/mural/" + token)
                .then()
                .statusCode(200)
                .header("Cache-Control", "no-store")
                .header("X-Robots-Tag", containsString("noindex"));
    }

    /**
     * On the frozen day, between the two slots of {@code scenario.yml}: the
     * afternoon is ahead, names are the first name and an initial, and the
     * last read is stamped on the link.
     */
    @Test
    void theViewReadsThePersistedPlanOnTheServerClock() {
        solveScenario();
        freezeClock(JOUR, "13:30");
        JsonPath cree = create("{\"libelle\":\"TV\"}");

        JsonPath vue = view(cree.getString("token"));

        assertThat(vue.getString("jour")).isEqualTo(JOUR);
        assertThat(vue.getString("now")).isEqualTo(JOUR + "T13:30:00");
        assertThat(vue.getString("libelle")).isEqualTo("TV");
        assertThat(vue.getList("stands")).isNotEmpty();
        List<String> noms = vue.getList("stands.vacations.flatten().noms.flatten()", String.class);
        assertThat(noms).isNotEmpty().allSatisfy(nom -> assertThat(nom).matches(".+ \\p{Lu}\\."));
        given().when()
                .get("/api/affichage-mural")
                .then()
                .body("find { it.id == " + cree.getLong("link.id") + " }.lastAccessAt", notNullValue());
    }

    @Test
    void fullNamesOnlyWhenTheLinkWasCreatedSo() {
        solveScenario();
        freezeClock(JOUR, "13:30");
        String token = create("{\"libelle\":\"TV\",\"fullNames\":true}").getString("token");

        List<String> noms = view(token).getList("stands.vacations.flatten().noms.flatten()", String.class);

        assertThat(noms).isNotEmpty().noneSatisfy(nom -> assertThat(nom).matches(".+ \\p{Lu}\\."));
    }

    /** A replacement made on the mode jour J screen shows at the next read, with no republication. */
    @Test
    void anAbsenceMarkedOnTheDayShowsAtTheNextRead() {
        solveScenario();
        freezeClock(JOUR, "13:30");
        String token = create("{\"libelle\":\"TV\"}").getString("token");
        int libresAvant = emptySeats(view(token));
        String absent = given().when()
                .get("/api/jour-j?date=" + JOUR + "&maintenant=" + JOUR + "T13:30")
                .jsonPath()
                .getString("animateursDeService[0].animateurId");

        given().contentType(ContentType.JSON)
                .body("{\"animateurId\":\"" + absent + "\"}")
                .when()
                .post("/api/jour-j/absences?date=" + JOUR + "&maintenant=" + JOUR + "T13:30")
                .then()
                .statusCode(200);

        assertThat(emptySeats(view(token))).isGreaterThan(libresAvant);
    }

    @Test
    void deletingTheEditionTakesItsLinksAlong() {
        String edition = given().contentType(ContentType.JSON)
                .body("{\"nom\":\"Mural éphémère\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        String token = given().header("X-Edition-Id", edition)
                .contentType(ContentType.JSON)
                .body("{\"libelle\":\"TV\"}")
                .when()
                .post("/api/affichage-mural")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("token");
        given().when().get("/api/mural/" + token).then().statusCode(200).body("edition", equalTo("Mural éphémère"));

        given().when().delete("/api/editions/" + edition).then().statusCode(anyOf(equalTo(200), equalTo(204)));

        given().when().get("/api/mural/" + token).then().statusCode(404);
    }

    @Test
    void theQrCodeIsAGridOfModules() {
        JsonPath qr = given().contentType(ContentType.JSON)
                .body("{\"link\":\"https://planning.example.org/mural/abc\"}")
                .when()
                .post("/api/affichage-mural/qr-code")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        int size = qr.getInt("size");
        assertThat(qr.getList("rows", String.class))
                .hasSize(size)
                .allSatisfy(row -> assertThat(row).matches("[01]{" + size + "}"));
    }

    /* ------------------------------ Fixtures ----------------------------- */

    private static JsonPath create(String body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/affichage-mural")
                .then()
                .statusCode(201)
                .header("Cache-Control", "no-store")
                .extract()
                .jsonPath();
    }

    private static JsonPath view(String token) {
        return given().when()
                .get("/api/mural/" + token)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    private static int emptySeats(JsonPath vue) {
        return vue.getList("stands.vacations.flatten().emptySeats", Integer.class).stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static void freezeClock(String date, String heure) {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType(ContentType.JSON)
                .body(Map.of("dateDuJour", date, "heureDuJour", heure))
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
    }

    private static void solveScenario() {
        String sample = given().when()
                .get("/api/planning/sample?name=scenario.yml")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        given().contentType(ContentType.JSON)
                .body(sample)
                .when()
                .post("/api/solve?seconds=3")
                .then()
                .statusCode(200);
    }
}
