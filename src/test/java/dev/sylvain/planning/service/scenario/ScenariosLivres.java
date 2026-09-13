package dev.sylvain.planning.service.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The scenarios shipped under {@code src/main/resources/scenarios} — the one
 * folder the selector of the Débogage screen reads, so everything a user can
 * pick is here: the hand-written demo fixtures, the thirty rungs of the ladder
 * and the fifteen extreme cases.
 *
 * <p>{@link #references()} is the subset the differential tests pin. Those
 * tests carry a canonical form per file under
 * {@code src/test/resources/scenario-empreintes*}, and a reference for a
 * generated file would be a generated reference: nobody would read it, and the
 * one for {@code extreme-09} alone would weigh more than the rest of the
 * corpus. The generated files have their own guard — {@code ScenarioLadder*}
 * and {@code ScenarioExtreme*} solve them — so what is left to pin here is the
 * files a human wrote.</p>
 */
public final class ScenariosLivres {

    /** Where every shipped scenario lives, as a source path (not the classpath one {@code ScenarioLadder} reads). */
    public static final Path SOURCE_FOLDER = Path.of("src/main/resources/scenarios");

    private ScenariosLivres() {}

    /** Every shipped scenario file, sorted. */
    public static List<Path> all() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE_FOLDER)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().matches(".*\\.ya?ml"))
                    // The reel-*.yaml scenarios are gitignored (real personal
                    // data): present only on the machine of whoever produced
                    // them, never in the repository nor in CI.
                    .filter(path -> !path.getFileName().toString().startsWith("reel-"))
                    .sorted()
                    .toList();
        }
    }

    /** The hand-written fixtures: everything that is neither a ladder rung nor an extreme case. */
    public static List<Path> references() throws IOException {
        return all().stream().filter(path -> !isGenerated(nom(path))).toList();
    }

    /** The names, without extension, of the shipped files carrying {@code prefix}. */
    public static List<String> noms(String prefix) throws IOException {
        return all().stream()
                .map(ScenariosLivres::nom)
                .filter(nom -> nom.startsWith(prefix))
                .toList();
    }

    public static String nom(Path scenario) {
        return scenario.getFileName().toString().replaceAll("\\.ya?ml$", "");
    }

    private static boolean isGenerated(String nom) {
        return nom.startsWith(ScenarioLadder.GAMME_PREFIX) || nom.startsWith(ScenarioLadder.EXTREME_PREFIX);
    }
}
