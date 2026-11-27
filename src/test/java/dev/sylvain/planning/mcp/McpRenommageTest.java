package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code mcp-renommages.tsv} maps every tool name published on {@code v3.1.0} to its current
 * name, or to {@code retiré} (ADR 0100). The 3.1.0 list is frozen in a test resource, copied
 * from the tag once: a CI checkout carries no tags to read it from.
 */
class McpRenommageTest {

    private static final Path TABLE = Path.of("src/main/resources/mcp-renommages.tsv");
    private static final Path V3_1_0 = Path.of("src/test/resources/mcp-outils-v3.1.0.txt");
    private static final Path DOC = Path.of("site/src/docs/comprendre/mcp-outils.md");
    private static final String DEBUT = "<!-- mcp-renommages:debut -->";
    private static final String FIN = "<!-- mcp-renommages:fin -->";

    static final String NOTHING_RENAMED = "Aucun outil n'est encore renommé ni retiré.";
    private static final String REMOVED = "retiré";

    /** {@code <verbe>_<objet>}, the verb drawn from the closed set of ADR 0100. */
    static final Pattern CONVENTION = Pattern.compile("(?:lister|consulter|analyser|diagnostiquer|comparer|simuler"
            + "|previsualiser|creer|modifier|supprimer|activer|desactiver|verrouiller|deverrouiller|appliquer"
            + "|lancer|arreter|importer|exporter|publier|envoyer|restaurer)(?:_[a-z0-9]+)+");

    /**
     * The two verbs of ADR 0100 that carry their object: what is locked is always the planning,
     * and ADR 0109 kept {@code verrouiller} under that name.
     */
    static final Set<String> BARE_VERBS = Set.of("verrouiller", "deverrouiller");

    /** How the description of a former name opens (ADR 0127 § 4), the tool to use next. */
    static final Pattern FORMER_NAME = Pattern.compile("^Ancien nom, gardé jusqu'en 5\\.0 : utiliser ([a-z0-9_]+)\\b");

    /** A published tool, as the guards below read it: its name, whether it is a former one, its description. */
    record Published(String name, boolean deprecated, String description) {}

    static Map<String, String> table(String tsv) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : tsv.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            assertThat(cells).as("two tab-separated columns: %s", line).hasSize(2);
            assertThat(rows.put(cells[0], cells[1]))
                    .as("duplicate row for %s", cells[0])
                    .isNull();
        }
        return rows;
    }

    /** Why a row is wrong, or {@code null}: the whole contract of the table, on plain data. */
    static String violation(Set<String> frozen, Set<String> published, Map<String, String> table) {
        if (!table.keySet().equals(frozen)) {
            Set<String> missing = new TreeSet<>(frozen);
            missing.removeAll(table.keySet());
            Set<String> extra = new TreeSet<>(table.keySet());
            extra.removeAll(frozen);
            return "rows must be exactly the names of v3.1.0; missing " + missing + ", not of v3.1.0 " + extra;
        }
        for (var row : table.entrySet()) {
            String old = row.getKey();
            String now = row.getValue();
            if (REMOVED.equals(now)) {
                if (published.contains(old)) {
                    return old + " is retired but still published";
                }
            } else if (!published.contains(now)) {
                return old + " -> " + now + ": the server publishes no " + now;
            } else if (!old.equals(now) && !CONVENTION.matcher(now).matches()) {
                return old + " -> " + now + ": not <verbe>_<objet> with a verb of ADR 0100";
            }
        }
        return null;
    }

    /**
     * Why the published names break ADR 0100 or 0127, or {@code null}: a current tool outside the
     * convention, a former name that does not say which current tool to use, a description opening
     * on « Ancien nom » without {@code @Deprecated}, or a former name of v3.1.0 whose row names
     * another tool than its description.
     */
    static String namingViolation(List<Published> tools, Map<String, String> table) {
        Set<String> current = tools.stream()
                .filter(tool -> !tool.deprecated())
                .map(Published::name)
                .collect(Collectors.toSet());
        for (Published tool : tools) {
            var former = FORMER_NAME.matcher(tool.description());
            boolean saysFormer = former.find();
            if (!tool.deprecated()) {
                if (saysFormer) {
                    return tool.name() + " opens on « Ancien nom » but is not @Deprecated";
                }
                if (!BARE_VERBS.contains(tool.name())
                        && !CONVENTION.matcher(tool.name()).matches()) {
                    return tool.name() + ": not <verbe>_<objet> with a verb of ADR 0100";
                }
            } else if (!saysFormer) {
                return tool.name() + " is @Deprecated: its description must open on « Ancien nom, gardé jusqu'en "
                        + "5.0 : utiliser <outil> »";
            } else if (!current.contains(former.group(1))) {
                return tool.name() + " sends to " + former.group(1) + ", which is not a current tool";
            } else if (table.containsKey(tool.name()) && !table.get(tool.name()).equals(former.group(1))) {
                return tool.name() + " sends to " + former.group(1) + " where " + TABLE + " says "
                        + table.get(tool.name());
            }
        }
        return null;
    }

    /** The table of {@code mcp-outils.md}: only the rows that changed something. */
    static String rendered(Map<String, String> table) {
        String rows = table.entrySet().stream()
                .filter(row -> !row.getKey().equals(row.getValue()))
                .map(row -> "| `" + row.getKey() + "` | "
                        + (REMOVED.equals(row.getValue()) ? REMOVED : "`" + row.getValue() + "`") + " |")
                .collect(Collectors.joining("\n"));
        // An empty table is a header with no data cell, which axe refuses (th-has-data-cells).
        if (rows.isEmpty()) {
            return NOTHING_RENAMED;
        }
        return ("| Nom en 3.x | Nom en 4.0 |\n| --- | --- |\n" + rows).strip();
    }

    private static Map<String, String> table() throws IOException {
        return table(Files.readString(TABLE, StandardCharsets.UTF_8));
    }

    private static List<Published> publishedTools() throws Exception {
        return OutilsMcp.all().stream()
                .map(method -> new Published(
                        FeatureNames.of(method),
                        method.isAnnotationPresent(Deprecated.class),
                        method.getAnnotation(Tool.class).description()))
                .toList();
    }

    private static Set<String> published() throws Exception {
        return OutilsMcp.all().stream().map(FeatureNames::of).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theTableAccountsForEveryNameOfV310() throws Exception {
        Set<String> frozen = Files.readAllLines(V3_1_0).stream()
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(frozen).as("the frozen 3.1.0 list").hasSize(157);
        assertThat(violation(frozen, published(), table())).isNull();
    }

    /** P77, the closing audit: no current name outside the convention, every former name pointing at one. */
    @Test
    void everyPublishedNameFollowsTheConventionOrSaysWhereItWent() throws Exception {
        assertThat(namingViolation(publishedTools(), table())).isNull();
    }

    /**
     * One row per tool, sorted by its 3.x name, the file ending on one newline: {@code
     * .gitattributes} merges the table as a union of lines, and this puts the order back after
     * one. Two branches renaming the same tool leave two rows, which {@link #table(String)} refuses.
     */
    @Test
    void theTableIsSortedByItsFormerName() throws IOException {
        String tsv = Files.readString(TABLE, StandardCharsets.UTF_8);
        assertThat(tsv)
                .as(TABLE + " ends on exactly one newline")
                .endsWith("\n")
                .doesNotEndWith("\n\n");
        assertThat(List.copyOf(table(tsv).keySet()))
                .as("rows of %s, in the order of their former name", TABLE)
                .isSortedAccordingTo(String::compareTo);
    }

    @Test
    void theDocumentationShowsTheTable() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        int debut = doc.indexOf(DEBUT);
        int fin = doc.indexOf(FIN);
        assertThat(debut).as("marker %s in %s", DEBUT, DOC).isNotNegative();
        assertThat(fin).as("marker %s in %s", FIN, DOC).isGreaterThan(debut);
        String attendu = rendered(table());
        assertThat(doc.substring(debut + DEBUT.length(), fin).strip())
                .as("the renamed tools table of %s is stale. Paste between the markers:\n\n%s\n", DOC, attendu)
                .isEqualTo(attendu);
    }

    /* The guards above, each shown to fire on the mistake it exists for. */

    @Test
    void aToolGoneWithoutARowFails() {
        assertThat(violation(
                        Set.of("lister_stands", "volumes"),
                        Set.of("lister_stands"),
                        Map.of("lister_stands", "lister_stands")))
                .contains("missing [volumes]");
    }

    @Test
    void aRowNamingAnUnpublishedToolFails() {
        assertThat(violation(Set.of("volumes"), Set.of("lister_stands"), Map.of("volumes", "volumes")))
                .contains("publishes no volumes");
    }

    @Test
    void aRetiredToolStillPublishedFails() {
        assertThat(violation(Set.of("volumes"), Set.of("volumes"), Map.of("volumes", REMOVED)))
                .contains("still published");
    }

    @Test
    void aRenameOutsideTheConventionFails() {
        assertThat(violation(Set.of("volumes"), Set.of("volumes_lister"), Map.of("volumes", "volumes_lister")))
                .contains("not <verbe>_<objet>");
        assertThat(violation(Set.of("volumes"), Set.of("calculer_volumes"), Map.of("volumes", "calculer_volumes")))
                .contains("not <verbe>_<objet>");
        assertThat(violation(Set.of("volumes"), Set.of("lister"), Map.of("volumes", "lister")))
                .contains("not <verbe>_<objet>");
        assertThat(violation(Set.of("volumes"), Set.of("consulter_volumes"), Map.of("volumes", "consulter_volumes")))
                .isNull();
    }

    @Test
    void aCurrentNameOutsideTheConventionFails() {
        assertThat(namingViolation(List.of(new Published("lever_consigne", false, "Lève la consigne.")), Map.of()))
                .contains("lever_consigne: not <verbe>_<objet>");
        assertThat(namingViolation(List.of(new Published("verrouiller", false, "Fige.")), Map.of()))
                .isNull();
    }

    @Test
    void aFormerNameMustSayWhichCurrentToolToUse() {
        Published current = new Published("supprimer_consigne", false, "Lève la consigne.");
        assertThat(namingViolation(
                        List.of(current, new Published("lever_consigne", true, "Lève la consigne.")), Map.of()))
                .contains("must open on « Ancien nom");
        assertThat(namingViolation(
                        List.of(
                                current,
                                new Published(
                                        "lever_consigne",
                                        true,
                                        "Ancien nom, gardé jusqu'en 5.0 : utiliser lever_consigne.")),
                        Map.of()))
                .contains("not a current tool");
        assertThat(namingViolation(
                        List.of(
                                current,
                                new Published(
                                        "lever_consigne",
                                        true,
                                        "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_consigne.")),
                        Map.of("lever_consigne", "modifier_consigne")))
                .contains("says modifier_consigne");
        assertThat(namingViolation(
                        List.of(
                                current,
                                new Published(
                                        "lever_consigne",
                                        true,
                                        "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_consigne, qui prend les "
                                                + "mêmes arguments.")),
                        Map.of("lever_consigne", "supprimer_consigne")))
                .isNull();
    }

    @Test
    void aFormerNameMustBeDeprecated() {
        assertThat(namingViolation(
                        List.of(new Published(
                                "lever_consigne",
                                false,
                                "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_consigne.")),
                        Map.of()))
                .contains("is not @Deprecated");
    }

    @Test
    void anUnchangedTableRendersASentenceNotAnEmptyTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("lister_stands", "lister_stands");
        assertThat(rendered(table)).isEqualTo(NOTHING_RENAMED);
    }

    @Test
    void theRenderingKeepsOnlyChangedRows() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("lister_stands", "lister_stands");
        table.put("volumes", "consulter_volumes");
        table.put("lever_gel", REMOVED);
        assertThat(rendered(table))
                .isEqualTo("| Nom en 3.x | Nom en 4.0 |\n| --- | --- |\n"
                        + "| `volumes` | `consulter_volumes` |\n| `lever_gel` | retiré |");
    }
}
