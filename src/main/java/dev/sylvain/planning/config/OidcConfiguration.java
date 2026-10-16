package dev.sylvain.planning.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to boot a deployment whose authentication is half configured: every
 * failure turned into a startup error here is otherwise discovered by a person
 * who cannot sign in — or, worse, by nobody.
 *
 * <ol>
 *   <li>{@code planning.auth.oidc.enabled} and the two tenant switches must
 *       agree: they are three names for one decision ({@code OIDC_ENABLED}),
 *       and disagreeing means half the application believes Keycloak is on.</li>
 *   <li>In production, a deployment must open <b>some</b> door: Keycloak, or
 *       the break-glass account. Neither would be an application nobody can
 *       administer, discovered by its administrator.</li>
 *   <li>In production, the confidential client needs a secret of at least
 *       {@value #SECRET_MIN} characters: Keycloak refuses a code exchange
 *       without one, at the very last step of a login that looked like it was
 *       working, and Quarkus draws a random PKCE state key per instance below
 *       that length.</li>
 *   <li>Provisioning needs a service account of its own, not the login
 *       client.</li>
 * </ol>
 */
@ApplicationScoped
public class OidcConfiguration {

    static final int SECRET_MIN = 32;

    @Inject
    ConfigOidc config;

    @Inject
    ConfigSecours secours;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled")
    boolean tenantEnabled;

    @ConfigProperty(name = "quarkus.oidc.mcptransport.tenant-enabled")
    boolean mcpTenantEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String authServerUrl;

    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    Optional<String> clientSecret;

    void check(@Observes StartupEvent startup) {
        check(
                config.enabled(),
                tenantEnabled,
                mcpTenantEnabled,
                secours.enabled(),
                clientSecret.orElse(null),
                config.provisioning(),
                LaunchMode.current().isProduction());
        if (secours.enabled()) {
            Log.warn("Compte de secours OUVERT (ADMIN_SECOURS_ENABLED=true) : /j_security_check accepte le mot "
                    + "de passe partagé du compte admin, sans second facteur. À refermer dès l'incident clos.");
        }
        if (config.enabled()) {
            Log.info("Authentification Keycloak sur " + authServerUrl
                    + (config.provisioning().enabled()
                            ? " ; fiches animateurs provisionnées dans le realm "
                                    + config.provisioning().realm()
                            : " ; provisioning des comptes désactivé"));
        }
    }

    /** Static so the rules are unit-tested without booting, like {@code DefaultSecrets.check}. */
    static void check(
            boolean oidc,
            boolean tenant,
            boolean mcpTenant,
            boolean secoursOuvert,
            String secret,
            ConfigOidc.Provisioning provisioning,
            boolean production) {
        if (oidc != tenant || oidc != mcpTenant) {
            throw new IllegalStateException("planning.auth.oidc.enabled (" + oidc + "), quarkus.oidc.tenant-enabled ("
                    + tenant + ") et quarkus.oidc.mcptransport.tenant-enabled (" + mcpTenant + ") se contredisent. "
                    + "Les trois dérivent de OIDC_ENABLED : n'en redéfinir qu'une laisse l'application à moitié "
                    + "branchée sur Keycloak. Voir docs/keycloak.md.");
        }
        if (production && !oidc && !secoursOuvert) {
            throw new IllegalStateException("OIDC_ENABLED=false sans ADMIN_SECOURS_ENABLED=true : aucune porte "
                    + "n'ouvre l'administration. Keycloak est la voie normale ; le compte de secours ne s'ouvre "
                    + "que le temps d'un incident. Voir docs/keycloak.md.");
        }
        if (!oidc) {
            return;
        }
        if (production && (secret == null || secret.length() < SECRET_MIN)) {
            throw new IllegalStateException("OIDC_CLIENT_SECRET doit faire au moins " + SECRET_MIN
                    + " caractères : le client confidentiel du realm ne peut pas échanger son code "
                    + "d'autorisation sans lui, et Quarkus chiffre l'état PKCE avec ce secret.");
        }
        if (!provisioning.enabled()) {
            return;
        }
        if (provisioning.serverUrl().filter(url -> !url.isBlank()).isEmpty()) {
            throw new IllegalStateException("OIDC_PROVISIONING_ENABLED=true exige OIDC_PROVISIONING_SERVER_URL : "
                    + "l'API d'administration de Keycloak n'est pas au même endroit que l'émetteur des jetons.");
        }
        if (provisioning.clientSecret().filter(s -> !s.isBlank()).isEmpty()) {
            throw new IllegalStateException("OIDC_PROVISIONING_ENABLED=true exige OIDC_PROVISIONING_CLIENT_SECRET : "
                    + "le compte de service qui crée les comptes animateurs s'authentifie par "
                    + "client_credentials, pas par la session de l'admin.");
        }
    }
}
