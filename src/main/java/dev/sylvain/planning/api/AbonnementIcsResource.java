package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.export.PlanningExportService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * The permanent calendar subscription of one animateur: the URL a calendar
 * client is given once and re-fetches on its own, for ever.
 *
 * <p><b>Why a path of its own.</b> Every espace-animateur route requires the
 * e-mail-code session on top of the URL token, which no calendar client can
 * carry — it holds no cookie and cannot answer a challenge. This feed is
 * therefore the one thing a URL alone opens, and it lives under its own
 * prefix so an access proxy can except exactly it from the admin
 * authentication, with a pattern that names nothing else. Widening
 * {@code /api/espace-animateur/*} instead would have turned every espace route
 * into a link-only one.</p>
 *
 * <p><b>What the token opens, exactly.</b> One document: the holder's own
 * published planning — their shifts, their stands, and the teammates already
 * printed on the PDF that carries the link. Nothing else. It reads no swap
 * request, no declaration, no colleague, and it writes nothing at all. It is
 * a second credential, stored beside the espace one and rotated separately,
 * so it can be revoked without invalidating the espace link printed on a
 * PDF.</p>
 *
 * <p><b>Nothing published yet answers an empty calendar, not a 404.</b> A
 * subscription is re-fetched unattended: clients react to a repeated 404 by
 * disabling the feed or by alerting their owner, and an animateur who
 * subscribed before the first publication would then have to subscribe again
 * without ever being told. An empty {@code VCALENDAR} is a valid answer to
 * "what is planned for me": nothing, yet.</p>
 */
@Path("/abonnements")
public class AbonnementIcsResource {

    @Inject
    EditionRequestScope editionRequestScope;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PlanningExportService planningExportService;

    /**
     * The published planning of the token's owner, rebuilt on every call — a
     * republication is visible at the client's next sync, with nothing to
     * resubscribe to.
     *
     * <p>{@code no-cache} is not decoration: the document is nominative, and
     * the answer must never be served from a shared cache nor replayed from a
     * private one after the admin has published again.</p>
     */
    @GET
    @Path("/{token}/planning.ics")
    @AbonnementTokenRequired
    @Produces("text/calendar")
    public Response planningIcs() {
        String animateurId = editionRequestScope.getTokenOwner().animateurId();
        PlanningEvenement planning = planPublieService.planPublie();
        String contenu = planningExportService.exportAnimateurIcs(planning, animateurId);
        return Response.ok(contenu)
                .type("text/calendar; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\""
                                + PlanningExportService.planningFileName(
                                        PlanningExportService.resolveAnimateurName(planning, animateurId), "ics")
                                + "\"")
                .build();
    }
}
