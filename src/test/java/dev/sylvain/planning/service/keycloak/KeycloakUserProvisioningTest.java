package dev.sylvain.planning.service.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.keycloak.KeycloakUserProvisioning.Situation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Who a bulk pass creates and who the invitation round mails, decided without
 * a Keycloak: the calls themselves are proven by the Playwright suite against
 * a real one.
 */
class KeycloakUserProvisioningTest {

    /** One person, one account: two fiches of the same address count once. */
    @Test
    void oneAddressIsOnePersonWhateverTheFichesAndTheCase() {
        Animateur premiere = fiche("A1", "Lea@Example.org ");
        Animateur seconde = fiche("A2", "lea@example.org");
        Animateur sansAdresse = fiche("A3", " ");

        assertThat(KeycloakUserProvisioning.byAddress(List.of(premiere, seconde, sansAdresse)))
                .containsOnlyKeys("lea@example.org")
                .containsEntry("lea@example.org", premiere);
    }

    @Test
    void anAccountAwaitsItsInvitationUntilSentOrProvenByACode() {
        assertThat(KeycloakUserProvisioning.situation(null)).isEqualTo(Situation.SANS_COMPTE);
        assertThat(KeycloakUserProvisioning.situation(compte(true, false))).isEqualTo(Situation.NON_VERIFIE);
        assertThat(KeycloakUserProvisioning.situation(compte(true, true))).isEqualTo(Situation.PRET);

        assertThat(KeycloakUserProvisioning.awaitsInvitation(Situation.SANS_COMPTE))
                .isTrue();
        assertThat(KeycloakUserProvisioning.awaitsInvitation(Situation.NON_VERIFIE))
                .isTrue();
        assertThat(KeycloakUserProvisioning.awaitsInvitation(Situation.PRET)).isFalse();
    }

    /** An account an administrator closed is never reopened, nor mailed, by a bulk pass. */
    @Test
    void aDisabledAccountIsLeftAlone() {
        assertThat(KeycloakUserProvisioning.situation(compte(false, false))).isEqualTo(Situation.DESACTIVE);
        assertThat(KeycloakUserProvisioning.awaitsInvitation(Situation.DESACTIVE))
                .isFalse();
    }

    private static Animateur fiche(String id, String email) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setEmail(email);
        return animateur;
    }

    private static UserRepresentation compte(boolean actif, boolean verifie) {
        UserRepresentation compte = new UserRepresentation();
        compte.setEnabled(actif);
        compte.setEmailVerified(verifie);
        return compte;
    }
}
