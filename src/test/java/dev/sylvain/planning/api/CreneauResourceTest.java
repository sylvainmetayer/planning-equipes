package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import java.time.LocalDate;
import java.time.LocalTime;
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
                .body("id", notNullValue())
                .extract().path("id");
    }
}
