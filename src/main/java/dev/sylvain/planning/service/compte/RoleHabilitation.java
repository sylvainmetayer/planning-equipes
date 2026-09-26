package dev.sylvain.planning.service.compte;

import java.util.Locale;

/**
 * The roles an administrator grants <b>in this application</b>, per edition,
 * with an expiry (issues #294, #295, ADR 0054).
 *
 * <p>The global roles — {@code admin}, {@code mcp}, {@code animateur} — are
 * Keycloak realm roles, granted in the realm. These are the delegated ones,
 * whose scope is data (an edition, a set of stands) rather than a fact about
 * the person. Code, not configuration: a configurable permission matrix is 2ⁿ
 * combinations nobody tests, and each role here earns its own structural
 * test when its routes open.</p>
 *
 * <p>Neither role opens a route yet: the HTTP policies name {@code admin} and
 * {@code mcp} only, so an identity carrying {@code rh} or
 * {@code responsable-stand} is refused everywhere — deny by default, until the
 * lot that builds each role's projections opens them.</p>
 */
public enum RoleHabilitation {
    /** Read-only access for HR: reporting, legality, compliance (#294). */
    RH,
    /** Read-only access to the planning of the stands in their scope (#295). */
    RESPONSABLE_STAND;

    /** The name carried by the {@code SecurityIdentity}, in the realm's spelling. */
    public String securityRole() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
