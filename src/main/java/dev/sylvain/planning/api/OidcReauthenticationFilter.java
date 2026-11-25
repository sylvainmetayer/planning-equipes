package dev.sylvain.planning.api;

import io.quarkus.oidc.OidcRedirectFilter;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Makes the provider ask again when the login route was entered through
 * {@code AuthResource#oidcReauthenticate} (ADR 0097): {@code prompt=login}
 * for providers that honour it, {@code max_age=0} for the ones that must then
 * return an {@code auth_time} claim — which is what the recent sign-in control
 * reads. A provider honouring neither answers from its own session and the
 * control keeps refusing: documented, not worked around.
 */
@io.quarkus.arc.Unremovable
@ApplicationScoped
public class OidcReauthenticationFilter implements OidcRedirectFilter {

    @Override
    public void filter(OidcRedirectContext context) {
        if (context.routingContext().request().getParam("reauth") != null) {
            context.additionalQueryParams().add("prompt", "login");
            context.additionalQueryParams().add("max_age", "0");
        }
    }
}
