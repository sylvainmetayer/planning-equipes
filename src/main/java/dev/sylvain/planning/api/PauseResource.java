package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /api/pauses}: where the legal breaks of the persisted plan fall
 * — for whom, at the latest when, on which stand, and who is there to take
 * the relay. A read-out of the plan, never a solve; an empty report rather
 * than an error when nothing is persisted yet.
 *
 * <p>Read under the organiser's <em>current</em> legal parameters and meal
 * windows rather than the ones the plan was assembled with: the question the
 * screen answers is « with what I declare today, what is there to
 * organise ».</p>
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
        return pauseAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresLegaux(),
                FenetreRepas.from(referenceDataService.getParametresDecoupage()));
    }
}
