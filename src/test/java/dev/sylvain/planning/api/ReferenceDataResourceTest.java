package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReferenceDataResourceTest {

    /** The browser uploads scenario YAML as {@code application/x-yaml}: encode it as plain text. */
    private static RequestSpecification yamlRequest(String yamlContent) {
        return given()
                .config(RestAssured.config().encoderConfig(
                        EncoderConfig.encoderConfig().encodeContentTypeAs("application/x-yaml", ContentType.TEXT)))
                .contentType("application/x-yaml")
                .body(yamlContent);
    }

    @Test
    void validANamedScenarioFileReportsNoErrors() {
        String yaml = new String(readAll("/scenarios/scenario.yml"), java.nio.charset.StandardCharsets.UTF_8);
        yamlRequest(yaml)
                .when().post("/api/reference-data/valider-scenario-fichier")
                .then()
                .statusCode(200)
                .body("valide", equalTo(true))
                .body("erreurs", empty());
    }

    @Test
    void invalidScenarioFileReportsStructuralErrors() {
        String yaml = """
                festival:
                  dateDebut: 2026-07-08
                creneaux:
                  - id: J1-MATIN
                    date: 2026-07-08
                    heureDebut: "09:00"
                    heureFin: "13:00"
                stands:
                  - id: STAND-STRAT
                    nom: ""
                    typologiesProposees:
                      - STRATEGIE
                    effectifMin: -1
                    effectifMax: 1
                animateurs:
                  - id: A1
                    prenom: Alice
                    nom: Referente
                    dateNaissance: 2002-07-19
                    manager: false
                    competences:
                      STRATEGIE: REFERENT
                postes:
                  - id: P1
                    standId: STAND-STRAT
                    creneauId: J1-MATIN
                """;
        yamlRequest(yaml)
                .when().post("/api/reference-data/valider-scenario-fichier")
                .then()
                .statusCode(200)
                .body("valide", equalTo(false))
                .body("erreurs", hasSize(2))
                .body("erreurs", org.hamcrest.Matchers.hasItem(containsString("effectifMin")))
                .body("erreurs", org.hamcrest.Matchers.hasItem(containsString("nom")));
    }

    @Test
    void malformedYamlReportsAParsingError() {
        yamlRequest("festival: [this is not: valid: yaml")
                .when().post("/api/reference-data/valider-scenario-fichier")
                .then()
                .statusCode(200)
                .body("valide", equalTo(false))
                .body("erreurs[0]", containsString("YAML invalide"));
    }

    @Test
    void emptyFileReportsAsInvalid() {
        yamlRequest("")
                .when().post("/api/reference-data/valider-scenario-fichier")
                .then()
                .statusCode(200)
                .body("valide", equalTo(false))
                .body("erreurs[0]", containsString("vide"));
    }

    private static byte[] readAll(String classpathResource) {
        try (var in = ReferenceDataResourceTest.class.getResourceAsStream(classpathResource)) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
