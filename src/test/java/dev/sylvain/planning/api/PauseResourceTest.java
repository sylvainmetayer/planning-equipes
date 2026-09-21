package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/pauses} reads the persisted plan under the organiser's
 * current declaration — a read-out, so none of these tests ever solves.
 */
@QuarkusTest
class PauseResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);

    @Inject
    PlanningPersistenceService persistence;

    @BeforeEach
    void seed() {
        persistence.clearDatabase();
    }

    @Test
    void sansPlanPersisteLeRapportEstVideEtLeDitSansErreur() {
        given().when()
                .get("/api/pauses")
                .then()
                .statusCode(200)
                .body("journeesAnalysees", equalTo(0))
                .body("pausesDues", equalTo(0))
                .body("journees.size()", equalTo(0));
    }

    /**
     * Two colleagues on a seven-hour stand relay each other: each owes a break
     * at 19:00, and the other is there to cover it. There is no mode to declare
     * any more (ADR 0048) — the report reads the current parameters and says
     * who can relay whom.
     */
    @Test
    void leRapportSitueLaPauseSonStandEtSonRelais() {
        Animateur alice = new Animateur("PAUSE-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PAUSE-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PAUSE-S1", "Stand des pauses", Set.of(), 2, 2, false);
        Creneau longue = new Creneau(9501L, 1, JOUR, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation posteAlice = new PosteAffectation("PAUSE-P1", stand, longue);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("PAUSE-P2", stand, longue);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));

        given().when()
                .get("/api/pauses")
                .then()
                .statusCode(200)
                .body("journeesAnalysees", equalTo(2))
                .body("pausesDues", equalTo(2))
                .body("relaisManquants", equalTo(0))
                .body("journees.size()", equalTo(2))
                .body("journees[0].animateurId", equalTo("PAUSE-A"))
                .body("journees[0].sequences[0].debut", equalTo("13:00:00"))
                .body("journees[0].sequences[0].fin", equalTo("20:00:00"))
                .body("journees[0].sequences[0].pausesDues[0].heureLimite", equalTo("19:00:00"))
                .body("journees[0].sequences[0].pausesDues[0].standId", equalTo("PAUSE-S1"))
                .body("journees[0].sequences[0].pausesDues[0].relais[0].animateurId", equalTo("PAUSE-B"));
    }

    /** Alone on the stand, the same seven hours owe a break nobody can take. */
    @Test
    void uneJourneeSeulSurSonStandCompteSaPauseSansRelais() {
        Animateur alice = new Animateur("PAUSE-SEUL", "Alice", "Seule", LocalDate.of(1990, 1, 1), false);
        Stand stand = new Stand("PAUSE-S2", "Stand à une place", Set.of(), 1, 1, false);
        Creneau longue = new Creneau(9502L, 1, JOUR, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation poste = new PosteAffectation("PAUSE-P3", stand, longue);
        poste.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice), List.of(poste)));

        given().when()
                .get("/api/pauses")
                .then()
                .statusCode(200)
                .body("pausesDues", equalTo(1))
                .body("relaisManquants", equalTo(1))
                .body("journees[0].sequences[0].pausesDues[0].relaisDisponible", equalTo(false))
                .body("message", org.hamcrest.Matchers.containsString("sans relais possible"));
    }
}
