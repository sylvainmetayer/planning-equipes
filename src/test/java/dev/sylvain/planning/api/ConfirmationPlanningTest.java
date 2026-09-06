package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
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
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConfirmationPlanningService;
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
 * « J'ai lu et je serai là » (issue #293), end to end: the click, what the
 * admin column reads back, and — the part that is easy to get wrong — who a
 * republication sends back to NON_VU.
 */
@QuarkusTest
class ConfirmationPlanningTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final String EMAIL_ALICE = "confirmation-alice@example.org";

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

    @Inject
    ConfirmationPlanningService confirmationService;

    @BeforeEach
    void seed() {
        mailbox.clear();
        forgetPublications();
        forgetConfirmations();
        persistence.clearDatabase();
        persistPlan("CONF-S1", "CONF-S2");
        donnerEmail("CONF-A", EMAIL_ALICE);

        RestAssured.requestSpecification = null;
        String session = EspaceSessions.open(mailbox, tokenOf("CONF-A"), EMAIL_ALICE);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addCookie("planning-espace", session)
                .build();
        mailbox.clear();
    }

    @AfterEach
    void nettoyer() {
        RestAssured.requestSpecification = null;
        forgetPublications();
        forgetConfirmations();
    }

    @Test
    void confirmingBeforeAnyPublicationIsRefused() {
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("CONF-A") + "/confirmation")
                .then()
                .statusCode(409);
    }

    @Test
    void confirmingMovesTheStatusAndStampsIt() {
        publication.publier();

        given().when().get("/api/espace-animateur/" + tokenOf("CONF-A"))
                .then()
                .statusCode(200)
                .body("statutConfirmation", equalTo("NON_VU"))
                .body("confirmeLe", nullValue());

        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("CONF-A") + "/confirmation")
                .then()
                .statusCode(200)
                .body("statut", equalTo("CONFIRME"))
                .body("confirmeLe", notNullValue());

        given().when().get("/api/espace-animateur/" + tokenOf("CONF-A"))
                .then()
                .statusCode(200)
                .body("statutConfirmation", equalTo("CONFIRME"));
    }

    @Test
    void clickingTwiceKeepsTheFirstDate() {
        publication.publier();
        String premiere = given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("CONF-A") + "/confirmation")
                .then().statusCode(200).extract().path("confirmeLe");

        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("CONF-A") + "/confirmation")
                .then()
                .statusCode(200)
                .body("confirmeLe", equalTo(premiere));
    }

    @Test
    void theAdminColumnReadsBackTheAnswerAndWhoHadNothingToConfirm() {
        publication.publier();
        given().contentType(ContentType.JSON)
                .when().post("/api/espace-animateur/" + tokenOf("CONF-A") + "/confirmation")
                .then().statusCode(200);

        given().when().get("/api/animateurs/confirmations")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'CONF-A' }.statut", equalTo("CONFIRME"))
                .body("find { it.animateurId == 'CONF-A' }.affecte", equalTo(true))
                // Bruno holds a seat and has not answered: silent.
                .body("find { it.animateurId == 'CONF-B' }.statut", equalTo("NON_VU"))
                .body("find { it.animateurId == 'CONF-B' }.affecte", equalTo(true))
                // Carla holds no seat in the published plan: she is not silent,
                // she was never asked — the column must not count her among the
                // people to chase.
                .body("find { it.animateurId == 'CONF-C' }.affecte", equalTo(false));
    }

    /**
     * Carla holds no seat in the published plan, so there is nothing for her to
     * acknowledge. Accepting the click would store a row the admin column then
     * shows as « — » (because {@code affecte} is false): she would believe she
     * had answered, and nobody would ever see it.
     */
    @Test
    void confirmingWithoutAnySeatIsRefused() {
        publication.publier();

        // Asserted on the service rather than over HTTP: the espace session of
        // this fixture belongs to Alice, so Carla's route would answer 401 and
        // prove nothing about the rule being tested.
        assertThatThrownBy(() -> confirmationService.confirmer("CONF-C"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("aucun poste");

        given().when().get("/api/animateurs/confirmations")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'CONF-C' }.statut", equalTo("NON_VU"));
    }

    /**
     * The point of the feature, and the one behaviour a naive implementation
     * gets wrong: republishing resets the people whose own schedule moved, and
     * leaves the others' answer where it was.
     */
    @Test
    void republishingOnlyResetsThePeopleWhoseScheduleMoved() {
        publication.publier();
        confirmFor("CONF-A");
        confirmFor("CONF-B");

        // Bruno's seat moves to another stand; Alice's day does not budge.
        persistPlan("CONF-S1", "CONF-S3");
        publication.publier();

        given().when().get("/api/animateurs/confirmations")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'CONF-A' }.statut", equalTo("CONFIRME"))
                .body("find { it.animateurId == 'CONF-B' }.statut", equalTo("NON_VU"));
    }

    /* -------------------------------- Helpers ------------------------------ */

    /** Alice on {@code standAlice}, Bruno on {@code standBruno}, same day and hours. */
    private void persistPlan(String standAlice, String standBruno) {
        Animateur alice = new Animateur("CONF-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("CONF-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur carla = new Animateur("CONF-C", "Carla", "Roux", LocalDate.of(1994, 3, 3), false);
        Stand un = new Stand("CONF-S1", "Stand confirmation un", Set.of("STRATEGIE"), 1, 1, false);
        Stand deux = new Stand("CONF-S2", "Stand confirmation deux", Set.of("STRATEGIE"), 1, 1, false);
        Stand trois = new Stand("CONF-S3", "Stand confirmation trois", Set.of("STRATEGIE"), 1, 1, false);
        Creneau creneau = new Creneau(9601L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));

        PosteAffectation posteAlice = new PosteAffectation("CONF-P1", stand(standAlice, un, deux, trois), creneau);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("CONF-P2", stand(standBruno, un, deux, trois), creneau);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno, carla),
                List.of(posteAlice, posteBruno)));
    }

    private static Stand stand(String id, Stand... candidats) {
        for (Stand candidat : candidats) {
            if (candidat.getId().equals(id)) {
                return candidat;
            }
        }
        throw new IllegalArgumentException("Unknown stand " + id);
    }

    /** Confirms without going through a session: the espace flow is covered above. */
    private void confirmFor(String animateurId) {
        confirmationService.confirmer(animateurId);
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
        execute("DELETE FROM plan_snapshot");
    }

    private void forgetConfirmations() {
        execute("DELETE FROM confirmation_planning");
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
    }
}
