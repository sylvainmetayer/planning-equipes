package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        Pattern citation = Pattern.compile("`(GET|POST|PUT|DELETE|PATCH) (/api/[^`?\\s]+)");
        Matcher matcher = citation.matcher(Files.readString(API_MD, StandardCharsets.UTF_8));
        Set<String> inexistantes = new TreeSet<>();
        while (matcher.find()) {
            String route = matcher.group(1) + " " + normalise(matcher.group(2));
            if (!declarees.contains(route)) {
                inexistantes.add(matcher.group(1) + " " + matcher.group(2));
            }
        }
        assertThat(inexistantes)
                .as("endpoints cited in docs/api.md that no resource declares — a typo, a renamed route, "
                        + "or shorthand like `PUT /api/stands` for `PUT /api/stands/{id}`")
                .isEmpty();
    }

    /**
     * Verb + path of every JAX-RS method, read off the sources: the class
     * {@code @Path} joined to the method's, placeholders neutralised.
     */
    private static Set<String> declaredRoutes() throws IOException {
        Pattern classPath = Pattern.compile("^@Path\\(\"([^\"]*)\"\\)", Pattern.MULTILINE);
        Pattern verbe = Pattern.compile("^\\s*@(GET|POST|PUT|DELETE|PATCH)\\b");
        Pattern methodPath = Pattern.compile("^\\s*@Path\\(\"([^\"]*)\"\\)");
        Pattern declaration = Pattern.compile("^\\s*(public|protected|private)?\\s*[\\w.<>,\\[\\] ]+\\s+\\w+\\s*\\(");
        Set<String> routes = new TreeSet<>();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher racine = classPath.matcher(source);
                if (!racine.find()) {
                    continue;
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
                    if (p.find() && !line.startsWith("@Path")) {
                        chemin = p.group(1);
                        continue;
                    }
                    if (verb != null && declaration.matcher(line).find()) {
                        routes.add(verb + " " + normalise(ROOT + "/" + base + "/" + chemin));
                        verb = null;
                        chemin = "";
                    }
                }
            }
        }
        return routes;
    }

    /** Placeholders neutralised, slashes collapsed, no trailing slash: what makes two spellings the same route. */
    private static String normalise(String path) {
        String normalised = path.replaceAll("\\{[^}]*\\}", "{}").replaceAll("/+", "/");
        return normalised.length() > 1 && normalised.endsWith("/")
                ? normalised.substring(0, normalised.length() - 1)
                : normalised;
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
        Matcher matcher =
                Pattern.compile("path: '([^']*)'").matcher(Files.readString(ROUTES_TS, StandardCharsets.UTF_8));
        List<String> absentes = new ArrayList<>();
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path.isEmpty() || path.equals("**")) {
                continue;
            }
            if (!agents.contains("`/" + path + "`")) {
                absentes.add("/" + path);
            }
        }
        assertThat(absentes)
                .as("routes of app.routes.ts that AGENTS.md does not list as `/route`")
                .isEmpty();
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
