package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The client's own vocabulary must not come back into the tree.
 *
 * <p>This repository was opened by rewriting the history of a private one: the
 * name of the organisation it was first built for, the town it takes place in
 * and the mascot were purged from every commit. The tree, however, was cleaned
 * by hand — three times in three weeks, and the last count went from eleven
 * occurrences to seventeen in a single day. A convention nothing checks drifts,
 * and this one drifts back into a repository that is now public.</p>
 *
 * <p>So this is a test, not a paragraph. It reads every text file of the tree,
 * its own path included, and fails on the client's name, on the town, on the
 * mascot, and on the acronym — <em>except</em> where the acronym is not the
 * client.</p>
 *
 * <h2>What is allowed, and why</h2>
 *
 * <p>Two exceptions, both real, both narrow:</p>
 *
 * <ul>
 *   <li><b>The English verb.</b> « a switch to flip », « the machine flips »,
 *       « already flipped itself » — twenty-odd lines of the frontend say it,
 *       and none of them names anybody. Only the capitalised forms are refused,
 *       so the lowercase verb passes untouched.</li>
 *   <li><b>A game whose name contains it</b>, {@code FLIP7} — a fixture stand
 *       code in {@code grille-horaires.spec.ts}. A word boundary does the job on
 *       its own: a digit is a word character, so {@code \bFLIP\b} never matches
 *       it.</li>
 * </ul>
 *
 * <p>What no exception covers is the acronym glued into an identifier — the
 * shape every real leak took: a purged file name, an edition id, a
 * configuration prefix, a contact address. Those are refused whatever their
 * case, which is why the two rules are separate rather than one clever one.</p>
 *
 * <p>The cost is one known false positive: « flip-flop », were anybody to write
 * it. Renaming it is cheaper than loosening the rule.</p>
 */
class ClientVocabularyStructuralTest {

    /** The town, and the mascot. Nothing legitimate spells either. */
    private static final Pattern TOWN = Pattern.compile("(?i)parthenay");

    private static final Pattern MASCOT = Pattern.compile("(?i)woopy");

    /**
     * The acronym standing alone, capitalised. {@code FLIP7} escapes on the
     * trailing word boundary, and the lowercase English verb is not matched at
     * all — that is the whole point of refusing only these two spellings.
     */
    private static final Pattern ACRONYM = Pattern.compile("\\b(?:FLIP|Flip)\\b");

    /**
     * The acronym glued into an identifier, any case: {@code demo-flip-2026},
     * {@code flip-planning}, {@code scenario-flip.yaml}, {@code FLIP_MCP_TOKEN},
     * {@code flip@…}. The separator must sit between two word characters, so a
     * sentence ending on « … to flip. » is not a match.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("(?i)(?:\\w[-._/@]flip|flip[-._/@]\\w)");

    private static final List<Pattern> RULES = List.of(TOWN, MASCOT, ACRONYM, IDENTIFIER);

    /** Not sources: build output, dependencies, and git's own storage. */
    private static final Set<String> SKIPPED =
            Set.of(".git", "node_modules", "target", "dist", "coverage", ".angular", ".mvn");

    /** This file names what it refuses, so it cannot hold itself to the rule. */
    private static final Path SELF = Path.of("src/test/java/dev/sylvain/planning/ClientVocabularyStructuralTest.java");

    private static final Path ROOT = Path.of(".");

    @Test
    void noFileNamesTheClient() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : textFiles()) {
            String relative = ROOT.relativize(file).toString().replace('\\', '/');
            hit(relative).ifPresent(m -> found.add(relative + " — path names « " + m + " »"));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int number = i + 1;
                hit(line).ifPresent(m -> found.add(relative + ":" + number + " — « " + m + " » in: " + excerpt(line)));
            }
        }
        assertThat(found)
                .as("the client's vocabulary is back in the tree. Rewrite these lines: the "
                        + "workbook is « the staffing workbook », the organisation is « the "
                        + "organiser », the town is « Centre-ville ». The history of this "
                        + "repository was purged of them once and cannot be a second time")
                .isEmpty();
    }

    /**
     * The rules, held to their own examples. Every refused string below is a
     * line this repository actually carried before it was opened; every accepted
     * one is a line it carries today. Three times during the migration a regex
     * was tightened or loosened and quietly stopped meaning what it was supposed
     * to — a scan that finds nothing looks exactly like a scan that checks
     * nothing.
     */
    @Test
    void theRulesSayWhatTheyMean() {
        List<String> refused = List.of(
                "the rule of the FLIP workbook",
                "Association Flip, 1 rue du Jeu",
                "the reference dataset is edition demo-flip-2026",
                "ghcr.io/sylvainmetayer/flip-planning",
                "contact: flip@sylvain.dev",
                "FLIP_MCP_TOKEN",
                "src/main/webui/public/flip.png",
                "Mairie de Parthenay",
                "PARTHENAY",
                "scroll-hint-woopy",
                "WoopyDialog",
                "scenario-flip.yaml");
        List<String> accepted = List.of(
                "stands: ['DIV', 'FLIP7']",
                "['FLIP7#1@14:00-20:00', [{ heureDebut: '14:00' }]]",
                "a reader must not go looking for a switch to flip.",
                "the *button* stops lying when the machine flips to dark at sunset",
                "the switch has already flipped itself to `checked`",
                "or \"tout replier\" would flip depending on the mode",
                "/** Flipped by the tests that want the dump to fail. */",
                "relay flags flipped in place, ids kept",
                "the decoupage flips it off on the amplitudes",
                "a single `color-scheme` flips every `light-dark()` pair");

        assertThat(refused.stream().filter(s -> hit(s).isEmpty()).toList())
                .as("lines this repository carried before it was opened, which must be refused")
                .isEmpty();
        assertThat(accepted.stream().filter(s -> hit(s).isPresent()).toList())
                .as("lines this repository carries today, which must pass")
                .isEmpty();
    }

    /** The first rule that matches, or empty. */
    private static java.util.Optional<String> hit(String text) {
        for (Pattern rule : RULES) {
            Matcher m = rule.matcher(text);
            if (m.find()) {
                return java.util.Optional.of(m.group());
            }
        }
        return java.util.Optional.empty();
    }

    private static String excerpt(String line) {
        String t = line.strip();
        return t.length() <= 90 ? t : t.substring(0, 90) + "…";
    }

    /**
     * Every text file of the tree. Binary content is skipped on a NUL byte in
     * its head rather than on a list of extensions: the list is what goes stale.
     */
    private static List<Path> textFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(ROOT)) {
            for (Path p : walk.toList()) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Path relative = ROOT.relativize(p);
                if (relative.equals(SELF) || skipped(relative) || isBinary(p)) {
                    continue;
                }
                files.add(p);
            }
        }
        files.sort(Path::compareTo);
        return files;
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (SKIPPED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBinary(Path file) throws IOException {
        byte[] head = new byte[8000];
        int read;
        try (var in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        for (int i = 0; i < read; i++) {
            if (head[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
