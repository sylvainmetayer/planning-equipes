package dev.sylvain.planning.service.espace;

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
class ApplicationLinksTest {

    private static ApplicationLinks linksTo(String baseUrl) {
        return new ApplicationLinks(Optional.ofNullable(baseUrl));
    }

    @Test
    void lesEcransAdminSontConstruitsDepuisLUrlPublique() {
        ApplicationLinks liens = linksTo("https://planning.example.org");

        assertThat(liens.echangesScreen()).contains("https://planning.example.org/echanges");
        assertThat(liens.problemesScreen()).contains("https://planning.example.org/problemes");
        assertThat(liens.disponible()).isTrue();
    }

    /** The regression this component exists to prevent. */
    @Test
    void unSlashFinalDansLUrlPubliqueNeDonneJamaisUnDoubleSlash() {
        ApplicationLinks liens = linksTo("https://planning.example.org/");

        assertThat(liens.echangesScreen()).contains("https://planning.example.org/echanges");
        assertThat(liens.espaceAnimateur("a1b2")).contains("https://planning.example.org/animateur/a1b2");
    }

    /** A deployment with no public URL prints no link, it does not invent one. */
    @Test
    void sansUrlPubliqueAucunLienNEstConstruit() {
        for (String base : new String[] { null, "", "   " }) {
            ApplicationLinks liens = linksTo(base);

            assertThat(liens.disponible()).isFalse();
            assertThat(liens.echangesScreen()).isEmpty();
            assertThat(liens.problemesScreen()).isEmpty();
            assertThat(liens.espaceAnimateur("a1b2")).isEmpty();
        }
    }

    @Test
    void sansJetonIlNYAPasDEspaceAnimateurAPointer() {
        ApplicationLinks liens = linksTo("https://planning.example.org");

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
        ApplicationLinks liens = linksTo("https://planning.example.org");

        assertThat(liens.espaceAnimateur("a b")).contains("https://planning.example.org/animateur/a%20b");
        assertThat(liens.espaceAnimateur("{jeton}")).contains("https://planning.example.org/animateur/%7Bjeton%7D");
    }
}
