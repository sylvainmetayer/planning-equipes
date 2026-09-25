package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.notification.RappelVeilleJob;
import dev.sylvain.planning.service.notification.RelanceConfirmationJob;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.ClientProxy;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.vertx.ext.mail.SMTPException;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What became of a mail to an animateur (the {@code envoi_mail} journal): a
 * send the relay refused is recorded with its category, the Animateurs page
 * reads « échec d'envoi » rather than « silencieux », and the reminders stop
 * insisting on a refused address until the address changes.
 *
 * <p>Alice holds a seat and has an address, Bruno holds one without an
 * address. The failures come from a {@link MailService} that throws what the
 * blocking mailer throws when the relay answers.</p>
 */
@QuarkusTest
class EnvoiMailResourceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 12);
    private static final String EMAIL_ALICE = "alice-envoi@example.org";

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    RelanceConfirmationJob relanceNuit;

    @Inject
    RappelVeilleJob rappelVeille;

    @Inject
    Event<Notification> notifications;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        execute("DELETE FROM plan_snapshot");
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM confirmation_planning");
        execute("DELETE FROM envoi_mail");
        Animateur alice = new Animateur("ENV-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("ENV-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand standUn = new Stand("ENV-S1", "Stand envoi un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("ENV-S2", "Stand envoi deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(9411L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation posteUn = new PosteAffectation("ENV-P1", standUn, creneau);
        posteUn.setAnimateur(alice);
        PosteAffectation posteDeux = new PosteAffectation("ENV-P2", standDeux, creneau);
        posteDeux.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));
        giveEmail("ENV-A", EMAIL_ALICE);
        giveEmail("ENV-B", null);
        PlansPublies.publier(publication);
        mailbox.clear();
    }

    @AfterEach
    void cleanUp() {
        execute("DELETE FROM plan_snapshot");
        execute("DELETE FROM envoi_mail");
    }

    @Test
    void aSendRefusedByTheRelayIsRecordedWithItsCategory() {
        failWith(550);

        relancer("ENV-A").statusCode(200).body("echecs", equalTo(List.of("ENV-A")));

        assertThat(journal("ENV-A"))
                .containsExactly("PLANNING_PUBLIE|ENVOYE|null", "RELANCE_MANUELLE|ECHEC|ADRESSE_REFUSEE");
        assertThat(journal("ENV-B")).containsExactly("PLANNING_PUBLIE|SANS_EMAIL|null");
    }

    @Test
    void thePageReadsAFailedSendNotASilenceAndTheSynthesisCountsItApart() {
        failWith(550);
        relancer("ENV-A").statusCode(200);

        given().when()
                .get("/api/animateurs/confirmations")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'ENV-A' }.dernierEnvoi.statut", equalTo("ECHEC"))
                .body("find { it.animateurId == 'ENV-A' }.dernierEnvoi.categorieEchec", equalTo("ADRESSE_REFUSEE"))
                .body("find { it.animateurId == 'ENV-A' }.dernierEnvoi.type", equalTo("RELANCE_MANUELLE"))
                // No address is not a failure: Bruno stays « sans e-mail ».
                .body("find { it.animateurId == 'ENV-B' }.dernierEnvoi.statut", equalTo("SANS_EMAIL"));
        given().when()
                .get("/api/animateurs/confirmations/synthese")
                .then()
                .statusCode(200)
                .body("echecsEnvoi", equalTo(1))
                .body("silencieux", equalTo(1));
    }

    @Test
    void theManualReminderDoesNotInsistOnARefusedAddressUntilTheAddressChanges() {
        failWith(550);
        relancer("ENV-A").statusCode(200);

        relancer("ENV-A").statusCode(200).body("envoyes", empty()).body("adresseRefusee", equalTo(List.of("ENV-A")));
        assertThat(mailbox.getTotalMessagesSent()).isZero();

        giveEmail("ENV-A", "alice-corrigee@example.org");

        relancer("ENV-A").statusCode(200).body("envoyes", equalTo(List.of("ENV-A")));
        given().when()
                .get("/api/animateurs/confirmations")
                .then()
                .body("find { it.animateurId == 'ENV-A' }.dernierEnvoi.statut", equalTo("ENVOYE"));
    }

    @Test
    void theNightDoesNotWriteToARefusedAddressAndACorrectedAddressRestoresIt() {
        failWith(550);
        relancer("ENV-A").statusCode(200);
        Instant plusTard = Instant.now().plus(Duration.ofDays(10));

        assertThat(relanceNuit.run(armed(), plusTard)).isZero();
        assertThat(mailbox.getTotalMessagesSent()).isZero();

        giveEmail("ENV-A", "alice-corrigee@example.org");

        assertThat(relanceNuit.run(armed(), plusTard)).isEqualTo(1);
        assertThat(mailbox.getMailsSentTo("alice-corrigee@example.org")).hasSize(1);
        assertThat(journal("ENV-A")).last().isEqualTo("RELANCE_NUIT|ENVOYE|null");
    }

    @Test
    void aTemporaryFailureDoesNotHoldTheNextReminderBack() {
        failWith(450);
        relancer("ENV-A").statusCode(200).body("echecs", equalTo(List.of("ENV-A")));

        assertThat(journal("ENV-A")).last().isEqualTo("RELANCE_MANUELLE|ECHEC|TEMPORAIRE");
        assertThat(relanceNuit.run(armed(), Instant.now().plus(Duration.ofDays(10))))
                .isEqualTo(1);
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    @Test
    void theJournalHoldsNoAddressAndNoContent() throws SQLException {
        List<String> colonnes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT column_name FROM information_schema.columns WHERE table_name = 'envoi_mail'")) {
            while (rs.next()) {
                colonnes.add(rs.getString(1));
            }
        }
        assertThat(colonnes)
                .containsExactlyInAnyOrder(
                        "edition_id", "id", "animateur_id", "type", "statut", "categorie_echec", "envoye_le");
    }

    @Test
    void beforeAnySendNothingIsReported() {
        execute("DELETE FROM envoi_mail");

        given().when()
                .get("/api/animateurs/confirmations")
                .then()
                .body("find { it.animateurId == 'ENV-A' }.dernierEnvoi", nullValue());
    }

    /* ------------------------- Every send skips it ------------------------- */

    @Test
    void savingTheFicheWithTheSameAddressKeepsTheBlockAndChangingItLiftsIt() {
        failWith(550);
        relancer("ENV-A").statusCode(200);

        Animateur alice = fiche("ENV-A");
        alice.setPrenom("Alicia");
        referenceData.updateAnimateur("ENV-A", alice);

        relancer("ENV-A").statusCode(200).body("adresseRefusee", equalTo(List.of("ENV-A")));
        given().when()
                .get("/api/animateurs/confirmations")
                .then()
                .body("find { it.animateurId == 'ENV-A' }.dernierEnvoi.categorieEchec", equalTo("ADRESSE_REFUSEE"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();

        giveEmail("ENV-A", "alice-corrigee@example.org");

        relancer("ENV-A").statusCode(200).body("envoyes", equalTo(List.of("ENV-A")));
    }

    @Test
    void thePublicationDoesNotAttemptARefusedAddressAndSaysSo() {
        refuseAddress("ENV-A");
        movePlan();

        PlanPublicationService.RapportPublication rapport = publication.publier();

        assertThat(rapport.adresseRefusee()).containsExactly("Alice Martin");
        assertThat(rapport.echecs()).isEmpty();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
        given().when()
                .get("/api/planning/publication/destinataires")
                .then()
                .statusCode(200)
                .body("find { it.animateurId == 'ENV-A' }.statut", equalTo("ADRESSE_REFUSEE"));
        // Nothing attempted, nothing journalled: the last line stays the refusal.
        assertThat(journal("ENV-A")).last().isEqualTo("PLANNING_PUBLIE|ECHEC|ADRESSE_REFUSEE");
    }

    @Test
    void theIndividualPlanningIsRefusedWithoutNamingAnybody() {
        refuseAddress("ENV-A");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/planning/envoi/animateur/ENV-A")
                .then()
                .statusCode(409)
                .body("message", containsString("corrigez l'adresse"))
                .body("message", not(containsString("Alice")));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void theAccessCodeIsRefusedLikeAMissingAddress() {
        refuseAddress("ENV-A");

        given().contentType(ContentType.JSON)
                .when()
                .post("/api/espace-animateur/" + accessToken("ENV-A") + "/code")
                .then()
                .statusCode(400)
                .body("message", containsString("contactez l'organisation"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
        assertThat(journal("ENV-A")).last().isEqualTo("PLANNING_PUBLIE|ECHEC|ADRESSE_REFUSEE");
    }

    @Test
    void theInvitationSkipsARefusedAddressAndListsItApart() {
        refuseAddress("ENV-A");
        try {
            given().contentType(ContentType.JSON)
                    .body("{\"collecteOuverte\":true,\"prevenirAnimateurs\":true}")
                    .when()
                    .put("/api/disponibilites/configuration")
                    .then()
                    .statusCode(200)
                    .body("invitation.adresseRefusee", equalTo(List.of("Alice Martin")));
            assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
        } finally {
            given().contentType(ContentType.JSON)
                    .body("{\"collecteOuverte\":false}")
                    .when()
                    .put("/api/disponibilites/configuration")
                    .then()
                    .statusCode(200);
        }
    }

    @Test
    void theDayBeforeReminderIsSkippedAndAlertedOnce() {
        refuseAddress("ENV-A");
        ZonedDateTime veille = JOUR.minusDays(1).atTime(19, 0).atZone(ZoneId.systemDefault());

        rappelVeille.run(armed(), veille);
        rappelVeille.run(armed(), veille.plusHours(1));

        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM notification_planifiee WHERE type = 'RAPPEL_VEILLE_INJOIGNABLE'"
                        + " AND animateur_id = 'ENV-A' AND libelle LIKE '%refusé%'"))
                .isEqualTo(1);
        assertThat(journal("ENV-A")).last().isEqualTo("PLANNING_PUBLIE|ECHEC|ADRESSE_REFUSEE");
    }

    @Test
    void aSwapNotificationToARefusedAddressIsSkippedWithoutALine() {
        refuseAddress("ENV-A");

        notifications.fire(new Notification.TargetSolicited("ENV-A", EMAIL_ALICE, "Bruno Petit", 1));

        assertThat(mailbox.getTotalMessagesSent()).isZero();
        assertThat(journal("ENV-A"))
                .containsExactly("PLANNING_PUBLIE|ENVOYE|null", "PLANNING_PUBLIE|ECHEC|ADRESSE_REFUSEE");
    }

    @Test
    void aSwapNotificationIsJournalledOnceItLeaves() {
        notifications.fire(new Notification.DemandeDeclinee("ENV-A", EMAIL_ALICE, "Bruno Petit", "samedi 14h-16h"));

        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(journal("ENV-A")).last().isEqualTo("ECHANGE_DECLINEE|ENVOYE|null");
    }

    /* ------------------------------ The resend ----------------------------- */

    @Test
    void aTemporaryFailureIsSentAgainAndLeavesTheScreen() {
        failWith(450);
        relancer("ENV-A").statusCode(200).body("echecs", equalTo(List.of("ENV-A")));

        renvoyer()
                .statusCode(200)
                .body("renvoyes", equalTo(List.of("ENV-A")))
                .body("echecs", empty())
                .body("nonRenvoyables", empty());

        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(journal("ENV-A")).last().isEqualTo("RELANCE_MANUELLE|ENVOYE|null");
        given().when().get("/api/animateurs/confirmations/synthese").then().body("echecsEnvoi", equalTo(0));
    }

    @Test
    void aRefusedAddressIsNotSentAgain() {
        failWith(550);
        relancer("ENV-A").statusCode(200);

        renvoyer().statusCode(200).body("renvoyes", empty()).body("nonRenvoyables", empty());

        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    @Test
    void aFailedPlanningIsSentAgainAsThePublishedPlanning() {
        insertLine("ENV-A", "PLANNING_PUBLIE", "TEMPORAIRE");

        renvoyer().statusCode(200).body("renvoyes", equalTo(List.of("ENV-A")));

        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(journal("ENV-A")).last().isEqualTo("PLANNING_INDIVIDUEL|ENVOYE|null");
    }

    @Test
    void anAccessCodeIsNotResendable() {
        insertLine("ENV-A", "CODE_ACCES", "TEMPORAIRE");

        renvoyer()
                .statusCode(200)
                .body("renvoyes", empty())
                .body("nonRenvoyables.animateurId", hasItem("ENV-A"))
                .body("nonRenvoyables.find { it.animateurId == 'ENV-A' }.motif", equalTo("TYPE_NON_RENVOYABLE"));
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }

    /* ---------------------- A failed night is not « déjà relancé » ---------------------- */

    @Test
    void afterAFailedNightTheHandMayRemindAndTheNightDoesNotInsist() {
        failNextSendAtTheRelay(450);
        Instant plusTard = Instant.now().plus(Duration.ofDays(10));

        assertThat(relanceNuit.run(armed(), plusTard)).isZero();
        assertThat(journal("ENV-A")).last().isEqualTo("RELANCE_NUIT|ECHEC|TEMPORAIRE");
        given().when()
                .get("/api/animateurs/confirmations")
                .then()
                .body("find { it.animateurId == 'ENV-A' }.statut", equalTo("NON_VU"));

        // The next hourly run does not try the same publication again.
        assertThat(relanceNuit.run(armed(), plusTard.plus(Duration.ofHours(1)))).isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();

        relancer("ENV-A").statusCode(200).body("envoyes", equalTo(List.of("ENV-A")));
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    /* -------------------------------- Helpers ------------------------------ */

    /**
     * The next mail through {@link MailMetrics} — the notification path, the
     * night's — fails the way the relay answered; the ones after it leave.
     */
    private void failNextSendAtTheRelay(int code) {
        MailMetrics real = ClientProxy.unwrap(realMetrics);
        QuarkusMock.installMockForType(
                new MailMetrics(new SimpleMeterRegistry()) {
                    private boolean failed;

                    @Override
                    public void send(Mailer mailer, String template, Mail mail) {
                        if (!failed) {
                            failed = true;
                            throw new CompletionException(new SMTPException(
                                    code + " refused", code, List.of(code + " refused"), code >= 500));
                        }
                        real.send(mailer, template, mail);
                    }
                },
                MailMetrics.class);
    }

    @Inject
    MailMetrics realMetrics;

    /** A refusal of the relay on the last send, as the journal records it. */
    private void refuseAddress(String animateurId) {
        insertLine(animateurId, "PLANNING_PUBLIE", "ADRESSE_REFUSEE");
    }

    private void insertLine(String animateurId, String type, String categorie) {
        execute("INSERT INTO envoi_mail (edition_id, animateur_id, type, statut, categorie_echec)"
                + " SELECT edition_id, id, '" + type + "', 'ECHEC', '" + categorie + "' FROM animateur WHERE id = '"
                + animateurId + "'");
    }

    /** Alice and Bruno swap stands: both are concerned by the next publication. */
    private void movePlan() {
        Animateur alice = fiche("ENV-A");
        Animateur bruno = fiche("ENV-B");
        Stand standUn = new Stand("ENV-S1", "Stand envoi un", Set.of(), 1, 1, false);
        Stand standDeux = new Stand("ENV-S2", "Stand envoi deux", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(9411L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(16, 0));
        PosteAffectation posteUn = new PosteAffectation("ENV-P1", standUn, creneau);
        posteUn.setAnimateur(bruno);
        PosteAffectation posteDeux = new PosteAffectation("ENV-P2", standDeux, creneau);
        posteDeux.setAnimateur(alice);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteUn, posteDeux)));
    }

    private String accessToken(String animateurId) {
        return fiche(animateurId).getAccessToken();
    }

    private long count(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ValidatableResponse renvoyer() {
        return given().when().post("/api/animateurs/renvois").then();
    }

    private Animateur fiche(String animateurId) {
        return referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The next manual reminder fails the way the relay answered, {@code code}
     * included; the ones after it leave through the real service.
     */
    private void failWith(int code) {
        QuarkusMock.installMockForType(
                new FailingOnceMailService(code, ClientProxy.unwrap(realMailService)), MailService.class);
    }

    @Inject
    MailService realMailService;

    static final class FailingOnceMailService extends MailService {
        private final int code;
        private final MailService real;
        private boolean failed;

        FailingOnceMailService(int code, MailService real) {
            // Every send goes to the delegate: this instance's own collaborators are never read.
            super(null, null, null, null, null);
            this.code = code;
            this.real = real;
        }

        @Override
        public void sendRelanceConfirmation(String emailAnimateur, String prenom, String lienEspace) {
            if (!failed) {
                failed = true;
                throw new CompletionException(
                        new SMTPException(code + " refused", code, List.of(code + " refused"), code >= 500));
            }
            real.sendRelanceConfirmation(emailAnimateur, prenom, lienEspace);
        }
    }

    private static ParametresNotifications armed() {
        return new ParametresNotifications(true, LocalTime.of(18, 0), 72, 3);
    }

    private static ValidatableResponse relancer(String... animateurIds) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("animateurIds", List.of(animateurIds)))
                .when()
                .post("/api/animateurs/relances")
                .then();
    }

    /** {@code type|statut|categorie} of each line for one animateur, oldest first. */
    private List<String> journal(String animateurId) {
        List<String> lignes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT type, statut, categorie_echec FROM envoi_mail WHERE animateur_id = '" + animateurId
                                + "' ORDER BY id")) {
            while (rs.next()) {
                lignes.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return lignes;
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run " + sql, e);
        }
    }

    private void giveEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
    }
}
