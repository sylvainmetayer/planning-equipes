package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/**
 * Keycloak-backed authentication (see {@code docs/keycloak.md}, ADR 0054).
 *
 * <p>Mandatory in production: the administrator, the espace animateur and
 * {@code /mcp} all authenticate through the realm. Turning it off is only
 * meaningful together with the break-glass account ({@link ConfigSecours}),
 * and {@link OidcConfiguration} refuses a production boot where neither opens
 * a door.</p>
 *
 * <p>Keycloak says <b>who</b> is calling — a verified e-mail address, a second
 * factor imposed on administrators by the realm — and carries the global realm
 * roles: {@code admin} opens {@code /api/*}, {@code mcp} opens {@code /mcp},
 * {@link #animateurRole()} opens the holder's own espace, and {@code user}
 * opens nothing. What a person may do <em>per edition</em> lives in the
 * application's own {@code habilitation} table (see
 * {@code service.compte.CompteService}), not in the realm.</p>
 *
 * <p><b>One account is one person, not one fiche.</b> An animateur present in
 * two editions has two fiches and a single Keycloak account: a realm cannot
 * both allow duplicate e-mail addresses and let people sign in with them. The
 * edition is decided the way it already is everywhere in the espace — by the
 * access token in the URL — and the Keycloak identity only has to match the
 * e-mail on the fiche that token designates.</p>
 */
@ConfigMapping(prefix = "planning.auth.oidc")
public interface ConfigOidc {

    /**
     * Master switch. Kept in step with {@code quarkus.oidc.tenant-enabled},
     * which {@code application.properties} derives from the same environment
     * variable — {@link OidcConfiguration} refuses to boot on a mismatch.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Realm role that opens the espace animateur — and the <b>only</b> thing
     * it opens. Deliberately not {@code user}, which Keycloak hands to every
     * account: a privilege built on it would reach every future account
     * without anyone granting it.
     */
    @WithDefault("animateur")
    String animateurRole();

    Provisioning provisioning();

    /**
     * Mirrors the animateur referential into Keycloak accounts: creating a
     * fiche creates the account that opens the espace, so the organiser does
     * not maintain the same list twice.
     */
    interface Provisioning {

        /**
         * Off by default: an instance whose accounts come from a federated
         * directory must not have this application writing into its realm.
         */
        @WithDefault("false")
        boolean enabled();

        /** Admin REST API base, e.g. {@code http://keycloak:8081}. */
        Optional<String> serverUrl();

        @WithDefault("planning")
        String realm();

        /** Service account client holding {@code manage-users} on the realm. */
        Optional<String> clientId();

        Optional<String> clientSecret();

        /**
         * Has Keycloak mail the "set your password" invitation when an account
         * is created. Off creates the accounts silently, for a bulk import that
         * would otherwise send 150 mails.
         */
        @WithDefault("true")
        boolean sendInvitation();

        /**
         * The Keycloak required actions the invitation carries, in the order
         * the mail lists them. By default the person confirms the address,
         * then registers a passkey — no password at all: the realm offers the
         * code by e-mail to an account without one, which is also the way
         * back in once the passkey is lost (docs/keycloak.md).
         * {@code UPDATE_PASSWORD} restores the "choose a password" invitation.
         */
        @WithDefault("VERIFY_EMAIL,webauthn-register-passwordless")
        List<String> invitationActions();
    }
}
