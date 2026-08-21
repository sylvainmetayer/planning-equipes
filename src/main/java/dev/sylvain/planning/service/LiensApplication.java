package dev.sylvain.planning.service;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.UriBuilder;

/**
 * Every link this application prints outside itself — in a notification mail,
 * on an exported PDF. Single place that knows the public base URL and the
 * screen each link targets, so a link cannot be assembled two different ways
 * in two different services (it was: {@code MailService} concatenated the base
 * URL as configured while {@code PlanningExportService} stripped its trailing
 * slash, so {@code planning.public-url=https://planning.example.org/} produced a
 * double slash in mails and a correct URL on PDFs).
 *
 * <p><b>These are routes of the Angular SPA</b>
 * ({@code src/main/webui/src/app/app.routes.ts}), <b>not</b> JAX-RS paths, so
 * they cannot be derived from a resource class with
 * {@code UriBuilder.fromResource(...)}: the API is mounted under
 * {@code quarkus.rest.path=/api}, so deriving {@code /echanges} from
 * {@link dev.sylvain.planning.api.DemandeEchangeResource}
 * would yield {@code /api/echanges} — the JSON endpoint instead of the screen —
 * and the two other screens have no resource to derive anything from. Renaming
 * a route in {@code app.routes.ts} means renaming it here; that coupling is
 * the reason it lives in exactly one file.</p>
 */
@ApplicationScoped
public class LiensApplication {

    /** Admin screen where échange demandes are accepted or refused. */
    private static final String ECRAN_ECHANGES = "echanges";

    /** Admin screen listing the constraints a solved plan still breaks. */
    private static final String ECRAN_PROBLEMES = "problemes";

    /** Espace animateur, whose {@code :jeton} segment IS the credential. */
    private static final String ESPACE_ANIMATEUR = "animateur";

    /**
     * Public base URL of the application. {@link Optional} because a deployment
     * that has none simply prints no link — every accessor is empty then, and
     * every caller already treats a missing link as "say nothing".
     */
    @ConfigProperty(name = "planning.public-url")
    Optional<String> baseUrl;

    /** Whether links can be printed at all — false when no base URL is configured. */
    public boolean disponible() {
        return base().isPresent();
    }

    public Optional<String> ecranEchanges() {
        return base().map(base -> UriBuilder.fromUri(base).path(ECRAN_ECHANGES).build().toString());
    }

    public Optional<String> ecranProblemes() {
        return base().map(base -> UriBuilder.fromUri(base).path(ECRAN_PROBLEMES).build().toString());
    }

    /**
     * The espace of the animateur holding {@code jeton}, empty without a token.
     * The token travels as a template value rather than being concatenated, so
     * it is URL-encoded and never reinterpreted as a URI template.
     */
    public Optional<String> espaceAnimateur(String jeton) {
        if (jeton == null || jeton.isBlank()) {
            return Optional.empty();
        }
        return base().map(base -> UriBuilder.fromUri(base)
                .path(ESPACE_ANIMATEUR).path("{jeton}")
                .build(jeton).toString());
    }

    /**
     * The configured base URL, trimmed, or empty when unset or blank. Also
     * guards {@code baseUrl == null}: the plain (non-CDI) tests of
     * {@code PlanningExportService} build their service with {@code new} and
     * never inject this one.
     */
    private Optional<String> base() {
        return baseUrl == null
                ? Optional.empty()
                : baseUrl.map(String::trim).filter(url -> !url.isBlank());
    }
}
