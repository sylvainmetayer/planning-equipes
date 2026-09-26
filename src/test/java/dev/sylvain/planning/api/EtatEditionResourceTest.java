package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.config.DevMode;
import io.quarkus.test.junit.QuarkusMock;
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
        // A request without X-Edition-Id works in the default edition, whose id
        // is drawn by the application (ADR 0050): read it, never assume it.
        String defaut = given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("find { it.defaut == true }.id");

        given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .body("editionId", equalTo(defaut))
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
                .body("foire.statut", equalTo("A_FAIRE"))
                // Without a timeslot the edition has no dates: still preparing, no day to read.
                .body("evenement.phase", equalTo("PREPARATION"))
                .body("evenement.jour", nullValue())
                .body("resolution.lecture", hasSize(0));

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
                .body("foire.ouverte", notNullValue())
                // « À traiter aujourd'hui »: nothing solved nor published yet, so no day to
                // read, nobody silent, nobody to tell — and the horizon it would look over.
                .body("aTraiter.aujourdhui", notNullValue())
                .body("aTraiter.horizonJours", equalTo(7))
                .body("aTraiter.journeesNonRelues", hasSize(0))
                .body("aTraiter.silencieuxARelancer", equalTo(0))
                .body("aTraiter.personnesAPrevenir", equalTo(0));
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

    /** A server launched with {@code quarkus:dev}, as far as the simulated clock can tell. */
    private static final class DevModeActif extends DevMode {
        @Override
        public boolean isActive() {
            return true;
        }
    }

    /**
     * On a day of the event the home screen reads the day under way — the
     * wall display's open stands and empty seats, the mode jour J's absences —
     * ranked from the first day, on the simulated clock like every screen.
     */
    @Test
    void onADayOfTheEventTheDayUnderWayIsRead() {
        seedScenario();
        String premierJour = given().when()
                .get("/api/editions/courant/etat")
                .then()
                .statusCode(200)
                .body("evenement.phase", notNullValue())
                .extract()
                .path("evenement.premierJour");
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        try {
            given().contentType("application/json")
                    .body("{\"dateDuJour\":\"" + premierJour + "\",\"heureDuJour\":\"08:00\"}")
                    .when()
                    .put("/api/horloge")
                    .then()
                    .statusCode(200);

            given().when()
                    .get("/api/editions/courant/etat")
                    .then()
                    .statusCode(200)
                    .body("evenement.aujourdhui", equalTo(premierJour))
                    .body("evenement.phase", equalTo("EVENEMENT"))
                    .body("evenement.jour.date", equalTo(premierJour))
                    .body("evenement.jour.numero", equalTo(1))
                    .body("evenement.jour.standsOuverts", notNullValue())
                    .body("evenement.jour.placesVides", notNullValue())
                    .body("evenement.jour.absents", equalTo(0));
        } finally {
            given().contentType("application/json")
                    .body("{\"dateDuJour\":null}")
                    .when()
                    .put("/api/horloge")
                    .then()
                    .statusCode(200);
        }
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }
}
