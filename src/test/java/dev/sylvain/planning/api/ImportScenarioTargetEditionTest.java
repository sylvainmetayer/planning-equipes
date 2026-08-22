package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The optional {@code edition:} scenario section routes the whole import into
 * the designated edition — created empty when missing, reused when present —
 * without touching the caller's current edition. The response always reports
 * where the data landed and whether the edition was created: it feeds the
 * mandatory recap the UI shows.
 */
@QuarkusTest
class ImportScenarioTargetEditionTest {

    private static final String EDITION_CIBLE = "ED-CIBLE-TEST";

    private static final String SCENARIO = """
            edition:
              id: ED-CIBLE-TEST
              nom: Édition cible de test

            festival:
              dateDebut: 2026-07-15

            creneaux:
              - id: EDC-J1
                jour: 1
                date: 2026-07-15
                heureDebut: "10:00"
                heureFin: "12:00"

            stands:
              - id: EDC-S1
                nom: Stand édition cible
                typologiesProposees:
                  - STRATEGIE
                effectifMin: 1
                effectifMax: 1
                reserveMajeurs: false

            animateurs:
              - id: EDC-A
                prenom: Cléo
                nom: Cible
                dateNaissance: 1990-01-01
                manager: false
                competences:
                  STRATEGIE: DEBUTANT
            """;

    @BeforeEach
    void nettoyerEditionCible() {
        // Leftover from a previous test run; 404 is fine.
        given().when().delete("/api/editions/" + EDITION_CIBLE);
    }

    private static void importScenario() {
        // Bytes, not String: RestAssured has no encoder for x-yaml text.
        given().contentType("application/x-yaml")
                .body(SCENARIO.getBytes(StandardCharsets.UTF_8))
                .when().post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", equalTo(EDITION_CIBLE));
    }

    @Test
    void lImportCreeLEditionCibleEtYEcritSansToucherALEditionCourante() {
        given().contentType("application/x-yaml")
                .body(SCENARIO.getBytes(StandardCharsets.UTF_8))
                .when().post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", equalTo(EDITION_CIBLE))
                .body("editionNom", equalTo("Édition cible de test"))
                .body("editionCreee", equalTo(true));

        given().when().get("/api/editions")
                .then().statusCode(200)
                .body("id", hasItem(EDITION_CIBLE));

        // The data landed in the target edition…
        given().header("X-Edition-Id", EDITION_CIBLE)
                .when().get("/api/stands")
                .then().statusCode(200)
                .body("id", hasItem("EDC-S1"));

        // …and nowhere near the caller's current edition.
        given().when().get("/api/stands")
                .then().statusCode(200)
                .body("id", not(hasItem("EDC-S1")));
    }

    @Test
    void unSecondImportReutiliseLEditionEtGardeSonNomExistant() {
        importScenario();

        // The edition exists now: renamed by hand, then re-imported into.
        given().contentType("application/json")
                .body("{\"nom\": \"Nom choisi à la main\"}")
                .when().put("/api/editions/" + EDITION_CIBLE)
                .then().statusCode(200);

        given().contentType("application/x-yaml")
                .body(SCENARIO.getBytes(StandardCharsets.UTF_8))
                .when().post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionCreee", equalTo(false))
                .body("editionNom", equalTo("Nom choisi à la main"));
    }

    @Test
    void sansSectionEditionLaReponseResteMuetteSurLEdition() {
        String withoutEdition = SCENARIO.replaceFirst("(?s)edition:.*?\\n\\n", "");
        given().contentType("application/x-yaml")
                .body(withoutEdition.getBytes(StandardCharsets.UTF_8))
                .when().post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", nullValue())
                .body("editionCreee", nullValue());
    }
}
