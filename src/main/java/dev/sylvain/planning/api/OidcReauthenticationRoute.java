package dev.sylvain.planning.api;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /api/auth/oidc/reconnexion?retour=…}: signs the person in
 * <b>again</b> at the provider, for a gesture that wants a recent
 * authentication (ADR 0097).
 *
 * <p>It drops the application's own OIDC session cookies ({@code q_session*})
 * and sends the browser through the login route marked {@code reauth}, which
 * {@link OidcReauthenticationFilter} turns into a {@code prompt=login} /
 * {@code max_age=0} request: the provider asks again instead of answering from
 * its own session, and the ID token that comes back carries a fresh
 * {@code auth_time}.</p>
 *
 * <p>A routing filter and not a JAX-RS route, because it must answer
 * <em>before</em> the OIDC mechanism looks at the request: with a stale or
 * unreadable session cookie that mechanism would redirect to the provider with
 * this very URL as the place to come back to, and the return would delete the
 * brand-new session again, in a loop.</p>
 */
@ApplicationScoped
public class OidcReauthenticationRoute {

    static final String PATH = "/api/auth/oidc/reconnexion";

    private static final String COOKIE_PREFIX = "q_session";

    void register(@Observes Filters filters) {
        filters.register(this::apply, 250);
    }

    private void apply(RoutingContext context) {
        if (!PATH.equals(context.normalizedPath())) {
            context.next();
            return;
        }
        String back = AuthResource.safeReturn(context.request().getParam("retour"));
        String cookies = context.request().getHeader(HttpHeaders.COOKIE);
        if (cookies != null) {
            for (String pair : cookies.split(";")) {
                String name = pair.trim();
                int equals = name.indexOf('=');
                name = equals < 0 ? name : name.substring(0, equals);
                if (name.startsWith(COOKIE_PREFIX)) {
                    context.response().headers().add(HttpHeaders.SET_COOKIE, name + "=; Path=/; Max-Age=0");
                }
            }
        }
        context.response()
                .setStatusCode(303)
                .putHeader(
                        HttpHeaders.LOCATION,
                        "/api/auth/oidc/login?reauth=1&retour=" + URLEncoder.encode(back, StandardCharsets.UTF_8))
                .end();
    }
}
