package dev.sylvain.planning.service.backup;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
import java.util.stream.Stream;
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
            return Set.of(PgDumpStub.class);
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

    @BeforeEach
    void emptyTheDirectory() throws IOException {
        dumpFails = false;
        Files.createDirectories(DIRECTORY);
        for (Path path : entries()) {
            Files.delete(path);
        }
        repository.setActive(true);
        repository.saveLastRun(BackupRun.never());
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
