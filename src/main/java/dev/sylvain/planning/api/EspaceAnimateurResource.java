package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.DemandeEchangeService;
import dev.sylvain.planning.service.DemandeEchangeService.NouvelleDemande;
import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.EspaceAccesService;
import dev.sylvain.planning.service.EspaceAnimateurService;
import dev.sylvain.planning.service.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.EspaceAnimateurService.EspaceAnimateurView;
import dev.sylvain.planning.service.PlanningExportService;
import dev.sylvain.planning.service.PlanPublieService;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The animateur self-service espace (issue #165), the only part of the API
 * reachable without the admin session: every route carries the animateur's
 * access token, printed as a link on their individual PDF planning.
 *
 * <p>The guards do all the plumbing, declaratively. {@link TokenRequired}
 * (bootstrap routes: code request, session opening) resolves the token — 404
 * unknown, nothing must help guessing one — and binds the owner's edition to
 * the request, so no {@code X-Edition-Id} header is ever trusted here.
 * {@link EspaceSessionRequired} (every other route) adds the session check on
 * top: since the espace serves the planning for download, the link alone is
 * not enough — a session opened by e-mail code (see {@link EspaceAccesService})
 * rides in the {@code planning-espace} HttpOnly cookie, and a valid token without
 * it answers 401 (the interface then shows the code screen). The methods below
 * only contain business calls: identity and edition come from the guards.</p>
 */
@Path("/espace-animateur")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EspaceAnimateurResource {

    static final String COOKIE_SESSION = "planning-espace";

    @Inject
    EspaceAccesService espaceAccesService;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    EditionRequestScope editionRequestScope;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PlanningExportService planningExportService;

    /** Who I am, my persisted planning (with teammates) and the colleagues I can swap with. */
    @GET
    @Path("/{jeton}")
    @EspaceSessionRequired
    public EspaceAnimateurView espace() {
        return espaceAnimateurService.buildView(animateurCourant());
    }

    /**
     * A colleague's seats, for the « créneau souhaité en échange » picker of
     * a directed exchange — the same slots-and-stands information the printed
     * global planning already circulates, nothing more.
     */
    @GET
    @Path("/{jeton}/collegues/{collegueId}/postes")
    @EspaceSessionRequired
    @FoireOpenRequired
    public List<EspaceAnimateurService.PosteAnimateurView> colleaguePostes(
            @PathParam("collegueId") String collegueId) {
        return espaceAnimateurService.colleaguePostes(collegueId);
    }

    /**
     * « Qui peut me remplacer ? » on one of MY seats: the colleagues an
     * échange would really work with, for the animateur who does not want that
     * créneau and has nobody in mind. Read-only — no demande is created, and
     * the chosen colleague still has to agree.
     *
     * <p>Bounded like the admin's repair assistant: {@code plafond} caps how
     * many colleagues are simulated (one full analyse each), and the answer
     * says how many were eligible against how many were tried.</p>
     */
    @GET
    @Path("/{jeton}/suggestions-echange")
    @EspaceSessionRequired
    @FoireOpenRequired
    public EspaceAnimateurService.SuggestionsEchangeView suggestionsEchange(
            @QueryParam("creneauId") Long creneauId,
            @QueryParam("standId") String standId,
            @QueryParam("plafond") Integer plafond) {
        return espaceAnimateurService.suggestionsEchange(animateurCourant(), creneauId, standId, plafond);
    }

    /** My demandes d'échange, most recent first, whatever their statut. */
    @GET
    @Path("/{jeton}/demandes")
    @EspaceSessionRequired
    public List<DemandeEchangeView> demandes() {
        return espaceAnimateurService.toViews(demandeEchangeService.listForRequester(animateurCourant()));
    }

    /**
     * Demandes targeting ME — the ones awaiting my agreement before the admin
     * ever sees them, plus their history for context.
     */
    @GET
    @Path("/{jeton}/demandes-recues")
    @EspaceSessionRequired
    public List<DemandeEchangeView> receivedDemandes() {
        return espaceAnimateurService.toViews(demandeEchangeService.listForTarget(animateurCourant()));
    }

    /** I agree with a demande targeting me: it enters the admin queue, both sides now OK. */
    @POST
    @Path("/{jeton}/demandes-recues/{demandeId}/accord")
    @EspaceSessionRequired
    public Response grantReceivedDemande(@PathParam("demandeId") String demandeId) {
        return Response.ok(espaceAnimateurService.toViews(
                List.of(demandeEchangeService.acceptByTarget(animateurCourant(), demandeId))).get(0)).build();
    }

    /** I decline a demande targeting me: terminal, the demandeur is told, the admin never arbitrates. */
    @POST
    @Path("/{jeton}/demandes-recues/{demandeId}/refus")
    @EspaceSessionRequired
    public Response declineReceivedDemande(@PathParam("demandeId") String demandeId) {
        return Response.ok(espaceAnimateurService.toViews(
                List.of(demandeEchangeService.declineByTarget(animateurCourant(), demandeId))).get(0)).build();
    }

    /**
     * Submits a batch of demandes. Each one is prevalidated against the hard
     * constraints; the batch is stored whatever the verdicts (the response
     * tells which ones are infeasible in the current planning), and the admin
     * is notified once.
     */
    @POST
    @Path("/{jeton}/demandes")
    @EspaceSessionRequired
    public Response submit(List<NouvelleDemande> nouvelles) {
        return Response.ok(espaceAnimateurService.toViews(
                demandeEchangeService.submit(animateurCourant(), nouvelles))).build();
    }

    /**
     * My planning as a PDF — same document as the admin's individual export,
     * downloadable by the animateur themself. Stays available when the foire
     * is closed: closing only stops the échanges, never the consultation.
     */
    @GET
    @Path("/{jeton}/planning.pdf")
    @EspaceSessionRequired
    @Produces("application/pdf")
    public Response planningPdf() {
        PlanningEvenement planning = planPublieService.planPublie();
        byte[] contenu = planningExportService.exportAnimateurPdf(planning, animateurCourant());
        return Response.ok(contenu)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName(planning, "pdf") + "\"")
                .build();
    }

    /** My planning as an ICS calendar, importable in any agenda app. */
    @GET
    @Path("/{jeton}/planning.ics")
    @EspaceSessionRequired
    @Produces("text/calendar")
    public Response planningIcs() {
        PlanningEvenement planning = planPublieService.planPublie();
        String contenu = planningExportService.exportAnimateurIcs(planning, animateurCourant());
        return Response.ok(contenu)
                .type("text/calendar; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName(planning, "ics") + "\"")
                .build();
    }

    /** Withdraws one of my own, still-pending demandes. */
    @POST
    @Path("/{jeton}/demandes/{demandeId}/annulation")
    @EspaceSessionRequired
    public Response cancel(@PathParam("demandeId") String demandeId) {
        demandeEchangeService.cancel(animateurCourant(), demandeId);
        return Response.noContent().build();
    }

    /**
     * Sends a fresh access code to the animateur's e-mail address. One of the
     * two bootstrap routes that only need the token: it is how a session gets
     * created in the first place.
     */
    @POST
    @Path("/{jeton}/code")
    @TokenRequired
    public Response requestCode() {
        try {
            return Response.ok(espaceAccesService.requestCode(animateurCourant())).build();
        } catch (EspaceAccesService.TooManyRequests e) {
            return Response.status(429)
                    .header(HttpHeaders.RETRY_AFTER, e.secondsBeforeNextTry())
                    .entity(new ValidationError(e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail an espace access code");
            return Response.serverError()
                    .entity(new ValidationError(
                            "L'envoi du code a échoué : réessayez dans quelques instants."))
                    .build();
        }
    }

    /**
     * Exchanges a valid code for the durable session cookie.
     *
     * <p>{@code Secure} is added as soon as the visitor reached the
     * application over HTTPS — the scheme of the request the visitor really
     * made, so a TLS-terminating reverse proxy announcing
     * {@code X-Forwarded-Proto: https} counts (see
     * {@code quarkus.http.proxy.proxy-address-forwarding}). Without it the
     * 30-day session cookie would also travel on a plain http request to the
     * same host, which is exactly what an attacker on the network needs.
     * Attaching it unconditionally instead would make the espace unusable on
     * the http-only local stack, so the flag follows the connection.</p>
     */
    @POST
    @Path("/{jeton}/session")
    @TokenRequired
    public Response openSession(CodeSession codeSession, @Context UriInfo uriInfo) {
        String session = espaceAccesService.openSession(animateurCourant(),
                codeSession == null ? null : codeSession.code());
        NewCookie cookie = new NewCookie.Builder(COOKIE_SESSION)
                .value(session)
                .path("/api/espace-animateur")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .secure(encryptedRequest(uriInfo))
                .maxAge((int) EspaceAccesService.VALIDITE_SESSION.toSeconds())
                .build();
        return Response.noContent().cookie(cookie).build();
    }

    /** True when the visitor's own request was HTTPS, proxy headers included. */
    private static boolean encryptedRequest(UriInfo uriInfo) {
        return "https".equalsIgnoreCase(uriInfo.getRequestUri().getScheme());
    }

    /** Body of the session opener: the code received by e-mail. */
    public record CodeSession(String code) {
    }

    /** The animateur the guard resolved from the URL token — never {@code null} once a guard ran. */
    private String animateurCourant() {
        return editionRequestScope.getTokenOwner().animateurId();
    }

    /** Same readable convention as the admin exports — the very same code, in fact. */
    private String fileName(PlanningEvenement planning, String extension) {
        return PlanningExportService.planningFileName(
                planningExportService.resolveAnimateurName(planning, animateurCourant()), extension);
    }

    private static Response badRequest(IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ValidationError(e.getMessage()))
                .build();
    }
}
