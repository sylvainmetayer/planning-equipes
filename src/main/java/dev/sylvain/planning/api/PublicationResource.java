package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.service.PlanPublicationService;
import dev.sylvain.planning.service.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.PlanPublicationService.RapportPublication;
import dev.sylvain.planning.service.PlanPublieService;
import dev.sylvain.planning.service.PlanSnapshotService;
import dev.sylvain.planning.service.PublicationTraceRepository;
import dev.sylvain.planning.service.PublicationTraceRepository.Destinataire;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Publishing the planning (issue #245): the button that replaced « envoyer à
 * tous », and what it needs to be honest about — who is concerned, what they
 * will read, and when the last publication left.
 *
 * <p>The count is read here, on demand: nothing is pushed and nothing polls.
 * A refusal (a solve running, nobody concerned) is a {@code BusinessError}
 * carrying its own status, so there is no {@code try/catch} at this level.</p>
 */
@Path("/planning/publication")
@Produces(MediaType.APPLICATION_JSON)
public class PublicationResource {

    @Inject
    PlanPublicationService publicationService;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PublicationTraceRepository traceRepository;

    /** Who would be written to and what they would read — sends nothing. */
    @GET
    public ApercuPublication apercu() {
        return publicationService.apercu();
    }

    /** Publishes, and writes to the concerned people only. */
    @POST
    @Consumes(MediaType.WILDCARD)
    public RapportPublication publier() {
        return publicationService.publier();
    }

    /**
     * The trace of one publication: who was told what, and when. Defaults to
     * the last published plan, which is the one an operator asks about.
     * Answers an empty list when nothing was ever published, rather than 404:
     * « personne n'a encore été prévenu » is an answer.
     */
    @GET
    @Path("/destinataires")
    public List<Destinataire> destinataires(@QueryParam("snapshot") Long snapshotId) {
        if (snapshotId != null) {
            return traceRepository.bySnapshot(snapshotId);
        }
        PlanSnapshotService.SnapshotMeta derniere = planPublieService.lastPublication();
        return derniere == null ? List.of() : traceRepository.bySnapshot(derniere.id());
    }
}
