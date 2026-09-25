package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.notification.RelanceConfirmationJob;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.arc.ClientProxy;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.vertx.ext.mail.SMTPException;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * insisting on a refused address until the fiche changes.
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
    void theManualReminderDoesNotInsistOnARefusedAddressUntilTheFicheChanges() {
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
    void theNightDoesNotWriteToARefusedAddressAndACorrectedFicheRestoresIt() {
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

    /* -------------------------------- Helpers ------------------------------ */

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
