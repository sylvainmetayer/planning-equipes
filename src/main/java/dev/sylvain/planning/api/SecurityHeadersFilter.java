package dev.sylvain.planning.api;

import jakarta.inject.Inject;
import dev.sylvain.planning.config.ConfigSecurite;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Browser-side hardening headers, added to every response before the
 * application is reachable from the Internet. They cost nothing on the local
 * stack and are the cheapest half of the defence: the API can be flawless and
 * still leak through a framed page, a sniffed content type, or a token
 * forwarded in a {@code Referer}.
 *
 * <p>Written as a Vert.x filter rather than {@code quarkus.http.header.*}
 * entries because that configuration keys the map by header name, so one
 * header cannot carry two different values on two path prefixes — and the CSP
 * below has to spare {@code /q/*}, where Quarkus' own Swagger UI serves
 * inline scripts it does not control. Everything else applies to the whole
 * server, static SPA files included.</p>
 *
 * <p>What each one buys here:</p>
 * <ul>
 * <li><b>Content-Security-Policy</b> — the SPA loads no third-party script
 * except the Cloudflare Web Analytics beacon, so anything else injected into
 * a page is refused execution. {@code style-src} keeps {@code 'unsafe-inline'}:
 * Angular Material writes inline styles.</li>
 * <li><b>Referrer-Policy: no-referrer</b> — the espace animateur carries its
 * access token <b>in the URL</b>, and the planning pages link out
 * (OpenStreetMap attribution, the issue tracker). Without this, that token
 * leaves in the {@code Referer} of every outbound navigation. The map tiles
 * opt back out per-image: openstreetmap.org refuses requests carrying no
 * {@code Referer} at all, so {@code MapPicker} sets
 * {@code referrerPolicy: 'strict-origin-when-cross-origin'} on its tile
 * layer, which sends the origin and never the token-bearing path.</li>
 * <li><b>X-Frame-Options / frame-ancestors</b> — nothing in the application
 * is meant to be embedded, and clickjacking a session that can rewrite the
 * whole planning is worth refusing.</li>
 * <li><b>Strict-Transport-Security</b> — only on an HTTPS visit (proxy
 * headers included, same rule as the espace cookie): sending it over http
 * would be ignored by browsers anyway, and pinning the local stack to
 * https would break it.</li>
 * </ul>
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    /** Runs early, before anything can start writing a response. */
    private static final int PRIORITE = 300;

    /** Quarkus' own management endpoints (Swagger UI, dev UI): no CSP, see the class javadoc. */
    private static final String PREFIXE_QUARKUS = "/q/";

    @Inject
    ConfigSecurite config;

    public void register(@Observes Filters filters) {
        filters.register(this::apply, PRIORITE);
    }

    private void apply(RoutingContext contexte) {
        HttpServerResponse reponse = contexte.response();
        reponse.putHeader("X-Content-Type-Options", "nosniff");
        reponse.putHeader("X-Frame-Options", "DENY");
        // The espace animateur URL carries its access token in the path, so any
        // outgoing link would leak the identifier of a person — often a minor —
        // to the site visited. Strictly "no-referrer", not "same-origin": a
        // token is a credential, and nothing needs the Referer here. See
        // docs/securite.md; the OpenStreetMap tiles opt out per-element.
        reponse.putHeader("Referrer-Policy", "no-referrer");
        reponse.putHeader("Cross-Origin-Opener-Policy", "same-origin");
        reponse.putHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()");
        if (config.csp().isPresent() && !contexte.normalizedPath().startsWith(PREFIXE_QUARKUS)) {
            reponse.putHeader("Content-Security-Policy", config.csp().get());
        }
        if (config.hsts().isPresent() && encryptedVisit(contexte)) {
            reponse.putHeader("Strict-Transport-Security", config.hsts().get());
        }
        contexte.next();
    }

    /**
     * Whether the visitor reached the application over HTTPS. The forwarded
     * header is read directly rather than relying on the request wrapper
     * installed by {@code quarkus.http.proxy.proxy-address-forwarding}, whose
     * ordering against this filter is not ours to guarantee. Trusting a
     * forgeable header costs nothing here: the worst an attacker achieves by
     * lying is pinning their own browser to HTTPS.
     */
    private static boolean encryptedVisit(RoutingContext contexte) {
        if (contexte.request().isSSL()) {
            return true;
        }
        String protocole = contexte.request().getHeader("X-Forwarded-Proto");
        return protocole != null && protocole.toLowerCase(Locale.ROOT).startsWith("https");
    }
}
