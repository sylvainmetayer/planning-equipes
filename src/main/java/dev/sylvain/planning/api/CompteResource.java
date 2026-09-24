package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * Named accounts and the rights delegated to them (issues #294, #295, ADR
 * 0049). Administrators only, like the rest of {@code /api}.
 *
 * <p>Keycloak owns the credentials: nothing here sets a password or a second
 * factor. An account is created on the person's first sign-in, or in advance
 * here to grant a right before they arrive; it is <b>deactivated</b>, never
 * deleted, and a right is <b>withdrawn</b>, never deleted — the history must
 * still be able to say who held what, and when.</p>
 */
@Path("/comptes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompteResource {

    @Inject
    CompteService comptes;

    @Inject
    SecurityIdentity identity;

    @GET
    public List<Compte> list() {
        return comptes.list();
    }

    @POST
    public Compte create(NouveauCompte nouveau) {
        return comptes.create(nouveau == null ? null : nouveau.email(), nouveau == null ? null : nouveau.nom());
    }

    /** Closes every door of the application to this person, whatever the realm says. */
    @POST
    @Path("/{id}/desactivation")
    @Consumes(MediaType.WILDCARD)
    public Compte deactivate(@PathParam("id") String id) {
        return comptes.deactivate(id);
    }

    @POST
    @Path("/{id}/reactivation")
    @Consumes(MediaType.WILDCARD)
    public Compte reactivate(@PathParam("id") String id) {
        return comptes.reactivate(id);
    }

    @POST
    @Path("/{id}/habilitations")
    public Compte grant(@PathParam("id") String id, NouvelleHabilitation habilitation) {
        if (habilitation == null) {
            return comptes.grant(id, null, null, null, null, auteur());
        }
        return comptes.grant(
                id,
                habilitation.role(),
                habilitation.editionId(),
                habilitation.expireLe(),
                habilitation.standIds(),
                auteur());
    }

    /** Withdraws a right: it stays in the list, dated, and opens nothing any more. */
    @DELETE
    @Path("/{id}/habilitations/{habilitationId}")
    public Compte withdraw(@PathParam("id") String id, @PathParam("habilitationId") String habilitationId) {
        return comptes.withdraw(id, habilitationId);
    }

    private String auteur() {
        return identity == null || identity.isAnonymous()
                ? null
                : identity.getPrincipal().getName();
    }

    public record NouveauCompte(String email, String nom) {}

    /**
     * @param editionId {@code null} for every edition
     * @param expireLe  {@code null} for no expiry
     * @param standIds  the scope of a {@code RESPONSABLE_STAND}, empty otherwise
     */
    public record NouvelleHabilitation(
            RoleHabilitation role, String editionId, Instant expireLe, List<String> standIds) {}
}
