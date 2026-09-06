package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.List;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The timeslot endpoint's refusals — what it answers to a request it cannot
 * write, and what it must keep accepting.
 */
@QuarkusTest
class CreneauResourceTest {

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    private static int countCreneaux() {
        return given().when().get("/api/creneaux").then().statusCode(200).extract().path("size()");
    }

    /**
     * A missing hour used to reach the {@code NOT NULL} column and come back as
     * a 500 with a Sentry alert: a bug's treatment for a bad request.
     */
    @Test
    void unCreneauSansHeureDeFinEstRefuseSansRienEcrire() {
        int avant = countCreneaux();

        given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"2030-01-03",
                          "heureDebut":"18:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(400)
                .body("message", containsString("heure de fin"));

        assertThat(countCreneaux()).isEqualTo(avant);
    }

    @Test
    void unCreneauSansDateEstRefuse() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "heureDebut":"18:00:00",
                          "heureFin":"22:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(400);
    }

    @Test
    void viderUnCreneauExistantParUnePutEstRefuse() {
        Object id = createCreneauNuit();
        try {
            given()
                    .contentType("application/json")
                    .body("""
                            {
                              "heureDebut":"20:00:00",
                              "heureFin":"00:00:00"
                            }
                            """)
                    .when().put("/api/creneaux/" + id)
                    .then()
                    .statusCode(400);
        } finally {
            given().when().delete("/api/creneaux/" + id).then().statusCode(204);
        }
    }

    /**
     * The case the validation must never catch: an end at or before the start is
     * how the domain writes a timeslot running past midnight — 20:00→00:00 is
     * 240 minutes, not an empty interval.
     */
    @Test
    void unCreneauDeNuitEstAccepteEtReluTelQuel() {
        Object id = createCreneauNuit();
        try {
            given()
                    .when().get("/api/creneaux")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == " + id + " }.heureDebut", startsWith("20:00"))
                    .body("find { it.id == " + id + " }.heureFin", startsWith("00:00"))
                    .body("find { it.id == " + id + " }.date", equalTo("2030-01-04"));
        } finally {
            given().when().delete("/api/creneaux/" + id).then().statusCode(204);
        }
    }

    /**
     * Deleting a timeslot a persisted plan still occupies used to come back as
     * a 500: {@code poste_affectation} is the one table referencing
     * {@code creneau} whose foreign key neither cascades nor nulls out, so the
     * database refused the delete and nobody caught it.
     *
     * <p>It bit the test suite before it bit a user — a class wiping the grid
     * to start from a deterministic state failed whenever an earlier test had
     * left a plan behind — but the endpoint was just as broken for anyone
     * clearing a grid after a solve.</p>
     */
    @Test
    void supprimerUnCreneauEmporteLesPostesQuiLOccupaient() {
        long creneauId = 9401L;
        Creneau creneau = new Creneau(creneauId, 1, LocalDate.of(2030, 6, 1),
                LocalTime.of(9, 0), LocalTime.of(12, 0));
        Stand stand = new Stand("CRN-S1", "Stand du test", Set.of(), 1, 1, false);
        Animateur animateur = new Animateur("CRN-A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("CRN-P1", stand, creneau);
        poste.setAnimateur(animateur);
        try {
            persistence.persist(new PlanningEvenement(creneau.getDate(), List.of(animateur), List.of(poste)));
            assertThat(persistence.loadPersistedPlanning().getPostes())
                    .anyMatch(p -> p.getCreneau() != null && p.getCreneau().getId() == creneauId);

            assertThat(referenceData.deleteCreneaux(List.of(creneauId))).isEqualTo(1);

            assertThat(referenceData.listCreneaux()).noneMatch(c -> c.getId() == creneauId);
            // The seat goes with the slot it was scheduled on, and only it.
            assertThat(persistence.loadPersistedPlanning().getPostes())
                    .noneMatch(p -> p.getCreneau() != null && p.getCreneau().getId() == creneauId);
        } finally {
            // The plan goes first, and unconditionally: should the delete under
            // test fail, its seat would still reference the stand and the
            // cleanup would throw in turn, reporting "Failed to delete CRN-S1"
            // over the real cause.
            persistence.persist(new PlanningEvenement(creneau.getDate(), List.of(), List.of()));
            referenceData.deleteCreneaux(List.of(creneauId));
            referenceData.deleteStand("CRN-S1");
            referenceData.deleteAnimateur("CRN-A1");
        }
    }

    private static Object createCreneauNuit() {
        return given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"2030-01-04",
                          "heureDebut":"20:00:00",
                          "heureFin":"00:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(200)
                .body("creneau.id", notNullValue())
                .body("avertissements", notNullValue())
                .extract().path("creneau.id");
    }

    /* ------------------------ The grid as a whole ------------------------ */

    private static final String REGLE_SEMAINE = """
            {
              "jours":"JOURS_SEMAINE",
              "dateDebut":"2031-03-03",
              "dateFin":"2031-03-09",
              "joursSemaine":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
              "fenetres":[{"heureDebut":"09:00:00","heureFin":"12:00:00"},{"heureDebut":"14:00:00","heureFin":"18:00:00"}]
            }
            """;

    /** The preview is the whole point of a rule that writes dozens of rows at once: it must write none. */
    @Test
    void lApercuDUneRecurrenceNEcritRien() {
        int avant = countCreneaux();

        given()
                .contentType("application/json")
                .body(REGLE_SEMAINE)
                .when().post("/api/creneaux/recurrence/apercu?mode=AMPLITUDES")
                .then()
                .statusCode(200)
                .body("nombreGeneres", equalTo(10))
                .body("creneaux.size()", equalTo(10))
                .body("controle.mode", equalTo("AMPLITUDES"))
                .body("controle.anomalies.type", org.hamcrest.Matchers.hasItem("TROU_DANS_LA_JOURNEE"));

        assertThat(countCreneaux()).isEqualTo(avant);
    }

    @Test
    void uneRecurrenceAjouteSesCreneauxEtUneRepetitionEstSignaleeEnDoublon() {
        int avant = countCreneaux();

        given().contentType("application/json").body(REGLE_SEMAINE)
                .when().post("/api/creneaux/recurrence?mode=AMPLITUDES")
                .then().statusCode(200)
                .body("nombreGeneres", equalTo(10))
                .body("creneaux[0].id", notNullValue());
        assertThat(countCreneaux()).isEqualTo(avant + 10);

        // Added, never replaced: the same rule again doubles the rows, and the verdict says so.
        given().contentType("application/json").body(REGLE_SEMAINE)
                .when().post("/api/creneaux/recurrence?mode=AMPLITUDES")
                .then().statusCode(200)
                .body("controle.anomalies.find { it.type == 'DOUBLON' }.severite", equalTo("ERREUR"));

        // Cleaned up so the other tests of the class keep their counts.
        List<Integer> ids = given().when().get("/api/creneaux").then().extract()
                .jsonPath().getList("findAll { it.date.startsWith('2031-03') }.id", Integer.class);
        for (Integer id : ids) {
            given().when().delete("/api/creneaux/" + id).then().statusCode(204);
        }
    }

    @Test
    void uneRecurrenceMalFormeeEstRefusee() {
        given()
                .contentType("application/json")
                .body("""
                        {"jours":"DATES","dates":[],"fenetres":[{"heureDebut":"09:00:00","heureFin":"12:00:00"}]}
                        """)
                .when().post("/api/creneaux/recurrence/apercu")
                .then()
                .statusCode(400)
                .body("message", containsString("DATES"));
    }

    /** No mode on the call: the edition's declared one applies, and it is what the screen persists. */
    @Test
    void leControleLitLeModeDeclareDeLEditionQuandLAppelNEnNommePas() {
        try {
            io.restassured.path.json.JsonPath parametres = given().when().get("/api/parametres-decoupage")
                    .then().statusCode(200).extract().jsonPath();
            assertThat(parametres.getString("modeGrille")).isNotBlank();
            // Its own write: the mode is not a field of the slicing payload.
            given().contentType("application/json").body(Map.of("modeGrille", "VACATIONS"))
                    .when().put("/api/parametres-decoupage/mode-grille")
                    .then().statusCode(200).body("modeGrille", equalTo("VACATIONS"));

            given().when().get("/api/creneaux/controle")
                    .then().statusCode(200)
                    .body("mode", equalTo("VACATIONS"))
                    .body("nombreCreneaux", notNullValue());
            given().when().get("/api/creneaux/controle?mode=AMPLITUDES")
                    .then().statusCode(200)
                    .body("mode", equalTo("AMPLITUDES"));
            // A mistyped mode is a bad request, not a missing page.
            given().when().get("/api/creneaux/controle?mode=VACATION").then().statusCode(400);
        } finally {
            // Restored whatever the assertions did: the edition is shared with
            // every other test of the class.
            given().contentType("application/json").body(Map.of("modeGrille", "AMPLITUDES"))
                    .when().put("/api/parametres-decoupage/mode-grille").then().statusCode(200);
        }
    }

    /** A Paramètres tab left open must not be able to revert what Créneaux declared. */
    @Test
    void enregistrerLesParametresDeDecoupageNeTouchePasAuModeDeclare() {
        try {
            given().contentType("application/json").body(Map.of("modeGrille", "VACATIONS"))
                    .when().put("/api/parametres-decoupage/mode-grille").then().statusCode(200);
            Map<String, Object> anciens = given().when().get("/api/parametres-decoupage")
                    .then().statusCode(200).extract().jsonPath().getMap("$");
            Map<String, Object> perimes = new java.util.LinkedHashMap<>(anciens);
            perimes.put("modeGrille", "AMPLITUDES");

            given().contentType("application/json").body(perimes)
                    .when().put("/api/parametres-decoupage")
                    .then().statusCode(200).body("modeGrille", equalTo("VACATIONS"));
        } finally {
            given().contentType("application/json").body(Map.of("modeGrille", "AMPLITUDES"))
                    .when().put("/api/parametres-decoupage/mode-grille").then().statusCode(200);
        }
    }

    @Test
    void leDiagnosticDecritLaGrilleSansTrancher() {
        given().when().get("/api/creneaux/diagnostic")
                .then().statusCode(200)
                .body("nombreCreneaux", notNullValue())
                .body("explication", notNullValue());
    }

    /* ------------------------ Derived from the stands ------------------------ */

    private static final String DERIVATION = """
            {"dateDebut":"2032-05-03","dateFin":"2032-05-04","heureFermeture":"20:00:00","remplacer":false}
            """;

    private void standOuvertSur(String id, String fenetres) {
        given().contentType("application/json")
                .body("""
                        {"id":"%s","nom":"%s","typologiesProposees":["STRATEGIE"],"effectifMin":1,"effectifMax":1,
                         "reserveMajeurs":false,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS","fenetres":[%s]}]}
                        """.formatted(id, id, fenetres))
                .when().post("/api/stands").then().statusCode(200);
    }

    @Test
    void lApercuDeLaDerivationNEcritRienEtNommeLesCoupures() {
        standOuvertSur("DERIV-A", "{\"heureDebut\":\"10:00:00\",\"heureFin\":\"12:00:00\"},{\"heureDebut\":\"14:00:00\"}");
        int avant = countCreneaux();

        given().contentType("application/json").body(DERIVATION)
                .when().post("/api/creneaux/derivation/apercu")
                .then().statusCode(200)
                .body("nombreGeneres", equalTo(4))
                .body("creneaux[0].heureDebut", startsWith("10:00"))
                .body("creneaux[1].heureFin", startsWith("20:00"))
                .body("coupures[0].standIds", org.hamcrest.Matchers.hasItem("DERIV-A"))
                .body("joursSansFenetre", org.hamcrest.Matchers.empty())
                .body("controle.mode", notNullValue());

        assertThat(countCreneaux()).isEqualTo(avant);
        given().when().delete("/api/stands/DERIV-A").then().statusCode(204);
    }

    @Test
    void laDerivationAjouteLesCreneauxOuRemplaceLaGrille() {
        standOuvertSur("DERIV-B", "{\"heureDebut\":\"10:00:00\"}");
        int avant = countCreneaux();

        given().contentType("application/json").body(DERIVATION)
                .when().post("/api/creneaux/derivation")
                .then().statusCode(200)
                .body("nombreGeneres", equalTo(2));
        assertThat(countCreneaux()).isEqualTo(avant + 2);

        given().contentType("application/json")
                .body(DERIVATION.replace("\"remplacer\":false", "\"remplacer\":true"))
                .when().post("/api/creneaux/derivation")
                .then().statusCode(200);
        // The whole grid is now the derived one: two days, one créneau each.
        assertThat(countCreneaux()).isEqualTo(2);

        given().when().delete("/api/stands/DERIV-B").then().statusCode(204);
        for (Integer id : given().when().get("/api/creneaux").then().extract().jsonPath().getList("id", Integer.class)) {
            given().when().delete("/api/creneaux/" + id).then().statusCode(204);
        }
    }

    @Test
    void sansAucuneFenetreLaDerivationEstRefusee() {
        given().contentType("application/json").body(DERIVATION)
                .when().post("/api/creneaux/derivation")
                .then().statusCode(400)
                .body("message", containsString("rien à dériver"));
    }
}
