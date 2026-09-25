package dev.sylvain.planning.service.journal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * What makes a {@code GET} a download: a file media type anywhere in its
     * {@code @Produces} — the method's, else its class's, read by reflection so
     * a constant or an array hides nothing — or a {@code Content-Disposition}
     * written by its own body, directly or through {@code CsvDownload.attachment}.
     * Either alone is enough, so moving the header into a helper hides nothing.
     */
    private static final Pattern TYPE_FICHIER = Pattern.compile(
            "(?i)(?:text/(?:csv|calendar|plain)|image/[\\w.+-]+|application/(?:pdf|zip|sql|x-yaml|octet-stream"
                    + "|vnd\\.[\\w.+-]+))(?:\\s*;.*)?");

    /**
     * A download announced by its header, whatever the helper that writes it:
     * the header name in any case, or the {@code attachment; filename=} value.
     */
    private static final Pattern EN_TETE_TELECHARGEMENT = Pattern.compile(
            "CONTENT_DISPOSITION|(?i:content-disposition)|CsvDownload\\.attachment|attachment;|filename=");

    /** Words that may precede a call, never a method's name in its declaration. */
    private static final Set<String> AVANT_UN_APPEL =
            Set.of("new", "return", "throw", "else", "case", "yield", "assert", "await");

    private static final Pattern DECLARATION =
            Pattern.compile("^\\s*(?:public\\s+|private\\s+|protected\\s+)?[\\w.<>,\\[\\]\\s]+?\\s+(\\w+)\\s*\\(");

    /** The published name declared on a {@code @Tool(name = "…")} annotation. */
    private static final Pattern TOOL_NAME = Pattern.compile("@Tool\\(\\s*name\\s*=\\s*\"(\\w+)\"");

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

    /**
     * A download is a read, but leaving with a file of people's data is an act
     * (« qui a sorti la liste des bénévoles, et quand ? »). So a {@code GET}
     * that answers a file is held to the same rule as a write: in the
     * catalogue, or excluded with its reason — a template file, a feed a
     * calendar re-reads on its own.
     */
    @Test
    void everyDownloadRouteIsEitherJournalledOrExcludedWithAReason() throws IOException {
        List<String> orphelines = new ArrayList<>();
        for (String cle : downloadRoutes()) {
            if (CatalogueActions.forRoute(cle).isEmpty()
                    && CatalogueActions.untrackedReason(cle).isEmpty()) {
                orphelines.add(cle);
            }
        }
        assertThat(orphelines)
                .as("téléchargements qui sortent un fichier sans laisser de trace — soit ils rejoignent "
                        + "CatalogueActions, soit ils sont déclarés sans trace avec leur motif")
                .isEmpty();
    }

    /** The detector itself: if it went blind, the test above would pass on nothing. */
    @Test
    void theDownloadScanSeesTheKnownDownloads() throws IOException {
        assertThat(downloadRoutes())
                .contains(
                        "DatabaseResource#export",
                        "ReferenceDataResource#exportCsv",
                        "EquiteResource#exportCsv",
                        "EspaceAnimateurResource#planningPdf",
                        "AbonnementIcsResource#planningIcs",
                        "TypologieResource#exempleCsv")
                .doesNotContain("ReferenceDataResource#volumesExportCsv", "HistoriqueResource#list");
    }

    /* ---------------- Fixtures of the download detector ---------------- */

    static final String CSV = "text/csv";

    /** Either signal alone makes a download: a helper hiding the header, or no {@code @Produces}. */
    static class EitherSignal {
        @GET
        @Produces("text/csv")
        public String byType() {
            return "";
        }

        @GET
        public String byHeader() {
            return "attachment; filename=\"b.csv\"";
        }

        @GET
        public java.util.Map<String, Integer> read() {
            return java.util.Map.of();
        }
    }

    private static final String EITHER_SIGNAL = """
            static class EitherSignal {
                @GET
                @Produces("text/csv")
                public String byType() {
                    return "";
                }

                @GET
                public String byHeader() {
                    return CsvDownload.attachment(csv, "b.csv");
                }

                @GET
                public Map<String, Integer> read() {
                    return Map.of();
                }
            }
            """;

    @Test
    void aDownloadIsRecognisedByItsMediaTypeOrByItsHeader() {
        assertThat(downloadRoutes(EitherSignal.class, EITHER_SIGNAL))
                .containsExactlyInAnyOrder("EitherSignal#byType", "EitherSignal#byHeader");
    }

    /**
     * A method's block ends with its body. Read « from one {@code @GET} to the
     * next verb », a plain read ran on into the javadoc of the next route, and
     * the last route of a class ran to the end of the file — into the private
     * helper that writes the header for somebody else.
     */
    static class NeighbourNoise {
        @GET
        public String first() {
            return "";
        }

        /** Unlike first(), writes a Content-Disposition. */
        @GET
        @Produces("text/csv")
        public String second() {
            return "";
        }

        @GET
        public String last() {
            return "";
        }

        private static String helper() {
            return "attachment; filename=\"x.csv\"";
        }
    }

    private static final String NEIGHBOUR_NOISE = """
            static class NeighbourNoise {
                @GET
                public String first() {
                    return "";
                }

                /** Unlike first(), writes a Content-Disposition. */
                @GET
                @Produces("text/csv")
                public String second() {
                    return "";
                }

                @GET
                public String last() {
                    // no header here: see helper()
                    if (true) { return "{"; }
                    return "";
                }

                private static String helper() {
                    return "attachment; filename=\\"x.csv\\"";
                }
            }
            """;

    @Test
    void aMethodBlockStopsAtTheEndOfItsBody() {
        assertThat(downloadRoutes(NeighbourNoise.class, NEIGHBOUR_NOISE)).containsExactly("NeighbourNoise#second");
        assertThat(methodBlocks(blank(NEIGHBOUR_NOISE, false), blank(NEIGHBOUR_NOISE, true), "last"))
                .singleElement()
                .satisfies(bloc ->
                        assertThat(bloc).startsWith("last()").endsWith("}").doesNotContain("helper()"));
    }

    /** A class-level {@code @Produces} is what every method of the class inherits. */
    @Produces(CSV)
    static class ClassLevelProduces {
        @GET
        public String inherited() {
            return "";
        }
    }

    /** A file type that is not first in the list, or that comes through a constant. */
    static class ListedProduces {
        @GET
        @Produces({"application/json", "text/csv"})
        public String second() {
            return "";
        }

        @GET
        @Produces(CSV)
        public String constant() {
            return "";
        }

        @GET
        @Produces({"application/json"})
        public String json() {
            return "";
        }
    }

    @Test
    void theMediaTypeIsReadWhereverItIsDeclared() {
        assertThat(downloadRoutes(ClassLevelProduces.class, "")).containsExactly("ClassLevelProduces#inherited");
        assertThat(downloadRoutes(ListedProduces.class, ""))
                .containsExactlyInAnyOrder("ListedProduces#second", "ListedProduces#constant");
    }

    /**
     * A download is a copy leaving, not a change to the problem: counted in
     * « données modifiées depuis la résolution », it would mark a plan stale
     * because somebody printed it.
     */
    @Test
    void noExportMarksTheResolutionStale() {
        assertThat(CatalogueActions.exportCodes())
                .isNotEmpty()
                .allSatisfy(code -> assertThat(CatalogueActions.codesChangingData())
                        .as("%s", code)
                        .doesNotContain(code));
    }

    /**
     * The catalogue owns the « Exports » classification the history filters
     * on: a download it journals is flagged as one, or the filter would miss
     * the very file it exists to find.
     */
    @Test
    void everyJournalledDownloadIsFlaggedAsAnExport() throws IOException {
        Set<String> exports = CatalogueActions.exportCodes();
        assertThat(downloadRoutes())
                .filteredOn(cle -> CatalogueActions.forRoute(cle).isPresent())
                .isNotEmpty()
                .allSatisfy(cle -> assertThat(exports)
                        .as("%s doit être déclaré par export(…) au catalogue", cle)
                        .contains(CatalogueActions.routes().get(cle)));
    }

    /**
     * A download on the espace — an open route, a free read — is journalled
     * only once the caller proved who they are, or anyone holding nothing
     * could fill the table. The marker is only meaningful on such a route.
     */
    @Test
    void theOpenDownloadsAreRecordedOnlyWhenProven() throws IOException {
        Set<String> telechargements = downloadRoutes();
        assertThat(CatalogueActions.routesWhenProven()).isNotEmpty().allSatisfy(cle -> {
            assertThat(telechargements)
                    .as("%s n'est pas un téléchargement", cle)
                    .contains(cle);
            assertThat(cle).startsWith("EspaceAnimateurResource#");
        });
        assertThat(telechargements)
                .filteredOn(cle -> cle.startsWith("EspaceAnimateurResource#"))
                .filteredOn(cle -> CatalogueActions.forRoute(cle).isPresent())
                .allSatisfy(cle -> assertThat(CatalogueActions.recordedOnlyWhenProven(cle))
                        .as("%s : téléchargement d'espace journalisé sans preuve d'identité", cle)
                        .isTrue());
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
        // Downloads included: a hand-written list of « journalled reads » is
        // how EXPORT_BASE once named a method that had been renamed, and
        // silently stopped writing anything.
        Set<String> routes = new java.util.HashSet<>(writingRoutes().keySet());
        routes.addAll(downloadRoutes());
        assertThat(CatalogueActions.routes().keySet())
                .as("routes du catalogue qui n'existent plus")
                .allSatisfy(cle -> assertThat(routes).contains(cle));
        assertThat(CatalogueActions.untracked().keySet())
                .filteredOn(cle -> cle.contains("#"))
                .as("routes exclues qui n'existent plus")
                .allSatisfy(cle -> assertThat(routes).contains(cle));

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
     * Action codes named by the sources themselves — a scheduled call, a
     * second act a request carried out beside the one its route names, or a
     * route stating which of several actions it just performed. Also proves
     * every such code exists, since the assertion above compares both ways.
     */
    private static Set<String> codesQuotedBySources() throws IOException {
        Pattern cite = Pattern.compile(
                "(?:recordSystemAction|recordAdminAction|currentAction\\.action)\\(\\s*[^)]*?\"([A-Z_]+)\"");
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

    /** {@code SimpleClassName#methodName} of every {@code GET} of {@code api/} that answers a file. */
    private static Set<String> downloadRoutes() throws IOException {
        Set<String> routes = new TreeSet<>();
        Path racine = Path.of("src/main/java");
        // Recursive, and every Java file: a resource moved to a sub-package or
        // named otherwise must not leave the net by its path.
        try (Stream<Path> fichiers = Files.walk(RESOURCES)) {
            for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String nom = racine.relativize(fichier).toString().replace(".java", "");
                Class<?> classe = loadClass(nom.replace(java.io.File.separatorChar, '.'));
                routes.addAll(downloadRoutes(classe, Files.readString(fichier)));
            }
        }
        return routes;
    }

    private static Class<?> loadClass(String nom) {
        try {
            // Not initialised: reading annotations must not run a resource's static code.
            return Class.forName(nom, false, JournalCoverageStructurelleTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Source sans classe compilée : " + nom, e);
        }
    }

    /**
     * The downloads of one class. The media type is read off the compiled
     * annotations; the header off the method's own source, delimited from its
     * declaration to the brace that closes its body — never lent to the
     * javadoc or the helper that follows it, and never running to the end of
     * the file.
     */
    static Set<String> downloadRoutes(Class<?> classe, String source) {
        Set<String> routes = new TreeSet<>();
        String code = blank(source, false);
        String squelette = blank(source, true);
        for (Method methode : classe.getDeclaredMethods()) {
            if (!methode.isAnnotationPresent(GET.class) || methode.isSynthetic()) {
                continue;
            }
            boolean enTete = methodBlocks(code, squelette, methode.getName()).stream()
                    .anyMatch(bloc -> EN_TETE_TELECHARGEMENT.matcher(bloc).find());
            if (producesAFile(methode) || enTete) {
                routes.add(classe.getSimpleName() + "#" + methode.getName());
            }
        }
        return routes;
    }

    /** A file media type anywhere in the method's {@code @Produces}, else in its class's. */
    static boolean producesAFile(Method methode) {
        Produces produit = methode.getAnnotation(Produces.class);
        if (produit == null) {
            produit = methode.getDeclaringClass().getAnnotation(Produces.class);
        }
        return produit != null
                && Arrays.stream(produit.value())
                        .flatMap(valeur -> Arrays.stream(valeur.split(",")))
                        .anyMatch(type -> TYPE_FICHIER.matcher(type.strip()).matches());
    }

    /**
     * The text of every {@code GET} declaration of {@code nom} — its signature
     * and its body, comments blanked — found on the skeleton, where neither a
     * comment nor a string literal can hold a brace that would mislead the
     * count. An overload that is not a {@code GET} is left out when one is.
     */
    static List<String> methodBlocks(String code, String squelette, String nom) {
        List<String> blocs = new ArrayList<>();
        List<String> sansGet = new ArrayList<>();
        Matcher appel = Pattern.compile("\\b" + Pattern.quote(nom) + "\\s*\\(").matcher(squelette);
        while (appel.find()) {
            int debut = appel.start();
            if (!declaresHere(squelette, debut)) {
                continue;
            }
            int finParametres = closing(squelette, appel.end() - 1, '(', ')');
            if (finParametres < 0) {
                continue;
            }
            Matcher corps =
                    Pattern.compile("\\G\\s*(?:throws\\s+[\\w.,\\s]+?)?\\s*\\{").matcher(squelette);
            if (!corps.find(finParametres + 1)) {
                continue;
            }
            int fin = closing(squelette, corps.end() - 1, '{', '}');
            if (fin < 0) {
                continue;
            }
            String bloc = code.substring(debut, fin + 1);
            (annotations(squelette, debut).contains("@GET") ? blocs : sansGet).add(bloc);
        }
        return blocs.isEmpty() ? sansGet : blocs;
    }

    /** Whether the identifier at {@code debut} names a declaration rather than a call. */
    private static boolean declaresHere(String squelette, int debut) {
        int i = debut - 1;
        while (i >= 0 && Character.isWhitespace(squelette.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char avant = squelette.charAt(i);
        if (!(Character.isJavaIdentifierPart(avant) || avant == '>' || avant == ']')) {
            return false;
        }
        int finMot = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(squelette.charAt(i))) {
            i--;
        }
        return !AVANT_UN_APPEL.contains(squelette.substring(i + 1, finMot));
    }

    /** What precedes a declaration back to the previous member's end: its modifiers and annotations. */
    private static String annotations(String squelette, int debut) {
        int i = debut - 1;
        while (i >= 0 && ";{}".indexOf(squelette.charAt(i)) < 0) {
            i--;
        }
        return squelette.substring(i + 1, debut);
    }

    /** Index of the delimiter closing the one opened at {@code ouverture}, {@code -1} when unbalanced. */
    private static int closing(String squelette, int ouverture, char ouvrant, char fermant) {
        int profondeur = 0;
        for (int i = ouverture; i < squelette.length(); i++) {
            char c = squelette.charAt(i);
            if (c == ouvrant) {
                profondeur++;
            } else if (c == fermant && --profondeur == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The source with its comments — and, when {@code chaines}, its string
     * and character literals — replaced by spaces, every offset and line
     * break kept, so a match on one view is a range of the other.
     */
    static String blank(String source, boolean chaines) {
        StringBuilder sortie = new StringBuilder(source);
        int i = 0;
        while (i < source.length()) {
            int fin;
            boolean chaine = false;
            if (source.startsWith("//", i)) {
                fin = source.indexOf('\n', i);
                fin = fin < 0 ? source.length() : fin;
            } else if (source.startsWith("/*", i)) {
                fin = source.indexOf("*/", i + 2);
                fin = fin < 0 ? source.length() : fin + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                fin = source.indexOf("\"\"\"", i + 3);
                fin = fin < 0 ? source.length() : fin + 3;
                chaine = true;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char guillemet = source.charAt(i);
                fin = i + 1;
                while (fin < source.length() && source.charAt(fin) != guillemet) {
                    fin += source.charAt(fin) == '\\' ? 2 : 1;
                }
                fin = Math.min(fin + 1, source.length());
                chaine = true;
            } else {
                i++;
                continue;
            }
            if (!chaine || chaines) {
                for (int j = i; j < fin; j++) {
                    if (source.charAt(j) != '\n') {
                        sortie.setCharAt(j, ' ');
                    }
                }
            }
            i = fin;
        }
        return sortie.toString();
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
                    // The published name, declared on the annotation; the method
                    // name is the Java one and names no journal entry.
                    Matcher nom = TOOL_NAME.matcher(annotation);
                    String outil = nom.find() ? nom.group(1) : methodAfter(lignes, fin);
                    if (outil != null && !annotation.contains("readOnlyHint = true")) {
                        outils.add(outil);
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
