package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where a visitor lands after signing in with Keycloak.
 *
 * <p>A route that runs immediately after a successful login is the single best
 * place to put an open redirect: the visitor has just typed their password,
 * the second factor is behind them, and a page that looks like the application
 * asking for "one more confirmation" is about as convincing as phishing gets.
 * The parameter is therefore treated as a suggestion, not an instruction —
 * anything that is not a path of this application is silently replaced by the
 * root rather than refused, since there is no legitimate caller to break.</p>
 */
class AuthResourceRedirectionTest {

    @Test
    void unCheminDeLApplicationEstConserve() {
        assertThat(AuthResource.localPath("/animateur/abc123")).isEqualTo("/animateur/abc123");
        assertThat(AuthResource.localPath("/")).isEqualTo("/");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://ailleurs.example/phishing",
                "http://ailleurs.example",
                // Protocol-relative: a path to the eye, an absolute URL to the
                // browser's resolver. The leading slash is not enough on its own.
                "//ailleurs.example/phishing",
                // Same trick with a backslash, which several browsers normalise
                // to a slash before resolving.
                "/\\ailleurs.example",
                "javascript:alert(1)",
                "animateur/sans-slash"
            })
    void toutCeQuiNEstPasUnCheminLocalRetombeSurLaRacine(String propose) {
        assertThat(AuthResource.localPath(propose)).isEqualTo("/");
    }

    /**
     * Where the browser is actually sent, base URI included.
     *
     * <p>{@code localPath} returning "/" is not the whole answer: this
     * application answers under {@code /api}, so a path-only redirect left to
     * the JAX-RS runtime is resolved against <b>that</b> base and the visitor
     * lands on {@code /api/} — a 404, one step after a successful sign-in and
     * a second factor. The assertion below is on the absolute URL, which is
     * the thing the browser obeys.</p>
     */
    @Test
    void laCibleQuitteLeCheminApiDeLApplication() {
        URI base = URI.create("http://planning.example.org/api/");
        assertThat(AuthResource.target(base, "/animateur/abc123"))
                .isEqualTo(URI.create("http://planning.example.org/animateur/abc123"));
        assertThat(AuthResource.target(base, "/"))
                .as("la racine de l'application, pas la racine de l'API")
                .isEqualTo(URI.create("http://planning.example.org/"));
        assertThat(AuthResource.target(base, null)).isEqualTo(URI.create("http://planning.example.org/"));
    }

    /** An outside address stays refused once resolved, not merely once trimmed. */
    @Test
    void uneAdresseEtrangereNeSurvitPasALaResolution() {
        URI base = URI.create("http://planning.example.org/api/");
        assertThat(AuthResource.target(base, "https://ailleurs.example/phishing"))
                .isEqualTo(URI.create("http://planning.example.org/"));
        assertThat(AuthResource.target(base, "//ailleurs.example/phishing"))
                .isEqualTo(URI.create("http://planning.example.org/"));
    }

    /**
     * A legal path and an illegal URI are not the same set, and the gap is
     * reachable from a query parameter.
     *
     * <p>{@code localPath} lets these through — they start with a single
     * slash and name no host — and {@link java.net.URI#resolve(String)} then
     * parses them and throws. That is a 500 one step after a successful
     * sign-in, written by whoever composed the link. Unparseable joins
     * everything else this application declines to follow: the root.</p>
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/un chemin avec une espace",
                "/animateur/%zz",
                "/animateur/a|b",
                "/animateur/<script>",
                "/animateur/\u0000"
            })
    void unCheminQuiNEstPasUneUriRetombeSurLaRacine(String propose) {
        URI base = URI.create("http://planning.example.org/api/");
        assertThat(AuthResource.target(base, propose))
                .as("« %s » ne doit pas faire échouer la redirection d'après connexion", propose)
                .isEqualTo(URI.create("http://planning.example.org/"));
    }

    @Test
    void labsenceDeParametreMeneALaRacine() {
        assertThat(AuthResource.localPath(null)).isEqualTo("/");
        assertThat(AuthResource.localPath("   ")).isEqualTo("/");
    }

    /** A backslash, a control character or a space anywhere, not only at the start, sends to the root. */
    @ParameterizedTest
    @ValueSource(strings = {"/ok/\\ailleurs.example", "/ok\tailleurs", "/ok\r\nLocation: https://ailleurs", "/a b"})
    void unCaractereHorsDUnCheminRenvoieALaRacine(String proposition) {
        assertThat(AuthResource.localPath(proposition)).isEqualTo("/");
    }

    /** Whatever the parser makes of it, the target keeps the scheme and host of the request. */
    @Test
    void laCibleResteSurLHoteDeLaRequete() {
        URI base = URI.create("https://planning.example/api/");
        assertThat(AuthResource.target(base, "/animateurs?filtre=a")).hasHost("planning.example");
        assertThat(AuthResource.target(base, "//ailleurs.example/x"))
                .isEqualTo(URI.create("https://planning.example/"));
    }
}
