package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;

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
        declarerPauseSurPoste(true);
    }

    @Test
    void sansPlanPersisteLeRapportEstVideEtLeDitSansErreur() {
        given().when().get("/api/pauses")
                .then()
                .statusCode(200)
                .body("journeesAnalysees", equalTo(0))
                .body("pausesDues", equalTo(0))
                .body("journees.size()", equalTo(0));
    }

    @Test
    void leRapportSitueLaPauseSonStandEtSonRelaisSousLaDeclarationCourante() {
        Animateur alice = new Animateur("PAUSE-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PAUSE-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PAUSE-S1", "Stand des pauses", Set.of(), 2, 2, false);
        Creneau longue = new Creneau(9501L, 1, JOUR, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation posteAlice = new PosteAffectation("PAUSE-P1", stand, longue);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("PAUSE-P2", stand, longue);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));

        given().when().get("/api/pauses")
                .then()
                .statusCode(200)
                .body("pauseSurPoste", equalTo(true))
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

        // The declaration withdrawn: the same breaks, flagged as not covered.
        declarerPauseSurPoste(false);
        given().when().get("/api/pauses")
                .then()
                .statusCode(200)
                .body("pauseSurPoste", equalTo(false))
                .body("pausesDues", equalTo(2));
    }

    private static void declarerPauseSurPoste(boolean declare) {
        String courant = given().when().get("/api/parametres-legaux").then().statusCode(200).extract().asString();
        String modifie = courant.replaceAll("\"pauseSurPoste\":(true|false)", "\"pauseSurPoste\":" + declare);
        given().contentType(ContentType.JSON).body(modifie)
                .when().put("/api/parametres-legaux")
                .then().statusCode(200);
    }
}
