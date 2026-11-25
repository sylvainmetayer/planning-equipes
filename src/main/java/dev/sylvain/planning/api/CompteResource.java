package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import jakarta.annotation.security.RolesAllowed;
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
 * Named accounts and the rights delegated to them. Administrators only, like
 * the rest of {@code /api}.
 *
 * <p>An account is <b>deactivated</b>, never deleted, and a right is
 * <b>withdrawn</b>, never deleted — the history must still be able to say who
 * held what, and when. A password is only ever chosen by its owner, through a mailed single-use link.</p>
 */
@Path("/comptes")
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompteResource {

    @Inject
    CompteService comptes;

    @Inject
    CompteAccesService acces;

    @GET
    public List<Compte> list() {
        return comptes.list();
    }

    /**
     * Creates the account; {@code inviter} also mails it the link to choose its
     * password. The invitation failing leaves the account created: the answer
     * is the refusal, and the account is invited again from its row.
     */
    @POST
    public Compte create(NouveauCompte nouveau) {
        Compte compte =
                comptes.create(nouveau == null ? null : nouveau.email(), nouveau == null ? null : nouveau.nom());
        if (nouveau != null && Boolean.TRUE.equals(nouveau.invite())) {
            acces.sendLink(compte.id(), true);
        }
        return compte;
    }

    /** Mails the link to choose a first password (72 h, single use); the administrator never sees it. */
    @POST
    @Path("/{id}/invitation")
    @Consumes(MediaType.WILDCARD)
    public void invite(@PathParam("id") String id) {
        acces.sendLink(id, true);
    }

    /** Mails the link to choose a new password (1 h, single use); the old one stays until it is used. */
    @POST
    @Path("/{id}/reinitialisation")
    @Consumes(MediaType.WILDCARD)
    public void reset(@PathParam("id") String id) {
        acces.sendLink(id, false);
    }

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
        return habilitation == null
                ? comptes.grant(id, null, null, null)
                : comptes.grant(id, habilitation.role(), habilitation.editionId(), habilitation.expireLe());
    }

    /** Withdraws a right: it stays in the list, dated, and opens nothing any more. */
    @DELETE
    @Path("/{id}/habilitations/{habilitationId}")
    public Compte withdraw(@PathParam("id") String id, @PathParam("habilitationId") String habilitationId) {
        return comptes.withdraw(id, habilitationId);
    }

    public record NouveauCompte(String email, String nom, Boolean invite) {}

    /**
     * @param editionId {@code null} for every edition
     * @param expireLe  {@code null} for no expiry
     */
    public record NouvelleHabilitation(RoleHabilitation role, String editionId, Instant expireLe) {}
}
