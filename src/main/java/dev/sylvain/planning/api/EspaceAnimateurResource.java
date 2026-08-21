package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.DemandeEchangeService;
import dev.sylvain.planning.service.DemandeEchangeService.NouvelleDemande;
import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.EspaceAccesService;
import dev.sylvain.planning.service.EspaceAnimateurService;
import dev.sylvain.planning.service.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.EspaceAnimateurService.EspaceAnimateurView;
import dev.sylvain.planning.service.PlanningExportService;
import dev.sylvain.planning.service.PlanningPersistenceService;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The animateur self-service espace (issue #165), the only part of the API
 * reachable without the admin session: every route carries the animateur's
 * access token, printed as a link on their individual PDF planning.
 *
 * <p>The guards do all the plumbing, declaratively. {@link JetonRequis}
 * (bootstrap routes: code request, session opening) resolves the token — 404
 * unknown, nothing must help guessing one — and binds the owner's edition to
 * the request, so no {@code X-Edition-Id} header is ever trusted here.
 * {@link SessionEspaceRequise} (every other route) adds the session check on
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
    PlanningPersistenceService persistenceService;

    @Inject
    PlanningExportService planningExportService;

    /** Who I am, my persisted planning (with teammates) and the colleagues I can swap with. */
    @GET
    @Path("/{jeton}")
    @SessionEspaceRequise
    public EspaceAnimateurView espace() {
        return espaceAnimateurService.construireVue(animateurCourant());
    }

    /**
     * A colleague's seats, for the « créneau souhaité en échange » picker of
     * a directed exchange — the same slots-and-stands information the printed
     * global planning already circulates, nothing more.
     */
    @GET
    @Path("/{jeton}/collegues/{collegueId}/postes")
    @SessionEspaceRequise
    @FoireOuverteRequise
    public List<EspaceAnimateurService.PosteAnimateurView> postesCollegue(
            @PathParam("collegueId") String collegueId) {
        return espaceAnimateurService.postesCollegue(collegueId);
    }

    /** My demandes d'échange, most recent first, whatever their statut. */
    @GET
    @Path("/{jeton}/demandes")
    @SessionEspaceRequise
    public List<DemandeEchangeView> demandes() {
        return espaceAnimateurService.versVues(demandeEchangeService.listerPourDemandeur(animateurCourant()));
    }

    /**
     * Demandes targeting ME — the ones awaiting my agreement before the admin
     * ever sees them, plus their history for context.
     */
    @GET
    @Path("/{jeton}/demandes-recues")
    @SessionEspaceRequise
    public List<DemandeEchangeView> demandesRecues() {
        return espaceAnimateurService.versVues(demandeEchangeService.listerPourCible(animateurCourant()));
    }

    /** I agree with a demande targeting me: it enters the admin queue, both sides now OK. */
    @POST
    @Path("/{jeton}/demandes-recues/{demandeId}/accord")
    @SessionEspaceRequise
    public Response accorderDemandeRecue(@PathParam("demandeId") String demandeId) {
        try {
            return Response.ok(espaceAnimateurService.versVues(
                    List.of(demandeEchangeService.accepterParCible(animateurCourant(), demandeId))).get(0)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** I decline a demande targeting me: terminal, the demandeur is told, the admin never arbitrates. */
    @POST
    @Path("/{jeton}/demandes-recues/{demandeId}/refus")
    @SessionEspaceRequise
    public Response declinerDemandeRecue(@PathParam("demandeId") String demandeId) {
        try {
            return Response.ok(espaceAnimateurService.versVues(
                    List.of(demandeEchangeService.declinerParCible(animateurCourant(), demandeId))).get(0)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /**
     * Submits a batch of demandes. Each one is prevalidated against the hard
     * constraints; the batch is stored whatever the verdicts (the response
     * tells which ones are infeasible in the current planning), and the admin
     * is notified once.
     */
    @POST
    @Path("/{jeton}/demandes")
    @SessionEspaceRequise
    public Response soumettre(List<NouvelleDemande> nouvelles) {
        try {
            return Response.ok(espaceAnimateurService.versVues(
                    demandeEchangeService.soumettre(animateurCourant(), nouvelles))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /**
     * My planning as a PDF — same document as the admin's individual export,
     * downloadable by the animateur themself. Stays available when the foire
     * is closed: closing only stops the échanges, never the consultation.
     */
    @GET
    @Path("/{jeton}/planning.pdf")
    @SessionEspaceRequise
    @Produces("application/pdf")
    public Response planningPdf() {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        byte[] contenu = planningExportService.exportAnimateurPdf(planning, animateurCourant());
        return Response.ok(contenu)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + nomFichier(planning, "pdf") + "\"")
                .build();
    }

    /** My planning as an ICS calendar, importable in any agenda app. */
    @GET
    @Path("/{jeton}/planning.ics")
    @SessionEspaceRequise
    @Produces("text/calendar")
    public Response planningIcs() {
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        String contenu = planningExportService.exportAnimateurIcs(planning, animateurCourant());
        return Response.ok(contenu)
                .type("text/calendar; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + nomFichier(planning, "ics") + "\"")
                .build();
    }

    /** Withdraws one of my own, still-pending demandes. */
    @POST
    @Path("/{jeton}/demandes/{demandeId}/annulation")
    @SessionEspaceRequise
    public Response annuler(@PathParam("demandeId") String demandeId) {
        try {
            demandeEchangeService.annuler(animateurCourant(), demandeId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /**
     * Sends a fresh access code to the animateur's e-mail address. One of the
     * two bootstrap routes that only need the token: it is how a session gets
     * created in the first place.
     */
    @POST
    @Path("/{jeton}/code")
    @JetonRequis
    public Response demanderCode() {
        try {
            return Response.ok(espaceAccesService.demanderCode(animateurCourant())).build();
        } catch (EspaceAccesService.TropDeDemandes e) {
            return Response.status(429)
                    .header(HttpHeaders.RETRY_AFTER, e.secondesAvantNouvelEssai())
                    .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail an espace access code");
            return Response.serverError()
                    .entity(new ReferenceDataResource.ErreurValidation(
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
    @JetonRequis
    public Response ouvrirSession(CodeSession codeSession, @Context UriInfo uriInfo) {
        try {
            String session = espaceAccesService.ouvrirSession(animateurCourant(),
                    codeSession == null ? null : codeSession.code());
            return Response.noContent()
                    .header("Set-Cookie", COOKIE_SESSION + "=" + session
                            + "; Path=/api/espace-animateur; HttpOnly; SameSite=Strict"
                            + (requeteChiffree(uriInfo) ? "; Secure" : "")
                            + "; Max-Age=" + EspaceAccesService.VALIDITE_SESSION.toSeconds())
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** True when the visitor's own request was HTTPS, proxy headers included. */
    private static boolean requeteChiffree(UriInfo uriInfo) {
        return "https".equalsIgnoreCase(uriInfo.getRequestUri().getScheme());
    }

    /** Body of the session opener: the code received by e-mail. */
    public record CodeSession(String code) {
    }

    /** The animateur the guard resolved from the URL token — never {@code null} once a guard ran. */
    private String animateurCourant() {
        return editionRequestScope.getProprietaireJeton().animateurId();
    }

    /** Same readable convention as the admin exports: {@code planning-Prenom-Nom.pdf}. */
    private String nomFichier(PlanningFestival planning, String extension) {
        String nom = planningExportService.resolveAnimateurName(planning, animateurCourant());
        String safe = (nom == null ? animateurCourant() : nom)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        return "planning-" + (safe.isEmpty() ? "animateur" : safe) + "." + extension;
    }

    private static Response badRequest(IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                .build();
    }
}
