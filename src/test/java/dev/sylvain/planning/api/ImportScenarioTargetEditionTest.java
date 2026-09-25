package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The optional {@code edition:} scenario section routes the whole import into
 * the designated edition — created empty when missing, reused when present —
 * without touching the caller's current edition. The response always reports
 * where the data landed and whether the edition was created: it feeds the
 * mandatory recap the UI shows.
 *
 * <p>An edition's id is generated ({@code E<n>}, ADR 0050), so the file
 * designates it by name, and the tests read the id back from the response.
 */
@QuarkusTest
class ImportScenarioTargetEditionTest {

    private static final String NOM_EDITION_CIBLE = "Édition cible de test";

    private static final String NOM_RENOMME = "Nom choisi à la main";

    private static final String SCENARIO = """
            edition:
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
        // Leftovers from a previous test run, found by name since their ids were drawn.
        List<Map<String, Object>> editions = given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("");
        for (Map<String, Object> edition : editions) {
            if (Set.of(NOM_EDITION_CIBLE, NOM_RENOMME).contains(edition.get("nom"))
                    && !Boolean.TRUE.equals(edition.get("defaut"))) {
                given().when().delete("/api/editions/" + edition.get("id"));
            }
        }
    }

    /** Imports {@code scenario} and returns the id of the edition it landed in. */
    private static String importScenario(String scenario) {
        // Bytes, not String: RestAssured has no encoder for x-yaml text.
        return given().contentType("application/x-yaml")
                .body(scenario.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .extract()
                .path("editionId");
    }

    @Test
    void importCreatesTargetEditionAndWritesThereWithoutTouchingCurrentEdition() {
        List<String> standsCourantsAvant = given().when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        String editionId = given().contentType("application/x-yaml")
                .body(SCENARIO.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", matchesPattern("E\\d+"))
                .body("editionNom", equalTo(NOM_EDITION_CIBLE))
                .body("editionCreee", equalTo(true))
                .extract()
                .path("editionId");

        given().when().get("/api/editions").then().statusCode(200).body("id", hasItem(editionId));

        // The data landed in the target edition — the file's stand id became its code…
        given().header("X-Edition-Id", editionId)
                .when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .body("code", hasItem("EDC-S1"));

        // …and nowhere near the caller's current edition, whose stands are the ones it had.
        given().when()
                .get("/api/stands")
                .then()
                .statusCode(200)
                .body("id", containsInAnyOrder(standsCourantsAvant.toArray()));
    }

    @Test
    void secondImportReusesEditionAndKeepsItsExistingName() {
        String editionId = importScenario(SCENARIO);

        // Designated by the same name, the edition is found again rather than created twice.
        given().contentType("application/x-yaml")
                .body(SCENARIO.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", equalTo(editionId))
                .body("editionCreee", equalTo(false));

        // Renamed by hand, then re-imported into by its id: the name the file carries does not win.
        given().contentType("application/json")
                .body("{\"nom\": \"" + NOM_RENOMME + "\"}")
                .when()
                .put("/api/editions/" + editionId)
                .then()
                .statusCode(200);

        String parId = SCENARIO.replaceFirst(
                "edition:\\n  nom: [^\\n]*\\n",
                "edition:\n  id: " + editionId + "\n  nom: " + NOM_EDITION_CIBLE + "\n");
        given().contentType("application/x-yaml")
                .body(parId.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", equalTo(editionId))
                .body("editionCreee", equalTo(false))
                .body("editionNom", equalTo(NOM_RENOMME));
    }

    @Test
    void sansSectionEditionLaReponseResteMuetteSurLEdition() {
        String withoutEdition = SCENARIO.replaceFirst("(?s)edition:.*?\\n\\n", "");
        given().contentType("application/x-yaml")
                .body(withoutEdition.getBytes(StandardCharsets.UTF_8))
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200)
                .body("editionId", nullValue())
                .body("editionCreee", nullValue());
    }
}
