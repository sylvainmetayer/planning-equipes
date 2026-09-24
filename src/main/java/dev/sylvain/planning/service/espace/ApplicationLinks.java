package dev.sylvain.planning.service.espace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
public class ApplicationLinks {

    /** Admin screen where échange demandes are accepted or refused. */
    private static final String ECRAN_ECHANGES = "echanges";

    /** Admin screen listing the constraints a solved plan still breaks. */
    private static final String ECRAN_PROBLEMES = "problemes";

    /** Admin screen where self-service declarations are applied or refused. */
    private static final String ECRAN_DISPONIBILITES = "disponibilites";

    /**
     * Admin screen holding the instance-wide settings, the automatic backup
     * among them; the tab is URL state (ADR 0012).
     */
    private static final String ECRAN_PARAMETRES = "parametres";

    private static final String ONGLET_GLOBAUX = "globaux";

    /** Espace animateur, whose {@code :token} segment IS the credential. */
    private static final String ESPACE_ANIMATEUR = "animateur";

    /** Tab of the espace where an animateur declares — a child route of the above. */
    private static final String ESPACE_DISPONIBILITES = "disponibilites";

    /** Configured base URL, trimmed, or empty when unset or blank. */
    private final Optional<String> base;

    /**
     * Constructor injection rather than a field: the value is immutable, and a
     * test builds the component with the base URL it wants instead of writing
     * into a package-private field from outside.
     *
     * @param baseUrl public base URL of the application. {@link Optional}
     *                because a deployment that has none simply prints no link
     *                — every accessor is empty then, and every caller already
     *                treats a missing link as "say nothing"
     */
    @Inject
    public ApplicationLinks(@ConfigProperty(name = "planning.public-url") Optional<String> baseUrl) {
        this.base =
                baseUrl == null ? Optional.empty() : baseUrl.map(String::trim).filter(url -> !url.isBlank());
    }

    /** Whether links can be printed at all — false when no base URL is configured. */
    public boolean disponible() {
        return base.isPresent();
    }

    public Optional<String> echangesScreen() {
        return base.map(
                url -> UriBuilder.fromUri(url).path(ECRAN_ECHANGES).build().toString());
    }

    public Optional<String> problemesScreen() {
        return base.map(
                url -> UriBuilder.fromUri(url).path(ECRAN_PROBLEMES).build().toString());
    }

    /** The Paramètres screen, on the tab where the backup report lives. */
    public Optional<String> parametresGlobauxScreen() {
        return base.map(url -> UriBuilder.fromUri(url)
                .path(ECRAN_PARAMETRES)
                .queryParam("onglet", ONGLET_GLOBAUX)
                .build()
                .toString());
    }

    public Optional<String> disponibilitesScreen() {
        return base.map(url ->
                UriBuilder.fromUri(url).path(ECRAN_DISPONIBILITES).build().toString());
    }

    /**
     * The espace of the animateur holding {@code token}, empty without a token.
     * The token travels as a template value rather than being concatenated, so
     * it is URL-encoded and never reinterpreted as a URI template.
     */
    public Optional<String> espaceAnimateur(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return base.map(url -> UriBuilder.fromUri(url)
                .path(ESPACE_ANIMATEUR)
                .path("{jeton}")
                .build(token)
                .toString());
    }

    /**
     * The declaration tab of that espace — where the invitation mail of a
     * collection window sends its reader, so the form is the first thing they
     * see rather than something to go looking for.
     */
    public Optional<String> espaceDisponibilites(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return base.map(url -> UriBuilder.fromUri(url)
                .path(ESPACE_ANIMATEUR)
                .path("{jeton}")
                .path(ESPACE_DISPONIBILITES)
                .build(token)
                .toString());
    }
}
