package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import dev.sylvain.planning.config.DevMode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A consigne over the API (issue #4): what it refuses, what it writes, what it
 * adds to the grid, and what lifting takes back.
 *
 * <p>The sample scenario is one day (2026-07-08) with two créneaux, 09h-13h
 * and 14h-18h, on two stands — the second one open 14h-16h only, by a dated
 * window. The clock is frozen a week before, since a consigne is only laid on
 * a day to come.</p>
 */
@QuarkusTest
class ConsigneResourceTest {

    private static final String JOUR = "2026-07-08";
    private static final String VEILLE = "2026-07-01";

    /** A server launched with {@code quarkus:dev}, as far as the guard can tell. */
    private static final class DevModeActif extends DevMode {
        @Override
        public boolean isActive() {
            return true;
        }
    }

    @BeforeEach
    void aPersistedPlanOnAFrozenClock() {
        QuarkusMock.installMockForType(new DevModeActif(), DevMode.class);
        given().contentType("application/json")
                .body("{\"dateDuJour\":\"" + VEILLE + "\"}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void handTheClockBack() {
        given().contentType("application/json")
                .body("{\"dateDuJour\":null}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);
        given().when().post("/api/planning/reset").then().statusCode(200);
    }

    private static Map<String, Object> demande(List<Map<String, Object>> ouvertures) {
        Map<String, Object> corps = new HashMap<>();
        corps.put("dates", List.of(JOUR));
        corps.put("fermetureDebut", "12:00");
        corps.put("fermetureFin", "16:00");
        corps.put("motif", "Arrêté préfectoral canicule");
        corps.put("fenetres", List.of(Map.of("debut", "18:00", "fin", "20:00")));
        corps.put("ouvertures", ouvertures);
        return corps;
    }

    private static Map<String, Object> ouverture(String standId) {
        Map<String, Object> ouverture = new HashMap<>();
        ouverture.put("standId", standId);
        ouverture.put("debut", "18:00");
        ouverture.put("fin", "20:00");
        return ouverture;
    }

    private static JsonPath creneaux() {
        return given().when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void thePreselectionSaysWhatTheBandTakesFromEachStand() {
        given().contentType("application/json")
                .body(Map.of("date", JOUR, "fermetureDebut", "12:00", "fermetureFin", "16:00"))
                .when()
                .post("/api/consignes/preselection")
                .then()
                .statusCode(200)
                .body("creneauxDuJour", is(2))
                .body("stands", hasSize(2))
                // Homme-jeu first by name: its whole 14h-16h dated window is in the band.
                .body("stands[0].standId", equalTo("HOMME-JEU"))
                .body("stands[0].minutesPerdues", is(120))
                .body("stands[0].exceptionDatee", is(true))
                .body("stands[0].preCoche", is(false))
                // 12h-13h on the morning créneau, 14h-16h on the afternoon one.
                .body("stands[1].minutesPerdues", is(180))
                .body("stands[1].effectifHerite", is(1))
                .body("stands[1].preCoche", is(true))
                .body("stands[1].exceptionDatee", is(false));
    }

    @Test
    void thePreviewCountsSeatsBeforeAndAfterWithoutWriting() {
        given().contentType("application/json")
                .body(demande(List.of(ouverture("STAND-STRAT"))))
                .when()
                .post("/api/consignes/apercu")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].date", equalTo(JOUR))
                .body("[0].dejaSousConsigne", is(false))
                .body("[0].creneauxDuJour", is(2))
                // STAND-STRAT on both créneaux, HOMME-JEU on the afternoon one (14h-16h).
                .body("[0].siegesAvant", is(3))
                // STAND-STRAT keeps 09h-12h and 16h-18h and gains 18h-20h; HOMME-JEU loses its seat.
                .body("[0].siegesApres", is(3))
                .body("[0].minutesAvant", is(240 + 240 + 120))
                .body("[0].minutesApres", is(180 + 120 + 120))
                .body("[0].creneauxAAjouter", hasSize(1))
                .body("[0].creneauxAAjouter[0].debut", equalTo("18:00:00"))
                .body("[0].vacationsSansSiege", hasSize(0))
                .body("[0].standsOuverts", is(1))
                .body("[0].standsEntrants", equalTo(List.of("STAND-STRAT")))
                .body("[0].standsExceptionCoches", hasSize(0))
                .body("[0].validationRetiree", is(false))
                // No plan persisted: nobody is seated in the band yet.
                .body("[0].animateursDansLaBande", is(0));

        given().when().get("/api/consignes").then().statusCode(200).body("consignes", hasSize(0));
        assertThat(creneaux().getList("id")).hasSize(2);
    }

    @Test
    void layingDownWritesTheConsigneAndAddsTheEveningCreneau() {
        given().contentType("application/json")
                .body(demande(List.of(ouverture("STAND-STRAT"))))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200)
                .body("[0].siegesApres", is(3));

        given().when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("aujourdhui", equalTo(VEILLE))
                .body("consignes", hasSize(1))
                .body("consignes[0].date", equalTo(JOUR))
                .body("consignes[0].motif", equalTo("Arrêté préfectoral canicule"))
                .body("consignes[0].ouvertures", hasSize(1))
                .body("consignes[0].creneauxAjoutes", hasSize(1));

        JsonPath grille = creneaux();
        assertThat(grille.getList("heureDebut")).containsExactlyInAnyOrder("09:00:00", "14:00:00", "18:00:00");
    }

    @Test
    void changingADateKeepsTheCreneauItsWindowsStillNeedAndDropsTheOthers() {
        given().contentType("application/json")
                .body(demande(List.of(ouverture("STAND-STRAT"))))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);
        Object soir =
                creneaux().getList("findAll { it.heureDebut == '18:00:00' }.id").get(0);

        // Same windows, one more stand: the evening créneau stays, id included.
        given().contentType("application/json")
                .body(demande(List.of(ouverture("STAND-STRAT"), ouverture("HOMME-JEU"))))
                .when()
                .post("/api/consignes/apercu")
                .then()
                .statusCode(200)
                .body("[0].dejaSousConsigne", is(true))
                .body("[0].creneauxAAjouter", hasSize(0))
                .body("[0].standsEntrants", equalTo(List.of("HOMME-JEU")))
                .body("[0].standsSortants", hasSize(0));
        given().contentType("application/json")
                .body(demande(List.of(ouverture("STAND-STRAT"), ouverture("HOMME-JEU"))))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);
        assertThat(creneaux().getList("findAll { it.heureDebut == '18:00:00' }.id"))
                .containsExactly(soir);

        // No opening at all: the evening créneau has no reason to exist.
        given().contentType("application/json")
                .body(demande(List.of()))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200)
                .body("[0].creneauxARetirer", hasSize(1))
                .body("[0].standsSortants", hasSize(2));
        assertThat(creneaux().getList("heureDebut")).containsExactlyInAnyOrder("09:00:00", "14:00:00");
    }

    @Test
    void liftingRemovesTheConsigneAndTheCreneauItAdded() {
        given().contentType("application/json")
                .body(demande(List.of(ouverture("STAND-STRAT"))))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body(Map.of("dates", List.of(JOUR)))
                .when()
                .post("/api/consignes/levee/apercu")
                .then()
                .statusCode(200)
                .body("[0].sousConsigne", is(true))
                .body("[0].creneauxARetirer", hasSize(1));
        given().contentType("application/json")
                .body(Map.of("dates", List.of(JOUR)))
                .when()
                .post("/api/consignes/levee")
                .then()
                .statusCode(204);

        given().when().get("/api/consignes").then().statusCode(200).body("consignes", hasSize(0));
        assertThat(creneaux().getList("heureDebut")).containsExactlyInAnyOrder("09:00:00", "14:00:00");
    }

    @Test
    void layingDownWithdrawsTheDaysReading() {
        given().contentType("application/json")
                .body(Map.of("jour", JOUR))
                .when()
                .post("/api/validations")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body(demande(List.of()))
                .when()
                .post("/api/consignes/apercu")
                .then()
                .statusCode(200)
                .body("[0].validationRetiree", is(true));
        given().contentType("application/json")
                .body(demande(List.of()))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);

        given().when().get("/api/validations").then().statusCode(200).body("size()", is(0));
    }

    @Test
    void aDayAlreadyBegunIsRefused() {
        given().contentType("application/json")
                .body("{\"dateDuJour\":\"" + JOUR + "\"}")
                .when()
                .put("/api/debug/date-du-jour")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body(demande(List.of()))
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("en cours"));
        given().contentType("application/json")
                .body(Map.of("dates", List.of(JOUR)))
                .when()
                .post("/api/consignes/levee")
                .then()
                .statusCode(400);
    }

    @Test
    void fourRefusalsWriteNothing() {
        Map<String, Object> inconnu = demande(List.of(ouverture("AUTRES-BOURSE")));
        given().contentType("application/json")
                .body(inconnu)
                .when()
                .post("/api/consignes/apercu")
                .then()
                .statusCode(400)
                .body("message", containsString("Stand inconnu"));

        Map<String, Object> sansMotif = demande(List.of());
        sansMotif.put("motif", " ");
        given().contentType("application/json")
                .body(sansMotif)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("motif"));

        Map<String, Object> aLEnvers = demande(List.of());
        aLEnvers.put("fermetureFin", "11:00");
        given().contentType("application/json")
                .body(aLEnvers)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("finir après"));

        Map<String, Object> dansLaBande = demande(List.of());
        dansLaBande.put("fenetres", List.of(Map.of("debut", "13:00", "fin", "15:00")));
        given().contentType("application/json")
                .body(dansLaBande)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("entièrement dans la bande"));

        Map<String, Object> sansGrille = demande(List.of());
        sansGrille.put("dates", List.of("2026-07-09"));
        given().contentType("application/json")
                .body(sansGrille)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("Aucun créneau"));

        given().when().get("/api/consignes").then().statusCode(200).body("consignes", hasSize(0));
    }

    @Test
    void presetsAreKeptOnTheEditionAndTravelWithADuplicate() {
        String id = given().contentType("application/json")
                .body(Map.of(
                        "nom", "Plan canicule",
                        "fermetureDebut", "12:00",
                        "fermetureFin", "18:00",
                        "motif", "Arrêté préfectoral canicule",
                        "fenetres", List.of(Map.of("debut", "18:00", "fin", "22:00"))))
                .when()
                .post("/api/consignes/prereglages")
                .then()
                .statusCode(200)
                .body("nom", equalTo("Plan canicule"))
                .body("fenetres", hasSize(1))
                .extract()
                .jsonPath()
                .getString("id");

        given().contentType("application/json")
                .body(Map.of("nom", "plan CANICULE", "fermetureDebut", "12:00", "motif", "x"))
                .when()
                .post("/api/consignes/prereglages")
                .then()
                .statusCode(400)
                .body("message", containsString("existe déjà"));

        given().contentType("application/json")
                .body(Map.of(
                        "nom", "Plan canicule",
                        "fermetureDebut", "13:00",
                        "fermetureFin", "18:00",
                        "motif", "Arrêté préfectoral canicule",
                        "fenetres", List.of()))
                .when()
                .put("/api/consignes/prereglages/" + id)
                .then()
                .statusCode(200)
                .body("fermetureDebut", equalTo("13:00:00"))
                .body("fenetres", hasSize(0));

        // A consigne made from the preset remembers it by name.
        Map<String, Object> corps = demande(List.of());
        corps.put("prereglage", "Plan canicule");
        given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);
        given().when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes[0].prereglage", equalTo("Plan canicule"));

        // The duplicate carries the preset, not the consigne.
        given().contentType("application/json")
                .body(Map.of("id", "COPIE", "nom", "Copie"))
                .when()
                .post("/api/editions/DEFAUT/dupliquer")
                .then()
                .statusCode(200);
        given().header("X-Edition-Id", "COPIE")
                .when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("prereglages", hasSize(1))
                .body("prereglages[0].nom", equalTo("Plan canicule"))
                .body("consignes", hasSize(0));
        given().when().delete("/api/editions/COPIE").then().statusCode(204);

        given().when().delete("/api/consignes/prereglages/" + id).then().statusCode(204);
        given().when().get("/api/consignes/prereglages").then().statusCode(200).body("size()", is(0));
        given().when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes[0].prereglage", equalTo("Plan canicule"));
    }
}
