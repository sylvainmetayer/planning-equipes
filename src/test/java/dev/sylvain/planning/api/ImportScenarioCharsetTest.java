package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An accented edition name in an uploaded scenario survives whatever charset the client declares, or none. */
@QuarkusTest
class ImportScenarioCharsetTest {

    private static final String NOM = "Édition accentuée ñ";

    private static final String YAML = """
            edition:
              nom: Édition accentuée ñ

            festival:
              dateDebut: 2026-07-15

            creneaux:
              - id: CS-J1
                jour: 1
                date: 2026-07-15
                heureDebut: "10:00"
                heureFin: "12:00"

            stands:
              - id: CS-S1
                nom: Stand
                typologiesProposees:
                  - STRATEGIE
                effectifMin: 1
                effectifMax: 1
                reserveMajeurs: false

            animateurs:
              - id: CS-A
                prenom: Cleo
                nom: Cible
                dateNaissance: 1990-01-01
                manager: false
                competences:
                  STRATEGIE: DEBUTANT
            """;

    @BeforeEach
    @AfterEach
    void dropEdition() {
        List<Map<String, Object>> editions = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("");
        for (Map<String, Object> e : editions) {
            if (NOM.equals(e.get("nom")) && !Boolean.TRUE.equals(e.get("active"))) {
                given().header("X-Edition-Id", "E1").when().delete("/api/editions/" + e.get("id"));
            }
        }
    }

    private String importedName(String contentType) {
        String id = given().config(io.restassured.RestAssured.config()
                        .encoderConfig(io.restassured.config.EncoderConfig.encoderConfig()
                                .appendDefaultContentCharsetToContentTypeIfUndefined(false)))
                .header("X-Edition-Id", "E1")
                .contentType(contentType)
                .body(YAML.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .log()
                .ifStatusCodeIsEqualTo(400)
                .statusCode(200)
                .extract()
                .path("editionId");
        List<Map<String, Object>> editions = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions")
                .then()
                .extract()
                .path("");
        return editions.stream()
                .filter(e -> id.equals(e.get("id")))
                .map(e -> (String) e.get("nom"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aStringBodyKeepsTheAccentsRestAssuredEncodesInLatin1() {
        String id = given().header("X-Edition-Id", "E1")
                .contentType("text/plain")
                .body(YAML)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .extract()
                .path("editionId");
        List<Map<String, Object>> editions = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions")
                .then()
                .extract()
                .path("");
        assertThat(editions.stream()
                        .filter(e -> id.equals(e.get("id")))
                        .map(e -> (String) e.get("nom"))
                        .findFirst()
                        .orElseThrow())
                .isEqualTo(NOM);
    }

    @Test
    void latin1Declared() {
        String id = given().header("X-Edition-Id", "E1")
                .contentType("text/plain; charset=ISO-8859-1")
                .body(YAML.getBytes(StandardCharsets.ISO_8859_1))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .extract()
                .path("editionId");
        List<Map<String, Object>> editions = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/editions")
                .then()
                .extract()
                .path("");
        assertThat(editions.stream()
                        .filter(e -> id.equals(e.get("id")))
                        .map(e -> (String) e.get("nom"))
                        .findFirst()
                        .orElseThrow())
                .isEqualTo(NOM);
    }

    @Test
    void utf8DeclaredByTheBrowser() {
        assertThat(importedName("application/x-yaml; charset=UTF-8")).isEqualTo(NOM);
    }

    @Test
    void noCharsetDeclared() {
        assertThat(importedName("application/x-yaml")).isEqualTo(NOM);
    }

    @Test
    void textPlainWithoutCharset() {
        assertThat(importedName("text/plain")).isEqualTo(NOM);
    }
}
