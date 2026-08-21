package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The public links printed in the mails and on the PDFs. The point of this
 * component is that there is exactly one way of assembling those URLs: before
 * it, {@code MailService} concatenated the base URL as configured while
 * {@code PlanningExportService} stripped its trailing {@code /} — two
 * conventions, hence a double slash in the mails as soon as
 * {@code planning.public-url} ended with a slash.
 */
class LiensApplicationTest {

    private static LiensApplication liensVers(String baseUrl) {
        return new LiensApplication(Optional.ofNullable(baseUrl));
    }

    @Test
    void lesEcransAdminSontConstruitsDepuisLUrlPublique() {
        LiensApplication liens = liensVers("https://planning.example.org");

        assertThat(liens.ecranEchanges()).contains("https://planning.example.org/echanges");
        assertThat(liens.ecranProblemes()).contains("https://planning.example.org/problemes");
        assertThat(liens.disponible()).isTrue();
    }

    /** The regression this component exists to prevent. */
    @Test
    void unSlashFinalDansLUrlPubliqueNeDonneJamaisUnDoubleSlash() {
        LiensApplication liens = liensVers("https://planning.example.org/");

        assertThat(liens.ecranEchanges()).contains("https://planning.example.org/echanges");
        assertThat(liens.espaceAnimateur("a1b2")).contains("https://planning.example.org/animateur/a1b2");
    }

    /** A deployment with no public URL prints no link, it does not invent one. */
    @Test
    void sansUrlPubliqueAucunLienNEstConstruit() {
        for (String base : new String[] { null, "", "   " }) {
            LiensApplication liens = liensVers(base);

            assertThat(liens.disponible()).isFalse();
            assertThat(liens.ecranEchanges()).isEmpty();
            assertThat(liens.ecranProblemes()).isEmpty();
            assertThat(liens.espaceAnimateur("a1b2")).isEmpty();
        }
    }

    @Test
    void sansJetonIlNYAPasDEspaceAnimateurAPointer() {
        LiensApplication liens = liensVers("https://planning.example.org");

        assertThat(liens.espaceAnimateur(null)).isEmpty();
        assertThat(liens.espaceAnimateur("  ")).isEmpty();
    }

    /**
     * The token travels as a template value rather than by concatenation: it is
     * encoded, and a template character inside a token imported from a scenario
     * cannot be reinterpreted by {@code UriBuilder}.
     */
    @Test
    void leJetonEstEncodeEtJamaisReluCommeUnGabarit() {
        LiensApplication liens = liensVers("https://planning.example.org");

        assertThat(liens.espaceAnimateur("a b")).contains("https://planning.example.org/animateur/a%20b");
        assertThat(liens.espaceAnimateur("{jeton}")).contains("https://planning.example.org/animateur/%7Bjeton%7D");
    }
}
