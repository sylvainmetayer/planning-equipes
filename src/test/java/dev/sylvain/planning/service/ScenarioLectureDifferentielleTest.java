package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.sylvain.planning.domain.ParametresLegaux;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What every bundled scenario reads as, pinned element by element, so that
 * replacing the reader cannot change it by accident.
 *
 * <p>A2 of issue #392 replaces roughly 590 lines of hand-written traversal of
 * {@code Map<String,Object>} by a mapping from {@code ScenarioDto}. That is a
 * lot of business semantics to move — default values, derivations, the order
 * typologies are applied in — and a suite that only checks a handful of fields
 * per scenario would let most of it drift silently. This test is the net, and
 * it is written <b>before</b> the switch rather than after: a reference taken
 * from the new reader would only prove the new reader agrees with itself.</p>
 *
 * <p><b>How to read a failure.</b> The message names the elements that differ —
 * {@code .planning.postes[912]}, {@code .sections.parametresLegaux…}. The full
 * form of both sides is written to {@code target/scenario-differentiel/} so the
 * exact field can be diffed; the reference is folded to one digest per element
 * precisely so the repository does not carry 4.4 Mio of it.</p>
 *
 * <p><b>When the reference legitimately changes</b> — a scenario file edited, a
 * domain field added — the failure says which file to regenerate, and the
 * regenerated file is reviewed like any other diff. A reference updated without
 * being read is worth nothing.</p>
 */
class ScenarioLectureDifferentielleTest {

    private static final Path REFERENCES = Path.of("src/test/resources/scenario-empreintes");

    private static final Path SORTIE = Path.of("target/scenario-differentiel");

    @Test
    void chaqueScenarioLivreSeLitCommeSaReference() throws IOException {
        List<Path> scenarios = scenariosLivres();
        assertThat(scenarios).as("les scénarios livrés doivent être trouvés").hasSizeGreaterThan(5);

        List<String> ecarts = new java.util.ArrayList<>();
        List<String> ecrites = new java.util.ArrayList<>();
        for (Path scenario : scenarios) {
            ecarts.addAll(comparer(scenario, ecrites));
        }

        // All of them at once, not the first one: regenerating one reference at
        // a time would take as many passes as there are scenarios.
        if (!ecrites.isEmpty()) {
            fail("Références absentes, elles viennent d'être écrites : %s. Relisez-les, puis commitez-les.",
                    String.join(", ", ecrites));
        }

        assertThat(ecarts)
                .as("""
                        Le scénario ne se lit plus comme sa référence. Les deux formes complètes \
                        sont dans target/scenario-differentiel/. Si l'écart est voulu, régénérez \
                        la référence — et relisez-la avant de la commiter.""")
                .isEmpty();
    }

    private List<String> comparer(Path scenario, List<String> ecrites) throws IOException {
        String nom = scenario.getFileName().toString().replaceAll("\\.ya?ml$", "");
        ScenarioYamlReader.ScenarioImporte lu =
                ScenarioYamlReader.buildFromScenarioText(Files.readString(scenario), ParametresLegaux::new);

        List<String> obtenues = FormeCanonique.empreintes(lu);
        Path reference = REFERENCES.resolve(nom + ".txt");

        if (!Files.exists(reference)) {
            Files.createDirectories(reference.getParent());
            Files.write(reference, obtenues);
            ecrites.add(reference.toString());
            return List.of();
        }

        List<String> attendues = Files.readAllLines(reference);
        if (attendues.equals(obtenues)) {
            return List.of();
        }

        writeBothFormsForDiffing(nom, lu, obtenues, attendues);
        return premieresDifferences(nom, attendues, obtenues);
    }

    /**
     * Both full forms on disk, not just the digests: a digest says that an
     * element moved, never which field inside it did.
     */
    private void writeBothFormsForDiffing(String nom, Object lu, List<String> obtenues, List<String> attendues)
            throws IOException {
        Files.createDirectories(SORTIE);
        Files.writeString(SORTIE.resolve(nom + "-obtenu-complet.txt"), FormeCanonique.of(lu));
        Files.write(SORTIE.resolve(nom + "-obtenu.txt"), obtenues);
        Files.write(SORTIE.resolve(nom + "-attendu.txt"), attendues);
    }

    /** At most five, named: a wall of differences says less than the first of them. */
    private static List<String> premieresDifferences(String nom, List<String> attendues, List<String> obtenues) {
        List<String> differences = new java.util.ArrayList<>();
        int commun = Math.min(attendues.size(), obtenues.size());
        for (int i = 0; i < commun && differences.size() < 5; i++) {
            if (!attendues.get(i).equals(obtenues.get(i))) {
                differences.add(nom + " : " + resume(attendues.get(i)) + " ≠ " + resume(obtenues.get(i)));
            }
        }
        if (differences.isEmpty() && attendues.size() != obtenues.size()) {
            differences.add(nom + " : " + attendues.size() + " éléments attendus, " + obtenues.size() + " obtenus");
        }
        return differences;
    }

    private static String resume(String ligne) {
        return ligne.length() <= 90 ? ligne : ligne.substring(0, 90) + "…";
    }

    private static List<Path> scenariosLivres() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/scenarios"))) {
            return files.filter(path -> path.toString().matches(".*\\.ya?ml")).sorted().toList();
        }
    }
}
