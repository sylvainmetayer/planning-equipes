package dev.sylvain.planning.service.notification;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.AlerteService;
import dev.sylvain.planning.service.edition.EditionActivationService;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.mail.MailDeliveryOutcome;
import dev.sylvain.planning.service.mail.MailDeliveryRepository;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.publication.MailService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reminder of the collection of availabilities: three days before the
 * window closes, the invited who declared nothing hear it once — and nobody
 * else, ever, unless the organisation switched it on.
 *
 * <p>Like the other nightly sends, every test that sends runs the job more
 * than once: the cron is hourly, and « once » is the property.</p>
 */
@QuarkusTest
class RelanceCollecteJobTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final LocalDate FIN = LocalDate.of(2026, 9, 20);

    private static final String ALICE = "collecte-alice@example.org";
    private static final String BRUNO = "collecte-bruno@example.org";
    private static final String CHLOE = "collecte-chloe@example.org";
    private static final String DAVID = "collecte-david@example.org";

    @Inject
    RelanceCollecteJob job;

    @Inject
    NotificationsPlanifieesService nightly;

    @Inject
    DeclarationDisponibiliteRepository declarations;

    @Inject
    MailDeliveryRepository deliveries;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    AlerteService alerteService;

    @Inject
    EditionContext editionContext;

    @Inject
    EditionActivationService activation;

    @Inject
    MockMailbox mailbox;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    DataSource dataSource;

    /** Alice: invited, declared nothing — the one person to remind. */
    private String alice;

    private final AtomicInteger refusals = new AtomicInteger();

    @BeforeEach
    void seed() {
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM envoi_mail");
        execute("DELETE FROM declaration_disponibilite");
        execute("DELETE FROM parametres_collecte");
        execute("DELETE FROM plan_snapshot");
        persistence.clearDatabase();
        alice = animateur("Alice", ALICE);
        // Bruno was invited and answered: declared is declared, whatever comes of it.
        String bruno = animateur("Bruno", BRUNO);
        // Chloé was never invited: nothing tells her there is anything to declare.
        animateur("Chloé", CHLOE);
        // David's invitation never left: the reminder would be the first he hears of it.
        String david = animateur("David", DAVID);
        deliveries.save(alice, MailService.AVAILABILITY_INVITATION, MailDeliveryOutcome.SENT);
        deliveries.save(bruno, MailService.AVAILABILITY_INVITATION, MailDeliveryOutcome.SENT);
        deliveries.save(
                david,
                MailService.AVAILABILITY_INVITATION,
                MailDeliveryOutcome.failed(new IllegalStateException("421 4.3.0 Try again later")));
        declare(bruno);
        declarations.saveFenetre(new FenetreCollecte(true, null, FIN, true));
        mailbox.clear();
    }

    /**
     * The window this class opens stays open otherwise, and the classes that run
     * after it — under another test profile, so in an order nobody chose — read
     * a collection they never opened.
     */
    @AfterEach
    void closeTheCollection() {
        execute("DELETE FROM parametres_collecte");
    }

    @Test
    void onlyTheInvitedWhoDeclaredNothingAreRemindedAndOnlyOnce() {
        assertThat(job.run(at(FIN.minusDays(3)))).isEqualTo(1);
        assertThat(job.run(at(FIN.minusDays(3)).plusHours(1))).isZero();
        assertThat(job.run(at(FIN.minusDays(2)))).isZero();
        assertThat(job.run(at(FIN))).isZero();

        List<Mail> mails = mailbox.getMailsSentTo(ALICE);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).contains("dimanche 20 septembre");
        assertThat(mails.get(0).getText()).contains("La collecte se termine le dimanche 20 septembre");
        assertThat(mailbox.getMailsSentTo(BRUNO)).isEmpty();
        assertThat(mailbox.getMailsSentTo(CHLOE)).isEmpty();
        assertThat(mailbox.getMailsSentTo(DAVID)).isEmpty();
        assertThat(deliveries.latestByAnimateur().get(alice)).satisfies(envoi -> {
            assertThat(envoi.type()).isEqualTo("relance-collecte");
            assertThat(envoi.status()).isEqualTo(MailDeliveryOutcome.Status.ENVOYE);
        });
    }

    /** Four days out is too early; a night the job missed is caught up, down to the last day. */
    @Test
    void theReminderLeavesFromThreeDaysOutToTheLastDay() {
        assertThat(job.run(at(FIN.minusDays(4)))).isZero();
        assertThat(job.run(ZonedDateTime.of(FIN, LocalTime.of(0, 5), ZONE)))
                .as("not before the sending time, even on the last day")
                .isZero();

        assertThat(job.run(at(FIN))).isEqualTo(1);
        assertThat(mailbox.getMailsSentTo(ALICE)).hasSize(1);
    }

    @Test
    void nothingLeavesOnceTheWindowHasEnded() {
        assertThat(job.run(at(FIN.plusDays(1)))).isZero();
        assertThat(mailbox.getMailsSentTo(ALICE)).isEmpty();
    }

    /** A new end is a new deadline: the key carries it. */
    @Test
    void movingTheEndOfTheWindowAllowsAnotherReminder() {
        assertThat(job.run(at(FIN.minusDays(3)))).isEqualTo(1);

        declarations.saveFenetre(new FenetreCollecte(true, null, FIN.plusDays(4), true));

        assertThat(job.run(at(FIN.plusDays(1)))).isEqualTo(1);
        assertThat(job.run(at(FIN.plusDays(2)))).isZero();
        assertThat(mailbox.getMailsSentTo(ALICE)).hasSize(2);
    }

    @Test
    void nothingLeavesWhileTheSwitchIsOff() {
        declarations.saveFenetre(new FenetreCollecte(true, null, FIN, false));

        assertThat(job.run(at(FIN.minusDays(3)))).isZero();
        assertThat(mailbox.getMailsSentTo(ALICE)).isEmpty();
    }

    @Test
    void nothingLeavesFromAClosedCollection() {
        declarations.saveFenetre(new FenetreCollecte(false, null, FIN, true));

        assertThat(job.run(at(FIN.minusDays(3)))).isZero();
        assertThat(mailbox.getMailsSentTo(ALICE)).isEmpty();
    }

    /** Only the active edition emits (ADR 0072): the nightly entry point serves no other. */
    @Test
    void nothingLeavesFromAnEditionThatIsNotActive() {
        String edition = editionContext.activeEditionId().orElseThrow();
        activation.deactivate(edition);
        try {
            nightly.run(at(FIN.minusDays(3)));
        } finally {
            activation.activate(edition);
        }
        assertThat(mailbox.getMailsSentTo(ALICE)).isEmpty();

        nightly.run(at(FIN.minusDays(3)));

        assertThat(mailbox.getMailsSentTo(ALICE))
                .as("the same run from the active edition does write")
                .hasSize(1);
    }

    /**
     * A failed send is not retried every hour, but the next day tries again —
     * and the alert, said once per deadline, names nobody.
     */
    @Test
    void aFailedReminderIsRetriedTheNextDayNotEveryHour() {
        relayRefuses("421 4.3.0 Try again later");

        assertThat(job.run(at(FIN.minusDays(3)))).isZero();
        assertThat(job.run(at(FIN.minusDays(3)).plusHours(1))).isZero();
        assertThat(refusals).as("one attempt on the day").hasValue(1);

        assertThat(job.run(at(FIN.minusDays(2)))).isZero();
        assertThat(refusals).as("the next day tries again").hasValue(2);

        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "RELANCE_COLLECTE".equals(alerte.type()))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.animateurId()).isEqualTo(alice);
                    assertThat(alerte.severite()).isEqualTo("ALERTE");
                    assertThat(alerte.libelle()).doesNotContain("Alice").doesNotContain("@");
                });
    }

    /** The relay down on J-3 and back on J-2: the reminder leaves on J-2, once. */
    @Test
    void aRelayDownOnOneDayIsCaughtUpTheNext() {
        relayRefuses("421 4.3.0 Try again later");
        assertThat(job.run(at(FIN.minusDays(3)))).isZero();
        relayAccepts();

        assertThat(job.run(at(FIN.minusDays(3)).plusHours(1)))
                .as("not again the same day")
                .isZero();
        assertThat(job.run(at(FIN.minusDays(2)))).isEqualTo(1);
        assertThat(job.run(at(FIN.minusDays(1)))).isZero();

        assertThat(mailbox.getMailsSentTo(ALICE)).hasSize(1);
    }

    /**
     * An address the relay refused is not written to again until the fiche is
     * edited — the organiser's answer to the refusal —, and is said once.
     */
    @Test
    void aRefusedAddressIsNotWrittenToUntilTheFicheIsEdited() {
        relayRefuses("550 5.1.1 Recipient address rejected");
        assertThat(job.run(at(FIN.minusDays(3)))).isZero();
        relayAccepts();

        assertThat(job.run(at(FIN.minusDays(2)))).isZero();
        assertThat(job.run(at(FIN.minusDays(2)).plusHours(1))).isZero();
        assertThat(mailbox.getMailsSentTo(ALICE)).isEmpty();
        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> alerte.cle().endsWith(RelanceCollecteJob.REFUSED_ADDRESS_SUFFIX))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.type()).isEqualTo("RELANCE_COLLECTE");
                    assertThat(alerte.animateurId()).isEqualTo(alice);
                    assertThat(alerte.libelle()).doesNotContain("Alice").doesNotContain("@");
                });

        giveEmail(alice, "collecte-alice-corrigee@example.org");

        assertThat(job.run(at(FIN.minusDays(2)).plusHours(2))).isEqualTo(1);
        assertThat(mailbox.getMailsSentTo("collecte-alice-corrigee@example.org"))
                .hasSize(1);
    }

    /** Invited, then the address went: nothing leaves, and the home screen says so once, by id. */
    @Test
    void anInvitedFicheWithoutAnAddressIsSaidNotWrittenTo() {
        giveEmail(alice, null);

        assertThat(job.run(at(FIN.minusDays(3)))).isZero();
        assertThat(job.run(at(FIN.minusDays(2)))).isZero();

        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "RELANCE_COLLECTE".equals(alerte.type()))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.cle()).endsWith(RelanceCollecteJob.NO_ADDRESS_SUFFIX);
                    assertThat(alerte.animateurId()).isEqualTo(alice);
                    assertThat(alerte.severite()).isEqualTo("WARNING");
                    assertThat(alerte.libelle()).doesNotContain("Alice");
                });

        giveEmail(alice, ALICE);

        assertThat(job.run(at(FIN.minusDays(1)))).isEqualTo(1);
    }

    /** The switch is stored with the window, and read back by the screen that sets it. */
    @Test
    void theSwitchTravelsThroughTheConfigurationRoute() {
        String edition = editionContext.activeEditionId().orElseThrow();
        given().header(EditionContext.HEADER, edition)
                .contentType(ContentType.JSON)
                .body(Map.of("collecteOuverte", true, "fin", FIN.toString(), "relanceAutomatique", true))
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200);

        assertThat(given().header(EditionContext.HEADER, edition)
                        .when()
                        .get("/api/disponibilites/configuration")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getBoolean("relanceAutomatique"))
                .isTrue();
        assertThat(declarations.fenetre()).isEqualTo(new FenetreCollecte(true, null, FIN, true));

        given().header(EditionContext.HEADER, edition)
                .contentType(ContentType.JSON)
                .body(Map.of("collecteOuverte", true, "fin", FIN.toString()))
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200)
                .body("relanceAutomatique", org.hamcrest.Matchers.is(true));

        assertThat(declarations.fenetre().relanceAutomatique())
                .as("a request that leaves the switch out keeps it")
                .isTrue();

        given().header(EditionContext.HEADER, edition)
                .contentType(ContentType.JSON)
                .body(Map.of("collecteOuverte", true, "fin", FIN.toString(), "relanceAutomatique", false))
                .when()
                .put("/api/disponibilites/configuration")
                .then()
                .statusCode(200);

        assertThat(declarations.fenetre().relanceAutomatique()).isFalse();
    }

    /* -------------------------------- Helpers ------------------------------ */

    /** 10 h on {@code day}, past the sending time. */
    private static ZonedDateTime at(LocalDate day) {
        return ZonedDateTime.of(day, LocalTime.of(10, 0), ZONE);
    }

    private String animateur(String prenom, String email) {
        Animateur animateur = new Animateur(null, prenom, "Collecte", LocalDate.of(1990, 1, 1), false);
        animateur.setEmail(email);
        return referenceData.createAnimateur(animateur).getId();
    }

    private void declare(String animateurId) {
        DeclarationDisponibilite declaration = new DeclarationDisponibilite();
        declaration.setId(UUID.randomUUID().toString());
        declaration.setAnimateurId(animateurId);
        declaration.setJoursIndisponibles(List.of());
        declaration.setSouhaits(List.of());
        declaration.setStatut(StatutDeclaration.EN_ATTENTE);
        declaration.setCreeLe(Instant.now());
        declarations.replacePending(declaration);
    }

    private void relayRefuses(String reply) {
        Mailer refusing = mails -> {
            refusals.incrementAndGet();
            throw new IllegalStateException(reply);
        };
        QuarkusMock.installMockForType(
                new MailMetrics(meterRegistry, deliveries) {
                    @Override
                    public void send(Mailer mailer, String template, Mail mail, String animateurId) {
                        super.send(refusing, template, mail, animateurId);
                    }
                },
                MailMetrics.class);
    }

    private void relayAccepts() {
        QuarkusMock.installMockForType(new MailMetrics(meterRegistry, deliveries), MailMetrics.class);
    }

    private void giveEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidate -> candidate.getId().equals(animateurId))
                .findFirst()
                .orElseThrow();
        animateur.setEmail(email);
        referenceData.updateAnimateur(animateurId, animateur);
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
