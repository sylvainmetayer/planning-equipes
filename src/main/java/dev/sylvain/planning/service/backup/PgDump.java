package dev.sylvain.planning.service.backup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runs the real {@code pg_dump} against the configured datasource.
 *
 * <p>A separate bean, and not three private methods of {@link BackupService},
 * for one reason: it is the single part of the feature that cannot run in a
 * test. Everything above it — rotation, the retention, what the screen shows
 * after a failure — is exercised against a stub replacing this bean, so the
 * suite never shells out to a binary whose presence and version depend on the
 * machine.</p>
 *
 * <p>The custom format is what the restore procedure expects: it is compressed,
 * it carries the schema, and {@code pg_restore --clean --if-exists} replays it
 * without the application being up. Restoring is out of the application's
 * scope on purpose — see {@code docs/exploitation.md}.</p>
 */
@ApplicationScoped
public class PgDump {

    @Inject
    BackupConfiguration configuration;

    /**
     * Optional, all three: with dev services the datasource is configured at
     * runtime, and a required injection would fail the boot of every test
     * before this class ever ran. A deployment missing them fails at the first
     * backup instead, where the message is recorded and shown.
     */
    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    Optional<String> jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    Optional<String> username;

    @ConfigProperty(name = "quarkus.datasource.password")
    Optional<String> password;

    /**
     * Writes a compressed dump of the whole database at {@code target}.
     *
     * @throws IOException when the binary is missing, the server refuses, or
     *         the dump does not finish inside {@code planning.backup.timeout}
     */
    public void dumpTo(Path target) throws IOException {
        DatabaseCoordinates coordinates = DatabaseCoordinates.parse(
                jdbcUrl.orElseThrow(() -> new IllegalStateException("No datasource URL is configured")));
        List<String> command = List.of(
                configuration.pgDumpCommand(),
                "--host=" + coordinates.host(),
                "--port=" + coordinates.port(),
                "--username=" + username.orElse(""),
                "--dbname=" + coordinates.database(),
                "--format=custom",
                // The password travels in the environment, never on a command
                // line every process on the host can read.
                "--no-password",
                "--file=" + target.toAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.put("PGPASSWORD", password.orElse(""));
        builder.redirectErrorStream(true);
        run(builder, command.get(0));
    }

    private void run(ProcessBuilder builder, String binary) throws IOException {
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Could not run " + binary + ": " + e.getMessage(), e);
        }
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        boolean finished;
        try {
            finished = process.waitFor(configuration.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for " + binary, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(binary + " did not finish within " + configuration.timeout());
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    binary + " failed (exit " + process.exitValue() + ")" + (output.isEmpty() ? "" : ": " + output));
        }
    }
}
