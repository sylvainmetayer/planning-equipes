package dev.sylvain.planning.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The half of the feature that deletes files, proved against a real directory.
 *
 * <p>Rotation is where a backup turns into data loss: keep one file too few and
 * the copy someone needed is gone, match one file too many and a dump an
 * operator dropped there by hand disappears. Both cases are asserted here
 * rather than left to a reading of the regular expression.</p>
 */
class BackupStoreTest {

    @TempDir
    Path directory;

    @Test
    void namesADumpAfterTheInstantItWasTaken() {
        assertThat(BackupStore.fileName(LocalDateTime.of(2026, 3, 8, 4, 0, 0)))
                .isEqualTo("planning-20260308-040000.dump");
    }

    @Test
    void listsTheDumpsMostRecentFirst() throws IOException {
        seed("planning-20260306-040000.dump", "planning-20260308-040000.dump", "planning-20260307-040000.dump");

        assertThat(new BackupStore(directory).list()).extracting(BackupFile::name)
                .containsExactly("planning-20260308-040000.dump", "planning-20260307-040000.dump",
                        "planning-20260306-040000.dump");
    }

    @Test
    void keepsTheRetentionMostRecentDumpsAndDeletesTheRest() throws IOException {
        seed("planning-20260301-040000.dump", "planning-20260302-040000.dump", "planning-20260303-040000.dump",
                "planning-20260304-040000.dump");

        List<String> deleted = new BackupStore(directory).rotate(2);

        assertThat(deleted).containsExactly("planning-20260302-040000.dump", "planning-20260301-040000.dump");
        assertThat(names()).containsExactlyInAnyOrder("planning-20260304-040000.dump",
                "planning-20260303-040000.dump");
    }

    @Test
    void deletesNothingWhileFewerDumpsThanTheRetentionArePresent() throws IOException {
        seed("planning-20260301-040000.dump", "planning-20260302-040000.dump");

        assertThat(new BackupStore(directory).rotate(10)).isEmpty();
        assertThat(names()).hasSize(2);
    }

    /**
     * The directory belongs to the operator too: a dump they took by hand
     * before a risky migration, and named as such, is not ours to rotate away.
     */
    @Test
    void neverTouchesAFileItDidNotWrite() throws IOException {
        seed("planning-20260301-040000.dump", "planning-20260302-040000.dump",
                "planning-avant-migration.dump", "notes.txt");

        new BackupStore(directory).rotate(1);

        assertThat(names()).contains("planning-avant-migration.dump", "notes.txt");
    }

    @Test
    void publishesADumpUnderItsFinalNameOnlyOnceItIsWritten() throws IOException {
        BackupStore store = new BackupStore(directory);
        store.prepare();

        Path published = store.publish(LocalDateTime.of(2026, 3, 8, 4, 0, 0),
                target -> Files.writeString(target, "dump"));

        assertThat(published).hasFileName("planning-20260308-040000.dump").hasContent("dump");
        assertThat(names()).containsExactly("planning-20260308-040000.dump");
    }

    /**
     * A failed dump must leave the directory as it found it. A half-written
     * file left under its final name would be counted as one of the copies
     * kept — and would push a real one out on the next rotation.
     */
    @Test
    void leavesNothingBehindWhenTheDumpFails() throws IOException {
        BackupStore store = new BackupStore(directory);
        store.prepare();

        assertThatThrownBy(() -> store.publish(LocalDateTime.of(2026, 3, 8, 4, 0, 0), target -> {
            Files.writeString(target, "half a dump");
            throw new IOException("pg_dump failed (exit 1)");
        })).isInstanceOf(IOException.class);

        assertThat(names()).isEmpty();
    }

    /** A run killed by a container restart leaves a {@code .part} file; the next one clears it. */
    @Test
    void clearsWhatAKilledRunLeftBehind() throws IOException {
        seed("planning-20260308-040000.dump.part", "planning-20260307-040000.dump");

        new BackupStore(directory).prepare();

        assertThat(names()).containsExactly("planning-20260307-040000.dump");
    }

    @Test
    void createsTheDirectoryWhenTheVolumeIsMountedEmpty() throws IOException {
        Path nested = directory.resolve("backups");

        new BackupStore(nested).prepare();

        assertThat(nested).isDirectory();
    }

    private void seed(String... names) throws IOException {
        for (String name : names) {
            Files.writeString(directory.resolve(name), name);
        }
    }

    private List<String> names() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
