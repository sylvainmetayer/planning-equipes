package dev.sylvain.planning.service.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.keycloak.KeycloakUserProvisioning.BilanComptes;
import dev.sylvain.planning.service.referentiel.AnimateurRepository;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the provisioning does to the realm, through the real admin client and
 * an in-memory Keycloak ({@link FakeKeycloak}): the accounts it creates, the
 * roles it grants, who it invites — and what it does, and never does, when the
 * realm refuses or does not answer.
 */
@QuarkusTest
class KeycloakUserProvisioningFlowTest {

    private FakeKeycloak keycloak;

    private KeycloakUserProvisioning provisioning;

    /** Addresses another fiche, in another edition, still carries. */
    private final Set<String> elsewhere = new HashSet<>();

    private boolean enabled;

    @BeforeEach
    void startRealm() throws IOException {
        keycloak = new FakeKeycloak();
        enabled = true;
        provisioning = new KeycloakUserProvisioning();
        provisioning.config = config();
        provisioning.repository = new AnimateurRepository(null, null, null, null) {
            @Override
            public boolean emailAnimateurExiste(String email) {
                return elsewhere.contains(email);
            }
        };
    }

    @AfterEach
    void stopRealm() {
        provisioning.close();
        keycloak.close();
    }

    @Test
    void aNewFicheGetsAVerifiedAccountItsRoleAndTheInvitation() {
        provisioning.synchroniser(fiche("A1", "Lea", "Martin", " Lea@Example.org "), true);

        FakeKeycloak.User compte = keycloak.byEmail("lea@example.org");
        assertThat(compte).isNotNull();
        assertThat(compte.firstName).isEqualTo("Lea");
        assertThat(compte.emailVerified).isTrue();
        assertThat(compte.roles).containsExactly("animateur");
        assertThat(keycloak.invited).containsExactly(compte.id);
    }

    @Test
    void aFicheWithoutAddressOrWithProvisioningOffTouchesNothing() {
        provisioning.synchroniser(fiche("A1", "Lea", "Martin", " "), true);
        enabled = false;
        provisioning.synchroniser(fiche("A2", "Lea", "Martin", "lea@example.org"), true);

        assertThat(keycloak.users).isEmpty();
        assertThat(provisioning.provisionMissing(List.of(fiche("A2", "L", "M", "lea@example.org")), Set.of()))
                .isEqualTo(new BilanComptes(0, 0, 0));
        assertThat(provisioning.invitationStatus(List.of()).actif()).isFalse();
        assertThatThrownBy(() -> provisioning.inviteAwaiting(List.of())).isInstanceOf(BusinessError.Conflict.class);
        assertThatThrownBy(() -> provisioning.grantAdmin("a@example.org", "A"))
                .isInstanceOf(BusinessError.Conflict.class);
        provisioning.retirer("lea@example.org");
        provisioning.disableAccounts(List.of("lea@example.org"));
    }

    @Test
    void anEditedFicheRenamesItsAccountButOnlyAnewAddressGrantsTheRoleBack() {
        FakeKeycloak.User compte = keycloak.add("lea@example.org", true, true);
        compte.firstName = "Old";

        provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), false);
        assertThat(compte.firstName).isEqualTo("Lea");
        assertThat(compte.lastName).isEqualTo("Martin");
        assertThat(compte.roles).isEmpty();

        provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), true);
        assertThat(compte.roles).containsExactly("animateur");
    }

    @Test
    void aClosedAccountIsReopenedByAFicheJustGivenItsAddressAndNotByAnEdit() {
        FakeKeycloak.User compte = keycloak.add("lea@example.org", false, true);

        provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), false);
        assertThat(compte.enabled).isFalse();

        provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), true);
        assertThat(compte.enabled).isTrue();
    }

    @Test
    void aRefusedRoleTakesTheFreshAccountBackAndSaysSo() {
        keycloak.refuseRoles = true;

        assertThatThrownBy(() -> provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), true))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("Keycloak");

        assertThat(keycloak.users).isEmpty();
        assertThat(keycloak.invited).isEmpty();
    }

    @Test
    void aRealmWithoutTheRoleStillCreatesTheAccountAndMailsIt() {
        keycloak.realmRoles.remove("animateur");

        provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), true);

        assertThat(keycloak.byEmail("lea@example.org").roles).isEmpty();
        assertThat(keycloak.invited).hasSize(1);
    }

    @Test
    void aMailThatDoesNotLeaveDoesNotUndoTheAccount() {
        keycloak.refuseMail = true;

        provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), true);

        assertThat(keycloak.byEmail("lea@example.org")).isNotNull();
    }

    @Test
    void aRefusedCreationIsReportedWithoutLeavingAnAccount() {
        keycloak.refuseCreation = true;

        assertThatThrownBy(() -> provisioning.synchroniser(fiche("A1", "Lea", "Martin", "lea@example.org"), true))
                .isInstanceOf(BusinessError.Conflict.class);
        assertThat(keycloak.users).isEmpty();
    }

    @Test
    void aBulkPassCreatesTheMissingReopensTheReturningAndInvitesNobody() {
        keycloak.add("here@example.org", true, true);
        FakeKeycloak.User fermee = keycloak.add("back@example.org", false, true);
        FakeKeycloak.User laissee = keycloak.add("left@example.org", false, true);
        List<Animateur> fiches = List.of(
                fiche("A1", "Here", "H", "here@example.org"),
                fiche("A2", "Back", "B", "back@example.org"),
                fiche("A3", "Left", "L", "left@example.org"),
                fiche("A4", "New", "N", "new@example.org"));

        BilanComptes bilan = provisioning.provisionMissing(fiches, Set.of("back@example.org"));

        assertThat(bilan).isEqualTo(new BilanComptes(2, 0, 0));
        assertThat(fermee.enabled).isTrue();
        assertThat(laissee.enabled).isFalse();
        FakeKeycloak.User nouveau = keycloak.byEmail("new@example.org");
        assertThat(nouveau.emailVerified).isFalse();
        assertThat(nouveau.roles).containsExactly("animateur");
        assertThat(keycloak.invited).isEmpty();
    }

    @Test
    void aBulkPassCountsTheAccountsTheRealmRefusesAndTheOnesItCannotReach() {
        List<Animateur> fiches = List.of(fiche("A1", "New", "N", "new@example.org"));
        assertThat(provisioning.provisionMissing(List.of(), Set.of())).isEqualTo(new BilanComptes(0, 0, 0));

        keycloak.refuseCreation = true;
        assertThat(provisioning.provisionMissing(fiches, Set.of())).isEqualTo(new BilanComptes(0, 0, 1));

        keycloak.down = true;
        assertThat(provisioning.provisionMissing(fiches, Set.of())).isEqualTo(new BilanComptes(0, 0, 1));
    }

    @Test
    void theStatusCountsWhoStillAwaitsAnInvitation() {
        keycloak.add("ready@example.org", true, true);
        keycloak.add("bulk@example.org", true, false);
        keycloak.add("closed@example.org", false, false);
        List<Animateur> fiches = List.of(
                fiche("A1", "R", "R", "ready@example.org"),
                fiche("A2", "B", "B", "bulk@example.org"),
                fiche("A3", "C", "C", "closed@example.org"),
                fiche("A4", "N", "N", "none@example.org"));

        assertThat(provisioning.invitationStatus(fiches).enAttente()).isEqualTo(2);
        assertThat(provisioning.invitationStatus(List.of()).enAttente()).isZero();

        keycloak.down = true;
        assertThatThrownBy(() -> provisioning.invitationStatus(fiches))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("Keycloak ne répond pas");
    }

    @Test
    void theInvitationRoundCreatesWhatIsMissingMailsEveryoneOnceAndMarksThemInvited() {
        keycloak.add("ready@example.org", true, true);
        FakeKeycloak.User bulk = keycloak.add("bulk@example.org", true, false);
        List<Animateur> fiches = List.of(
                fiche("A1", "R", "R", "ready@example.org"),
                fiche("A2", "B", "B", "bulk@example.org"),
                fiche("A4", "N", "N", "none@example.org"));

        assertThat(provisioning.inviteAwaiting(List.of())).isEqualTo(new BilanComptes(0, 0, 0));
        assertThat(provisioning.inviteAwaiting(fiches)).isEqualTo(new BilanComptes(1, 2, 0));

        assertThat(bulk.emailVerified).isTrue();
        assertThat(keycloak.byEmail("none@example.org").emailVerified).isTrue();
        assertThat(keycloak.invited).hasSize(2);
        assertThat(provisioning.inviteAwaiting(fiches)).isEqualTo(new BilanComptes(0, 0, 0));
    }

    @Test
    void anInvitationThatFailsIsCountedAndTheRoundGoesOn() {
        keycloak.add("bulk@example.org", true, false);
        keycloak.refuseMail = true;

        BilanComptes bilan = provisioning.inviteAwaiting(List.of(fiche("A2", "B", "B", "bulk@example.org")));

        assertThat(bilan).isEqualTo(new BilanComptes(0, 0, 1));
    }

    @Test
    void anAdministratorIsAnAccountWithTheRealmRoleAndAnInvitation() {
        assertThat(provisioning.grantAdmin(" Boss@Example.org", "Boss")).isTrue();

        FakeKeycloak.User compte = keycloak.byEmail("boss@example.org");
        assertThat(compte.roles).containsExactly("admin");
        assertThat(compte.emailVerified).isTrue();
        assertThat(keycloak.invited).containsExactly(compte.id);
    }

    @Test
    void anExistingAccountBecomesAnAdministratorAndItsSessionsEnd() {
        FakeKeycloak.User compte = keycloak.add("lea@example.org", true, true);

        assertThat(provisioning.grantAdmin("lea@example.org", "Lea")).isFalse();

        assertThat(compte.roles).containsExactly("admin");
        assertThat(keycloak.loggedOut).containsExactly(compte.id);
        assertThat(keycloak.invited).isEmpty();
    }

    @Test
    void aClosedAccountIsNotMadeAnAdministratorBehindTheConsolesBack() {
        keycloak.add("lea@example.org", false, true);

        assertThatThrownBy(() -> provisioning.grantAdmin("lea@example.org", "Lea"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("désactivé");
    }

    @Test
    void anAdministratorWhoseRoleIsMissingLeavesNoFreshAccountBehind() {
        keycloak.realmRoles.remove("admin");

        assertThatThrownBy(() -> provisioning.grantAdmin("boss@example.org", "Boss"))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("administrateur");
        assertThat(keycloak.users).isEmpty();

        FakeKeycloak.User existant = keycloak.add("lea@example.org", true, true);
        assertThatThrownBy(() -> provisioning.grantAdmin("lea@example.org", "Lea"))
                .isInstanceOf(BusinessError.Conflict.class);
        assertThat(keycloak.users).containsKey(existant.id);
    }

    @Test
    void aDeletedFicheClosesItsAccountUnlessAnotherEditionStillHasTheAddress() {
        FakeKeycloak.User compte = keycloak.add("lea@example.org", true, true);
        FakeKeycloak.User gardee = keycloak.add("kept@example.org", true, true);
        elsewhere.add("kept@example.org");

        provisioning.retirer(null);
        provisioning.retirer("kept@example.org");
        provisioning.retirer("unknown@example.org");
        assertThat(compte.enabled).isTrue();

        provisioning.retirer(" Lea@Example.org");
        assertThat(compte.enabled).isFalse();
        assertThat(gardee.enabled).isTrue();
    }

    @Test
    void aDeletedFicheNeverFailsWhateverTheRealmAnswers() {
        keycloak.down = true;

        provisioning.retirer("lea@example.org");
        provisioning.disableAccounts(List.of("lea@example.org"));
    }

    @Test
    void aReplacementImportClosesTheAccountsOfAddressesNoFicheCarriesAnyMore() {
        FakeKeycloak.User partie = keycloak.add("gone@example.org", true, true);
        FakeKeycloak.User gardee = keycloak.add("kept@example.org", true, true);
        elsewhere.add("kept@example.org");

        provisioning.disableAccounts(null);
        provisioning.disableAccounts(List.of());
        provisioning.disableAccounts(
                java.util.Arrays.asList("kept@example.org", "gone@example.org", " ", null, "nobody@example.org"));

        assertThat(partie.enabled).isFalse();
        assertThat(gardee.enabled).isTrue();
    }

    private Animateur fiche(String id, String prenom, String nom, String email) {
        Animateur animateur = new Animateur();
        animateur.setId(id);
        animateur.setPrenom(prenom);
        animateur.setNom(nom);
        animateur.setEmail(email);
        return animateur;
    }

    private ConfigOidc config() {
        ConfigOidc.Provisioning provisioning = new ConfigOidc.Provisioning() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<String> serverUrl() {
                return Optional.of(keycloak.url());
            }

            @Override
            public String realm() {
                return "planning";
            }

            @Override
            public Optional<String> clientId() {
                return Optional.of("planning-provisioning");
            }

            @Override
            public Optional<String> clientSecret() {
                return Optional.of("secret");
            }

            @Override
            public boolean sendInvitation() {
                return true;
            }

            @Override
            public List<String> invitationActions() {
                return List.of("VERIFY_EMAIL");
            }
        };
        return new ConfigOidc() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public String animateurRole() {
                return "animateur";
            }

            @Override
            public Provisioning provisioning() {
                return provisioning;
            }
        };
    }
}
