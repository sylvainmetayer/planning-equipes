package dev.sylvain.planning.api;

import dev.sylvain.planning.OidcJetons;
import java.util.List;

/**
 * Opens espace-animateur sessions for the tests the way the application sees
 * them since ADR 0049: a Keycloak token carrying the {@code animateur} realm
 * role and the verified address of the fiche. Signed by the in-memory OIDC
 * server's key, so the guard under test ({@code SessionEspaceFilter}) is the
 * production one — only the browser's code flow is skipped.
 */
final class EspaceSessions {

    /** Where the tokens are sent: the {@code Authorization} header of the request. */
    static final String EN_TETE = "Authorization";

    private EspaceSessions() {}

    /** The {@code Authorization} header value of a session for {@code email}. */
    static String open(String email) {
        return "Bearer " + OidcJetons.jeton(email, List.of("user", "animateur"), "planning-app", email, true);
    }
}
