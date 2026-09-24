package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import org.junit.jupiter.api.Test;

/**
 * The provider answers for the MCP key's principal only. It once answered
 * every trusted request, the break-glass form session included, with no role:
 * whenever Quarkus asked it before the embedded realm's provider, the admin
 * signed in and met 403 everywhere.
 */
class McpApiKeyIdentityProviderTest {

    private final McpApiKeyIdentityProvider provider = new McpApiKeyIdentityProvider();

    @Test
    void lePrincipalDeLaCleMcpPorteLeRoleMcp() {
        SecurityIdentity identite = provider.authenticate(
                        new TrustedAuthenticationRequest(McpApiKeyAuthenticationMechanism.PRINCIPAL), null)
                .await()
                .indefinitely();
        assertThat(identite.getRoles()).containsExactly(McpApiKeyIdentityProvider.ROLE_MCP);
    }

    @Test
    void uneSessionDuFormulaireEstLaisseeAuFournisseurQuiLaConnait() {
        SecurityIdentity identite = provider.authenticate(new TrustedAuthenticationRequest("admin"), null)
                .await()
                .indefinitely();
        assertThat(identite).isNull();
    }
}
