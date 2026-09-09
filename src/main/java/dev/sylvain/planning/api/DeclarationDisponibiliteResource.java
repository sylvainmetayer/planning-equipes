package dev.sylvain.planning.api;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.service.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.DeclarationDisponibiliteService.InvitationReport;
import dev.sylvain.planning.service.EspaceAnimateurService;
import dev.sylvain.planning.service.EspaceAnimateurService.DeclarationAdminView;
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
 * Admin side of the self-service declarations (issue #291): open or close the
 * collection window, read what the animateurs proposed, and decide.
 *
 * <p>The decision is <b>all or nothing</b>: applying writes the whole proposal
 * onto the fiche through the very code path the CRUD screen uses, refusing
 * touches nothing. There is deliberately no per-line acceptance — a
 * disagreement is settled outside the application, and the animateur sends a
 * corrected version while the window is open.</p>
 */
@Path("/disponibilites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeclarationDisponibiliteResource {

    @Inject
    DeclarationDisponibiliteService declarationService;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    /** Every declaration of the current edition, most recent first, all statuts. */
    @GET
    public List<DeclarationAdminView> list() {
        return espaceAnimateurService.toDeclarationViews(declarationService.list());
    }

    /**
     * The collection window as configured. Closed while nobody has decided
     * anything — a route that writes from the public Internet does not open by
     * omission.
     */
    @GET
    @Path("/configuration")
    public ConfigurationCollecte configuration() {
        return ConfigurationCollecte.of(declarationService.fenetre(), null);
    }

    /**
     * Opens or closes the window, and — only when {@code prevenirAnimateurs}
     * is ticked — mails every animateur the link to their own espace.
     *
     * <p>That tick is a decision per opening, not a setting: the invitation is
     * indispensable on the first round and merely tiresome when the window is
     * reopened after a correction. Nobody is invited to a window that is being
     * closed.</p>
     *
     * <p>Both halves are one service call, deliberately. Sequenced here, the
     * window got saved and the invitation then refused the whole request: the
     * admin read an error on a collection that was already open. Ordering
     * "everything refusable first, then the write" is a business rule, and it
     * belongs where the business rules are.</p>
     */
    @PUT
    @Path("/configuration")
    public ConfigurationCollecte configure(ConfigurationCollecte configuration) {
        ConfigurationCollecte demandee = configuration == null
                ? new ConfigurationCollecte(false, null, null, false, null)
                : configuration;
        DeclarationDisponibiliteService.ConfigurationAppliquee appliquee = declarationService.configure(
                new FenetreCollecte(demandee.collecteOuverte(), demandee.debut(), demandee.fin()),
                demandee.prevenirAnimateurs());
        return ConfigurationCollecte.of(appliquee.fenetre(), appliquee.invitation());
    }

    /**
     * The collection window, plus the one-shot decision that rides with a
     * change of it.
     *
     * @param prevenirAnimateurs request only: send the invitation mails now.
     *                           Never echoed back — it is an action, not a
     *                           stored setting
     * @param invitation         response only: who was reached, who has no
     *                           address, whose send failed; {@code null} when
     *                           no invitation was asked for
     */
    @Schema(requiredProperties = {"collecteOuverte", "prevenirAnimateurs"})
    public record ConfigurationCollecte(boolean collecteOuverte, LocalDate debut, LocalDate fin,
            boolean prevenirAnimateurs, InvitationReport invitation) {

        static ConfigurationCollecte of(FenetreCollecte fenetre, InvitationReport invitation) {
            return new ConfigurationCollecte(fenetre.ouverte(), fenetre.debut(), fenetre.fin(),
                    false, invitation);
        }
    }

    /**
     * Applies one pending declaration to the animateur's fiche, whole. Writes
     * through the referential service, so the reference data is marked as
     * modified and the staleness indicator fires — an applied declaration
     * really did move the solver's input.
     */
    @POST
    @Path("/{id}/application")
    public Response apply(@PathParam("id") String id) {
        return Response.ok(view(declarationService.apply(id))).build();
    }

    /** Refuses one pending declaration; the referential is untouched. */
    @POST
    @Path("/{id}/refus")
    public Response refuse(@PathParam("id") String id, Decision decision) {
        return Response.ok(view(declarationService.refuse(id,
                decision == null ? null : decision.commentaire()))).build();
    }

    private DeclarationAdminView view(DeclarationDisponibilite declaration) {
        return espaceAnimateurService.toDeclarationViews(List.of(declaration)).get(0);
    }

    /** Optional admin comment carried by a decision — the reason of a refusal, typically. */
    public record Decision(String commentaire) {
    }
}
