package dev.sylvain.planning.api;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.solve.PlanningWhatIf.EchangeSimulation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Admin side of the foire au planning (issue #165): review the demandes
 * d'échange, measure their impact against the current persisted planning, and
 * decide. Accepting applies the swap exactly as simulated and pins it
 * (ANIMATEUR_CRENEAU locks); refusing changes nothing. No demande is ever
 * applied without one of these explicit calls.
 */
@Path("/echanges")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DemandeEchangeResource {

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    /** Every demande of the current edition, most recent first, all statuts. */
    @GET
    public List<DemandeEchangeView> list() {
        return espaceAnimateurService.toViews(demandeEchangeService.list());
    }

    /** The foire window: the switch, its optional bounds, and whether it is open today. */
    @GET
    @Path("/configuration")
    public ConfigurationFoire configuration() {
        return configurationView();
    }

    /**
     * Opens or closes the foire for the current edition, and bounds it with the
     * optional dates. Closing turns the espaces animateurs read-only:
     * submissions and withdrawals are refused server-side, the planning stays
     * consultable (and downloadable). The dates are enforced the same way — a
     * bound checked only at display time would not be a bound.
     *
     * <p>An end preceding the start is a {@code BusinessError.Invalid} raised
     * by the service before it writes, so nothing is stored: no
     * {@code try/catch} here.</p>
     */
    @PUT
    @Path("/configuration")
    public ConfigurationFoire configure(ConfigurationFoire configuration) {
        demandeEchangeService.openFoire(configuration == null
                ? DemandeEchangeService.FenetreFoire.unbounded()
                : new DemandeEchangeService.FenetreFoire(
                        configuration.foireOuverte(), configuration.debut(), configuration.fin()));
        return configurationView();
    }

    private ConfigurationFoire configurationView() {
        DemandeEchangeService.FenetreFoire fenetre = demandeEchangeService.fenetre();
        return new ConfigurationFoire(fenetre.ouverte(), fenetre.debut(), fenetre.fin(),
                demandeEchangeService.isFoireOpen());
    }

    /**
     * The foire window as the admin sets it.
     *
     * @param foireOuverte the switch, and the master: a dated window that is
     *                     switched off accepts nothing
     * @param ouverteAujourdhui read-only — the switch AND today's date against
     *                     the bounds. Kept apart from {@code foireOuverte} so
     *                     the screen can say « ouverte à partir du… » rather
     *                     than showing a switch that reads « on » while nothing
     *                     is accepted
     */
    @Schema(requiredProperties = {"foireOuverte"})
    public record ConfigurationFoire(boolean foireOuverte, LocalDate debut, LocalDate fin,
            boolean ouverteAujourdhui) {
    }

    /**
     * Fresh impact of one demande against the current persisted planning:
     * score delta and the hard constraints the échange would newly break.
     * Recomputed on demand — the stored prevalidation only reflects the
     * planning at submission time.
     */
    @GET
    @Path("/{id}/impact")
    public Response impact(@PathParam("id") String id) {
        EchangeSimulation simulation = demandeEchangeService.impact(id);
        return Response.ok(simulation).build();
    }

    @POST
    @Path("/{id}/acceptation")
    public Response accept(@PathParam("id") String id, DecisionEchange decision) {
        return Response.ok(view(demandeEchangeService.accept(id,
                decision == null ? null : decision.commentaire()))).build();
    }

    @POST
    @Path("/{id}/refus")
    public Response refuse(@PathParam("id") String id, DecisionEchange decision) {
        return Response.ok(view(demandeEchangeService.refuse(id,
                decision == null ? null : decision.commentaire()))).build();
    }

    private DemandeEchangeView view(DemandeEchange demande) {
        return espaceAnimateurService.toViews(List.of(demande)).get(0);
    }

    /** Optional admin comment carried by a decision (the reason of a refusal, typically). */
    public record DecisionEchange(String commentaire) {
    }
}
