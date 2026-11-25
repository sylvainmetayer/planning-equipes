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
 * who cannot sign in.
 *
 * <ol>
 *   <li>{@code planning.auth.oidc.enabled} and {@code quarkus.oidc.tenant-enabled}
 *       are two names for one decision ({@code OIDC_ENABLED}); disagreeing means
 *       half the application believes a provider is plugged in.</li>
 *   <li>In production, a deployment must open <b>some</b> door: the provider or
 *       the local account. Neither would be an application nobody can
 *       administer.</li>
 *   <li>In production, the confidential client needs a secret of at least
 *       {@value #SECRET_MIN} characters: Quarkus encrypts the PKCE state with
 *       it, and draws a random key per instance below that length.</li>
 * </ol>
 */
@ApplicationScoped
public class OidcConfiguration {

    static final int SECRET_MIN = 32;

    private final ConfigOidc config;
    private final ConfigSecours secours;
    private final boolean tenantEnabled;
    private final Optional<String> authServerUrl;
    private final Optional<String> clientSecret;

    @Inject
    public OidcConfiguration(
            ConfigOidc config,
            ConfigSecours secours,
            @ConfigProperty(name = "quarkus.oidc.tenant-enabled") boolean tenantEnabled,
            @ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> authServerUrl,
            @ConfigProperty(name = "quarkus.oidc.credentials.secret") Optional<String> clientSecret) {
        this.config = config;
        this.secours = secours;
        this.tenantEnabled = tenantEnabled;
        this.authServerUrl = authServerUrl;
        this.clientSecret = clientSecret;
    }

    void check(@Observes StartupEvent startup) {
        check(
                config.enabled(),
                tenantEnabled,
                secours.open(config.enabled()),
                clientSecret.orElse(null),
                LaunchMode.current().isProduction());
        if (config.enabled()) {
            Log.info("Connexion OIDC sur " + authServerUrl.orElse("?")
                    + (secours.open(true)
                            ? " ; compte de secours OUVERT (ADMIN_LOCAL_SECOURS)"
                            : " ; compte de secours fermé"));
        }
    }

    /** Static so the rules are unit-tested without booting, like {@code DefaultSecrets.check}. */
    static void check(boolean oidc, boolean tenant, boolean secoursOuvert, String secret, boolean production) {
        if (oidc != tenant) {
            throw new IllegalStateException("planning.auth.oidc.enabled (" + oidc + ") et "
                    + "quarkus.oidc.tenant-enabled (" + tenant + ") se contredisent. Les deux dérivent de "
                    + "OIDC_ENABLED : n'en redéfinir qu'une laisse l'application à moitié branchée sur le "
                    + "fournisseur. Voir site/src/docs/installer/oidc.md.");
        }
        if (production && !oidc && !secoursOuvert) {
            throw new IllegalStateException("OIDC_ENABLED=false et ADMIN_LOCAL_SECOURS=false : aucune porte "
                    + "n'ouvre l'administration. Branchez un fournisseur OIDC, ou laissez le compte local ouvert. "
                    + "Voir site/src/docs/installer/oidc.md.");
        }
        if (oidc && production && (secret == null || secret.length() < SECRET_MIN)) {
            throw new IllegalStateException("OIDC_CLIENT_SECRET doit faire au moins " + SECRET_MIN
                    + " caractères : le client confidentiel ne peut pas échanger son code d'autorisation sans "
                    + "lui, et Quarkus chiffre l'état PKCE avec ce secret.");
        }
    }
}
