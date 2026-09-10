package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * The promise of issue #245, seen from the espace animateur: what you see is
 * what somebody sent you. Nothing before the first publication, and nothing
 * new until the next one — however much the working plan moved in between.
 */
@QuarkusTest
class EspacePlanPublieTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9401L;
    private static final String EMAIL_ALICE = "espace-alice@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        mailbox.clear();
        forgetPublications();
        persistence.clearDatabase();
        persistPlan("PUBESP-A");
        donnerEmail("PUBESP-A", EMAIL_ALICE);

        // The espace session (e-mail code flow) rides on every request.
        RestAssured.requestSpecification = null;
        String session = EspaceSessions.open(mailbox, tokenOf("PUBESP-A"), EMAIL_ALICE);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-espace", session)
                .build();
        mailbox.clear();
    }

    @AfterEach
    void nettoyer() {
        RestAssured.requestSpecification = null;
        forgetPublications();
    }

    @Test
    void tantQueRienNEstPublieLEspaceNeMontreAucunPlanning() {
        given().when().get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("publieLe", nullValue())
                .body("postes.size()", equalTo(0));
    }

    @Test
    void aPresPublicationLEspaceMontreLePlanningEtSaDate() {
        publication.publier();

        given().when().get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("publieLe", containsString("20"))
                .body("postes.size()", equalTo(1))
                .body("postes[0].standNom", equalTo("Stand espace un"));
    }

    @Test
    void unPlanDeTravailModifieNeBougePasLEspaceAvantLaProchainePublication() {
        publication.publier();

        // The working plan moves — repair assistant, validated échange,
        // incremental solve: the espace must not follow on its own.
        persistPlan("PUBESP-B");

        given().when().get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1))
                .body("postes[0].standNom", equalTo("Stand espace un"));

        publication.publier();

        given().when().get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(0));
    }

    @Test
    void leRenvoiIndividuelRefuseTantQueRienNAEtePublie() {
        given().contentType(ContentType.JSON)
                .when().post("/api/planning/envoi/animateur/PUBESP-A")
                .then()
                .statusCode(400)
                .body("message", containsString("pas encore été publié"));
    }

    /* -------------------------------- Helpers ------------------------------ */

    @Test
    void lEspaceAnnonceLaPauseQueLaJourneePubliseDoit() {
        Animateur alice = new Animateur("PUBESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUBESP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PUBESP-S1", "Stand espace un", Set.of(), 2, 2, false);
        Creneau longue = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(13, 0), LocalTime.of(20, 0));
        PosteAffectation posteAlice = new PosteAffectation("PUBESP-P1", stand, longue);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("PUBESP-P2", stand, longue);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
        publication.publier();

        given().when().get("/api/espace-animateur/" + tokenOf("PUBESP-A"))
                .then()
                .statusCode(200)
                .body("postes.size()", equalTo(1))
                .body("pauses.size()", equalTo(1))
                .body("pauses[0].date", equalTo(JOUR.toString()))
                .body("pauses[0].heureLimite", equalTo("19:00:00"))
                .body("pauses[0].debut", equalTo("19:00:00"))
                .body("pauses[0].fin", equalTo("19:20:00"))
                .body("pauses[0].dureeMinutes", equalTo(20))
                .body("pauses[0].standNom", equalTo("Stand espace un"))
                .body("pauses[0].relaisDisponible", equalTo(true));
    }

    private void persistPlan(String titulaireId) {
        Animateur alice = new Animateur("PUBESP-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PUBESP-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PUBESP-S1", "Stand espace un", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));
        PosteAffectation poste = new PosteAffectation("PUBESP-P1", stand, creneau);
        poste.setAnimateur("PUBESP-A".equals(titulaireId) ? alice : bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(poste)));
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }

    private String tokenOf(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow()
                .getAccessToken();
    }

    /** A published snapshot survives {@code clearDatabase()} — see PublicationResourceTest. */
    private void forgetPublications() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }
}
