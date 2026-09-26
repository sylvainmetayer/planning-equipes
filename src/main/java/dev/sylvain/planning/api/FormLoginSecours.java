package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigSecours;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Closes {@code /j_security_check} unless the break-glass door is open
 * ({@code ADMIN_SECOURS_ENABLED}, ADR 0054).
 *
 * <p>Keycloak is the way in, and the realm imposes a second factor on
 * administrators. The form login and its single embedded {@code admin} account
 * stay wired underneath for the day the realm does not answer: a
 * {@code POST /j_security_check} carrying {@code ADMIN_PASSWORD} would
 * otherwise open a session with the {@code admin} role, and all of
 * {@code /api/*} with it — without the realm, without the second factor. A door
 * the login page merely stops offering is still a door, and the weaker one.</p>
 *
 * <p><b>Why a filter and not a permission.</b> {@code quarkus.http.auth.permission.*}
 * is checked by the authorizer, which runs <em>after</em> authentication — and
 * the form mechanism completes the login and sets its cookie from inside that
 * phase, so a {@code deny} policy would arrive too late.
 * {@code quarkus.http.auth.form.enabled} would be the honest knob, but it is
 * fixed at build time. This filter runs where {@link AdminLoginLimiter} already
 * answers {@code 429}: before anything handles the request.</p>
 *
 * <p>{@code 409} rather than {@code 401}, which would read as a wrong password
 * and send an operator hunting for a credential rather than reading their
 * configuration. A form session opened while the door was open is stripped of
 * its role once it closes, by {@code CompteIdentityAugmentor}.</p>
 */
@ApplicationScoped
public class FormLoginSecours {

    /**
     * Just above {@link AdminLoginLimiter}: a closed door has no attempts to
     * count, and counting them would let anyone fill the limiter's
     * per-address map through a route that answers the same to everyone.
     */
    private static final int PRIORITE = 260;

    @Inject
    ConfigSecours secours;

    public void register(@Observes Filters filtres) {
        filtres.register(this::apply, PRIORITE);
    }

    private void apply(RoutingContext contexte) {
        if (secours.enabled() || !AdminLoginLimiter.isLoginPath(contexte.normalizedPath())) {
            contexte.next();
            return;
        }
        contexte.response()
                .setStatusCode(409)
                .putHeader("Content-Type", "application/json")
                .end("{\"message\":\"La connexion par mot de passe est fermée : ce déploiement authentifie par"
                        + " Keycloak. Le compte de secours ne s'ouvre qu'avec ADMIN_SECOURS_ENABLED=true.\"}");
    }
}
