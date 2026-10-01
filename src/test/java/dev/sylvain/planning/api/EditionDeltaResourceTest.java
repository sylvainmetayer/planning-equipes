package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.journal.JournalActionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The referential delta between two editions over HTTP: an edition
 * duplicated then edited three times lists exactly those three changes, an
 * edition compared with itself lists nothing, and the CSV names nobody.
 */
@QuarkusTest
class EditionDeltaResourceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String PREFIXE = "Delta de test ";

    @Inject
    EditionService editionService;

    @Inject
    JournalActionRepository journal;

    @AfterEach
    void dropCreatedEditions() {
        editionService.listEditions().stream()
                .filter(edition -> edition.getNom() != null && edition.getNom().startsWith(PREFIXE))
                .forEach(edition -> editionService.delete(edition.getId()));
    }

    private static String createEdition(String nom) {
        return given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"nom\":\"" + PREFIXE + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private static String duplicate(String sourceId, String nom) {
        return given().header("X-Edition-Id", "E1")
                .contentType("application/json")
                .body("{\"nom\":\"" + PREFIXE + nom + "\"}")
                .when()
                .post("/api/editions/" + sourceId + "/dupliquer")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private static String post(String editionId, String path, String body, String idPath) {
        return given().header(HEADER, editionId)
                .contentType("application/json")
                .body(body)
                .when()
                .post(path)
                .then()
                .statusCode(200)
                .extract()
                .path(idPath);
    }

    private static String standBody(String nom, String typologieId, int effectifMax) {
        return "{\"nom\":\"" + nom + "\",\"typologiesProposees\":[\"" + typologieId
                + "\"],\"effectifMin\":1,\"effectifMax\":" + effectifMax + "}";
    }

    private static JsonPath delta(String a, String b) {
        return given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions/" + a + "/delta/" + b)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    /** « Année 2025 »: a game category, two stands, one timeslot, two people. */
    private static String[] seededEdition() {
        String a = createEdition("2025");
        String typologie = post(a, "/api/typologies", "{\"code\":\"JEUX\",\"label\":\"Jeux\"}", "id");
        String cirque = post(a, "/api/stands", standBody("Cirque", typologie, 2), "stand.id");
        post(a, "/api/stands", standBody("Échecs", typologie, 1), "stand.id");
        post(
                a,
                "/api/creneaux",
                "{\"date\":\"2027-07-10\",\"heureDebut\":\"10:00:00\",\"heureFin\":\"14:00:00\"}",
                "id");
        post(
                a,
                "/api/animateurs",
                "{\"prenom\":\"Alice\",\"nom\":\"Deltaroux\",\"dateNaissance\":\"1990-01-01\","
                        + "\"email\":\"alice.delta@example.org\"}",
                "animateur.id");
        String bob = post(
                a,
                "/api/animateurs",
                "{\"prenom\":\"Bob\",\"nom\":\"Deltamartin\",\"dateNaissance\":\"1991-02-02\","
                        + "\"email\":\"bob.delta@example.org\"}",
                "animateur.id");
        return new String[] {a, typologie, cirque, bob};
    }

    @Test
    void aDuplicationThenThreeEditsListsExactlyThoseThree() {
        String[] seed = seededEdition();
        String a = seed[0];
        String typologie = seed[1];
        String cirque = seed[2];
        String bob = seed[3];
        String b = duplicate(a, "2026");
        String buvette = post(b, "/api/stands", standBody("Buvette", typologie, 3), "stand.id");
        given().header(HEADER, b)
                .contentType("application/json")
                .body(standBody("Cirque", typologie, 4))
                .when()
                .put("/api/stands/" + cirque)
                .then()
                .statusCode(200);
        given().header(HEADER, b).when().delete("/api/animateurs/" + bob).then().statusCode(204);

        JsonPath delta = delta(a, b);

        assertThat(delta.getList("stands.change", String.class)).containsExactlyInAnyOrder("ADDED", "MODIFIED");
        assertThat(delta.getString("stands.find { it.change == 'ADDED' }.targetId"))
                .isEqualTo(buvette);
        assertThat(delta.getList("stands.find { it.change == 'MODIFIED' }.fields", String.class))
                .containsExactly("effectifMax");
        assertThat(delta.getList("animateurs.change", String.class)).containsExactly("REMOVED");
        assertThat(delta.getString("animateurs[0].referenceId")).isEqualTo(bob);
        // Named on the administrator's screen.
        assertThat(delta.getString("animateurs[0].label")).isEqualTo("Bob Deltamartin");
        for (String famille : List.of("typologies", "emplacements", "journeesTypes", "creneaux", "parametres")) {
            assertThat(delta.getList(famille)).as(famille).isEmpty();
        }
        assertThat(delta.getBoolean("noAnimateurMatched")).isFalse();
        // The Volumétrie of each edition, read inside it: more seats where a stand was added.
        int sieges = delta.getInt("referenceVolumes.posteCount");
        int siegesCible = delta.getInt("targetVolumes.posteCount");
        assertThat(siegesCible).isGreaterThan(sieges);
        assertThat(delta.getInt("summary.seatDifference")).isEqualTo(siegesCible - sieges);
    }

    @Test
    void anEditionComparedWithItselfListsNothing() {
        String a = seededEdition()[0];

        JsonPath delta = delta(a, a);

        for (String famille : List.of(
                "typologies",
                "emplacements",
                "stands",
                "animateurs",
                "journeesTypes",
                "creneaux",
                "parametres",
                "ajustements")) {
            assertThat(delta.getList(famille)).as(famille).isEmpty();
        }
        Map<String, Object> volumes = delta.getMap("referenceVolumes");
        assertThat(delta.getMap("targetVolumes")).isEqualTo(volumes);
    }

    @Test
    void theCsvNamesNobodyAndIsJournalled() {
        String a = seededEdition()[0];
        String b = duplicate(a, "sans personne");
        given().header(HEADER, b)
                .when()
                .delete("/api/animateurs/" + seededBob(b))
                .then()
                .statusCode(204);

        String csv = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions/" + a + "/delta/" + b + "/export.csv")
                .then()
                .statusCode(200)
                .contentType("text/csv")
                .header("Content-Disposition", "attachment; filename=\"delta-" + a + "-" + b + ".csv\"")
                .extract()
                .asString();

        assertThat(csv).contains("animateur;REMOVED;").contains("volumes;");
        assertThat(csv)
                .doesNotContain("Deltamartin")
                .doesNotContain("Bob")
                .doesNotContain("bob.delta")
                .doesNotContain("1991");
        assertThat(journal.listAmong(List.of("EXPORT_DELTA_EDITIONS"), 10))
                .extracting(entree -> entree.action())
                .contains("EXPORT_DELTA_EDITIONS");
    }

    @Test
    void anUnknownEditionIsNotFound() {
        String a = createEdition("seule");

        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions/" + a + "/delta/E999999")
                .then()
                .statusCode(404);
        given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions/E999999/delta/" + a + "/export.csv")
                .then()
                .statusCode(404);
    }

    private static String seededBob(String editionId) {
        return given().header(HEADER, editionId)
                .when()
                .get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("find { it.nom == 'Deltamartin' }.id");
    }
}
