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
import dev.sylvain.planning.service.PlanPublicationService;
import dev.sylvain.planning.service.PlanningPersistenceService;
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
        oublierLesPublications();
        persistence.clearDatabase();
        persisterPlan("PUBESP-A");
        donnerEmail("PUBESP-A", EMAIL_ALICE);

        // La session de l'espace (code par e-mail) accompagne chaque requête.
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
        oublierLesPublications();
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

        // Le plan de travail bouge — assistant de réparation, échange validé,
        // solve incrémental : l'espace ne doit pas suivre tout seul.
        persisterPlan("PUBESP-B");

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

    private void persisterPlan(String titulaireId) {
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
    private void oublierLesPublications() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_snapshot");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear the plan snapshots", e);
        }
    }
}
