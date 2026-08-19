package dev.sylvain.planning.api;

import java.util.List;
import java.util.function.Function;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.DemandeEchangeService;
import dev.sylvain.planning.service.DemandeEchangeService.NouvelleDemande;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EspaceAnimateurService;
import dev.sylvain.planning.service.PlanningExportService;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataRepository;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
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
 *
 * <p>Since the espace serves the planning for download, the link alone is no
 * longer enough: every route except the two code endpoints also requires a
 * session opened by e-mail code (see {@link EspaceAccesService}), carried by
 * the {@code planning-espace} HttpOnly cookie. Without it, a valid token answers
 * 401 — the interface then shows the code screen.</p>
 */
@Path("/espace-animateur")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EspaceAnimateurResource {

    static final String COOKIE_SESSION = "planning-espace";

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    dev.sylvain.planning.service.EspaceAccesService espaceAccesService;

    @jakarta.ws.rs.core.Context
    HttpHeaders entetes;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    PlanningExportService planningExportService;

    /** Who I am, my persisted planning (with teammates) and the colleagues I can swap with. */
    @GET
    @Path("/{jeton}")
    public Response espace(@PathParam("jeton") String jeton) {
        return avecSession(jeton, animateurId -> Response.ok(
                espaceAnimateurService.construireVue(animateurId)).build());
    }

    /** My demandes d'échange, most recent first, whatever their statut. */
    @GET
    @Path("/{jeton}/demandes")
    public Response demandes(@PathParam("jeton") String jeton) {
        return avecSession(jeton, animateurId -> Response.ok(
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
        return avecSession(jeton, animateurId -> {
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

    /**
     * My planning as a PDF — same document as the admin's individual export,
     * downloadable by the animateur themself. Stays available when the foire
     * is closed: closing only stops the échanges, never the consultation.
     */
    @GET
    @Path("/{jeton}/planning.pdf")
    @Produces("application/pdf")
    public Response planningPdf(@PathParam("jeton") String jeton) {
        return avecSession(jeton, animateurId -> {
            PlanningFestival planning = persistenceService.loadPersistedPlanning();
            byte[] contenu = planningExportService.exportAnimateurPdf(planning, animateurId);
            return Response.ok(contenu)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + nomFichier(planning, animateurId, "pdf") + "\"")
                    .build();
        });
    }

    /** My planning as an ICS calendar, importable in any agenda app. */
    @GET
    @Path("/{jeton}/planning.ics")
    @Produces("text/calendar")
    public Response planningIcs(@PathParam("jeton") String jeton) {
        return avecSession(jeton, animateurId -> {
            PlanningFestival planning = persistenceService.loadPersistedPlanning();
            String contenu = planningExportService.exportAnimateurIcs(planning, animateurId);
            return Response.ok(contenu)
                    .type("text/calendar; charset=utf-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + nomFichier(planning, animateurId, "ics") + "\"")
                    .build();
        });
    }

    /** Same readable convention as the admin exports: {@code planning-Prenom-Nom.pdf}. */
    private String nomFichier(PlanningFestival planning, String animateurId, String extension) {
        String nom = planningExportService.resolveAnimateurName(planning, animateurId);
        String safe = (nom == null ? animateurId : nom)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        return "planning-" + (safe.isEmpty() ? "animateur" : safe) + "." + extension;
    }

    /** Withdraws one of my own, still-pending demandes. */
    @POST
    @Path("/{jeton}/demandes/{demandeId}/annulation")
    public Response annuler(@PathParam("jeton") String jeton, @PathParam("demandeId") String demandeId) {
        return avecSession(jeton, animateurId -> {
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
     * Sends a fresh access code to the animateur's e-mail address. The one
     * espace call (with the session opener below) that only needs the token:
     * it is how a session gets bootstrapped.
     */
    @POST
    @Path("/{jeton}/code")
    public Response demanderCode(@PathParam("jeton") String jeton) {
        return avecJeton(jeton, animateurId -> {
            try {
                return Response.ok(espaceAccesService.demanderCode(animateurId)).build();
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                        .build();
            } catch (RuntimeException e) {
                io.quarkus.logging.Log.errorf(e, "Failed to mail an espace access code");
                return Response.serverError()
                        .entity(new ReferenceDataResource.ErreurValidation(
                                "L'envoi du code a échoué : réessayez dans quelques instants."))
                        .build();
            }
        });
    }

    /** Exchanges a valid code for the durable session cookie. */
    @POST
    @Path("/{jeton}/session")
    public Response ouvrirSession(@PathParam("jeton") String jeton, CodeSession codeSession) {
        return avecJeton(jeton, animateurId -> {
            try {
                String session = espaceAccesService.ouvrirSession(animateurId,
                        codeSession == null ? null : codeSession.code());
                return Response.noContent()
                        .header("Set-Cookie", COOKIE_SESSION + "=" + session
                                + "; Path=/api/espace-animateur; HttpOnly; SameSite=Strict; Max-Age="
                                + dev.sylvain.planning.service.EspaceAccesService.VALIDITE_SESSION
                                        .toSeconds())
                        .build();
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                        .build();
            }
        });
    }

    /** Body of the session opener: the code received by e-mail. */
    public record CodeSession(String code) {
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

    /**
     * Same as {@link #avecJeton}, plus the session requirement: the
     * {@code planning-espace} cookie must carry a live session of the animateur
     * the token resolves to. 401 otherwise — the interface then offers the
     * code screen. Checked inside the edition context, like everything else.
     */
    private Response avecSession(String jeton, Function<String, Response> action) {
        return avecJeton(jeton, animateurId -> {
            jakarta.ws.rs.core.Cookie cookie = entetes.getCookies().get(COOKIE_SESSION);
            if (!espaceAccesService.sessionValide(cookie == null ? null : cookie.getValue(), animateurId)) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new ReferenceDataResource.ErreurValidation(
                                "Authentification requise : demandez un code d'accès par e-mail."))
                        .build();
            }
            return action.apply(animateurId);
        });
    }
}
