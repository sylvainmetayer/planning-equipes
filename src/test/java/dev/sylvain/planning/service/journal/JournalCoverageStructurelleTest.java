package dev.sylvain.planning.service.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * « Tracer CHAQUE action » (issue #406), enforced rather than remembered.
 *
 * <p>The journal has two entry points and no single seam: a REST route never
 * meets an MCP tool. So what keeps the promise is not diligence at the call
 * site — there is no call site — but this test: every write-shaped entry point
 * of the application must be either in {@link CatalogueActions}, and therefore
 * journalled, or in its exclusion list <b>with a written reason</b>. A route
 * added tomorrow and forgotten is a build failure, not a silent hole in the
 * history.</p>
 *
 * <p>Reads the sources rather than the classpath, like the other structural
 * tests here: it must see what a developer wrote, including the annotations a
 * proxy would hide.</p>
 */
class JournalCoverageStructurelleTest {

    private static final Path RESOURCES = Path.of("src/main/java/dev/sylvain/planning/api");
    private static final Path OUTILS = Path.of("src/main/java/dev/sylvain/planning/mcp");

    /** A JAX-RS verb that changes something, and the method declaration under it. */
    private static final Pattern VERBE = Pattern.compile("@(POST|PUT|DELETE|PATCH)\\b");

    private static final Pattern DECLARATION =
            Pattern.compile("^\\s*(?:public\\s+|private\\s+|protected\\s+)?[\\w.<>,\\[\\]\\s]+?\\s+(\\w+)\\s*\\(");

    @Test
    void everyWritingRouteIsEitherJournalledOrExcludedWithAReason() throws IOException {
        List<String> orphelines = new ArrayList<>();
        for (Map.Entry<String, String> route : writingRoutes().entrySet()) {
            String cle = route.getKey();
            boolean journalisee = CatalogueActions.forRoute(cle).isPresent();
            boolean exclue = CatalogueActions.untrackedReason(cle).isPresent();
            if (!journalisee && !exclue) {
                orphelines.add(cle + " (" + route.getValue() + ")");
            }
        }
        assertThat(orphelines)
                .as("routes qui écrivent sans laisser de trace — soit elles rejoignent CatalogueActions, "
                        + "soit elles sont déclarées sans trace avec leur motif")
                .isEmpty();
    }

    @Test
    void everyWritingToolIsEitherJournalledOrExcludedWithAReason() throws IOException {
        List<String> orphelins = new ArrayList<>();
        for (String outil : writingTools()) {
            if (CatalogueActions.forTool(outil).isEmpty()
                    && CatalogueActions.untrackedReason(outil).isEmpty()) {
                orphelins.add(outil);
            }
        }
        assertThat(orphelins)
                .as("outils MCP qui écrivent sans laisser de trace")
                .isEmpty();
    }

    /**
     * The mirror of the two tests above: a catalogue entry naming an entry
     * point that no longer exists is dead weight, and would quietly stop
     * covering the route that replaced it.
     */
    @Test
    void theCatalogueNamesNothingThatIsGone() throws IOException {
        var routes = writingRoutes().keySet();
        var lecturesJournalisees = List.of("DatabaseResource#exportDatabase");
        assertThat(CatalogueActions.routes().keySet())
                .as("routes du catalogue qui n'existent plus")
                .allSatisfy(cle -> assertThat(routes.contains(cle) || lecturesJournalisees.contains(cle))
                        .as("%s", cle)
                        .isTrue());

        var outils = writingTools();
        assertThat(CatalogueActions.outils().keySet())
                .as("outils du catalogue qui n'existent plus")
                .allSatisfy(nom -> assertThat(outils).contains(nom));
    }

    /** Every code a route or a tool points at must describe something. */
    @Test
    void everyEntryPointPointsAtADescribedAction() {
        Map<String, ActionJournalisee> actions = CatalogueActions.actions();
        assertThat(CatalogueActions.routes().values())
                .allSatisfy(code ->
                        assertThat(actions).as("action inconnue : %s", code).containsKey(code));
        assertThat(CatalogueActions.outils().values())
                .allSatisfy(code ->
                        assertThat(actions).as("action inconnue : %s", code).containsKey(code));
        assertThat(actions.values()).allSatisfy(action -> {
            assertThat(action.libelle()).as("libellé de %s", action.code()).isNotBlank();
            // Written for an organiser: a sentence, not a constant.
            assertThat(action.libelle()).as("libellé de %s", action.code()).doesNotContain("_");
        });
    }

    /**
     * Every class declaring a journalled tool carries {@code @Journalise}.
     *
     * <p>The hole this closes was real and silent: the interceptor was bound
     * to {@code @EditionCiblee}, which the cross-edition tool classes
     * legitimately do not carry, so six write tools — deleting an entire
     * edition among them — were in the catalogue and intercepted by nobody.
     * Catalogue membership was never proof that a line gets written; this is.</p>
     */
    @Test
    void everyClassHoldingAJournalledToolCarriesTheBinding() throws IOException {
        List<String> sansBinding = new ArrayList<>();
        try (Stream<Path> fichiers = Files.list(OUTILS)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith("McpTools.java"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(fichier);
                boolean journalise = CatalogueActions.outils().keySet().stream()
                        .anyMatch(outil -> source.contains(" " + outil + "("));
                if (journalise && !source.contains("@Journalise")) {
                    sansBinding.add(fichier.getFileName().toString());
                }
            }
        }
        assertThat(sansBinding)
                .as("classes d'outils journalisés que l'intercepteur ne verra jamais — il leur manque @Journalise")
                .isEmpty();
    }

    /**
     * No dead entry in the inventory. One that no route, no tool and no
     * scheduled call can reach is a promise the screen cannot keep: its filter
     * offers a line that will never appear.
     */
    @Test
    void everyActionOfTheInventoryIsReachable() throws IOException {
        Set<String> atteignables =
                new java.util.HashSet<>(CatalogueActions.routes().values());
        atteignables.addAll(CatalogueActions.outils().values());
        atteignables.addAll(codesQuotedBySources());

        assertThat(CatalogueActions.actions().keySet())
                .as("actions décrites que rien ne peut écrire")
                .allSatisfy(code -> assertThat(atteignables).as("%s", code).contains(code));
    }

    /**
     * Action codes named by the sources themselves — a scheduled call, or a
     * route stating which of several actions it just performed. Also proves
     * every such code exists, since the assertion above compares both ways.
     */
    private static Set<String> codesQuotedBySources() throws IOException {
        Pattern cite = Pattern.compile("(?:recordSystemAction|currentAction\\.action)\\(\\s*[^)]*?\"([A-Z_]+)\"");
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> fichiers = Files.walk(Path.of("src/main/java/dev/sylvain/planning"))) {
            for (Path fichier :
                    fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = cite.matcher(Files.readString(fichier));
                while (matcher.find()) {
                    codes.add(matcher.group(1));
                }
            }
        }
        assertThat(codes).as("codes cités par les sources").isNotEmpty();
        assertThat(CatalogueActions.actions().keySet())
                .as("un code cité par une source doit exister au catalogue")
                .containsAll(codes);
        return codes;
    }

    /** A reason that says nothing would be worse than no list at all. */
    @Test
    void everyExclusionCarriesARealReason() {
        assertThat(CatalogueActions.actions()).isNotEmpty();
        for (String cle : List.of(
                "AffectationExplanationResource#explain",
                "CreneauResource#previewRecurrence",
                "StandResource#analyseGrille")) {
            assertThat(CatalogueActions.untrackedReason(cle))
                    .as("motif de %s", cle)
                    .isPresent()
                    .hasValueSatisfying(motif -> assertThat(motif).hasSizeGreaterThan(15));
        }
    }

    /** {@code SimpleClassName#methodName} → the verb, for every write route of {@code api/}. */
    private static Map<String, String> writingRoutes() throws IOException {
        Map<String, String> routes = new java.util.LinkedHashMap<>();
        try (Stream<Path> fichiers = Files.list(RESOURCES)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith("Resource.java"))
                    .sorted()
                    .toList()) {
                String classe = fichier.getFileName().toString().replace(".java", "");
                List<String> lignes = Files.readAllLines(fichier);
                for (int i = 0; i < lignes.size(); i++) {
                    Matcher verbe = VERBE.matcher(lignes.get(i));
                    if (!verbe.find()) {
                        continue;
                    }
                    String methode = methodAfter(lignes, i);
                    if (methode != null) {
                        routes.put(classe + "#" + methode, verbe.group(1));
                    }
                }
            }
        }
        return routes;
    }

    /** Every MCP tool that is not declared read-only. */
    private static java.util.Set<String> writingTools() throws IOException {
        java.util.Set<String> outils = new TreeSet<>();
        try (Stream<Path> fichiers = Files.list(OUTILS)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                List<String> lignes = Files.readAllLines(fichier);
                for (int i = 0; i < lignes.size(); i++) {
                    if (!lignes.get(i).contains("@Tool(")) {
                        continue;
                    }
                    int fin = annotationEnd(lignes, i);
                    String annotation = String.join(" ", lignes.subList(i, Math.min(fin + 1, lignes.size())));
                    String methode = methodAfter(lignes, fin);
                    if (methode != null && !annotation.contains("readOnlyHint = true")) {
                        outils.add(methode);
                    }
                }
            }
        }
        return outils;
    }

    /** The first method declaration after {@code depuis}, skipping annotations. */
    private static String methodAfter(List<String> lignes, int depuis) {
        for (int j = depuis + 1; j < Math.min(depuis + 25, lignes.size()); j++) {
            String ligne = lignes.get(j);
            if (ligne.isBlank()
                    || ligne.strip().startsWith("@")
                    || ligne.strip().startsWith("//")
                    || ligne.strip().startsWith("*")
                    || ligne.strip().startsWith("/*")) {
                continue;
            }
            Matcher declaration = DECLARATION.matcher(ligne);
            if (declaration.find()) {
                return declaration.group(1);
            }
        }
        return null;
    }

    /** Index of the line closing the {@code @Tool(...)} annotation that opens at {@code debut}. */
    private static int annotationEnd(List<String> lignes, int debut) {
        int profondeur = 0;
        for (int i = debut; i < lignes.size(); i++) {
            for (char caractere : lignes.get(i).toCharArray()) {
                if (caractere == '(') {
                    profondeur++;
                } else if (caractere == ')') {
                    profondeur--;
                    if (profondeur == 0) {
                        return i;
                    }
                }
            }
        }
        return debut;
    }
}
