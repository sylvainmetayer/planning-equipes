package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.edition.EtatEditionService;
import dev.sylvain.planning.service.edition.EtatEditionView;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * The editions the whole reference model is partitioned into. Which one a
 * request reads and writes is <b>not</b> decided here: the client designates it
 * per request through the {@code X-Edition-Id} header (see
 * {@code EditionHeaderFilter}), so two browser tabs can sit on two different
 * editions at once. These endpoints only manage the list itself.
 */
@Path("/editions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EditionResource {

    private final EditionService editionService;

    private final EtatEditionService etatEditionService;

    private final CoherenceReferentielService coherenceService;

    @Inject
    public EditionResource(
            EditionService editionService,
            EtatEditionService etatEditionService,
            CoherenceReferentielService coherenceService) {
        this.editionService = editionService;
        this.etatEditionService = etatEditionService;
        this.coherenceService = coherenceService;
    }

    @GET
    public List<Edition> list() {
        return editionService.listEditions();
    }

    /**
     * The edition this very request was resolved to. Not redundant with the
     * list: a client whose stored {@code X-Edition-Id} names an edition someone
     * else has deleted silently falls back to the default one, and this is how
     * it finds out which edition it is actually looking at.
     */
    @GET
    @Path("/courant")
    public Edition courant() {
        return editionService.editionCourante();
    }

    /**
     * Where the current edition stands in its cycle: one line per step of
     * the guide, each with its state and the figures behind it (issue #485).
     * The single call the home screen makes; the nine screens it links to
     * keep their own routes. Never refused while a solve runs — the solve is
     * one of the states it reports.
     */
    @GET
    @Path("/courant/etat")
    public EtatEditionView etat() {
        return etatEditionService.etat();
    }

    /**
     * The coherence checklist of the current edition: every anomaly the
     * application already detects on what was entered — write-time warnings
     * replayed on the whole referential, opening anomalies, grid check,
     * contradictory or untenable exceptions, staffing bound — one line each,
     * with its family, its severity, its source's sentence and the fiche it
     * is about. Read-only; never waits for a solve.
     */
    @GET
    @Path("/courant/coherence")
    public CoherenceReferentielService.CoherenceReport coherence() {
        return coherenceService.report();
    }

    @POST
    public Response create(Edition edition) {
        return Response.ok(editionService.create(edition)).build();
    }

    @PUT
    @Path("/{id}")
    public Response rename(@PathParam("id") String id, Edition edition) {
        return Response.ok(editionService.renommer(id, edition)).build();
    }

    /**
     * Copies {@code id}'s whole reference model into a brand-new edition —
     * "2026 = 2025 minus the assignments". Solver results are excluded: they
     * belong to the edition they were computed for. This is the action that
     * makes multi-edition usable at all; without it, preparing next year's
     * edition means re-importing everything by hand.
     *
     * <p>{@code avecAnimateurs} (default {@code true}, so the gesture of issue
     * #172 is untouched) decides whether the <b>people</b> come along. Set to
     * {@code false} the copy is a <i>year template</i>: stands, emplacements,
     * typologies, opening hours, timeslots and every parameter, and nobody —
     * neither the roster nor their availability, competences, wishes or the ad
     * hoc constraints naming them. Preparing 2027 from 2026 has no business
     * duplicating a file of persons who have not signed up again (issue #90,
     * {@code docs/rgpd.md}).</p>
     */
    @POST
    @Path("/{id}/dupliquer")
    public Response duplicate(
            @PathParam("id") String id,
            @QueryParam("avecAnimateurs") @DefaultValue("true") boolean avecAnimateurs,
            Edition target) {
        return Response.ok(editionService.duplicate(id, target, avecAnimateurs)).build();
    }

    /** Designates the fallback edition for any caller sending no {@code X-Edition-Id}. */
    @PUT
    @Path("/{id}/defaut")
    public Response setAsDefault(@PathParam("id") String id) {
        editionService.setAsDefault(id);
        return Response.noContent().build();
    }

    /**
     * Drops the edition and its whole reference model. Returns 400 with an
     * explanation when it is the default edition, the current one, or the last
     * remaining one, rather than letting the caller lose data or end up with
     * nothing to fall back on.
     */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        editionService.delete(id);
        return Response.noContent().build();
    }
}
