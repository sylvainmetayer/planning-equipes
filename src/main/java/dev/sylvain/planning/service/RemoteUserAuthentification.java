package dev.sylvain.planning.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Opt-in authentication by a header an access proxy injects — Pangolin's
 * {@code Remote-Email} and its kin. Off by default: the application keeps its
 * form login, and nothing below is ever consulted.
 *
 * <h2>Why a shared secret is mandatory, not optional</h2>
 * <p>A header is a claim, not a proof. Trusting {@code Remote-Email} alone
 * means anyone who can reach the origin without going through the proxy
 * becomes admin by typing one header — and an origin being reachable
 * directly is the normal state of affairs, not an exotic misconfiguration
 * (a container port published for debugging, a second ingress, an internal
 * network). So enabling the mode without
 * {@code planning.auth.remote-user.secret} is refused at startup rather than
 * accepted with a warning: the failure mode is a silent full compromise,
 * which is exactly the class of mistake a startup check exists to prevent.
 *
 * <p>The secret is what makes the header trustworthy, so it must travel on a
 * header the proxy <b>injects itself</b> and that a client cannot forge —
 * i.e. one the proxy overwrites unconditionally on inbound requests.</p>
 *
 * <h2>Who the e-mail designates</h2>
 * <p>One address, {@code admin-email}, gets the admin role. Every other
 * recognised address is an animateur, identified by the e-mail on their fiche
 * — the very address the espace animateur already sends its access code to,
 * so the proxy's assertion simply takes that second factor's place.</p>
 */
@ApplicationScoped
public class RemoteUserAuthentification {

    /** Principal name of the header-authenticated admin, distinct from the form login's {@code admin}. */
    public static final String PRINCIPAL_ADMIN = "admin";

    @ConfigProperty(name = "planning.auth.remote-user.enabled", defaultValue = "false")
    boolean actif;

    @ConfigProperty(name = "planning.auth.remote-user.header", defaultValue = "Remote-Email")
    String enTeteEmail;

    @ConfigProperty(name = "planning.auth.remote-user.secret-header", defaultValue = "Remote-Auth-Secret")
    String enTeteSecret;

    @ConfigProperty(name = "planning.auth.remote-user.secret")
    Optional<String> secret;

    @ConfigProperty(name = "planning.auth.remote-user.admin-email")
    Optional<String> emailAdmin;

    @Inject
    ReferenceDataRepository repository;

    public boolean actif() {
        return actif;
    }

    /**
     * The address this request may be trusted to carry, or empty when the
     * mode is off, the secret does not match, or no address was presented.
     *
     * @param enTete reads one request header by name — the same check has to
     *               serve a Vert.x {@code RoutingContext} (the HTTP
     *               authentication mechanism) and a JAX-RS
     *               {@code ContainerRequestContext} (the espace-animateur
     *               guard), which share no header API
     */
    public Optional<String> emailDeConfiance(Function<String, String> enTete) {
        if (!actif || secret.isEmpty() || secret.get().isBlank()) {
            return Optional.empty();
        }
        if (!secretsEgaux(secret.get(), enTete.apply(enTeteSecret))) {
            return Optional.empty();
        }
        String email = enTete.apply(enTeteEmail);
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(normaliser(email));
    }

    /** True when {@code email} is the single address configured as the administrator's. */
    public boolean estAdmin(String email) {
        return emailAdmin.isPresent() && !emailAdmin.get().isBlank()
                && normaliser(emailAdmin.get()).equals(normaliser(email));
    }

    /**
     * Refuses to boot a half-configured trust chain, and flags the one
     * ambiguity the design cannot resolve on its own: an
     * {@code admin-email} that is also an animateur's address. Admin wins
     * (see the mechanism), so that person silently loses their espace — worth
     * saying out loud at startup rather than letting them discover it.
     *
     * <p>Nothing here runs on an ordinary deployment: the whole method returns
     * immediately when the mode is off.</p>
     */
    void verifierConfiguration(@Observes StartupEvent demarrage) {
        if (!actif) {
            return;
        }
        if (secret.isEmpty() || secret.get().isBlank()) {
            throw new IllegalStateException("planning.auth.remote-user.enabled=true exige "
                    + "planning.auth.remote-user.secret : sans secret partagé, l'en-tête "
                    + enTeteEmail + " est une simple affirmation du client et n'importe qui atteignant "
                    + "l'origine sans passer par le proxy obtiendrait le rôle admin.");
        }
        if (emailAdmin.isEmpty() || emailAdmin.get().isBlank()) {
            Log.warn("planning.auth.remote-user.admin-email n'est pas renseignée : aucun porteur de l'en-tête "
                    + enTeteEmail + " n'obtiendra le rôle admin, seuls les animateurs seront reconnus.");
            return;
        }
        try {
            if (repository.emailAnimateurExiste(emailAdmin.get())) {
                Log.warn("planning.auth.remote-user.admin-email (" + emailAdmin.get() + ") est aussi l'adresse "
                        + "d'un animateur : cette personne sera authentifiée comme administratrice et perdra "
                        + "l'accès à son espace animateur. Utiliser une adresse distincte.");
            }
        } catch (RuntimeException e) {
            // A referential not readable at startup is not a reason to refuse
            // to boot: this check is an ergonomic warning, unlike the secret
            // check above which guards a security property.
            Log.warn("Collision admin-email/animateur non vérifiable au démarrage : " + e.getMessage());
        }
    }

    /** Addresses are compared case-insensitively and trimmed: a proxy may not preserve the original casing. */
    private static String normaliser(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Constant-time comparison so a wrong secret cannot be recovered by measuring response times. */
    private static boolean secretsEgaux(String attendu, String presente) {
        if (presente == null) {
            return false;
        }
        return MessageDigest.isEqual(attendu.getBytes(StandardCharsets.UTF_8),
                presente.getBytes(StandardCharsets.UTF_8));
    }
}
