package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/editions/courant/etat}, the one call behind the home screen
 * (issue #485): it answers on an empty edition — the first thing a new user
 * sees — and reads the nine services once the referential is filled.
 */
@QuarkusTest
class EtatEditionResourceTest {

    @Test
    void anEmptyEditionAnswersWithEveryLineToDo() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .body("editionId", equalTo("DEFAUT"))
                .body("editionNom", notNullValue())
                .body("referentiels.stands", equalTo(0))
                .body("referentiels.statut", equalTo("A_FAIRE"))
                .body("collecte.statut", equalTo("A_FAIRE"))
                .body("ouvertures.statut", equalTo("A_FAIRE"))
                .body("besoin.statut", equalTo("A_FAIRE"))
                .body("resolution.resolue", equalTo(false))
                .body("resolution.solveEnCours", equalTo(false))
                .body("resolution.score", nullValue())
                .body("resolution.statut", equalTo("A_FAIRE"))
                .body("problemes.statut", equalTo("A_FAIRE"))
                .body("publication.jamaisPublie", equalTo(true))
                .body("publication.statut", equalTo("A_FAIRE"))
                .body("confirmations.statut", equalTo("A_FAIRE"))
                .body("foire.statut", equalTo("A_FAIRE"));

        seedScenario();
    }

    @Test
    void aFilledEditionReadsItsReferentialsAndStillHasNothingSolved() {
        seedScenario();

        given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .body("referentiels.stands", greaterThan(0))
                .body("referentiels.animateurs", greaterThan(0))
                .body("referentiels.creneaux", greaterThan(0))
                .body("referentiels.statut", equalTo("FAIT"))
                .body("ouvertures.anomalies", notNullValue())
                .body("besoin.minimum", greaterThan(0))
                .body("besoin.animateurs", greaterThan(0))
                .body("resolution.resolue", equalTo(false))
                .body("resolution.statut", equalTo("A_FAIRE"))
                .body("problemes.bloquants", notNullValue())
                .body("publication.jamaisPublie", equalTo(true))
                .body("foire.ouverte", notNullValue());
    }

    /**
     * The coherence checklist: the counts on the home screen and the detail
     * behind them add up, every family is listed, and an empty edition has an
     * empty checklist that holds nothing back.
     */
    @Test
    void theCoherenceChecklistMatchesItsBlockOnTheHomeScreen() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .get("/api/editions/courant/coherence")
                .then()
                .statusCode(200)
                .body("anomalies", hasSize(0))
                .body("familles", hasSize(5));
        given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .body("coherence.statut", equalTo("FAIT"));

        seedScenario();
        io.restassured.path.json.JsonPath detail = given().when()
                .get("/api/editions/courant/coherence")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        io.restassured.path.json.JsonPath etat = given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        org.assertj.core.api.Assertions.assertThat(etat.getInt("coherence.bloquants"))
                .isEqualTo(detail.getInt("bloquants"));
        org.assertj.core.api.Assertions.assertThat(etat.getInt("coherence.aVerifier"))
                .isEqualTo(detail.getInt("aVerifier"));
        org.assertj.core.api.Assertions.assertThat(etat.getInt("coherence.informations"))
                .isEqualTo(detail.getInt("informations"));
        org.assertj.core.api.Assertions.assertThat(detail.getList("anomalies"))
                .hasSize(detail.getInt("bloquants") + detail.getInt("aVerifier") + detail.getInt("informations"));
    }

    /** No name, no birth date, no address leaves this route: the view is counts and dates only. */
    @Test
    void theViewCarriesNoPersonalData() {
        seedScenario();

        String body = given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContainIgnoringCase("prenom")
                .doesNotContainIgnoringCase("dateNaissance")
                .doesNotContainIgnoringCase("email")
                .doesNotContain("destinataires");
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
