package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Les liens publics imprimés dans les mails et sur les PDF. Le point de ce
 * composant est qu'il n'existe qu'une seule façon d'assembler ces URL : avant
 * lui, {@code MailService} concaténait l'URL de base telle que configurée
 * tandis que {@code PlanningExportService} lui retirait son {@code /} final —
 * deux conventions, donc un double slash dans les mails dès que
 * {@code planning.public-url} se terminait par un slash.
 */
class LiensApplicationTest {

    private static LiensApplication liensVers(String baseUrl) {
        LiensApplication liens = new LiensApplication();
        liens.baseUrl = Optional.ofNullable(baseUrl);
        return liens;
    }

    @Test
    void lesEcransAdminSontConstruitsDepuisLUrlPublique() {
        LiensApplication liens = liensVers("https://planning.example.org");

        assertThat(liens.ecranEchanges()).contains("https://planning.example.org/echanges");
        assertThat(liens.ecranProblemes()).contains("https://planning.example.org/problemes");
        assertThat(liens.disponible()).isTrue();
    }

    /** La régression que ce composant existe pour empêcher. */
    @Test
    void unSlashFinalDansLUrlPubliqueNeDonneJamaisUnDoubleSlash() {
        LiensApplication liens = liensVers("https://planning.example.org/");

        assertThat(liens.ecranEchanges()).contains("https://planning.example.org/echanges");
        assertThat(liens.espaceAnimateur("a1b2")).contains("https://planning.example.org/animateur/a1b2");
    }

    /** Un déploiement sans URL publique n'imprime pas de lien, il n'en invente pas. */
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
     * Le jeton voyage comme valeur de gabarit et non par concaténation : il est
     * encodé, et un caractère de gabarit dans un jeton importé d'un scénario ne
     * peut pas être réinterprété par {@code UriBuilder}.
     */
    @Test
    void leJetonEstEncodeEtJamaisReluCommeUnGabarit() {
        LiensApplication liens = liensVers("https://planning.example.org");

        assertThat(liens.espaceAnimateur("a b")).contains("https://planning.example.org/animateur/a%20b");
        assertThat(liens.espaceAnimateur("{jeton}")).contains("https://planning.example.org/animateur/%7Bjeton%7D");
    }
}
