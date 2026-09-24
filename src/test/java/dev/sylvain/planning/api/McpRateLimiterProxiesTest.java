package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The proxies the MCP guards believe: their own list when it names anything,
 * the login lock's otherwise. A compose file passing the variable through blank
 * hands over an empty entry, and that used to win over the fallback.
 */
class McpRateLimiterProxiesTest {

    private static final List<String> LOGIN = List.of("172.18.0.0/16");

    @Test
    void aBlankOrMissingMcpListFallsBackToTheLoginLocks() {
        assertThat(McpRateLimiter.proxies(List.of(), LOGIN)).isEqualTo(LOGIN);
        assertThat(McpRateLimiter.proxies(List.of(""), LOGIN)).isEqualTo(LOGIN);
        assertThat(McpRateLimiter.proxies(List.of(" "), LOGIN)).isEqualTo(LOGIN);
    }

    @Test
    void aDeclaredMcpListWins() {
        assertThat(McpRateLimiter.proxies(List.of("10.0.0.1"), LOGIN)).containsExactly("10.0.0.1");
    }
}
