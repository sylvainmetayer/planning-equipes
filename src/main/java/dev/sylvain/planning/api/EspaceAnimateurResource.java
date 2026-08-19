package dev.sylvain.planning.api;

import java.util.List;
import java.util.function.Function;

import dev.sylvain.planning.service.DemandeEchangeService;
import dev.sylvain.planning.service.DemandeEchangeService.NouvelleDemande;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EspaceAnimateurService;
import dev.sylvain.planning.service.ReferenceDataRepository;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The animateur self-service espace (issue #165), the only part of the API
 * reachable without the admin session: every route carries the animateur's
 * access token, printed as a link on their individual PDF planning. The token
 * alone resolves who is calling <b>and</b> which edition they belong to — no
 * {@code X-Edition-Id} header is trusted here (see
 * {@code EditionContext.executeDans}).
 *
 * <p>An unknown token answers a plain 404 with no distinction between "no such
 * token" and "no such route": the token is the credential, nothing should help
 * guessing one.</p>
 */
@Path("/espace-animateur")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EspaceAnimateurResource {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    EditionContext editionContext;

    /** Who I am, my persisted planning (with teammates) and the colleagues I can swap with. */
    @GET
    @Path("/{jeton}")
    public Response espace(@PathParam("jeton") String jeton) {
        return avecJeton(jeton, animateurId -> Response.ok(
                espaceAnimateurService.construireVue(animateurId)).build());
    }

    /** My demandes d'échange, most recent first, whatever their statut. */
    @GET
    @Path("/{jeton}/demandes")
    public Response demandes(@PathParam("jeton") String jeton) {
        return avecJeton(jeton, animateurId -> Response.ok(
                espaceAnimateurService.versVues(demandeEchangeService.listerPourDemandeur(animateurId))).build());
    }

    /**
     * Submits a batch of demandes. Each one is prevalidated against the hard
     * constraints; the batch is stored whatever the verdicts (the response
     * tells which ones are infeasible in the current planning), and the admin
     * is notified once.
     */
    @POST
    @Path("/{jeton}/demandes")
    public Response soumettre(@PathParam("jeton") String jeton, List<NouvelleDemande> nouvelles) {
        return avecJeton(jeton, animateurId -> {
            try {
                return Response.ok(espaceAnimateurService.versVues(
                        demandeEchangeService.soumettre(animateurId, nouvelles))).build();
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                        .build();
            }
        });
    }

    /** Withdraws one of my own, still-pending demandes. */
    @POST
    @Path("/{jeton}/demandes/{demandeId}/annulation")
    public Response annuler(@PathParam("jeton") String jeton, @PathParam("demandeId") String demandeId) {
        return avecJeton(jeton, animateurId -> {
            try {
                demandeEchangeService.annuler(animateurId, demandeId);
                return Response.noContent().build();
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                        .build();
            }
        });
    }

    /**
     * Resolves the token and runs {@code action} inside the owner's edition.
     * The espace never trusts the request's edition header.
     */
    private Response avecJeton(String jeton, Function<String, Response> action) {
        ReferenceDataRepository.ProprietaireJeton proprietaire = referenceDataService.resoudreJetonAnimateur(jeton);
        if (proprietaire == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ReferenceDataResource.ErreurValidation("Lien inconnu ou expiré"))
                    .build();
        }
        return editionContext.executeDans(proprietaire.editionId(),
                () -> action.apply(proprietaire.animateurId()));
    }
}
