package dev.sylvain.planning.service.notification;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.AlerteService;
import dev.sylvain.planning.service.mail.MailDeliveryRepository;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.publication.RelanceManuelleService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three nightly jobs (issues #298, #299, #300), and the one property they
 * all live or die by: <b>running twice writes to nobody twice</b>.
 *
 * <p>Every test here runs the job at least two times on purpose. A scheduled
 * job that is only ever exercised once proves nothing about the case that
 * actually happens in production — an hourly cron, a restart mid-run, an
 * operator replaying the job by hand.</p>
 */
@QuarkusTest
class NotificationsPlanifieesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 11);
    private static final String EMAIL_ALICE = "planifiee-alice@example.org";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** The evening before {@link #JOUR}, past any sensible sending time. */
    private static final ZonedDateTime VEILLE_AU_SOIR = ZonedDateTime.of(JOUR.minusDays(1), LocalTime.of(19, 30), ZONE);

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanPublicationService publication;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Inject
    RappelVeilleJob rappelVeille;

    @Inject
    RelanceConfirmationJob relanceConfirmation;

    @Inject
    RelanceManuelleService relanceManuelle;

    @Inject
    AlerteService alerteService;

    @Inject
    PlanPublieService planPublie;

    @Inject
    JournalNotificationsRepository journal;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    MailDeliveryRepository deliveries;

    /** How many sends the refusing relay of {@link #relayRefuses} was asked for. */
    private final AtomicInteger refusals = new AtomicInteger();

    @BeforeEach
    void seed() {
        mailbox.clear();
        execute("DELETE FROM plan_snapshot");
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM confirmation_planning");
        execute("DELETE FROM envoi_mail");
        persistence.clearDatabase();
        persistPlan();
        donnerEmail("PLAN-A", EMAIL_ALICE);
        // Bruno keeps no address on purpose: he is the "injoignable" case.
        publication.publier();
        mailbox.clear();
    }

    /* --------------------- #298 — day-before reminder ---------------------- */

    @Test
    void theDayBeforeReminderNamesTomorrowsSeats() {
        assertThat(rappelVeille.run(actives(), VEILLE_AU_SOIR)).isEqualTo(1);

        List<io.quarkus.mailer.Mail> mails = mailbox.getMailsSentTo(EMAIL_ALICE);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getText()).contains("Stand planifie un").contains("10h-12h");
    }

    /**
     * The property the whole feature rests on. An hourly cron calls this six
     * times between 19 h and midnight.
     */
    @Test
    void runningTheDayBeforeReminderAgainSendsNothingMore() {
        rappelVeille.run(actives(), VEILLE_AU_SOIR);
        int deuxieme = rappelVeille.run(actives(), VEILLE_AU_SOIR.plusHours(1));
        int troisieme = rappelVeille.run(actives(), VEILLE_AU_SOIR.plusHours(2));

        assertThat(deuxieme).isZero();
        assertThat(troisieme).isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    /** Before the edition's own sending time, the job is a no-op — not an early send. */
    @Test
    void nothingLeavesBeforeTheConfiguredSendingTime() {
        ZonedDateTime tropTot = ZonedDateTime.of(JOUR.minusDays(1), LocalTime.of(9, 0), ZONE);

        assertThat(rappelVeille.run(actives(), tropTot)).isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
    }

    /**
     * Somebody with no address is skipped — and said out loud. Silently
     * dropping them is what leaves a stand unmanned on the day.
     */
    @Test
    void someoneWithNoAddressIsSkippedAndReported() {
        rappelVeille.run(actives(), VEILLE_AU_SOIR);

        List<AlerteService.AlerteView> alertes = alerteService.alertes(null);
        assertThat(alertes)
                .filteredOn(alerte -> "RAPPEL_VEILLE_INJOIGNABLE".equals(alerte.type()))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.animateurId()).isEqualTo("PLAN-B");
                    // The name is joined at read time; the table itself stores
                    // only the id (docs/rgpd.md).
                    assertThat(alerte.nomAffiche()).isEqualTo("Bruno Petit");
                    assertThat(alerte.libelle()).doesNotContain("Bruno");
                });
    }

    /** The same alert must not pile up one row per hour either. */
    @Test
    void theUnreachableAlertIsRaisedOnlyOnce() {
        rappelVeille.run(actives(), VEILLE_AU_SOIR);
        rappelVeille.run(actives(), VEILLE_AU_SOIR.plusHours(1));
        rappelVeille.run(actives(), VEILLE_AU_SOIR.plusHours(2));

        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "RAPPEL_VEILLE_INJOIGNABLE".equals(alerte.type()))
                .hasSize(1);
    }

    /**
     * One noisy kind of alert must not push another off the screen.
     *
     * <p>An addressless fiche raises one row <b>every evening</b>, so a flat
     * « newest N » let a handful of unreachable people bury the swap-request
     * alerts within days — the only ones asking the admin for a decision, and
     * whose sole way out is this screen. The cap is therefore per type.</p>
     */
    @Test
    void aNoisyKindOfAlertCannotBuryTheOnesNeedingADecision() {
        // Far more unreachable-reminder alerts than the requested cap.
        for (int jour = 0; jour < 6; jour++) {
            journal.claim(
                    JournalNotificationsRepository.Type.RAPPEL_VEILLE_INJOIGNABLE,
                    "PLAN-B|bruit-" + jour,
                    "PLAN-B",
                    "Rappel impossible, fiche sans adresse.",
                    JournalNotificationsRepository.Severite.WARNING);
        }
        journal.claim(
                JournalNotificationsRepository.Type.ALERTE_ECHANGE,
                "demande-qui-dort",
                null,
                "Une demande d'échange attend une décision depuis 9 jours.",
                JournalNotificationsRepository.Severite.WARNING);

        List<AlerteService.AlerteView> alertes = alerteService.alertes(2);

        assertThat(alertes)
                .filteredOn(alerte -> "RAPPEL_VEILLE_INJOIGNABLE".equals(alerte.type()))
                .hasSize(2);
        assertThat(alertes)
                .filteredOn(alerte -> "ALERTE_ECHANGE".equals(alerte.type()))
                .hasSize(1);
    }

    /**
     * A claim given back can be taken again. That is the whole point: the
     * manual reminder claims before sending, and a send that fails releases the
     * key — held, it would refuse the retry by hand <b>and</b> the night, and
     * the person would never be reminded at all.
     */
    @Test
    void aReleasedClaimCanBeTakenAgain() {
        JournalNotificationsRepository.Type type = JournalNotificationsRepository.Type.RELANCE_CONFIRMATION;

        assertThat(journal.claim(type, "PLAN-B|reprise", "PLAN-B")).isTrue();
        assertThat(journal.claim(type, "PLAN-B|reprise", "PLAN-B")).isFalse();

        journal.release(type, "PLAN-B|reprise");

        assertThat(journal.claim(type, "PLAN-B|reprise", "PLAN-B")).isTrue();
    }

    /* ------------------- #299 — reminding the silent ---------------------- */

    @Test
    void theSilentAreRemindedOnceAndThenLeftAlone() {
        // 72 h after the publication, which happened in the seed.
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));

        assertThat(relanceConfirmation.run(actives(), plusTard)).isEqualTo(1);
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);

        // The status moved to RELANCE, which takes Alice out of the selection.
        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(3600)))
                .isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(confirmationService.byAnimateur())
                .filteredOn(vue -> vue.animateurId().equals("PLAN-A"))
                .singleElement()
                .satisfies(vue -> assertThat(vue.statut()).isEqualTo("RELANCE"));
    }

    /** Before the configured delay, silence is not yet a problem. */
    @Test
    void nobodyIsRemindedBeforeTheDelayHasPassed() {
        assertThat(relanceConfirmation.run(actives(), java.time.Instant.now())).isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
    }

    /** Someone who answered is never chased. */
    @Test
    void whoeverConfirmedIsNotReminded() {
        confirmationService.confirmer("PLAN-A");
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));

        assertThat(relanceConfirmation.run(actives(), plusTard)).isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
    }

    /**
     * The one-reminder rule holds across hands (issue #504): reminded by the
     * organiser in the afternoon, Alice is left alone by the night — the two
     * claim the same key, and the hand claimed it first.
     */
    @Test
    void someoneRemindedByHandIsNotRemindedAgainByTheNight() {
        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));
        assertThat(rapport.envoyes()).containsExactly("PLAN-A");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);

        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        assertThat(relanceConfirmation.run(actives(), plusTard)).isZero();
        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(3600)))
                .isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    /** And the other way round: once the night wrote, the hand is refused. */
    @Test
    void someoneRemindedByTheNightIsRefusedToTheHand() {
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        assertThat(relanceConfirmation.run(actives(), plusTard)).isEqualTo(1);

        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));

        assertThat(rapport.envoyes()).isEmpty();
        assertThat(rapport.dejaRelancesPourCettePublication()).containsExactly("PLAN-A");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    /* ------------- #663 — a failed send is not a silence ------------------- */

    /**
     * The asymmetry this fixes: the night used to mark Alice « relancée »
     * before firing a notification whose failure was swallowed — reminded on
     * screen, never reminded again, and nobody told. Now the status stays, an
     * alert is left, and the night keeps its own attempt so it does not hammer
     * a relay that is down.
     */
    @Test
    void aNightReminderThatFailsLeavesTheStatusAlertsAndDoesNotInsist() {
        relayRefuses("421 4.3.0 Try again later");
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));

        assertThat(relanceConfirmation.run(actives(), plusTard)).isZero();
        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(3600)))
                .isZero();

        assertThat(refusals)
                .as("the night's attempt is kept: one attempt, not one per hour")
                .hasValue(1);
        assertThat(confirmationService.byAnimateur())
                .filteredOn(vue -> vue.animateurId().equals("PLAN-A"))
                .singleElement()
                .satisfies(vue -> {
                    assertThat(vue.statut()).isEqualTo("NON_VU");
                    assertThat(vue.relanceLe()).isNull();
                    assertThat(vue.dernierEnvoi().statut()).isEqualTo("ECHEC");
                    assertThat(vue.dernierEnvoi().categorie()).isEqualTo("TEMPORAIRE");
                    assertThat(vue.dernierEnvoi().type()).isEqualTo("relance-confirmation");
                    assertThat(vue.dernierEnvoi().enEchec()).isTrue();
                });
        assertThat(alerteService.alertes(null))
                .filteredOn(
                        alerte -> "RELANCE_INJOIGNABLE".equals(alerte.type()) && "PLAN-A".equals(alerte.animateurId()))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.severite()).isEqualTo("ALERTE");
                    assertThat(alerte.libelle()).contains("relancez à la main");
                });
        ConfirmationPlanningService.SyntheseConfirmations synthese = confirmationService.synthese();
        assertThat(synthese.echecsEnvoi()).isEqualTo(1);
        assertThat(synthese.relances()).isZero();
        assertThat(synthese.silencieux()).as("Bruno, who has no address").isEqualTo(1);
    }

    /** The night keeps its claim, but the person was never reminded: the hand may try again. */
    @Test
    void theHandMayRetryAReminderTheNightCouldNotSend() {
        relayRefuses("421 4.3.0 Try again later");
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        relanceConfirmation.run(actives(), plusTard);
        relayAccepts();

        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));

        assertThat(rapport.envoyes()).containsExactly("PLAN-A");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(3600)))
                .isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    /**
     * The hand's retry does not hang on the last mail sent to Alice: a
     * day-before reminder that left in between used to hide the night's
     * failure, and Alice was refused as already reminded while still silent.
     */
    @Test
    void anotherMailAfterTheNightsFailureDoesNotCloseTheHandsRetry() {
        relayRefuses("421 4.3.0 Try again later");
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        relanceConfirmation.run(actives(), plusTard);
        relayAccepts();
        assertThat(rappelVeille.run(actives(), VEILLE_AU_SOIR)).isEqualTo(1);
        mailbox.clear();

        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));

        assertThat(rapport.envoyes()).containsExactly("PLAN-A");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).hasSize(1);
    }

    /**
     * Two hands on the same failed reminder write once: the retry goes through
     * the very key a first reminder claims, which another click — here, one
     * still sending — holds.
     */
    @Test
    void aRetryHeldByAnotherHandIsNotSentTwice() {
        relayRefuses("421 4.3.0 Try again later");
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        relanceConfirmation.run(actives(), plusTard);
        relayAccepts();
        String cle = "PLAN-A|" + planPublie.lastPublication().publieLe();
        assertThat(journal.claim(JournalNotificationsRepository.Type.RELANCE_CONFIRMATION, cle, "PLAN-A"))
                .as("the other hand, mid-send")
                .isTrue();

        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));

        assertThat(rapport.envoyes()).isEmpty();
        assertThat(rapport.dejaRelancesPourCettePublication()).containsExactly("PLAN-A");
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
    }

    /**
     * The night's own refusal follows the rule the alert states: nothing is
     * sent to the refused address again, and the first edit of the fiche puts
     * Alice back in the next run — the night does not hold her for good.
     */
    @Test
    void aNightReminderToARefusedAddressResumesOnceTheFicheIsEdited() {
        relayRefuses("550 5.1.1 Recipient address rejected");
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        assertThat(relanceConfirmation.run(actives(), plusTard)).isZero();
        relayAccepts();

        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(3600)))
                .isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "PLAN-A".equals(alerte.animateurId()))
                .extracting(alerte -> alerte.severite() + " " + alerte.libelle())
                .anySatisfy(ligne -> assertThat(ligne).startsWith("ALERTE").contains("Corrigez-la"))
                .anySatisfy(ligne -> assertThat(ligne).startsWith("WARNING").contains("Corrigez-la"));

        donnerEmail("PLAN-A", "planifiee-alice-corrigee@example.org");

        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(7200)))
                .isEqualTo(1);
        assertThat(mailbox.getMailsSentTo("planifiee-alice-corrigee@example.org"))
                .hasSize(1);
    }

    /**
     * A failure alert is not swallowed by the address-less warning raised
     * before it: the fiche got its address since, and the send that followed
     * failed — each one has a key of its own.
     */
    @Test
    void aSendFailureAfterAnAddresslessWarningStillLeavesItsAlert() {
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        relanceConfirmation.run(actives(), plusTard);
        rappelVeille.run(actives(), VEILLE_AU_SOIR);
        donnerEmail("PLAN-B", "planifiee-bruno@example.org");
        relayRefuses("421 4.3.0 Try again later");

        relanceConfirmation.run(actives(), plusTard.plusSeconds(3600));
        rappelVeille.run(actives(), VEILLE_AU_SOIR.plusHours(1));

        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> "PLAN-B".equals(alerte.animateurId()))
                .extracting(alerte -> alerte.type() + " " + alerte.severite())
                .containsExactlyInAnyOrder(
                        "RELANCE_INJOIGNABLE WARNING",
                        "RELANCE_INJOIGNABLE ALERTE",
                        "RAPPEL_VEILLE_INJOIGNABLE WARNING",
                        "RAPPEL_VEILLE_INJOIGNABLE ALERTE");
    }

    /**
     * A reminder that left is not undone by a later refusal: Alice, reminded,
     * whose day-before reminder then bounced, is « already reminded » to the
     * hand, not « address refused ».
     */
    @Test
    void someoneAlreadyRemindedIsReportedSoEvenAfterALaterRefusal() {
        assertThat(relanceManuelle.relancer(List.of("PLAN-A")).envoyes()).containsExactly("PLAN-A");
        relayRefuses("550 5.1.1 Recipient address rejected");
        rappelVeille.run(actives(), VEILLE_AU_SOIR);

        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));

        assertThat(rapport.dejaRelancesPourCettePublication()).containsExactly("PLAN-A");
        assertThat(rapport.adresseRefusee()).isEmpty();
    }

    /** The day-before reminder that fails leaves an alert too, once. */
    @Test
    void aDayBeforeReminderThatFailsLeavesAnAlertOnce() {
        relayRefuses("454 4.7.0 TLS not available");

        assertThat(rappelVeille.run(actives(), VEILLE_AU_SOIR)).isZero();
        assertThat(rappelVeille.run(actives(), VEILLE_AU_SOIR.plusHours(1))).isZero();

        assertThat(refusals).hasValue(1);
        assertThat(alerteService.alertes(null))
                .filteredOn(alerte ->
                        "RAPPEL_VEILLE_INJOIGNABLE".equals(alerte.type()) && "PLAN-A".equals(alerte.animateurId()))
                .singleElement()
                .satisfies(alerte -> {
                    assertThat(alerte.severite()).isEqualTo("ALERTE");
                    assertThat(alerte.libelle()).doesNotContain(EMAIL_ALICE).doesNotContain("Alice");
                });
    }

    /**
     * « Ne pas insister »: once the relay refused Alice's address, neither the
     * hand nor the night writes to it again — until the fiche is edited, which
     * is the organiser's answer to the refusal.
     */
    @Test
    void aRefusedAddressIsNotRemindedUntilTheFicheIsEdited() {
        relayRefuses("550 5.1.1 Recipient address rejected");
        assertThat(relanceManuelle.relancer(List.of("PLAN-A")).echecs()).containsExactly("PLAN-A");
        relayAccepts();

        RelanceManuelleService.RapportRelance rapport = relanceManuelle.relancer(List.of("PLAN-A"));
        java.time.Instant plusTard = java.time.Instant.now().plus(java.time.Duration.ofHours(80));
        int nuit = relanceConfirmation.run(actives(), plusTard);

        assertThat(rapport.adresseRefusee()).containsExactly("PLAN-A");
        assertThat(rapport.envoyes()).isEmpty();
        assertThat(nuit).isZero();
        assertThat(mailbox.getMailsSentTo(EMAIL_ALICE)).isEmpty();
        assertThat(alerteService.alertes(null))
                .filteredOn(alerte -> alerte.cle().endsWith("|adresse-refusee"))
                .singleElement()
                .satisfies(alerte -> assertThat(alerte.animateurId()).isEqualTo("PLAN-A"));

        donnerEmail("PLAN-A", "planifiee-alice-corrigee@example.org");

        assertThat(relanceConfirmation.run(actives(), plusTard.plusSeconds(3600)))
                .isEqualTo(1);
        assertThat(mailbox.getMailsSentTo("planifiee-alice-corrigee@example.org"))
                .hasSize(1);
    }

    /** What is kept of a send is an id, a template, a state and a date — never the address. */
    @Test
    void aRecordedSendKeepsNoAddress() {
        relayRefuses("550 5.1.1 <" + EMAIL_ALICE + ">: Recipient address rejected");
        rappelVeille.run(actives(), VEILLE_AU_SOIR);

        List<String> colonnes = new ArrayList<>();
        List<String> valeurs = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT * FROM envoi_mail")) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    colonnes.add(meta.getColumnName(i));
                    valeurs.add(String.valueOf(rs.getObject(i)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        assertThat(colonnes)
                .containsOnly("edition_id", "id", "animateur_id", "type", "statut", "categorie_echec", "envoye_le");
        assertThat(valeurs)
                .contains("PLAN-A", "rappel-veille", "ECHEC", "ADRESSE_REFUSEE")
                .noneMatch(valeur -> valeur.contains("@"));
    }

    /** The Animateurs page reads the failure from the confirmations route, beside the answer. */
    @Test
    void theConfirmationsRouteExposesTheLastSendBesideTheAnswer() {
        relayRefuses("535 5.7.8 Authentication credentials invalid");
        rappelVeille.run(actives(), VEILLE_AU_SOIR);

        io.restassured.path.json.JsonPath confirmations = given().header("X-Edition-Id", "E1")
                .when()
                .get("/api/animateurs/confirmations")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        java.util.Map<String, Object> alice = confirmations.getMap("find { it.animateurId == 'PLAN-A' }");

        assertThat(alice).containsEntry("statut", "NON_VU");
        assertThat(confirmations.getString("find { it.animateurId == 'PLAN-A' }.dernierEnvoi.statut"))
                .isEqualTo("ECHEC");
        assertThat(confirmations.getString("find { it.animateurId == 'PLAN-A' }.dernierEnvoi.categorie"))
                .isEqualTo("AUTHENTIFICATION");
        assertThat(confirmations.getString("find { it.animateurId == 'PLAN-A' }.dernierEnvoi.le"))
                .isNotBlank();
        assertThat(confirmations.getString("find { it.animateurId == 'PLAN-B' }.dernierEnvoi"))
                .as("nothing was ever sent to Bruno, who has no address")
                .isNull();
        assertThat(confirmations.prettify()).doesNotContain("@");
        assertThat(given().header("X-Edition-Id", "E1")
                        .when()
                        .get("/api/animateurs/confirmations/synthese")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getInt("echecsEnvoi"))
                .isEqualTo(1);
    }

    /* -------------------------------- Helpers ------------------------------ */

    /** Every mail from now on meets a relay answering {@code reply}, counted in {@link #refusals}. */
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

    /** The relay is back: mails reach the mock mailbox again. */
    private void relayAccepts() {
        QuarkusMock.installMockForType(new MailMetrics(meterRegistry, deliveries), MailMetrics.class);
    }

    /** An armed edition, with the delays this test reasons about. */
    private static ParametresNotifications actives() {
        return new ParametresNotifications(LocalTime.of(18, 0), 72, 3);
    }

    private void persistPlan() {
        Animateur alice = new Animateur("PLAN-A", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bruno = new Animateur("PLAN-B", "Bruno", "Petit", LocalDate.of(1992, 2, 2), false);
        Stand stand = new Stand("PLAN-S1", "Stand planifie un", Set.of(), 1, 2, false);
        // Persisted with an explicit id the sequence never hands out: a small one
        // is reached by the suite's own inserts, and the next createCreneau of
        // whichever test gets there dies on creneau_pkey.
        Creneau creneau = new Creneau(970_100_001L, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(12, 0));

        PosteAffectation posteAlice = new PosteAffectation("PLAN-P1", stand, creneau);
        posteAlice.setAnimateur(alice);
        PosteAffectation posteBruno = new PosteAffectation("PLAN-P2", stand, creneau);
        posteBruno.setAnimateur(bruno);
        persistence.persist(new PlanningEvenement(JOUR, List.of(alice, bruno), List.of(posteAlice, posteBruno)));
    }

    private void donnerEmail(String animateurId, String email) {
        Animateur animateur = referenceData.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
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
