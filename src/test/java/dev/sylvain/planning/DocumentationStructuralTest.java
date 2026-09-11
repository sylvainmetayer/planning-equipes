package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the documentation enumerates, checked against what the code declares.
 *
 * <p>Issue #392 measured the drift: {@code docs/api.md} cited three endpoints
 * that do not exist, {@code AGENTS.md} listed 31 of the 48 routes and
 * {@code docs/contraintes.md} named 13 of the 40 constraints — and the
 * repository had already proved its own theorem, <em>only the tested rule
 * survives</em>. Each test here holds one enumeration, in the direction that
 * costs nothing to keep: a cited path must exist (the reverse — every path
 * cited — is what the OpenAPI contract is for), every route must be listed,
 * and the constraints table is generated from the catalogue so it cannot be
 * wrong, only stale, and stale fails.</p>
 */
class DocumentationStructuralTest {

    private static final Path API_MD = Path.of("docs/api.md");
    private static final Path AGENTS_MD = Path.of("AGENTS.md");
    private static final Path CONTRAINTES_MD = Path.of("docs/contraintes.md");
    private static final Path RESOURCES = Path.of("src/main/java/dev/sylvain/planning/api");
    private static final Path ROUTES_TS = Path.of("src/main/webui/src/app/app.routes.ts");

    /** {@code quarkus.rest.path} — every resource path hangs under it. */
    private static final String ROOT = "/api";

    /* -------------------------------- api.md ------------------------------- */

    /**
     * A cited endpoint exists. {@code docs/api.md} is the narrated reading of
     * the API (rule 5 of AGENTS.md: the contract is the OpenAPI), so it need
     * not cite everything — but what it cites must be real, otherwise a reader
     * follows it to a 404.
     */
    @Test
    void everyEndpointCitedInApiMdExists() throws IOException {
        Set<String> declarees = declaredRoutes();
        Set<String> inexistantes = new TreeSet<>();
        for (String[] citation : citations(Files.readString(API_MD, StandardCharsets.UTF_8))) {
            if (!declarees.contains(citation[0] + " " + normalise(citation[1]))) {
                inexistantes.add(citation[0] + " " + citation[1]);
            }
        }
        assertThat(inexistantes)
                .as("endpoints cited in docs/api.md that no resource declares — a typo, a renamed route, "
                        + "or shorthand like `PUT /api/stands` for `PUT /api/stands/{id}`")
                .isEmpty();
    }

    /**
     * Every « verb path » the document cites, whatever the markdown around it:
     * one inline span, the verb and the path in two spans, a table row, a
     * fenced block, or a citation cut by a line break. Backticks, pipes and
     * newlines are flattened first — the first version of this check wanted
     * both in one span, and `PUT` … `/api/stands`, split over two lines, was
     * exactly the shorthand it existed to catch.
     */
    static List<String[]> citations(String markdown) {
        String flat = markdown.replaceAll("[`|\\r\\n]", " ");
        Pattern citation = Pattern.compile("\\b(GET|POST|PUT|DELETE|PATCH)\\s+(/api/[^\\s?,;)]+)");
        Matcher matcher = citation.matcher(flat);
        List<String[]> found = new ArrayList<>();
        while (matcher.find()) {
            String path = matcher.group(2);
            while (path.endsWith(".") || path.endsWith(":")) {
                path = path.substring(0, path.length() - 1);
            }
            found.add(new String[] {matcher.group(1), path});
        }
        return found;
    }

    @Test
    void aCitationSplitOverTwoSpansOrTwoLinesIsStillRead() {
        String markdown = """
                `POST` et `PUT
                /api/stands` répondent `400`.

                | `GET` | `/api/animateurs/import-csv/exemple` | Rend le CSV |

                ```
                DELETE /api/creneaux/{id}
                ```
                """;

        assertThat(citations(markdown))
                .extracting(c -> c[0] + " " + c[1])
                .containsExactly(
                        "PUT /api/stands", "GET /api/animateurs/import-csv/exemple", "DELETE /api/creneaux/{id}");
    }

    /**
     * Verb + path of every JAX-RS method, read off the sources: the class
     * {@code @Path} joined to the method's, placeholders neutralised.
     */
    private static Set<String> declaredRoutes() throws IOException {
        Set<String> routes = new TreeSet<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                routes.addAll(routesOf(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return routes;
    }

    /**
     * The class-level annotation sits in column 0, a method's is indented;
     * both may be written fully qualified ({@code @jakarta.ws.rs.Path}), which
     * a resource does when it also imports {@code java.nio.file.Path}. Missing
     * that form once registered {@code GET /api/planning/export}, a route that
     * does not exist, and lost {@code /pdf/global}, which does.
     */
    static Set<String> routesOf(String source) {
        Pattern classPath = Pattern.compile("^@(?:jakarta\\.ws\\.rs\\.)?Path\\(\"([^\"]*)\"\\)", Pattern.MULTILINE);
        Pattern verbe = Pattern.compile("^\\s*@(?:jakarta\\.ws\\.rs\\.)?(GET|POST|PUT|DELETE|PATCH)\\b");
        Pattern methodPath = Pattern.compile("^\\s+@(?:jakarta\\.ws\\.rs\\.)?Path\\(\"([^\"]*)\"\\)");
        Pattern declaration = Pattern.compile("^\\s*(public|protected|private)?\\s*[\\w.<>,\\[\\] ]+\\s+\\w+\\s*\\(");
        Set<String> routes = new TreeSet<>();
        Matcher racine = classPath.matcher(source);
        if (!racine.find()) {
            return routes;
        }
        String base = racine.group(1);
        String verb = null;
        String chemin = "";
        for (String line : source.split("\n")) {
            Matcher v = verbe.matcher(line);
            if (v.find()) {
                verb = v.group(1);
                continue;
            }
            Matcher p = methodPath.matcher(line);
            if (p.find()) {
                chemin = p.group(1);
                continue;
            }
            if (verb != null && declaration.matcher(line).find()) {
                routes.add(verb + " " + normalise(ROOT + "/" + base + "/" + chemin));
                verb = null;
                chemin = "";
            }
        }
        return routes;
    }

    @Test
    void aFullyQualifiedPathAnnotationDeclaresItsRoute() {
        String source = """
                @Path("/planning/export")
                public class PlanningExportResource {
                    @GET
                    @jakarta.ws.rs.Path("/pdf/global")
                    public Response pdfGlobal() {
                        return null;
                    }

                    @jakarta.ws.rs.GET
                    @Path("/ics/all")
                    public Response icsAll() {
                        return null;
                    }
                }
                """;

        assertThat(routesOf(source))
                .containsExactly("GET /api/planning/export/ics/all", "GET /api/planning/export/pdf/global");
    }

    /** Placeholders neutralised, slashes collapsed, no trailing slash: what makes two spellings the same route. */
    private static String normalise(String path) {
        String normalised = path.replaceAll("\\{[^}]*\\}", "{}").replaceAll("/+", "/");
        return normalised.length() > 1 && normalised.endsWith("/")
                ? normalised.substring(0, normalised.length() - 1)
                : normalised;
    }

    /* ------------------------ .git-blame-ignore-revs ---------------------- */

    /**
     * Every revision listed is a full SHA. `git blame` refuses a short one
     * loudly, and ignores an unknown full one silently — which is how a
     * branch SHA, replaced by the rebase that merged it, would put the
     * reformatting back on every line's blame without a word (#467). This
     * holds the form; `.github/scripts/check-blame-ignore-revs.sh`, run
     * where the clone has its history, holds that each one is an ancestor.
     */
    @Test
    void everyIgnoredRevisionIsAFullSha() throws IOException {
        List<String> malformees = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(".git-blame-ignore-revs"), StandardCharsets.UTF_8)) {
            String nue = line.trim();
            if (nue.isEmpty() || nue.startsWith("#")) {
                continue;
            }
            if (!nue.matches("[0-9a-f]{40}")) {
                malformees.add(nue);
            }
        }
        assertThat(malformees)
                .as("lines of .git-blame-ignore-revs that are not a full 40-character SHA")
                .isEmpty();
    }

    /* ------------------------------ AGENTS.md ----------------------------- */

    /**
     * "One route = one page = one block", and AGENTS.md lists the routes — all
     * of them, since the list is what a reader uses to find the page behind a
     * screen. Seventeen were missing when #392 counted.
     */
    @Test
    void everyFrontendRouteIsListedInAgentsMd() throws IOException {
        String agents = Files.readString(AGENTS_MD, StandardCharsets.UTF_8);
        List<String> absentes = new ArrayList<>();
        for (String route : frontendRoutes(Files.readString(ROUTES_TS, StandardCharsets.UTF_8))) {
            if (!agents.contains("`" + route + "`")) {
                absentes.add(route);
            }
        }
        assertThat(absentes)
                .as("routes of app.routes.ts that AGENTS.md does not list as `/route`")
                .isEmpty();
    }

    /**
     * Every `path:` of the routes file, a child prefixed by its parent. Read
     * flat, the four screens of the espace animateur asked for `/echanges`,
     * `/disponibilites` and `/aide` — which the admin routes of the same
     * name already satisfied — and were never listed. The file is
     * prettier-formatted: `children: [` opens at one indentation, its `]`
     * closes at the same one.
     */
    static List<String> frontendRoutes(String routesTs) {
        Pattern path = Pattern.compile("path: '([^']*)'");
        List<String> routes = new ArrayList<>();
        Deque<String> parents = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        String dernier = null;
        for (String line : routesTs.split("\n")) {
            int indent = line.length() - line.stripLeading().length();
            if (!indents.isEmpty() && line.trim().startsWith("]") && indent == indents.peek()) {
                indents.pop();
                parents.pop();
            }
            Matcher matcher = path.matcher(line);
            if (matcher.find()) {
                dernier = matcher.group(1);
                if (dernier.equals("**")) {
                    continue;
                }
                StringBuilder route = new StringBuilder();
                for (var it = parents.descendingIterator(); it.hasNext(); ) {
                    String parent = it.next();
                    if (!parent.isEmpty()) {
                        route.append('/').append(parent);
                    }
                }
                if (!dernier.isEmpty()) {
                    route.append('/').append(dernier);
                }
                // A parent with an '' child is one route, listed once.
                if (!route.isEmpty() && !routes.contains(route.toString())) {
                    routes.add(route.toString());
                }
            }
            if (line.contains("children: [")) {
                indents.push(indent);
                parents.push(dernier);
            }
        }
        return routes;
    }

    @Test
    void aChildRouteIsListedUnderItsParent() {
        String routes = """
                export const routes = [
                  {
                    path: 'animateur/:jeton',
                    children: [
                      { path: '', loadComponent: () => a },
                      { path: 'echanges', loadComponent: () => b },
                    ],
                  },
                  {
                    path: '',
                    children: [
                      { path: 'echanges', loadComponent: () => c },
                      { path: '**', redirectTo: '' },
                    ],
                  },
                ];
                """;

        assertThat(frontendRoutes(routes))
                .containsExactly("/animateur/:jeton", "/animateur/:jeton/echanges", "/echanges");
    }

    /* --------------------------- contraintes.md --------------------------- */

    private static final String DEBUT = "<!-- catalogue:debut -->";
    private static final String FIN = "<!-- catalogue:fin -->";

    /**
     * The table of constraints in {@code docs/contraintes.md} is the catalogue,
     * rendered — kept between two markers so the prose around it stays
     * hand-written. When it is stale the failure prints the block to paste.
     */
    @Test
    void theConstraintsTableIsTheCatalogueRendered() throws IOException {
        String doc = Files.readString(CONTRAINTES_MD, StandardCharsets.UTF_8);
        int debut = doc.indexOf(DEBUT);
        int fin = doc.indexOf(FIN);
        assertThat(debut).as("marker %s in docs/contraintes.md", DEBUT).isNotNegative();
        assertThat(fin).as("marker %s in docs/contraintes.md", FIN).isGreaterThan(debut);

        String attendu = tableau();
        String actuel = doc.substring(debut + DEBUT.length(), fin).strip();
        assertThat(actuel)
                .as("the constraints table of docs/contraintes.md is not the catalogue any more. "
                        + "Replace what stands between the markers with:\n\n" + attendu + "\n")
                .isEqualTo(attendu);
    }

    private static String tableau() {
        String lignes = ConstraintCatalog.definitions().stream()
                .map(definition ->
                        "| `" + definition.name() + "` | " + definition.niveau() + " | " + definition.categorie()
                                + " | " + definition.description().replace("|", "\\|") + " |")
                .collect(Collectors.joining("\n"));
        return "| Contrainte | Niveau | Catégorie | Ce qu'elle dit |\n|---|---|---|---|\n" + lignes;
    }
}
