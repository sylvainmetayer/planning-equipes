package dev.sylvain.planning.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Comparing what something reads as against a reference committed next to it.
 *
 * <p>Shared by the two halves of the scenario format, which pin the same thing
 * for the same reason: A2 of issue #392 replaces both the reader and the
 * writer, and a suite checking a handful of fields per scenario would let most
 * of the semantics drift silently.</p>
 *
 * <p><b>How to read a failure.</b> The message names the elements that differ —
 * {@code .planning.postes[912]}, {@code .sections.parametresLegaux…}. The full
 * form of both sides is written under {@code target/scenario-differentiel/} so
 * the exact field can be diffed; the reference is folded to one digest per
 * element precisely so the repository does not carry megabytes of it.</p>
 *
 * <p><b>When a reference legitimately changes</b> — a scenario file edited, a
 * domain field added — the failure says which file to regenerate, and the
 * regenerated file is reviewed like any other diff. A reference updated without
 * being read is worth nothing.</p>
 */
final class ReferenceComparison {

    private static final Path SORTIE = Path.of("target/scenario-differentiel");

    private final Path references;

    private final List<String> written = new ArrayList<>();

    ReferenceComparison(String dossier) {
        this.references = Path.of("src/test/resources").resolve(dossier);
    }

    /** @return the differences found, empty when the reference is matched or was just written */
    List<String> compare(String nom, Object lu) throws IOException {
        List<String> obtenues = FormeCanonique.empreintes(lu);
        Path reference = references.resolve(nom + ".txt");

        if (!Files.exists(reference)) {
            Files.createDirectories(reference.getParent());
            Files.write(reference, obtenues);
            written.add(reference.toString());
            return List.of();
        }

        List<String> attendues = Files.readAllLines(reference);
        if (attendues.equals(obtenues)) {
            return List.of();
        }

        // Both full forms on disk, not just the digests: a digest says that an
        // element moved, never which field inside it did.
        Files.createDirectories(SORTIE);
        Files.writeString(SORTIE.resolve(nom + "-obtenu-complet.txt"), FormeCanonique.of(lu));
        Files.write(SORTIE.resolve(nom + "-obtenu.txt"), obtenues);
        Files.write(SORTIE.resolve(nom + "-attendu.txt"), attendues);
        return premieresDifferences(nom, attendues, obtenues);
    }

    /** References written during this run, empty when they all already existed. */
    List<String> written() {
        return written;
    }

    /** At most five, named: a wall of differences says less than the first of them. */
    private static List<String> premieresDifferences(String nom, List<String> attendues, List<String> obtenues) {
        List<String> differences = new ArrayList<>();
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
}
