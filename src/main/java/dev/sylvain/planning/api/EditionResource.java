package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.edition.EditionActivationService;
import dev.sylvain.planning.service.edition.EditionDelta;
import dev.sylvain.planning.service.edition.EditionDeltaService;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.edition.EtatEditionService;
import dev.sylvain.planning.service.edition.EtatEditionView;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService;
import dev.sylvain.planning.service.referentiel.GelReferentielService;
import dev.sylvain.planning.service.referentiel.ReferentialFamily;
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

    private final GelReferentielService gelService;

    private final EditionActivationService activationService;

    private final EditionDeltaService deltaService;

    @Inject
    public EditionResource(
            EditionService editionService,
            EtatEditionService etatEditionService,
            CoherenceReferentielService coherenceService,
            GelReferentielService gelService,
            EditionActivationService activationService,
            EditionDeltaService deltaService) {
        this.activationService = activationService;
        this.editionService = editionService;
        this.etatEditionService = etatEditionService;
        this.coherenceService = coherenceService;
        this.gelService = gelService;
        this.deltaService = deltaService;
    }

    @GET
    public List<Edition> list() {
        return editionService.listEditions();
    }

    /**
     * The edition this very request was resolved to — refused with
     * {@code EDITION_REQUISE} or {@code EDITION_INCONNUE} when the request names
     * none or one someone deleted, which is how the client learns it must
     * choose again (ADR 0072).
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

    /**
     * The freeze of the current edition's referential, family by family
     * (ADR 0052): which of the four are frozen, and since when.
     */
    @GET
    @Path("/courant/gel")
    public List<GelReferentielService.EtatGel> gel() {
        return gelService.etat();
    }

    @GET
    @Path("/courant/gel/{famille}")
    public GelReferentielService.EtatGel gel(@PathParam("famille") ReferentialFamily famille) {
        return gelService.etat(famille);
    }

    /**
     * Freezes the family: from now on every write of it is refused in
     * {@code 409 REFERENTIEL_FIGE}, whatever path it takes. Idempotent — a
     * family already frozen keeps its date. Accepted while a solve runs, which
     * read its data at its start.
     */
    @PUT
    @Path("/courant/gel/{famille}")
    public GelReferentielService.EtatGel freeze(@PathParam("famille") ReferentialFamily famille) {
        gelService.freeze(famille);
        return gelService.etat(famille);
    }

    /** Lifts the freeze of the family; lifting an open family changes nothing. */
    @DELETE
    @Path("/courant/gel/{famille}")
    public GelReferentielService.EtatGel lift(@PathParam("famille") ReferentialFamily famille) {
        gelService.lift(famille);
        return gelService.etat(famille);
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
     * <p>{@code avecAnimateurs} (default {@code true}) decides whether the
     * <b>people</b> come along. Set to
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

    /**
     * What changed in the referential from edition {@code id} to edition
     * {@code cible}: stands, animateurs, timeslots, settings and volumes, rows
     * matched by code, name or e-mail — never by id, which two editions share
     * by coincidence. Read-only; animateurs are named, for this
     * administrator's screen only.
     */
    @GET
    @Path("/{id}/delta/{cible}")
    public EditionDelta delta(@PathParam("id") String id, @PathParam("cible") String cible) {
        return deltaService.compareNamingAnimateurs(id, cible);
    }

    /** The same delta as a CSV: no animateur is named, an animateur line carries its ids only. */
    @GET
    @Path("/{id}/delta/{cible}/export.csv")
    @Produces("text/csv")
    public Response exportDeltaCsv(@PathParam("id") String id, @PathParam("cible") String cible) {
        return CsvDownload.attachment(deltaService.csv(id, cible), "delta-" + id + "-" + cible + ".csv");
    }

    /**
     * What activating {@code id} would close in the edition active today —
     * links that stop working, swap requests left open, solves queued — read
     * before the switch is confirmed (ADR 0072).
     */
    @GET
    @Path("/{id}/activation")
    public EditionActivationService.ActivationPreview activationPreview(@PathParam("id") String id) {
        return activationService.preview(id);
    }

    /**
     * Makes {@code id} the active edition — the only one that publishes, sends
     * mail and opens the espace, the ICS feed and the wall display — and every
     * other one inactive, atomically. {@code 409} while a solve runs in the
     * outgoing or the incoming edition.
     */
    @PUT
    @Path("/{id}/active")
    public Edition activate(@PathParam("id") String id) {
        return activationService.activate(id);
    }

    /** Leaves no edition active: between two events, nothing reaches outside. */
    @DELETE
    @Path("/{id}/active")
    public Response deactivate(@PathParam("id") String id) {
        activationService.deactivate(id);
        return Response.noContent().build();
    }

    /**
     * What the editions' state asks of the organiser today: the active edition
     * is over, or an inactive one starts within a few days. The admin shell
     * shows a banner when the list is not empty.
     */
    @GET
    @Path("/situations")
    public List<EditionActivationService.Situation> situations() {
        return activationService.situations();
    }

    /**
     * Drops the edition and its whole reference model. Returns 400 with an
     * explanation when it is the active edition, the current one, or the last
     * remaining one, rather than letting the caller lose data or the links it
     * serves.
     */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        editionService.delete(id);
        return Response.noContent().build();
    }
}
