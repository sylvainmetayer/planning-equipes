package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code src/test/container-tests.txt} against the annotations it claims to
 * list: the perimeter of {@code ./mvnw test -Punit} (issue #475, D4 of the
 * audit #392).
 *
 * <p>The profile hands that file to surefire as its {@code excludesFile}, so
 * a class that boots the application is never even selected — which is the
 * only place the exclusion can happen: a JUnit tag is filtered after
 * discovery, and discovery is already what starts Quarkus, dev services and
 * all.</p>
 *
 * <p>A list nothing checks is a list that drifts, and here it drifts in
 * silence: a missing line makes {@code -Punit} fail for want of a database
 * (loud, at least), a stale one makes it skip a test that no longer needs
 * anything (silent, and green). Both are refused below, with the exact lines
 * to write.</p>
 */
class ContainerTestsInventoryTest {

    static final Path INVENTORY = Path.of("src/test/container-tests.txt");

    private static final Path TESTS = Path.of("src/test/java");

    /** {@code @QuarkusTest} on the class, never the word inside the javadoc above it. */
    private static final Pattern BOOTS_THE_APPLICATION = Pattern.compile("^\\s*@QuarkusTest\\b", Pattern.MULTILINE);

    @Test
    void chaqueTestQuiDemarreLApplicationEstInventorie() {
        Set<String> annotees = classesBootingTheApplication();

        assertThat(annotees)
                .as("aucune classe @QuarkusTest trouvée : le scan ne reconnaît plus rien, "
                        + "et -Punit jouerait la suite entière sans base")
                .isNotEmpty();
        assertThat(manquantes(annotees, listed()))
                .as("classes qui démarrent l'application et que " + INVENTORY
                        + " ne liste pas : -Punit les jouerait sans base. Ajoutez la ligne telle quelle.")
                .isEmpty();
    }

    @Test
    void lInventaireNeListeQueDesTestsQuiDemarrentLApplication() {
        assertThat(manquantes(listed(), classesBootingTheApplication()))
                .as("lignes de " + INVENTORY + " qui ne démarrent plus l'application — un test converti, "
                        + "renommé ou supprimé : -Punit les écarte pour rien, et passe au vert sans les jouer")
                .isEmpty();
    }

    /**
     * The annotation, read from the classes themselves rather than from the
     * text above: the two readings are independent, and a regex that stopped
     * matching would otherwise empty the inventory without a word.
     */
    @Test
    void lInventaireDitBienCeQueLAnnotationDit() {
        Set<String> nonAnnotees = new TreeSet<>();
        for (String line : listed()) {
            String name = line.replace(".java", "").replace('/', '.');
            try {
                Class<?> classe = Class.forName(name, false, getClass().getClassLoader());
                if (!classe.isAnnotationPresent(QuarkusTest.class)) {
                    nonAnnotees.add(line);
                }
            } catch (ClassNotFoundException e) {
                nonAnnotees.add(line + " (classe introuvable)");
            }
        }

        assertThat(nonAnnotees)
                .as("lignes de " + INVENTORY + " que l'annotation dément")
                .isEmpty();
    }

    private static Set<String> manquantes(Set<String> attendues, Set<String> presentes) {
        Set<String> manquantes = new TreeSet<>(attendues);
        manquantes.removeAll(presentes);
        return manquantes;
    }

    /** The file, without its header: one source path per line. */
    static Set<String> listed() {
        try {
            return Files.readAllLines(INVENTORY, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The same paths, read from the sources that carry the annotation. */
    static Set<String> classesBootingTheApplication() {
        try (Stream<Path> files = Files.walk(TESTS)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(ContainerTestsInventoryTest::declaresQuarkusTest)
                    .map(source -> TESTS.relativize(source).toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean declaresQuarkusTest(Path source) {
        try {
            return BOOTS_THE_APPLICATION
                    .matcher(Files.readString(source, StandardCharsets.UTF_8))
                    .find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
