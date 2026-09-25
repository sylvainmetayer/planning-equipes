package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The optional {@code typologies:} scenario section: a scenario may declare
 * {@code {id, label}} pairs for the CRUD-managed {@code typologie}
 * referential up front, instead of letting every reference a stand/animateur
 * makes fall back to the reference-as-its-own-label default the import derives
 * on the fly. See {@code scenario-typologies.yaml} (test fixture): {@code
 * STRATEGIE} is redeclared with a real label, {@code JEUX_VIDEO} is only
 * referenced by the stand.
 *
 * <p>The file's references are local to it (ADR 0050): the rows they land on
 * carry a generated id and the reference as their {@code code}, so everything
 * read back here is designated by code.</p>
 */
@QuarkusTest
class ReferenceDataResourceTypologiesTest {

    @Test
    void scenarioImportAppliesTheDeclaredTypologieLabels() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/reference-data/import-scenario?name=scenario-typologies.yaml")
                .then()
                .statusCode(200);

        List<Map<String, Object>> typologies = given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");

        assertThat(typologies)
                .filteredOn(t -> "STRATEGIE".equals(t.get("code")))
                .extracting(t -> t.get("label"))
                .containsExactly("Stratégie");
        assertThat(typologies)
                .filteredOn(t -> "JEUX_VIDEO".equals(t.get("code")))
                .extracting(t -> t.get("label"))
                .containsExactly("JEUX_VIDEO");
    }

    @Test
    void importScenarioAppliqueLaTypologieNinjaDeclaree() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/reference-data/import-scenario?name=scenario-typologies.yaml")
                .then()
                .statusCode(200);

        assertThat(typologiesNinja()).containsExactly("STRATEGIE");
    }

    @Test
    void promotingANewNinjaTypologieDemotesThePreviousOne() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario-typologies.yaml")
                .then()
                .statusCode(200);
        assertThat(typologiesNinja()).containsExactly("STRATEGIE");

        // Only one typologie may be ninja at a time: promoting another one must
        // demote the previous holder rather than fail on the unique index.
        String jeuxVideo = typologieIdByCode("JEUX_VIDEO");
        given().contentType(ContentType.JSON)
                .body(Map.of("id", jeuxVideo, "code", "JEUX_VIDEO", "label", "Jeux vidéo", "ninja", true))
                .when()
                .put("/api/typologies/" + jeuxVideo)
                .then()
                .statusCode(200);

        assertThat(typologiesNinja()).containsExactly("JEUX_VIDEO");
    }

    /** Codes of the typologies currently flagged ninja — expected to hold at most one. */
    private static List<String> typologiesNinja() {
        List<Map<String, Object>> typologies = given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        return typologies.stream()
                .filter(t -> Boolean.TRUE.equals(t.get("ninja")))
                .map(t -> (String) t.get("code"))
                .toList();
    }

    /** The generated id of the typologie carrying {@code code} in the default edition. */
    private static String typologieIdByCode(String code) {
        List<Map<String, Object>> typologies = given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
        return typologies.stream()
                .filter(t -> code.equals(t.get("code")))
                .map(t -> (String) t.get("id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No typologie of code " + code));
    }

    @Test
    void scenarioFileImportAppliesTheDeclaredTypologieLabels() throws Exception {
        given().when().post("/api/planning/reset").then().statusCode(200);

        String yamlContent = Files.readString(Path.of("src/main/resources/scenarios/scenario-typologies.yaml"));

        given().config(RestAssured.config()
                        .encoderConfig(encoderConfig()
                                .encodeContentTypeAs("application/x-yaml", ContentType.TEXT)
                                .defaultContentCharset("UTF-8")))
                .contentType("application/x-yaml")
                .body(yamlContent)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);

        List<Map<String, Object>> typologies = given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");

        assertThat(typologies)
                .filteredOn(t -> "STRATEGIE".equals(t.get("code")))
                .extracting(t -> t.get("label"))
                .containsExactly("Stratégie");
        assertThat(typologies)
                .filteredOn(t -> "JEUX_VIDEO".equals(t.get("code")))
                .extracting(t -> t.get("label"))
                .containsExactly("JEUX_VIDEO");
    }

    /**
     * The per-typologie cap of issue #594, over the API the screen calls.
     *
     * <p>It was droppable in the middle: the form sent it, {@code
     * TypologieService} rebuilt the item through an overload that had no cap
     * parameter, and the response came back without it. Both ends were tested
     * and green — the repository wrote the column, the form filled the field —
     * so this checks the one thing neither did: that a POST then a PUT give it
     * back.</p>
     */
    @Test
    void theTimeslotCapSurvivesCreationAndUpdate() {
        // The id is generated on creation (ADR 0050): the one the response
        // carries designates the row from then on.
        String id = given().contentType(ContentType.JSON)
                .body("{\"label\":\"Typologie plafonnée\",\"maxCreneauxParAnimateur\":4}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200)
                .body("maxCreneauxParAnimateur", org.hamcrest.Matchers.equalTo(4))
                .extract()
                .path("id");

        given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + id + "' }.maxCreneauxParAnimateur", org.hamcrest.Matchers.equalTo(4));

        given().contentType(ContentType.JSON)
                .body("{\"id\":\"" + id + "\",\"label\":\"Typologie plafonnée\",\"maxCreneauxParAnimateur\":2}")
                .when()
                .put("/api/typologies/" + id)
                .then()
                .statusCode(200)
                .body("maxCreneauxParAnimateur", org.hamcrest.Matchers.equalTo(2));

        // No cap at all is a legitimate value, and must erase the one before:
        // « vide » on the form means « plus de plafond », not « inchangé ».
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"" + id + "\",\"label\":\"Typologie plafonnée\"}")
                .when()
                .put("/api/typologies/" + id)
                .then()
                .statusCode(200)
                .body("maxCreneauxParAnimateur", org.hamcrest.Matchers.nullValue());

        given().when().delete("/api/typologies/" + id).then().statusCode(204);
    }

    /**
     * The organiser's own note, over the same API, and the same trap: a write
     * that carries every other field must not drop it, and a blank one has to
     * mean « no note » rather than an empty string the screens would each have
     * to test for.
     */
    @Test
    void theDescriptionSurvivesCreationAndUpdate() {
        String id = given().contentType(ContentType.JSON)
                .body("{\"label\":\"Typologie annotée\",\"description\":\"Nécessite d'apprendre 45 jeux\"}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200)
                .body("description", org.hamcrest.Matchers.equalTo("Nécessite d'apprendre 45 jeux"))
                .extract()
                .path("id");

        given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .body(
                        "find { it.id == '" + id + "' }.description",
                        org.hamcrest.Matchers.equalTo("Nécessite d'apprendre 45 jeux"));

        // The plan read by typologie carries it too: it is the screen the note
        // was written for.
        given().when()
                .get("/api/planning/typologies")
                .then()
                .statusCode(200)
                .body(
                        "typologies.find { it.typologie == '" + id + "' }.description",
                        org.hamcrest.Matchers.equalTo("Nécessite d'apprendre 45 jeux"));

        given().contentType(ContentType.JSON)
                .body("{\"id\":\"" + id + "\",\"label\":\"Typologie annotée\",\"description\":\"   \"}")
                .when()
                .put("/api/typologies/" + id)
                .then()
                .statusCode(200)
                .body("description", org.hamcrest.Matchers.nullValue());

        given().when().delete("/api/typologies/" + id).then().statusCode(204);
    }
}
