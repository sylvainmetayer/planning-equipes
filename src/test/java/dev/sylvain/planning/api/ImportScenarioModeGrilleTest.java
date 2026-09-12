package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a scenario says of its own grid, on the way in.
 *
 * <p>The mode was parsed and then dropped: {@code updateParametresDecoupage}
 * deliberately refuses to write it — the settings form must not revert a choice
 * made on the Créneaux screen — so a file declaring {@code modeGrille:
 * VACATIONS} landed in an edition still declared {@code AMPLITUDES}. Its relay
 * vacations were then read as overlapping amplitudes, and the découpage cards
 * stayed on screen over a grid with nothing to slice.</p>
 */
@QuarkusTest
class ImportScenarioModeGrilleTest {

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

    private static final String EN_VACATIONS = """
            parametresDecoupage:
              modeGrille: VACATIONS

            """ + ENTETE;

    /** The same file, plus the day templates its créneaux imply. */
    private static final String AVEC_JOURNEES_TYPES = EN_VACATIONS + """

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

    @Test
    void unFichierQuiSeDeclareEnVacationsLandeEnVacations() {
        importer(EN_VACATIONS);

        given().header(HEADER, EDITION)
                .when()
                .get("/api/parametres-decoupage")
                .then()
                .statusCode(200)
                .body("modeGrille", equalTo("VACATIONS"));
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
                .body("modeADeclarer", equalTo(false))
                .body("aucunChangement", equalTo(true));
    }

    /** Absent the section, the import recognises the templates the créneaux imply. */
    @Test
    void sansSectionLesJourneesTypesSontReconnuesDepuisLesCreneaux() {
        importer(EN_VACATIONS);

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
