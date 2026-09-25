package dev.sylvain.planning.service.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The backup directory: how a dump is named, which files belong to us, and
 * which ones the rotation removes.
 *
 * <p>Deliberately a plain class over a {@link Path}, with no injection and no
 * database: rotation is the part of this feature that <i>deletes</i>, and it
 * has to be provable against a temporary directory in a plain unit test rather
 * than only inside a running application.</p>
 *
 * <p>Two rules make the deletion safe. Only files matching {@link #PATTERN} are
 * ever considered — an operator's own {@code planning-avant-migration.dump}
 * dropped in the same directory is never touched — and a dump is written under
 * a {@code .part} suffix, then moved into place: a run killed halfway leaves
 * something the rotation ignores, never a truncated file that would count as
 * one of the copies kept.</p>
 */
public record BackupStore(Path directory) {

    /** Same shape as the manual command documented in {@code docs/exploitation.md}, to the second. */
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static final String PREFIX = "planning-";
    static final String SUFFIX = ".dump";
    static final String PARTIAL_SUFFIX = ".part";

    /**
     * Fixed-width timestamp, so sorting the names lexicographically sorts them
     * chronologically — the rotation never has to trust a modification time,
     * which a copy or a volume restore rewrites.
     */
    private static final Pattern PATTERN =
            Pattern.compile(Pattern.quote(PREFIX) + "\\d{8}-\\d{6}" + Pattern.quote(SUFFIX));

    static String fileName(LocalDateTime at) {
        return PREFIX + STAMP.format(at) + SUFFIX;
    }

    /**
     * When the dump named {@code name} was taken, in the zone it was named in
     * — empty for a name this class did not produce.
     */
    static Optional<LocalDateTime> takenAt(String name) {
        if (!PATTERN.matcher(name).matches()) {
            return Optional.empty();
        }
        String stamp = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        return Optional.of(LocalDateTime.parse(stamp, STAMP));
    }

    /** Creates the directory if needed, and clears whatever a killed run left behind. */
    void prepare() throws IOException {
        Files.createDirectories(directory);
        for (Path leftover : entries(name -> name.endsWith(PARTIAL_SUFFIX))) {
            Files.deleteIfExists(leftover);
        }
    }

    /**
     * Runs {@code write} against a temporary file and publishes it under its
     * final name only once it returns. The partial file is removed on failure,
     * so a failed run leaves the directory exactly as it found it.
     */
    Path publish(LocalDateTime at, DumpWriter write) throws IOException {
        Path target = directory.resolve(fileName(at));
        Path partial = directory.resolve(target.getFileName() + PARTIAL_SUFFIX);
        try {
            write.writeTo(partial);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
    }

    /** The dumps present, most recent first. */
    public List<BackupFile> list() throws IOException {
        List<BackupFile> files = new ArrayList<>();
        for (Path path : entries(PATTERN.asMatchPredicate())) {
            files.add(new BackupFile(
                    path.getFileName().toString(),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant()));
        }
        files.sort(Comparator.comparing(BackupFile::name).reversed());
        return files;
    }

    /**
     * Keeps the {@code retention} most recent dumps and deletes the rest.
     *
     * @return the names deleted, in the order they went
     */
    List<String> rotate(int retention) throws IOException {
        List<BackupFile> files = list();
        List<String> deleted = new ArrayList<>();
        for (int i = retention; i < files.size(); i++) {
            String name = files.get(i).name();
            Files.deleteIfExists(directory.resolve(name));
            deleted.add(name);
        }
        return deleted;
    }

    private List<Path> entries(java.util.function.Predicate<String> matches) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> matches.test(path.getFileName().toString()))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Produces the dump at the given path; the process call in production, a stub in tests. */
    @FunctionalInterface
    interface DumpWriter {
        void writeTo(Path target) throws IOException;
    }
}
