package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * An applied Flyway migration is never edited — held by a test, not by a hook.
 *
 * <p>The rule is in {@code AGENTS.md}, and a Claude hook refuses an
 * {@code Edit} on {@code V*.sql}. Issue #392's C12 pointed out what the hook
 * cannot see: a {@code Write}, a {@code sed -i}, an editor. Flyway itself only
 * notices at startup against a database that already ran the old text — which
 * the test suite, on a fresh container every run, never has. So the check
 * lives here: every migration's digest is committed next to it, and a
 * migration whose text changed fails the build with the file's name.</p>
 *
 * <p>A new migration fails too, on purpose, until its line is appended to
 * {@code src/test/resources/migrations-empreintes.txt}: adding a migration is
 * the moment to say it is meant to be frozen from now on. The failure message
 * prints the line to add.</p>
 */
class FlywayMigrationsFrozenTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path EMPREINTES = Path.of("src/test/resources/migrations-empreintes.txt");

    @Test
    void noAppliedMigrationHasChangedSinceItWasFrozen() throws IOException, NoSuchAlgorithmException {
        Map<String, String> gelees = new LinkedHashMap<>();
        for (String ligne : Files.readAllLines(EMPREINTES, StandardCharsets.UTF_8)) {
            if (ligne.isBlank() || ligne.startsWith("#")) {
                continue;
            }
            String[] champs = ligne.trim().split("\\s+");
            gelees.put(champs[0], champs[1]);
        }

        Map<String, String> actuelles = new TreeMap<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).toList()) {
                actuelles.put(file.getFileName().toString(), sha256(file));
            }
        }

        List<String> modifiees = new ArrayList<>();
        List<String> disparues = new ArrayList<>();
        gelees.forEach((nom, empreinte) -> {
            String actuelle = actuelles.get(nom);
            if (actuelle == null) {
                disparues.add(nom);
            } else if (!actuelle.equals(empreinte)) {
                modifiees.add(nom);
            }
        });
        List<String> nouvelles = actuelles.entrySet().stream()
                .filter(entree -> !gelees.containsKey(entree.getKey()))
                .map(entree -> entree.getKey() + " " + entree.getValue())
                .toList();

        assertThat(modifiees)
                .as("applied migrations whose text changed. Never edit one: add a new versioned file "
                        + "(Vn+1__*.sql) that corrects the schema, and leave this one as it ran")
                .isEmpty();
        assertThat(disparues)
                .as("frozen migrations that no longer exist — a migration that ran somewhere cannot be removed")
                .isEmpty();
        assertThat(nouvelles)
                .as("new migrations not yet frozen. Append these lines to " + EMPREINTES + ":\n"
                        + String.join("\n", nouvelles))
                .isEmpty();
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
