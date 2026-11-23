package dev.sylvain.planning.service.compte;

import java.util.Locale;

/**
 * The roles an administrator grants <b>in this application</b>, per edition,
 * with an expiry (issues #294, #295, ADR 0077).
 *
 * <p>The identity provider only says who is calling; what that person may do
 * is granted here — {@link #ADMIN} included, so an instance plugged into a
 * directory it does not administer (a client's ADFS) still decides who
 * administers it. A provider that does map an {@code admin} role into its
 * tokens is honoured too, for the deployments that prefer it there. Code, not configuration: a configurable permission matrix is 2ⁿ
 * combinations nobody tests, and each role here earns its own structural
 * test when its routes open.</p>
 *
 * <p>{@code responsable-stand} opens {@code /api/responsable/*} and nothing
 * else (#295, {@code ResponsableService} scopes each read to the account's
 * rights). {@code rh} opens no route yet: an identity carrying it is refused
 * everywhere — deny by default, until the lot that builds its projections
 * opens them.</p>
 */
public enum RoleHabilitation {
    /** The whole instance, like the local account: never scoped to an edition. */
    ADMIN,
    /** Read-only access for HR: reporting, legality, compliance (#294). */
    RH,
    /** Read-only access to the planning of the stands in their scope (#295). */
    RESPONSABLE_STAND;

    /** The name carried by the {@code SecurityIdentity}, the HTTP policies' spelling. */
    public String securityRole() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
