package dev.sylvain.planning.api;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Forces revalidation of everything the frontend serves under a stable name,
 * so a redeployment reaches every browser on its very next visit.
 *
 * <p>Quarkus serves the packaged frontend through its static-resources
 * handler, whose defaults cache everything for 24 hours ({@code
 * quarkus.http.static-resources.max-age}) and mark it {@code immutable} — not
 * even a manual reload revalidates. That is right for the content-hashed
 * bundles ({@code chunk-*.js}, {@code main-*.js}…): their name changes with
 * their content, a stale copy can never be served under a fresh name. It is
 * wrong for everything else, and "everything else" is wider than the shell —
 * the Angular build hashes what it compiles and copies {@code
 * src/main/webui/public/} across verbatim, so the i18n catalogs, the favicon,
 * the logos and the fonts all keep the name they were written with, next to
 * {@code index.html} and its SPA-fallback copies on every route.</p>
 *
 * <p>The shell is the urgent case, being the map to the hashed files: a
 * browser reusing a day-old {@code index.html} boots the previous build, then
 * asks for lazy chunks a redeployment has deleted, and every menu link dies
 * silently — the mobile "dead menu on the Solveur page" bug (the entry URL is
 * the one everyone has in cache). The rest is the same disease with a milder
 * symptom: swapping a logo or a favicon would otherwise leave returning
 * browsers a day behind with no way to force the issue.</p>
 *
 * <p>{@code no-cache} does not mean "do not store": it means "revalidate
 * before reuse" — one conditional request answered {@code 304 Not Modified}
 * (the static handler serves {@code Last-Modified}) until the next
 * deployment. A handful of files asked for once per page load; the hashed
 * bundles, which are the volume, keep their long-lived caching untouched.</p>
 *
 * <p>Written on {@link RoutingContext#addHeadersEndHandler} rather than as a
 * plain header put: the static-resources handler writes its own {@code
 * Cache-Control} while serving the file, and would overwrite anything set
 * before it. A headers-end handler runs as the headers are flushed, after
 * every handler has spoken, and is also the only place where the response
 * {@code Content-Type} — which is what distinguishes the shell from the
 * bundles — is reliably known.</p>
 */
@ApplicationScoped
public class SpaCacheControlFilter {

    /** Same neighbourhood as {@link SecurityHeadersFilter}: before any response is written. */
    private static final int PRIORITE = 290;

    /**
     * Folders of {@code src/main/webui/public/}: runtime-fetched translation
     * catalogs and self-hosted fonts, copied verbatim by the Angular build.
     */
    static final List<String> UNHASHED_FOLDERS = List.of("/i18n/", "/fonts/");

    /**
     * Files of {@code src/main/webui/public/}, same verbatim copy. Enumerated
     * rather than guessed from the extension, and kept in step with the folder
     * by {@code SpaCacheControlTest}.
     */
    static final Set<String> UNHASHED_FILES =
            Set.of("/favicon.ico", "/robots.txt");

    public void register(@Observes Filters filters) {
        filters.register(this::apply, PRIORITE);
    }

    private void apply(RoutingContext contexte) {
        contexte.addHeadersEndHandler(fin -> {
            HttpServerResponse reponse = contexte.response();
            String type = reponse.headers().get("Content-Type");
            boolean shell = type != null && type.toLowerCase(Locale.ROOT).contains("text/html");
            if (shell || hasStableName(contexte.normalizedPath())) {
                reponse.putHeader("Cache-Control", "no-cache");
            }
        });
        contexte.next();
    }

    /** Whether this path is served under a name a redeployment will not change. */
    static boolean hasStableName(String path) {
        return UNHASHED_FILES.contains(path) || UNHASHED_FOLDERS.stream().anyMatch(path::startsWith);
    }
}
