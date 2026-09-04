package dev.sylvain.planning.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import dev.sylvain.planning.service.PauseAnalyzer;
import dev.sylvain.planning.service.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.ReferenceDataService;

/**
 * {@code GET /api/pauses}: where the legal breaks of the persisted plan fall
 * — for whom, at the latest when, on which stand, and who is there to take
 * the relay. A read-out of the plan, never a solve; an empty report rather
 * than an error when nothing is persisted yet.
 *
 * <p>Read under the organiser's <em>current</em> legal parameters rather than
 * the ones the plan was assembled with: the question the screen answers is
 * « with what I declare today, what is there to organise ».</p>
 */
@Path("/pauses")
@Produces(MediaType.APPLICATION_JSON)
public class PauseResource {

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PauseAnalyzer pauseAnalyzer;

    @GET
    public RapportPauses analyze() {
        return pauseAnalyzer.analyze(persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresLegaux());
    }
}
