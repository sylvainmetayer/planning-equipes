package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import java.nio.charset.StandardCharsets;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CsvImportResourceTest {

    /**
     * Mimics the browser upload: raw UTF-8 bytes with a {@code text/csv}
     * content type carrying no charset parameter.
     */
    private static RequestSpecification csv(String body) {
        return given().contentType("text/csv").body(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void animateursCsvReplacesTheWholeTable() {
        String body = """
                id;prenom;nom;dateNaissance;manager;competences;joursIndisponibles
                CSV-1;Ada;Lovelace;1990-05-04;false;STRATEGIE:REFERENT|ENFANT:AUTONOME;2026-07-02|2026-07-03
                CSV-2;Alan;Turing;2010-01-15;true;;
                """;

        csv(body)
                .when().post("/api/import/csv/animateurs")
                .then()
                .statusCode(200)
                .body("imported", equalTo(2));

        given()
                .when().get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2))
                .body("id", hasItem("CSV-1"))
                .body("find { it.id == 'CSV-1' }.competences.STRATEGIE", equalTo("REFERENT"))
                .body("find { it.id == 'CSV-1' }.joursIndisponibles.size()", equalTo(2))
                .body("find { it.id == 'CSV-2' }.manager", equalTo(true));
    }

    @Test
    void standsAndCreneauxCsvAreImportedWithCommaSeparatorToo() {
        csv("""
                id,nom,typologies,effectifMin,effectifMax,reserveMajeurs
                S-CSV,"Stand des énigmes",ENIGME|STRATEGIE,1,3,true
                """)
                .when().post("/api/import/csv/stands")
                .then()
                .statusCode(200)
                .body("imported", equalTo(1));

        given()
                .when().get("/api/stands")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].nom", equalTo("Stand des énigmes"))
                .body("[0].reserveMajeurs", equalTo(true))
                .body("[0].typologiesProposees", hasItem("ENIGME"));

        csv("""
                date;heureDebut;heureFin
                2026-07-02;10:00;13:00
                """)
                .when().post("/api/import/csv/creneaux")
                .then()
                .statusCode(200)
                .body("imported", equalTo(1));

        given()
                .when().get("/api/creneaux")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].heureDebut", containsString("10:00"));
    }

    @Test
    void invalidCsvIsRejectedWithAnExplicitMessage() {
        csv("""
                id;prenom;nom;manager
                CSV-1;Ada;Lovelace;false
                """)
                .when().post("/api/import/csv/animateurs")
                .then()
                .statusCode(400)
                .body("message", containsString("dateNaissance"));

        csv("""
                date;heureDebut;heureFin
                02/07/2026;10:00;13:00
                """)
                .when().post("/api/import/csv/creneaux")
                .then()
                .statusCode(400)
                .body("message", containsString("ISO date"));

        csv("""
                id;nom;typologies;effectifMin;effectifMax;reserveMajeurs
                S-1;Stand;INCONNUE;1;2;false
                """)
                .when().post("/api/import/csv/stands")
                .then()
                .statusCode(400)
                .body("message", containsString("must be one of"));

        csv("id;prenom;nom;dateNaissance;manager\n")
                .when().post("/api/import/csv/animateurs")
                .then()
                .statusCode(400)
                .body("message", containsString("does not contain any data row"));
    }

    @Test
    void unknownEntityIsRejected() {
        csv("id\nX\n")
                .when().post("/api/import/csv/typologies")
                .then()
                .statusCode(404)
                .body("message", containsString("Unknown CSV entity"));
    }
}
