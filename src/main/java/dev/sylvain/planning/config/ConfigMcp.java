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

    /** Per-address lockout on repeated wrong API keys, see {@code McpRateLimiter}. */
    Lockout lockout();

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

    /**
     * How many wrong keys one address may present before {@code /mcp} stops
     * answering it, and for how long.
     *
     * <p>Distinct from {@link RateLimit}, which counts <em>every</em> request:
     * that one bounds a stolen key, this one bounds guessing at one. The rate
     * ceiling alone still allows thousands of tries an hour from a single
     * address.</p>
     */
    interface Lockout {

        /**
         * Consecutive wrong keys tolerated per address. Zero or less switches
         * the lockout off, leaving the rate ceiling as the only guard.
         *
         * <p>Only a request that <b>presented</b> a key can fail this way, and
         * one that authenticates clears the run — so a client still being
         * configured never locks itself out.</p>
         */
        @WithDefault("5")
        int maxFailures();

        /** Counted from the last failure, so trying again while blocked gains nothing. */
        @WithDefault("PT10M")
        Duration duration();
    }

    /** Credentials of the Pangolin access proxy, when one fronts this instance. */
    interface Pangolin {

        Optional<String> accessTokenId();

        Optional<String> accessToken();
    }
}
