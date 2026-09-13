package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a scenario says of its own grid, on the way in: the day templates it
 * names govern their dates, and a file that names none has them recognised
 * from its créneaux.
 *
 * <p>It also holds the refusal of the two sections the découpage owned. That
 * refusal is the safe reading: accepted and ignored, a file of day-long
 * opening amplitudes would import as day-long vacations, and its author would
 * hear about it from the solver rather than from the import.</p>
 */
@QuarkusTest
class ImportScenarioJourneesTypesTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String EDITION = "IMPORT-MODE-GRILLE";

    private static final String ENTETE = """
            festival:
              dateDebut: 2033-07-08

            creneaux:
              - id: J1-MATIN
                jour: 1
                date: 2033-07-08
                heureDebut: "09:00"
                heureFin: "12:00"
              - id: J1-MIDI
                jour: 1
                date: 2033-07-08
                heureDebut: "12:00"
                heureFin: "13:00"
                couverturePause: true

            stands:
              - id: STAND-MODE
                nom: Stand du mode
                typologiesProposees:
                  - STRATEGIE
                effectifMin: 1
                effectifMax: 2
                reserveMajeurs: false

            animateurs:
              - id: A1
                prenom: Alice
                nom: Referente
                dateNaissance: 2002-07-19
                manager: false
                competences:
                  STRATEGIE: REFERENT
                joursIndisponibles: []
            """;

    /** The same file, plus the day templates its créneaux imply. */
    private static final String AVEC_JOURNEES_TYPES = ENTETE + """

            journeesTypes:
              - nom: Jour normal
                vacations:
                  - heureDebut: "09:00"
                    heureFin: "12:00"
                  - heureDebut: "12:00"
                    heureFin: "13:00"
                    couverturePause: true
                dates:
                  - 2033-07-08
            """;

    @BeforeEach
    void creerLEditionDAtterrissage() {
        given().contentType("application/json")
                .body("{\"id\":\"" + EDITION + "\",\"nom\":\"Import mode grille\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void supprimerLEditionDAtterrissage() {
        given().when().delete("/api/editions/" + EDITION);
    }

    private void importer(String yaml) {
        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);
    }

    /**
     * And the whole round trip holds: the templates the file names govern its
     * dates, and applying right after the import moves no créneau — a file
     * consistent with itself describes the edition it just created.
     */
    @Test
    void lesJourneesTypesDuFichierGouvernentSesDatesEtNeChangentRienALApplication() {
        importer(AVEC_JOURNEES_TYPES);

        given().header(HEADER, EDITION)
                .when()
                .get("/api/journees-types")
                .then()
                .statusCode(200)
                .body("journeesTypes", hasSize(1))
                .body("journeesTypes[0].nom", equalTo("Jour normal"))
                .body("journeesTypes[0].vacations", hasSize(2))
                .body("journeesTypes[0].vacations[1].couverturePause", equalTo(true))
                .body("calendrier", hasSize(1))
                .body("calendrier[0].date", equalTo("2033-07-08"))
                .body("datesEnEcart", hasSize(0));

        given().header(HEADER, EDITION)
                .when()
                .post("/api/journees-types/application/apercu")
                .then()
                .statusCode(200)
                .body("crees", equalTo(0))
                .body("supprimes", equalTo(0))
                .body("misAJour", equalTo(0))
                .body("conserves", equalTo(2))
                .body("aucunChangement", equalTo(true));
    }

    /**
     * The two sections the découpage owned are refused at the door, by name.
     * A 400 with a message the author can act on, not a silent import of the
     * wrong grid.
     */
    @Test
    void lesSectionsDuDecoupageRetireSontRefusees() {
        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body("""
                        parametresDecoupage:
                          dureeVacationCibleMinutes: 180

                        """ + ENTETE)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400)
                .body("message", containsString("parametresDecoupage"));

        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body("decoupageAuto: {}\n\n" + ENTETE)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400)
                .body("message", containsString("decoupageAuto"));
    }

    /** Absent the section, the import recognises the templates the créneaux imply. */
    @Test
    void sansSectionLesJourneesTypesSontReconnuesDepuisLesCreneaux() {
        importer(ENTETE);

        given().header(HEADER, EDITION)
                .when()
                .get("/api/journees-types")
                .then()
                .statusCode(200)
                .body("journeesTypes", hasSize(1))
                .body("journeesTypes[0].vacations", hasSize(2))
                .body("calendrier", hasSize(1))
                .body("datesEnEcart", hasSize(0));
    }
}
