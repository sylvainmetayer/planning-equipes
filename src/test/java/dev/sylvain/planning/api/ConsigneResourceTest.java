package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import dev.sylvain.planning.config.DevMode;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.service.consigne.ConsigneRepository;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    @Inject
    ReferenceDataService referenceData;

    @Inject
    ConsigneRepository consigneRepository;

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
        // The duplicate one test makes: gone whether that test reached its own cleanup or not.
        given().when().delete("/api/editions/COPIE");
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
                // An inherited headcount reads back as absent, never as zero:
                // sent back as is, the row would be refused as « not positive ».
                .body("consignes[0].ouvertures[0].effectif", equalTo(null))
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

    /**
     * A consigne may restate the meal windows of its own date (issue #4): the
     * evening compensation is where lunch already happened, and the rule of
     * the edition must not be edited by hand and put back at the lifting.
     */
    @Test
    void aConsigneMayRestateTheMealWindowsOfItsDayWithAReason() {
        Map<String, Object> corps = demande(List.of(ouverture("STAND-STRAT")));
        Map<String, Object> repas = new HashMap<>();
        repas.put("soirDebut", "18:00");
        repas.put("soirFin", "20:00");
        corps.put("repas", repas);
        given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("justification"));

        repas.put("justification", "Les équipes mangent pendant la fermeture de midi");
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
                .body("consignes[0].repas.soirDebut", equalTo("18:00:00"))
                .body("consignes[0].repas.soirFin", equalTo("20:00:00"))
                .body("consignes[0].repas.midiDebut", equalTo(null))
                .body("consignes[0].repas.justification", containsString("mangent"));

        // The edition's own parameters have not moved.
        given().when()
                .get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("coupureRepasSoirDebut", equalTo("19:00:00"));

        // Lifting takes the override away with the consigne: nothing to put back.
        given().contentType("application/json")
                .body(Map.of("dates", List.of(JOUR)))
                .when()
                .post("/api/consignes/levee")
                .then()
                .statusCode(204);
        given().when().get("/api/consignes").then().statusCode(200).body("consignes", hasSize(0));
    }

    /**
     * « Jusqu'à minuit » is typed {@code 00:00} as readily as left empty, and
     * the tables only know the empty form: both must land, and read back the
     * same. Before this, {@code 00:00} passed the service and died on the
     * {@code debut < fin} check of the table — a 500 from the screen.
     */
    @Test
    void midnightAsAnEndIsStoredAsAnOpenEnd() {
        Map<String, Object> corps = demande(List.of());
        corps.put("fermetureFin", "00:00");
        corps.put("fenetres", List.of(Map.of("debut", "08:00", "fin", "10:00")));
        given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200);
        given().when().get("/api/consignes").then().statusCode(200).body("consignes[0].fermetureFin", equalTo(null));

        Map<String, Object> ouverture = ouverture("STAND-STRAT");
        ouverture.put("fin", "00:00");
        corps = demande(List.of(ouverture));
        corps.put("fenetres", List.of(Map.of("debut", "18:00", "fin", "00:00")));
        given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(200)
                .body("[0].creneauxAAjouter", hasSize(1))
                .body("[0].creneauxAAjouter[0].fin", equalTo(null));
        given().when()
                .get("/api/consignes")
                .then()
                .statusCode(200)
                .body("consignes[0].fermetureFin", equalTo("16:00:00"))
                .body("consignes[0].fenetres[0].fin", equalTo(null))
                .body("consignes[0].ouvertures[0].fin", equalTo(null));
        // The créneau the opening needed runs to midnight, as any créneau crossing it does.
        assertThat(creneaux().getList("heureDebut")).contains("18:00:00");

        given().contentType("application/json")
                .body(Map.of(
                        "nom", "Journée entière",
                        "fermetureDebut", "12:00",
                        "fermetureFin", "00:00",
                        "motif", "Fermeture totale",
                        "fenetres", List.of(Map.of("debut", "14:00", "fin", "00:00"))))
                .when()
                .post("/api/consignes/prereglages")
                .then()
                .statusCode(400)
                .body("message", containsString("entièrement dans la bande"));
        given().contentType("application/json")
                .body(Map.of(
                        "nom", "Journée entière",
                        "fermetureDebut", "12:00",
                        "fermetureFin", "00:00",
                        "motif", "Fermeture totale",
                        "fenetres", List.of(Map.of("debut", "08:00", "fin", "10:00"))))
                .when()
                .post("/api/consignes/prereglages")
                .then()
                .statusCode(200)
                .body("fermetureFin", equalTo(null))
                .body("fenetres[0].fin", equalTo("10:00:00"));
    }

    /** A meal window the break does not fit in would leave the date with no rule at all: refused. */
    @Test
    void aRestatedMealWindowShorterThanTheBreakIsRefused() {
        Map<String, Object> corps = demande(List.of(ouverture("STAND-STRAT")));
        Map<String, Object> repas = new HashMap<>();
        repas.put("soirDebut", "19:00");
        repas.put("soirFin", "19:30");
        repas.put("justification", "Les équipes mangent pendant la fermeture");
        corps.put("repas", repas);
        given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(400)
                .body("message", containsString("plus courte que la coupure"));
        given().when().get("/api/consignes").then().statusCode(200).body("consignes", hasSize(0));
    }

    /**
     * Five dates in one gesture, the third refused by the database: the
     * créneaux and rows of the first two must not survive the failure — the
     * screen would otherwise show two dates under consigne out of a request
     * that reported none.
     */
    @Test
    void aFailureOnTheThirdOfFiveDatesLeavesNothingBehind() {
        LocalDate jour = LocalDate.parse(JOUR);
        for (int i = 1; i <= 4; i++) {
            referenceData.createCreneau(
                    new Creneau(null, 0, jour.plusDays(i), LocalTime.of(9, 0), LocalTime.of(13, 0)));
        }
        int creneauxAvant = creneaux().getList("id").size();
        ConsigneRepository real = ClientProxy.unwrap(consigneRepository);
        QuarkusMock.installMockForType(new FailingOnThirdSave(real), ConsigneRepository.class);

        Map<String, Object> corps = demande(List.of(ouverture("STAND-STRAT")));
        corps.put(
                "dates",
                List.of(
                        JOUR,
                        jour.plusDays(1).toString(),
                        jour.plusDays(2).toString(),
                        jour.plusDays(3).toString(),
                        jour.plusDays(4).toString()));
        given().contentType("application/json")
                .body(corps)
                .when()
                .post("/api/consignes")
                .then()
                .statusCode(500);

        given().when().get("/api/consignes").then().statusCode(200).body("consignes", hasSize(0));
        assertThat(creneaux().getList("id")).hasSize(creneauxAvant);
        assertThat(creneaux().getList("heureDebut")).doesNotContain("18:00:00");
    }

    /** The real repository, except that the third consigne written in a transaction fails. */
    private static final class FailingOnThirdSave extends ConsigneRepository {
        private final ConsigneRepository real;
        private int saves;

        FailingOnThirdSave(ConsigneRepository real) {
            super(null);
            this.real = real;
        }

        @Override
        public void save(Connection connection, ConsigneEdition consigne) throws SQLException {
            if (++saves == 3) {
                throw new IllegalStateException("the third date fails");
            }
            real.save(connection, consigne);
        }

        @Override
        public void save(ConsigneEdition consigne) {
            real.save(consigne);
        }

        @Override
        public List<ConsigneEdition> list() {
            return real.list();
        }

        @Override
        public Optional<ConsigneEdition> find(LocalDate date) {
            return real.find(date);
        }

        @Override
        public Set<Long> creneauxAjoutes() {
            return real.creneauxAjoutes();
        }

        @Override
        public boolean delete(LocalDate date) {
            return real.delete(date);
        }

        @Override
        public List<PrereglageConsigne> listPrereglages() {
            return real.listPrereglages();
        }

        @Override
        public void savePrereglage(PrereglageConsigne prereglage) {
            real.savePrereglage(prereglage);
        }

        @Override
        public boolean deletePrereglage(String id) {
            return real.deletePrereglage(id);
        }
    }

    @Test
    void presetsAreKeptOnTheEditionAndTravelWithADuplicate() {
        String id = given().contentType("application/json")
                .body(Map.of(
                        "nom", "Plan canicule",
                        "fermetureDebut", "12:00",
                        "fermetureFin", "18:00",
                        "motif", "Arrêté préfectoral canicule",
                        "fenetres", List.of(Map.of("debut", "18:00", "fin", "22:00")),
                        "repas",
                                Map.of(
                                        "soirDebut", "18:00",
                                        "soirFin", "22:00",
                                        "justification", "Les équipes mangent pendant la fermeture")))
                .when()
                .post("/api/consignes/prereglages")
                .then()
                .statusCode(200)
                .body("nom", equalTo("Plan canicule"))
                .body("fenetres", hasSize(1))
                .body("repas.soirDebut", equalTo("18:00:00"))
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
                        "fenetres", List.of(),
                        "repas",
                                Map.of(
                                        "soirDebut", "18:00",
                                        "soirFin", "22:00",
                                        "justification", "Les équipes mangent pendant la fermeture")))
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
                .body("prereglages[0].repas.soirFin", equalTo("22:00:00"))
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
