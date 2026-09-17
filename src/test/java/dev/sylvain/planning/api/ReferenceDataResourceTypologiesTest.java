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
 * referential up front, instead of letting every id a stand/animateur
 * references fall back to the id-as-its-own-label default {@code
 * ReferenceDataImportRepository#importFromPlanning} derives on the fly. See
 * {@code scenario-typologies.yaml} (test fixture): {@code STRATEGIE} is a
 * seeded typologie (label {@code STRATEGIE} by default) redeclared with a
 * real label, {@code JEUX_VIDEO} is only referenced by the stand.
 */
@QuarkusTest
class ReferenceDataResourceTypologiesTest {

    @Test
    void importScenarioAppliqueLesLibellesDeTypologiesDeclares() {
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
                .filteredOn(t -> "STRATEGIE".equals(t.get("id")))
                .extracting(t -> t.get("label"))
                .containsExactly("Stratégie");
        assertThat(typologies)
                .filteredOn(t -> "JEUX_VIDEO".equals(t.get("id")))
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
    void designerUneNouvelleTypologieNinjaRetrogradeLaPrecedente() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario-typologies.yaml")
                .then()
                .statusCode(200);
        assertThat(typologiesNinja()).containsExactly("STRATEGIE");

        // Only one typologie may be ninja at a time: promoting another one must
        // demote the previous holder rather than fail on the unique index.
        given().contentType(ContentType.JSON)
                .body(Map.of("id", "JEUX_VIDEO", "label", "Jeux vidéo", "ninja", true))
                .when()
                .put("/api/typologies/JEUX_VIDEO")
                .then()
                .statusCode(200);

        assertThat(typologiesNinja()).containsExactly("JEUX_VIDEO");
    }

    /** Ids of the typologies currently flagged ninja — expected to hold at most one. */
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
                .map(t -> (String) t.get("id"))
                .toList();
    }

    @Test
    void importScenarioFichierAppliqueLesLibellesDeTypologiesDeclares() throws Exception {
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
                .filteredOn(t -> "STRATEGIE".equals(t.get("id")))
                .extracting(t -> t.get("label"))
                .containsExactly("Stratégie");
        assertThat(typologies)
                .filteredOn(t -> "JEUX_VIDEO".equals(t.get("id")))
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
    void lePlafondDeCreneauxSurvitALaCreationEtALaModification() {
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"TYPO-PLAFOND\",\"label\":\"Typologie plafonnée\",\"maxCreneauxParAnimateur\":4}")
                .when()
                .post("/api/typologies")
                .then()
                .statusCode(200)
                .body("maxCreneauxParAnimateur", org.hamcrest.Matchers.equalTo(4));

        given().when()
                .get("/api/typologies")
                .then()
                .statusCode(200)
                .body("find { it.id == 'TYPO-PLAFOND' }.maxCreneauxParAnimateur", org.hamcrest.Matchers.equalTo(4));

        given().contentType(ContentType.JSON)
                .body("{\"id\":\"TYPO-PLAFOND\",\"label\":\"Typologie plafonnée\",\"maxCreneauxParAnimateur\":2}")
                .when()
                .put("/api/typologies/TYPO-PLAFOND")
                .then()
                .statusCode(200)
                .body("maxCreneauxParAnimateur", org.hamcrest.Matchers.equalTo(2));

        // No cap at all is a legitimate value, and must erase the one before:
        // « vide » on the form means « plus de plafond », not « inchangé ».
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"TYPO-PLAFOND\",\"label\":\"Typologie plafonnée\"}")
                .when()
                .put("/api/typologies/TYPO-PLAFOND")
                .then()
                .statusCode(200)
                .body("maxCreneauxParAnimateur", org.hamcrest.Matchers.nullValue());

        given().when().delete("/api/typologies/TYPO-PLAFOND").then().statusCode(204);
    }
}
