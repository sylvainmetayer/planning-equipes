package dev.sylvain.planning.service.backup;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The nightly backup, end to end — everything but the {@code pg_dump} process
 * itself, which a stub stands in for: its presence and its version depend on
 * the machine, and a suite that shells out to it would prove nothing about
 * this code and fail for reasons belonging to another one.
 *
 * <p>What is proven here is what the operator is promised: a run leaves exactly
 * one new dump, the retention bounds how many survive, a failure is <b>kept and
 * shown</b> rather than swallowed into a log, and the switch really stops the
 * scheduled run.</p>
 */
@QuarkusTest
@TestProfile(BackupServiceTest.Profil.class)
class BackupServiceTest {

    /** Two, so one run on a seeded directory is enough to watch the rotation bite. */
    private static final int RETENTION = 2;

    static final Path DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "planning-backup-test");

    /** Flipped by the tests that want the dump to fail. */
    static boolean dumpFails;

    public static class Profil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "planning.backup.directory", DIRECTORY.toString(),
                    "planning.backup.retention", String.valueOf(RETENTION));
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(PgDumpStub.class, DatabaseOutage.class);
        }
    }

    /**
     * The repository, with a database that stops answering on demand — the
     * likeliest cause of a failed night, and the one the alert must survive.
     * No {@code @Priority}: selected by {@link Profil} alone.
     */
    @Alternative
    @ApplicationScoped
    public static class DatabaseOutage extends BackupRepository {
        static boolean readFails;
        static boolean writeFails;

        @Override
        public boolean isActive() {
            if (readFails) {
                throw new IllegalStateException("Failed to read the automatic backup settings");
            }
            return super.isActive();
        }

        @Override
        public BackupStreak saveLastRun(BackupRun run, int unrecordedFailures) {
            if (writeFails) {
                throw new IllegalStateException("Failed to record the last automatic backup");
            }
            return super.saveLastRun(run, unrecordedFailures);
        }
    }

    /**
     * Writes a plausible file instead of calling the binary. Enabled for this
     * test only ({@link Profil#getEnabledAlternatives()}): no other test should
     * silently inherit a fake backup.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class PgDumpStub extends PgDump {
        @Override
        public void dumpTo(Path target) throws IOException {
            if (dumpFails) {
                throw new IOException("pg_dump failed (exit 1): connection refused");
            }
            Files.writeString(target, "PGDMP stub");
        }
    }

    @Inject
    BackupService backupService;

    @Inject
    BackupRepository repository;

    @Inject
    MockMailbox mailbox;

    /** The {@code %test} value of {@code MAIL_ADMIN}. */
    private static final String ADMIN = "admin@example.org";

    @BeforeEach
    void emptyTheDirectory() throws IOException {
        mailbox.clear();
        dumpFails = false;
        DatabaseOutage.readFails = false;
        DatabaseOutage.writeFails = false;
        Files.createDirectories(DIRECTORY);
        for (Path path : entries()) {
            Files.delete(path);
        }
        repository.setActive(true);
        repository.saveLastRun(BackupRun.never());
    }

    /**
     * The service keeps the failures the database refused in memory, and the
     * bean outlives each test: a successful run folds them in so none leaks
     * into the next test's counts.
     */
    @AfterEach
    void foldWhatTheDatabaseMissed() {
        DatabaseOutage.readFails = false;
        DatabaseOutage.writeFails = false;
        dumpFails = false;
        backupService.run();
    }

    @Test
    void aRunWritesOneDumpAndRecordsIt() throws IOException {
        BackupRun run = backupService.run();

        assertThat(run.succeeded()).isTrue();
        assertThat(run.file()).matches("planning-\\d{8}-\\d{6}\\.dump");
        assertThat(entries()).hasSize(1);
        assertThat(repository.lastRun().file()).isEqualTo(run.file());
    }

    @Test
    void theRetentionBoundsHowManyDumpsSurvive() throws IOException {
        seed("planning-20260301-040000.dump", "planning-20260302-040000.dump", "planning-20260303-040000.dump");

        backupService.run();

        assertThat(names()).hasSize(RETENTION);
        // The one just written, and the most recent of the seeded ones.
        assertThat(names())
                .contains("planning-20260303-040000.dump")
                .doesNotContain("planning-20260301-040000.dump", "planning-20260302-040000.dump");
    }

    /**
     * A failure at four in the morning that only reaches the container log is a
     * failure nobody reads. It is recorded, and the screen shows it.
     */
    @Test
    void aFailedDumpIsRecordedRatherThanThrown() throws IOException {
        dumpFails = true;

        BackupRun run = backupService.run();

        assertThat(run.succeeded()).isFalse();
        assertThat(run.message()).contains("connection refused");
        assertThat(entries()).isEmpty();
        assertThat(repository.lastRun().succeeded()).isFalse();
    }

    @Test
    void aSuspendedBackupSkipsItsScheduledRun() throws IOException {
        backupService.setActive(false);

        backupService.scheduledBackup();

        assertThat(entries()).isEmpty();
        assertThat(repository.lastRun().ranAtLeastOnce()).isFalse();
    }

    @Test
    void theScheduledRunBacksUpWhileTheSwitchIsOn() throws IOException {
        backupService.scheduledBackup();

        assertThat(entries()).hasSize(1);
    }

    /**
     * A failure at four in the morning is recorded for the screen, and the
     * admin hears about it without opening that screen.
     */
    @Test
    void aFailedScheduledBackupMailsTheAdmin() {
        backupService.scheduledBackup();
        mailbox.clear();
        dumpFails = true;

        backupService.scheduledBackup();

        assertThat(mailbox.getMailsSentTo(ADMIN)).hasSize(1);
        var mail = mailbox.getMailsSentTo(ADMIN).get(0);
        assertThat(mail.getSubject())
                .contains("échec de la sauvegarde nocturne")
                .doesNotContain("consécutives");
        assertThat(mail.getText()).contains("connection refused").contains("Dernière sauvegarde réussie");
    }

    @Test
    void aSecondFailedNightSaysHowManyInARow() {
        dumpFails = true;
        backupService.scheduledBackup();
        backupService.scheduledBackup();

        var mails = mailbox.getMailsSentTo(ADMIN);
        assertThat(mails).hasSize(2);
        assertThat(mails.get(1).getSubject()).contains("2 nuits consécutives");
        assertThat(mails.get(1).getText()).contains("Aucune sauvegarde réussie n'est enregistrée");
    }

    /**
     * The streak lives in the database, not in the bean — which holds no
     * state at all — so reading it back through the repository is what a
     * restart would do.
     */
    @Test
    void theStreakIsReadBackFromTheDatabase() {
        dumpFails = true;
        backupService.scheduledBackup();
        backupService.scheduledBackup();

        BackupStreak streak = repository.saveLastRun(repository.lastRun());

        assertThat(streak.failuresBefore()).isEqualTo(2);
    }

    @Test
    void theFirstSuccessAfterAFailureClosesTheLoop() {
        dumpFails = true;
        backupService.scheduledBackup();
        mailbox.clear();
        dumpFails = false;
        DatabaseOutage.readFails = false;
        DatabaseOutage.writeFails = false;

        backupService.scheduledBackup();

        assertThat(mailbox.getMailsSentTo(ADMIN)).hasSize(1);
        assertThat(mailbox.getMailsSentTo(ADMIN).get(0).getSubject()).contains("rétablie");

        mailbox.clear();
        backupService.scheduledBackup();
        assertThat(mailbox.getMailsSentTo(ADMIN)).isEmpty();
    }

    /** Suspended is a choice, not an outage: nothing to alert about. */
    @Test
    void aSuspendedBackupAlertsNobody() {
        dumpFails = true;
        backupService.setActive(false);

        backupService.scheduledBackup();

        assertThat(mailbox.getMailsSentTo(ADMIN)).isEmpty();
    }

    /**
     * The switch cannot be read: the run is attempted anyway, and its failure
     * still reaches the admin.
     */
    @Test
    void anUnreadableSwitchStillAttemptsAndAlerts() {
        DatabaseOutage.readFails = true;
        dumpFails = true;

        backupService.scheduledBackup();

        assertThat(mailbox.getMailsSentTo(ADMIN)).hasSize(1);
        assertThat(repository.lastRun().succeeded()).isFalse();
    }

    /**
     * The database refuses the record: the mail goes out all the same, and
     * says it cannot date the last success rather than claiming there was none.
     */
    @Test
    void aFailureTheDatabaseCannotRecordIsStillMailedAsUnknown() {
        DatabaseOutage.writeFails = true;
        dumpFails = true;

        backupService.scheduledBackup();

        var mails = mailbox.getMailsSentTo(ADMIN);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).doesNotContain("consécutives");
        assertThat(mails.get(0).getText()).contains("inconnue");
    }

    /**
     * The failure the database never saw is still owed its « rétablie »: the
     * success that follows folds it in and closes the loop.
     */
    @Test
    void theSuccessAfterAnUnrecordedFailureStillClosesTheLoop() {
        DatabaseOutage.writeFails = true;
        dumpFails = true;
        backupService.scheduledBackup();
        mailbox.clear();
        DatabaseOutage.writeFails = false;
        dumpFails = false;

        backupService.scheduledBackup();

        var mails = mailbox.getMailsSentTo(ADMIN);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getSubject()).contains("rétablie");
        assertThat(mails.get(0).getText()).contains("après une tentative en échec");

        mailbox.clear();
        backupService.scheduledBackup();
        assertThat(mailbox.getMailsSentTo(ADMIN)).isEmpty();
    }

    @Test
    void anUnrecordedFailureStillCountsInTheStreak() {
        dumpFails = true;
        backupService.scheduledBackup();
        DatabaseOutage.writeFails = true;
        backupService.scheduledBackup();
        DatabaseOutage.writeFails = false;
        mailbox.clear();

        backupService.scheduledBackup();

        assertThat(mailbox.getMailsSentTo(ADMIN).get(0).getSubject()).contains("3 nuits consécutives");
    }

    /**
     * A database migrated while the last attempt was a failure knows of no
     * success, although the dumps before it are on disk: the newest one dates it.
     */
    @Test
    void theNewestDumpOnDiskDatesTheLastSuccessWhenTheDatabaseCannot() throws IOException {
        seed("planning-20260301-040000.dump", "planning-20260308-040000.dump");
        dumpFails = true;

        backupService.scheduledBackup();

        assertThat(mailbox.getMailsSentTo(ADMIN).get(0).getText())
                .contains("Dernière sauvegarde réussie : 8 mars 2026 à 04:00")
                .doesNotContain("Aucune sauvegarde réussie")
                .doesNotContain("inconnue");
    }

    /** Observed without a real tracker: the event is caught before it would leave. */
    @Test
    void aFailureIsReportedToTheErrorTracker() {
        List<SentryEvent> sent = new CopyOnWriteArrayList<>();
        Sentry.init(options -> {
            options.setDsn("https://public@sentry.invalid/1");
            options.setBeforeSend((event, hint) -> {
                sent.add(event);
                return null;
            });
        });
        try {
            dumpFails = true;

            backupService.run();

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).getTag("job.type")).isEqualTo("backup");
            assertThat(sent.get(0).getThrowable()).hasMessageContaining("connection refused");
        } finally {
            Sentry.close();
        }
    }

    @Test
    void theEndpointReportsWhatTheDeploymentConfiguredAndWhatIsOnDisk() {
        backupService.run();

        given().when()
                .get("/api/backups")
                .then()
                .statusCode(200)
                .body("configured", is(true))
                .body("directory", equalTo(DIRECTORY.toString()))
                .body("active", is(true))
                .body("retention", equalTo(RETENTION))
                .body("directoryError", nullValue())
                .body("alertRecipientMissing", is(false))
                .body("files.size()", equalTo(1))
                .body("lastRun.succeeded", is(true));
    }

    @Test
    void theEndpointSuspendsAndResumesTheBackup() {
        given().contentType("application/json")
                .body(Map.of("active", false))
                .when()
                .put("/api/backups/active")
                .then()
                .statusCode(200)
                .body("active", is(false));

        assertThat(repository.isActive()).isFalse();

        given().contentType("application/json")
                .body(Map.of("active", true))
                .when()
                .put("/api/backups/active")
                .then()
                .statusCode(200)
                .body("active", is(true));
    }

    private void seed(String... names) throws IOException {
        for (String name : names) {
            Files.writeString(DIRECTORY.resolve(name), name);
        }
    }

    private List<String> names() throws IOException {
        return entries().stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
    }

    private List<Path> entries() throws IOException {
        try (Stream<Path> paths = Files.list(DIRECTORY)) {
            return paths.toList();
        }
    }
}
