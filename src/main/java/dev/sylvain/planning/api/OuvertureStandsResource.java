package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.referentiel.GrilleHorairesStands;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Stand × jour view of the opening schedule actually in force, for the
 * "Ouvertures des stands" screen: what a solve would see once recurring
 * horaires are expanded, dated exceptions applied and every window clamped to
 * its créneaux — plus the anomalies worth a second look.
 *
 * <p>No solve involved: it exists so an administrator can validate an opening
 * schedule <em>before</em> spending minutes on a solver run, the same way
 * {@link FeasibilityResource} does for staffing capacity — and, since the
 * same grid is also where the schedule is typed, {@link #saisir} writes it
 * back, one integer per créneau.</p>
 */
@Path("/ouvertures-stands")
@Produces(MediaType.APPLICATION_JSON)
public class OuvertureStandsResource {

    @Inject
    ReferenceDataService referenceDataService;

    /** The grid as submitted: only the stands that were edited, each with all its cells. */
    public record SaisieGrille(List<GrilleHorairesStands.SaisieStand> stands) {}

    /** One line per stand written, in the order submitted. */
    public record RapportSaisieGrille(List<GrilleHorairesStands.LigneGrille> stands) {}

    /**
     * Writes the schedules typed in the grid. Each stand's schedule is replaced
     * in full by its cells (see {@link GrilleHorairesStands}); stands absent
     * from the body are left untouched. {@code 400} on an unknown créneau or a
     * headcount below one, {@code 409} while a solve is running.
     */
    @PUT
    @Path("/grille")
    @Consumes(MediaType.APPLICATION_JSON)
    public RapportSaisieGrille saisir(SaisieGrille saisie) {
        return new RapportSaisieGrille(referenceDataService.saisirGrilleHoraires(
                saisie == null || saisie.stands() == null ? List.of() : saisie.stands()));
    }

    /**
     * Stands come <b>resolved</b> and créneaux from the <em>active</em> group
     * only — the exact pair {@code PlanningService} builds a problem from, which
     * is the whole point: the screen must show what the solver gets, not a
     * second interpretation of the same data.
     */
    @GET
    public RapportOuvertures analyze() {
        return OuvertureStandsAnalyzer.analyze(
                referenceDataService.listSolvedStands(), referenceDataService.listCreneaux());
    }
}
