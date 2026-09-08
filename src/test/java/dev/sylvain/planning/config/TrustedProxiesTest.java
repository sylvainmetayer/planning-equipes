package dev.sylvain.planning.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Parsing and matching of the declared reverse proxies.
 *
 * <p>The block form is what a containerised deployment needs: behind Pangolin
 * (or any tunnel client that terminates locally), the peer the application sees
 * is a container on a bridge network whose address is handed out at attach time
 * — a reboot that starts the stacks in another order moves it, and a literal
 * pinned by hand then stops matching <em>silently</em>, since the fallback is
 * to trust nothing. The network's subnet is fixed at creation, so the block is
 * the stable thing to declare.</p>
 */
class TrustedProxiesTest {

    @Test
    void aLiteralMatchesThatAddressAndNothingElse() {
        TrustedProxies fiables = TrustedProxies.of(List.of("172.18.0.2"));

        assertThat(fiables.contains("172.18.0.2")).isTrue();
        assertThat(fiables.contains("172.18.0.3")).isFalse();
    }

    /** The reason the block form exists: any container of the bridge, not one pinned address. */
    @Test
    void aBlockMatchesEveryAddressInsideItAndStopsAtItsEdges() {
        TrustedProxies fiables = TrustedProxies.of(List.of("172.18.0.0/16"));

        assertThat(fiables.contains("172.18.0.2")).isTrue();
        assertThat(fiables.contains("172.18.255.254")).isTrue();
        assertThat(fiables.contains("172.19.0.2")).isFalse();
        assertThat(fiables.contains("172.17.255.254")).isFalse();
    }

    /** A prefix that does not land on a byte boundary — where an off-by-one hides. */
    @Test
    void aPrefixInsideAByteMasksOnlyTheBitsItDeclares() {
        TrustedProxies fiables = TrustedProxies.of(List.of("10.0.0.0/12"));

        assertThat(fiables.contains("10.0.0.1")).isTrue();
        assertThat(fiables.contains("10.15.255.255")).isTrue();
        assertThat(fiables.contains("10.16.0.1")).isFalse();
    }

    @Test
    void bothFormsCanBeDeclaredTogether() {
        TrustedProxies fiables = TrustedProxies.of(List.of("127.0.0.1", "172.18.0.0/16"));

        assertThat(fiables.contains("127.0.0.1")).isTrue();
        assertThat(fiables.contains("172.18.4.5")).isTrue();
        assertThat(fiables.contains("203.0.113.10")).isFalse();
    }

    /** Families never cross: the byte lengths differ, and comparing them would read past the end. */
    @Test
    void anIpv4BlockDoesNotContainAnIpv6Address() {
        TrustedProxies fiables = TrustedProxies.of(List.of("0.0.0.0/0"));

        assertThat(fiables.contains("0.0.0.0")).isTrue();
        assertThat(fiables.contains("::1")).isFalse();
    }

    @Test
    void anIpv6BlockWorksTheSameWay() {
        TrustedProxies fiables = TrustedProxies.of(List.of("fd00::/8"));

        assertThat(fiables.contains("fd00::1")).isTrue();
        // Written differently, the same address: the comparison is on bytes,
        // which a string equality would have missed.
        assertThat(fiables.contains("FD00:0:0:0:0:0:0:1")).isTrue();
        assertThat(fiables.contains("fe80::1")).isFalse();
    }

    /**
     * Nothing declared trusts nothing — the default, and the one that keeps a
     * deployment with no proxy safe.
     */
    @Test
    void nothingDeclaredTrustsNothing() {
        assertThat(TrustedProxies.NONE.isEmpty()).isTrue();
        assertThat(TrustedProxies.NONE.contains("127.0.0.1")).isFalse();
        assertThat(TrustedProxies.of(List.of("", "  ")).isEmpty()).isTrue();
    }

    /**
     * Candidates come from {@code X-Forwarded-For}, which holds obfuscated
     * identifiers, ports and rubbish in the wild. None of it may throw on the
     * request path.
     */
    @Test
    void rubbishInTheHeaderIsNotTrustedRatherThanFatal() {
        TrustedProxies fiables = TrustedProxies.of(List.of("172.18.0.0/16"));

        assertThat(fiables.contains("_hidden")).isFalse();
        assertThat(fiables.contains("unknown")).isFalse();
        assertThat(fiables.contains("172.18.0.2:41234")).isFalse();
        assertThat(fiables.contains("")).isFalse();
        assertThat(fiables.contains(null)).isFalse();
    }

    /**
     * A hostname is refused rather than resolved: {@code getByName} would put a
     * DNS lookup — and whatever it answers today — inside a trust decision.
     */
    @Test
    void aHostnameIsRefusedRatherThanResolved() {
        assertThatThrownBy(() -> TrustedProxies.of(List.of("newt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newt");
    }

    /**
     * A malformed entry fails the boot rather than being skipped. Skipping it
     * would leave the operator believing the proxy was declared while every
     * visitor shared the proxy's single counter — the failure this whole
     * setting exists to avoid, made invisible.
     */
    @Test
    void aMalformedEntryIsRefusedAtStartup() {
        assertThatThrownBy(() -> TrustedProxies.of(List.of("172.18.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 et 32");
        assertThatThrownBy(() -> TrustedProxies.of(List.of("172.18.0.0/seize")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedProxies.of(List.of("172.18.0.999")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
