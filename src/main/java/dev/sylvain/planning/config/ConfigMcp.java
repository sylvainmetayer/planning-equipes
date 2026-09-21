package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The MCP endpoint's own credentials and guards (see {@code docs/mcp.md}).
 * Declared once here, where it used to be declared twice — in
 * {@code McpResource} and in {@code McpApiKeyAuthenticationMechanism}, two
 * packages apart, each carrying its own copy of the header default.
 */
@ConfigMapping(prefix = "planning.mcp")
public interface ConfigMcp {

    /** Empty means the MCP endpoint is closed, which is the default. */
    Optional<String> apiKey();

    @WithDefault("X-MCP-Api-Key")
    String apiKeyHeader();

    /**
     * Headers an incoming MCP call must carry to be considered as coming
     * through the access proxy rather than straight off the Internet.
     */
    Optional<List<String>> requiredHeaders();

    /** Per-address rate limit on the {@code /mcp} transport, see {@code McpRateLimiter}. */
    RateLimit rateLimit();

    Pangolin pangolin();

    /** How fast one address may call {@code /mcp}, and how to tell one address from another. */
    interface RateLimit {

        /**
         * Requests tolerated per address and per {@link #window()}. Zero or less
         * switches the limit off entirely — a deployment driving the MCP tools
         * from a script on the same machine may want that, and saying so in
         * configuration beats commenting a filter out.
         */
        @WithDefault("120")
        int maxRequests();

        @WithDefault("PT1M")
        Duration window();

        /**
         * The reverse proxies in front of this deployment, as literal addresses
         * or CIDR blocks. Defaults to the list the login lock already reads
         * ({@code CONNEXION_PROXYS_FIABLES}): the proxies are a fact about the
         * deployment, not about one endpoint, and two lists to keep in step is
         * one list too many. See {@link TrustedProxies} for what declaring a
         * block means, and {@code ClientAddress} for what is done with it.
         */
        Optional<List<String>> trustedProxies();
    }

    /** Credentials of the Pangolin access proxy, when one fronts this instance. */
    interface Pangolin {

        Optional<String> accessTokenId();

        Optional<String> accessToken();
    }
}
