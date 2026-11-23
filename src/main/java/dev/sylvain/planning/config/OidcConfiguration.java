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
 *       and disagreeing means half the application believes a provider is
 *       plugged in.</li>
 *   <li>In production, a deployment must open <b>some</b> door: the OIDC
 *       provider, or the local account. Neither would be an application
 *       nobody can administer, discovered by its administrator.</li>
 *   <li>In production, the confidential client needs a secret of at least
 *       {@value #SECRET_MIN} characters: Quarkus encrypts the PKCE state with
 *       it, and draws a random key per instance below that length.</li>
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
    Optional<String> authServerUrl;

    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    Optional<String> clientSecret;

    void check(@Observes StartupEvent startup) {
        check(
                config.enabled(),
                tenantEnabled,
                mcpTenantEnabled,
                secours.enabled(),
                clientSecret.orElse(null),
                LaunchMode.current().isProduction());
        if (config.enabled()) {
            Log.info("Connexion OIDC sur " + authServerUrl.orElse("?") + " ; second facteur des administrateurs : "
                    + (config.adminMfa() == ConfigOidc.AdminMfa.TOKEN ? "vérifié dans le jeton" : "du fournisseur")
                    + (secours.enabled() ? " ; compte local ouvert à côté" : " ; compte local fermé"));
        }
    }

    /** Static so the rules are unit-tested without booting, like {@code DefaultSecrets.check}. */
    static void check(
            boolean oidc, boolean tenant, boolean mcpTenant, boolean secoursOuvert, String secret, boolean production) {
        if (oidc != tenant || oidc != mcpTenant) {
            throw new IllegalStateException("planning.auth.oidc.enabled (" + oidc + "), quarkus.oidc.tenant-enabled ("
                    + tenant + ") et quarkus.oidc.mcptransport.tenant-enabled (" + mcpTenant + ") se contredisent. "
                    + "Les trois dérivent de OIDC_ENABLED : n'en redéfinir qu'une laisse l'application à moitié "
                    + "branchée sur le fournisseur. Voir docs/oidc.md.");
        }
        if (production && !oidc && !secoursOuvert) {
            throw new IllegalStateException("OIDC_ENABLED=false et ADMIN_SECOURS_ENABLED=false : aucune porte "
                    + "n'ouvre l'administration. Branchez un fournisseur OIDC, ou laissez le compte local ouvert. "
                    + "Voir docs/oidc.md.");
        }
        if (oidc && production && (secret == null || secret.length() < SECRET_MIN)) {
            throw new IllegalStateException("OIDC_CLIENT_SECRET doit faire au moins " + SECRET_MIN
                    + " caractères : le client confidentiel ne peut pas échanger son code d'autorisation sans "
                    + "lui, et Quarkus chiffre l'état PKCE avec ce secret.");
        }
    }
}
