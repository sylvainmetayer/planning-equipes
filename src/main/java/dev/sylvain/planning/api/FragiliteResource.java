package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.FragiliteAnalyzer;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Single points of failure of the plan currently persisted: who is
 * irreplaceable, and which stand × timeslot rests on one competent person —
 * what the « Fragilité du planning » screen displays.
 *
 * <p>Read-only and <b>no solve involved</b>: the answer comes from the seats
 * already persisted and from the competence referential, like
 * {@link FeasibilityResource} and {@link StaffingResource} do for capacity.
 * Nothing here re-solves a plan to throw it away.</p>
 *
 * <p>Deliberately a route of its own rather than a section of {@code /staffing}
 * or {@code /problemes}: those two answer « combien faut-il recruter » and
 * « pourquoi ce planning ne tient pas », where this one answers « qui est
 * irremplaçable ». Same data, three different questions.</p>
 */
@Path("/fragilite")
@Produces(MediaType.APPLICATION_JSON)
public class FragiliteResource {

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    FragiliteAnalyzer fragiliteAnalyzer;

    /**
     * Returns an empty report rather than an error when nothing is persisted
     * yet: this only feeds a read-only screen, which a user opens precisely
     * before the first solve.
     */
    @GET
    public RapportFragilite analyze() {
        return fragiliteAnalyzer.analyze(persistenceService.loadPersistedPlanning());
    }
}
