package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The CSP names a tool's origin only when that tool is configured. */
class SecurityHeadersFilterTest {

    private static final String POLICY =
            "default-src 'self'; script-src 'self'; connect-src 'self' https://api.github.com";

    @Test
    void neitherToolConfiguredLeavesThePolicyAsWritten() {
        assertThat(SecurityHeadersFilter.withObservability(POLICY, Optional.empty(), Optional.empty()))
                .isEqualTo(POLICY)
                .doesNotContain("cloudflare");
        assertThat(SecurityHeadersFilter.withObservability(POLICY, Optional.of(" "), Optional.of("")))
                .isEqualTo(POLICY);
    }

    @Test
    void theAnalyticsTokenOpensTheBeaconAndItsReportEndpoint() {
        assertThat(SecurityHeadersFilter.withObservability(POLICY, Optional.of("t0k3n"), Optional.empty()))
                .isEqualTo("default-src 'self'; script-src 'self' https://static.cloudflareinsights.com; "
                        + "connect-src 'self' https://api.github.com https://cloudflareinsights.com");
    }

    @Test
    void theDsnOpensItsOwnOriginOnly() {
        assertThat(SecurityHeadersFilter.withObservability(
                        POLICY, Optional.empty(), Optional.of("https://k3y@bugsink.example.org:8443/12")))
                .isEqualTo("default-src 'self'; script-src 'self'; "
                        + "connect-src 'self' https://api.github.com https://bugsink.example.org:8443");
        assertThat(SecurityHeadersFilter.withObservability(POLICY, Optional.empty(), Optional.of("not a url")))
                .isEqualTo(POLICY);
    }

    /** An operator's own policy without the directive keeps it that way, and nothing is added twice. */
    @Test
    void anOperatorPolicyDecides() {
        assertThat(SecurityHeadersFilter.withObservability("default-src 'self'", Optional.of("t"), Optional.empty()))
                .isEqualTo("default-src 'self'");
        String alreadyOpen = "script-src 'self' https://static.cloudflareinsights.com";
        assertThat(SecurityHeadersFilter.withObservability(alreadyOpen, Optional.of("t"), Optional.empty()))
                .isEqualTo(alreadyOpen);
    }
}
