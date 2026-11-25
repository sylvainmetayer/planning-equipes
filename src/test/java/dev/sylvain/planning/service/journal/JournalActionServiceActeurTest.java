package dev.sylvain.planning.service.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.compte.Compte;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The journal names an admin by account id, never by the principal's name (an address on some modes). */
class JournalActionServiceActeurTest {

    private static JournalActionService journal(SecurityIdentity identity) {
        return new JournalActionService(null, identity, Duration.ofDays(90));
    }

    @Test
    void anIdentityBoundToAnAccountIsNamedByTheAccountId() {
        Compte compte = new Compte("c-1", "marie@example.org", "Marie", null, null, null, null, List.of(), false);
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("marie@example.org"))
                .addAttribute(Compte.IDENTITY_ATTRIBUTE, compte)
                .build();

        assertThat(journal(identity).nomAdmin()).isEqualTo("c-1");
    }

    @Test
    void anIdentityWithNoAccountIsTheConfigurationAccountWhateverItsPrincipal() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("proxy-user@example.org"))
                .build();

        assertThat(journal(identity).nomAdmin()).isEqualTo(Compte.CONFIG_ID);
    }

    @Test
    void theMcpKeyKeepsItsOwnNameInsteadOfBecomingTheConfigurationAccount() {
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("mcp"))
                .addRole("mcp")
                .build();

        assertThat(journal(identity).nomAdmin()).isEqualTo("mcp");
    }

    @Test
    void anAnonymousIdentityNamesNobody() {
        assertThat(journal(QuarkusSecurityIdentity.builder().setAnonymous(true).build())
                        .nomAdmin())
                .isNull();
    }
}
