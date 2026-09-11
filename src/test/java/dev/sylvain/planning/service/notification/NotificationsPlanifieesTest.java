package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.AlerteService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.RelanceManuelleService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
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
    JournalNotificationsRepository journal;

    @Inject
    MockMailbox mailbox;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() {
        mailbox.clear();
        execute("DELETE FROM plan_snapshot");
        execute("DELETE FROM notification_planifiee");
        execute("DELETE FROM confirmation_planning");
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

    /* -------------------------------- Helpers ------------------------------ */

    /** An armed edition, with the delays this test reasons about. */
    private static ParametresNotifications actives() {
        return new ParametresNotifications(true, LocalTime.of(18, 0), 72, 3);
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
