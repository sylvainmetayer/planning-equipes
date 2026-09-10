package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
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

    Pangolin pangolin();

    /** Credentials of the Pangolin access proxy, when one fronts this instance. */
    interface Pangolin {

        Optional<String> accessTokenId();

        Optional<String> accessToken();
    }
}
