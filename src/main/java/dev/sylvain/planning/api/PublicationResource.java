package dev.sylvain.planning.api;

import dev.sylvain.planning.service.publication.EtatEnvoisService;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.publication.PlanPublicationService.RapportPublication;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.publication.PublicationTraceRepository;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.Destinataire;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

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

    private final PlanPublicationService publicationService;

    private final PlanPublieService planPublieService;

    private final PublicationTraceRepository traceRepository;

    private final EtatEnvoisService etatEnvoisService;

    @Inject
    public PublicationResource(
            PlanPublicationService publicationService,
            PlanPublieService planPublieService,
            PublicationTraceRepository traceRepository,
            EtatEnvoisService etatEnvoisService) {
        this.publicationService = publicationService;
        this.planPublieService = planPublieService;
        this.traceRepository = traceRepository;
        this.etatEnvoisService = etatEnvoisService;
    }

    /** Who would be written to and what they would read — sends nothing. */
    @GET
    public ApercuPublication apercu() {
        return publicationService.apercu();
    }

    /**
     * What the admin takes out of this send (issue #503).
     *
     * @param exclusions animateur ids whose message is deferred: they keep the
     *                   plan they were really told about, so they come back in
     *                   the next count. An absent body, or an empty list,
     *                   publishes to everybody concerned.
     * @param cibles     when set, the only people to write to — « Prévenir les
     *                   2 personnes » after an échange or a replacement.
     *                   Everybody else concerned is deferred as an exclusion
     *                   defers them
     */
    public record DemandePublication(List<String> exclusions, List<String> cibles) {}

    /**
     * Publishes, and writes to the concerned people the admin kept.
     *
     * <p>The deferred people travel in a body rather than as repeated query
     * parameters: the list is unbounded, and an animateur id is
     * client-supplied — only « not blank » is required of it — so neither a
     * separator nor a URL is a safe place to carry a variable number of them.
     * The call therefore now requires a {@code Content-Type: application/json},
     * where it used to accept anything; an empty body is enough to say « je
     * n'exclus personne ».</p>
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public RapportPublication publier(DemandePublication demande) {
        return demande == null
                ? publicationService.publier(List.of())
                : publicationService.publier(demande.exclusions(), demande.cibles());
    }

    /**
     * The same review table as a file: one line per person, the sentences in
     * one cell. Read away from the screen — « on passe la liste en réunion » —
     * and it sends nothing, exactly like the preview above.
     */
    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv() {
        return CsvDownload.attachment(
                PlanPublicationService.generateCsv(publicationService.apercu()), "diff-publication.csv");
    }

    /**
     * Who received which version, one line per animateur of the edition: the
     * version they were last told about, how their latest planning mail went,
     * the night's reminders and their acknowledgement. Reads only.
     */
    @GET
    @Path("/etat")
    public EtatEnvoisService.EtatEnvois etat() {
        return etatEnvoisService.etat();
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
