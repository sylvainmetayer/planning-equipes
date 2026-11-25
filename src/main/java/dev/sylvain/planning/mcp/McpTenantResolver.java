package dev.sylvain.planning.mcp;

import io.quarkus.oidc.TenantResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Sends the MCP transport - {@code /mcp} and below, nothing else - to the
 * {@code mcptransport} OIDC tenant (a bearer-only resource server validating
 * the {@code planning-mcp} audience, ADR 0097); every other path keeps the
 * default web-app tenant.
 *
 * <p>Written as code, and the tenant is deliberately <b>not</b> called
 * {@code mcp}: Quarkus also selects a static tenant whose id appears as a
 * segment of the request path, so a tenant named {@code mcp} would catch
 * {@code /api/mcp/*} - the administration's MCP page - and check the admin
 * session against the MCP audience.</p>
 */
@ApplicationScoped
public class McpTenantResolver implements TenantResolver {

    public static final String TENANT = "mcptransport";

    @Override
    public String resolve(RoutingContext context) {
        return McpApiKeyAuthenticationMechanism.isMcpTransport(context.normalizedPath()) ? TENANT : null;
    }
}
