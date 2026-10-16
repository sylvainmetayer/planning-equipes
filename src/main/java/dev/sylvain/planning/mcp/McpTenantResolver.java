package dev.sylvain.planning.mcp;

import io.quarkus.oidc.TenantResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Sends the MCP transport — {@code /mcp} and below, nothing else — to the
 * {@code mcp} OIDC tenant (a resource server validating the
 * {@code planning-mcp} audience); every other path keeps the default web-app
 * tenant.
 *
 * <p>Written as code, and the tenant is deliberately <b>not</b> called
 * {@code mcp}: Quarkus also selects a static tenant whose id appears as a
 * segment of the request path, so a tenant named {@code mcp} caught
 * {@code /api/mcp/*} — the administration's MCP page — and checked the admin
 * session against the MCP audience, which refused it. The transport and the
 * page share a word, not a credential ({@code RolesKeycloakTest} holds it).</p>
 */
@ApplicationScoped
public class McpTenantResolver implements TenantResolver {

    public static final String TENANT = "mcptransport";

    @Override
    public String resolve(RoutingContext context) {
        String path = context.normalizedPath();
        return path.equals("/mcp") || path.startsWith("/mcp/") ? TENANT : null;
    }
}
