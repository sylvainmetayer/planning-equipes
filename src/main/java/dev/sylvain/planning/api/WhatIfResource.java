package dev.sylvain.planning.api;

import dev.sylvain.planning.service.SolverJobService;
import dev.sylvain.planning.service.SolverJobService.SolverBusyException;
import dev.sylvain.planning.service.SolverJobService.SolverJob;
import dev.sylvain.planning.service.WhatIfService;
import dev.sylvain.planning.service.WhatIfService.Mutations;
import dev.sylvain.planning.service.WhatIfService.ResultatWhatIf;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * "What if?" simulation (issue #73), in two levels:
 *
 * <ul>
 *   <li>{@code POST /api/what-if} — instant, no solve: the capacity check of
 *       {@code FeasibilityAnalyzer} on the variant, next to the same check on
 *       today's referential so the caller reads a delta;</li>
 *   <li>{@code POST /api/what-if/analyze} — explicitly requested short solve,
 *       for a comparable score. Bounded by {@code ?seconds=}, and subject to
 *       the same one-solver-at-a-time lock as a real run.</li>
 * </ul>
 *
 * <p>Neither writes anything: the variant lives in the request.</p>
 */
@Path("/what-if")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WhatIfResource {

    /**
     * Default duration of the short solve. Deliberately far below a real run
     * (600 s on the 2026 data): the point is a comparable order of magnitude in
     * a time the user will actually wait, not the best plan.
     */
    private static final long SECONDES_PAR_DEFAUT = 30;

    @Inject
    WhatIfService whatIfService;

    @Inject
    SolverJobService solverJobService;

    @POST
    public ResultatWhatIf simulate(Mutations mutations) {
        return whatIfService.simulate(mutations == null ? new Mutations(0, null, null, null) : mutations);
    }

    /**
     * Launches an ANALYZE job on the variant. The job carries a score for the
     * simulated data and <b>never persists a plan</b> — analysis never does,
     * unlike a solve.
     */
    @POST
    @Path("/analyze")
    public Response analyze(Mutations mutations, @QueryParam("seconds") Long secondsLimit) {
        Mutations sures = mutations == null ? new Mutations(0, null, null, null) : mutations;
        long secondes = secondsLimit == null || secondsLimit <= 0 ? SECONDES_PAR_DEFAUT : secondsLimit;
        try {
            SolverJob job = solverJobService.submitAnalyze(whatIfService.buildProblem(sures), secondes);
            return Response.accepted(SolverJobResource.JobView.withoutResult(job)).build();
        } catch (SolverBusyException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(SolverJobResource.JobView.withoutResult(e.getActiveJob()))
                    .build();
        }
    }
}
