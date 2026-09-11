package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * « Relancer maintenant » (issue #504) over {@code POST /api/animateurs/relances}
 * and the synthesis of {@code GET /api/animateurs/confirmations/synthese},
 * against the real database and the mock mailbox.
 *
 * <p>Alice holds a seat and has an address, Bruno holds one without an
 * address, Chloé has an address and no seat: the three people a reminder has
 * to tell apart — and Alice, reminded once, is the one it must refuse to
 * write to a second time.</p>
 */
@QuarkusTest
class RelanceResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final long CRENEAU_ID = 9401L;
    private static final String EMAIL_ALICE = "alice-relance@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ConfirmationPlanningService confirmationService;

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
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM confirmation_planning");
        Animateur alice = new Animateur("REL-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("REL-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Animateur chloe = new Animateur("REL-C", "Chloé", "Durand", LocalDate.of(1995, 3, 3), false);
        Stand standUn = new Stand("REL-S1", "Stand relance un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("REL-S2", "Stand relance deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(CRENEAU_ID, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation posteUn = new PosteAffectation("REL-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("REL-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno, chloe), List.of(posteUn, posteDeux)));

        donnerEmail("REL-A", EMAIL_ALICE);
        donnerEmail("REL-B", null);
        donnerEmail("REL-C", "chloe-relance@example.org");
        PlansPublies.publier(publication);
        mailbox.clear();
    }

    /** A published snapshot survives {@code clearDatabase()}; the suite shares one database. */
    @AfterEach
    void cleanUp() {
        forgetPublications();
    }

    /* -------------------------------- Nominal ------------------------------ */

    @Test
    void theReminderLeavesWithTheEspaceLinkAndMovesTheStatus() {
        relancer("REL-A")
                .statusCode(200)
                .body("envoyes", equalTo(List.of("REL-A")))
                .body("dejaConfirmes", empty())
                .body("sansEmail", empty())
                .body("dejaRelancesPourCettePublication", empty())
                .body("echecs", empty())
                .body("sansPoste", empty());

        List<Mail> mails = mailbox.getMailsSentTo(EMAIL_ALICE);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).contains("confirmez-vous votre planning ?");
        assertThat(mails.get(0).getText()).contains("Bonjour Alice").contains("/animateur/" + tokenOf("REL-A"));

        given().when()
                .get("/api/animateurs/confirmations")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'REL-A' }.statut", equalTo("RELANCE"));
    }

    /** The whole point of the rule: a second click does not write a second mail. */
    @Test
    void aSecondReminderAboutTheSamePublicationIsRefused() {
        relancer("REL-A").statusCode(200).body("envoyes", equalTo(List.of("REL-A")));

        relancer("REL-A")
                .statusCode(200)
                .body("envoyes", empty())
                .body("dejaRelancesPourCettePublication", equalTo(List.of("REL-A")));
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    @Test
    void whoeverConfirmedIsLeftAlone() {
        confirmationService.confirmer("REL-A");

        relancer("REL-A").statusCode(200).body("envoyes", empty()).body("dejaConfirmes", equalTo(List.of("REL-A")));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    /** One call sorts the whole selection: each person lands in exactly one list. */
    @Test
    void theReportSortsEverybodyOfTheSelection() {
        relancer("REL-A", "REL-B", "REL-C", "REL-A")
                .statusCode(200)
                .body("envoyes", equalTo(List.of("REL-A")))
                .body("sansEmail", equalTo(List.of("REL-B")))
                .body("sansPoste", equalTo(List.of("REL-C")));
        assertThat(mailbox.getTotalMessagesSent()).isEqualTo(1);
    }

    /* ------------------------------- Refusals ------------------------------ */

    @Test
    void nothingPublishedMeansNobodyToRemind() {
        forgetPublications();

        relancer("REL-A").statusCode(400).body("message", containsString("pas encore été publié"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void anUnknownIdIsRefusedAndNothingLeaves() {
        relancer("REL-A", "REL-FANTOME").statusCode(400).body("message", containsString("REL-FANTOME"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void anEmptySelectionIsRefused() {
        relancer().statusCode(400);
    }

    /* ------------------------------- Synthesis ----------------------------- */

    @Test
    void theSynthesisCountsThePeopleOfThePublishedPlanAndFollowsTheReminder() {
        given().when()
                .get("/api/animateurs/confirmations/synthese")
                .then()
                .statusCode(200)
                .body("jamaisPublie", equalTo(false))
                .body("confirmes", equalTo(0))
                .body("relances", equalTo(0))
                // Chloé has no seat: she is not silent, nothing was asked of her.
                .body("silencieux", equalTo(2))
                .body("dernierePublicationLe", org.hamcrest.Matchers.notNullValue());

        relancer("REL-A").statusCode(200);
        confirmationService.confirmer("REL-B");

        given().when()
                .get("/api/animateurs/confirmations/synthese")
                .then()
                .statusCode(200)
                .body("confirmes", equalTo(1))
                .body("relances", equalTo(1))
                .body("silencieux", equalTo(0));
    }

    @Test
    void beforeAnyPublicationTheSynthesisSaysSo() {
        forgetPublications();

        given().when()
                .get("/api/animateurs/confirmations/synthese")
                .then()
                .statusCode(200)
                .body("jamaisPublie", equalTo(true))
                .body("dernierePublicationLe", nullValue())
                .body("silencieux", equalTo(0));
    }

    /* -------------------------------- Helpers ------------------------------ */

    private static ValidatableResponse relancer(String... animateurIds) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("animateurIds", List.of(animateurIds)))
                .when()
                .post("/api/animateurs/relances")
                .then();
    }

    private void forgetPublications() {
        execute("DELETE FROM plan_snapshot");
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
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
}
