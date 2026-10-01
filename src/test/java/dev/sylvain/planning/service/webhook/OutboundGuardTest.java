package dev.sylvain.planning.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.service.BusinessError;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a webhook may call, decided without a network: the resolver is a table. */
class OutboundGuardTest {

    private static final Map<String, String> DNS = Map.of(
            "hooks.example.org", "93.184.216.34",
            "rebind.example.org", "10.1.2.3",
            "metadata.example.org", "169.254.169.254",
            "n8n", "172.18.0.5");

    private static OutboundGuard guard(String... allowed) {
        return new OutboundGuard(TrustedProxies.of(List.of(allowed), "WEBHOOKS_RESEAUX_AUTORISES invalide"), host -> {
            String address = DNS.get(host);
            if (address == null) {
                // Literals resolve to themselves, as InetAddress.getAllByName does.
                try {
                    return new InetAddress[] {InetAddress.ofLiteral(host)};
                } catch (IllegalArgumentException e) {
                    throw new UnknownHostException(host);
                }
            }
            return new InetAddress[] {InetAddress.ofLiteral(address)};
        });
    }

    @Test
    void aPublicHttpsAddressIsAcceptedAndPinnedToTheCheckedIp() {
        OutboundGuard.Target target = guard().check("https://hooks.example.org/services/T0/B0/XYZ?x=1");

        assertThat(target.address().getHostAddress()).isEqualTo("93.184.216.34");
        assertThat(target.host()).isEqualTo("hooks.example.org");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.https()).isTrue();
        assertThat(target.requestUri()).isEqualTo("/services/T0/B0/XYZ?x=1");
    }

    @Test
    void port8443IsAcceptedButNotAnyOtherPort() {
        assertThat(guard().check("https://hooks.example.org:8443/x").port()).isEqualTo(8443);
        assertThatThrownBy(() -> guard().check("https://hooks.example.org:8080/x"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("Port 8080");
    }

    @Test
    void plainHttpIsRefusedOnThePublicInternet() {
        assertThatThrownBy(() -> guard().check("http://hooks.example.org/x"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("https://");
    }

    @Test
    void internalAddressesAreRefused() {
        for (String url : List.of(
                "https://127.0.0.1/",
                "https://10.0.0.1/",
                "https://172.16.4.4/",
                "https://192.168.1.1/",
                "https://169.254.169.254/latest/meta-data",
                "https://[::1]/",
                "https://[fd00::1]/",
                "https://[fe80::1]/",
                "https://[::ffff:127.0.0.1]/",
                "https://0.0.0.0/",
                "https://100.64.0.1/",
                "https://224.0.0.1/",
                // IPv6 forms carrying an internal IPv4: compatible, NAT64, 6to4, Teredo.
                "https://[::169.254.169.254]/",
                "https://[64:ff9b::a9fe:a9fe]/",
                "https://[64:ff9b::7f00:1]/",
                "https://[2002:a9fe:a9fe::1]/",
                "https://[2002:a00:1::1]/",
                "https://[2001:0:4136:e378:8000:63bf:80ff:fffe]/",
                // Local-use NAT64: where the IPv4 sits is the site's own choice.
                "https://[64:ff9b:1::808:808]/")) {
            assertThatThrownBy(() -> guard().check(url))
                    .as(url)
                    .isInstanceOf(BusinessError.Invalid.class)
                    .hasMessageContaining("adresse interne");
        }
    }

    /** A public IPv4 behind NAT64 or 6to4 is what it carries: public. */
    @Test
    void anIpv6CarryingAPublicIpv4IsAccepted() {
        assertThat(guard().check("https://[64:ff9b::808:808]/hook").address().getHostAddress())
                .isEqualTo("64:ff9b:0:0:0:0:808:808");
        assertThat(guard().check("https://[2002:808:808::1]/hook").port()).isEqualTo(443);
        assertThat(OutboundGuard.embeddedIpv4(
                        InetAddress.ofLiteral("2001:db8::1").getAddress()))
                .isEmpty();
    }

    /**
     * A name that does not resolve may be a DNS hiccup: refused when the
     * webhook is saved, but told apart at a send, which retries it.
     */
    @Test
    void anUnresolvedNameIsToldApartForASend() {
        assertThatThrownBy(() -> guard().checkForSend("https://unknown.invalid/hook"))
                .isInstanceOf(OutboundGuard.UnresolvedHost.class)
                .hasMessageContaining("introuvable (DNS)");
        OutboundGuard empty = new OutboundGuard(
                TrustedProxies.of(List.of(), "WEBHOOKS_RESEAUX_AUTORISES invalide"), host -> new InetAddress[0]);
        assertThatThrownBy(() -> empty.checkForSend("https://hooks.example.org/hook"))
                .isInstanceOf(OutboundGuard.UnresolvedHost.class);
        // An address refused for what it is stays a final refusal.
        assertThatThrownBy(() -> guard().checkForSend("https://rebind.example.org/hook"))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    /** The system resolver is bounded: a literal comes back at once, an unknown name as unknown. */
    @Test
    void theBoundedResolverAnswersLikeTheSystemOne() throws UnknownHostException {
        assertThat(OutboundGuard.resolveWithin("127.0.0.1", java.time.Duration.ofSeconds(1))[0].getHostAddress())
                .isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> OutboundGuard.resolveWithin("x.invalid", java.time.Duration.ofSeconds(5)))
                .isInstanceOf(UnknownHostException.class);
    }

    /** The name is resolved by the guard, so a public-looking name pointing inside is refused too. */
    @Test
    void aNameResolvingToAnInternalAddressIsRefused() {
        assertThatThrownBy(() -> guard().check("https://rebind.example.org/hook"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("10.1.2.3");
        assertThatThrownBy(() -> guard().check("https://metadata.example.org/"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("169.254.169.254");
    }

    @Test
    void anAllowedNetworkOpensHttpAndAnyPort() {
        OutboundGuard.Target target = guard("172.18.0.0/16").check("http://n8n:5678/webhook/abc");

        assertThat(target.address().getHostAddress()).isEqualTo("172.18.0.5");
        assertThat(target.port()).isEqualTo(5678);
        assertThat(target.https()).isFalse();
        // The opening is that network only.
        assertThatThrownBy(() -> guard("172.18.0.0/16").check("http://10.0.0.1/"))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void credentialsInTheAddressAndOtherSchemesAreRefused() {
        assertThatThrownBy(() -> guard().check("https://user:pw@hooks.example.org/"))
                .isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> guard().check("ftp://hooks.example.org/")).isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> guard().check("file:///etc/passwd")).isInstanceOf(BusinessError.Invalid.class);
        assertThatThrownBy(() -> guard().check("https://unknown.invalid/"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("introuvable");
    }

    /** No refusal quotes the path: for Slack, Discord and Matrix the path is the secret. */
    @Test
    void aRefusalNeverQuotesThePath() {
        assertThatThrownBy(() -> guard().check("https://rebind.example.org/services/SECRET-PATH"))
                .hasMessageNotContaining("SECRET-PATH");
        assertThatThrownBy(() -> guard().check("http://hooks.example.org/services/SECRET-PATH"))
                .hasMessageNotContaining("SECRET-PATH");
    }

    @Test
    void aMalformedAllowedNetworkFailsLikeAMalformedProxy() {
        assertThatThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/33"), "WEBHOOKS_RESEAUX_AUTORISES invalide"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("WEBHOOKS_RESEAUX_AUTORISES invalide");
        assertThatThrownBy(() -> TrustedProxies.of(List.of("n8n"), "WEBHOOKS_RESEAUX_AUTORISES invalide"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
