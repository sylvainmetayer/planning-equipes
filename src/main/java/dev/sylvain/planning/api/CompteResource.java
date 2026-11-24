package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.journal.JournalActionService;
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
import java.util.Optional;

/**
 * Named accounts and the rights delegated to them. Administrators only, like
 * the rest of {@code /api}.
 *
 * <p>An account is <b>deactivated</b>, never deleted, and a right is
 * <b>withdrawn</b>, never deleted — the history must still be able to say who
 * held what, and when. Nothing here sets a password or a second factor.</p>
 */
@Path("/comptes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompteResource {

    @Inject
    CompteService comptes;

    @Inject
    JournalActionService journal;

    @GET
    public List<Compte> list() {
        return comptes.list();
    }

    @POST
    public Compte create(NouveauCompte nouveau) {
        return comptes.create(nouveau == null ? null : nouveau.email(), nouveau == null ? null : nouveau.nom());
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
                ? comptes.grant(id, null, null, null, auteur())
                : comptes.grant(id, habilitation.role(), habilitation.editionId(), habilitation.expireLe(), auteur());
    }

    /** Withdraws a right: it stays in the list, dated, and opens nothing any more. */
    @DELETE
    @Path("/{id}/habilitations/{habilitationId}")
    public Compte withdraw(@PathParam("id") String id, @PathParam("habilitationId") String habilitationId) {
        return comptes.withdraw(id, habilitationId);
    }

    /** Who grants: an account id, never an address; the configuration's account when nobody is bound. */
    private String auteur() {
        return Optional.ofNullable(journal.nomAdmin()).orElse(Compte.CONFIG_ID);
    }

    public record NouveauCompte(String email, String nom) {}

    /**
     * @param editionId {@code null} for every edition
     * @param expireLe  {@code null} for no expiry
     */
    public record NouvelleHabilitation(RoleHabilitation role, String editionId, Instant expireLe) {}
}
