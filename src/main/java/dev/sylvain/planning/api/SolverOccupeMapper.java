package dev.sylvain.planning.api;

import dev.sylvain.planning.service.SolverJobService.SolverBusyException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A solve was asked for while another is running: {@code 409}, with the job
 * that holds the lock as the body — the client needs to know <i>which</i> run
 * it is waiting on, not just that it lost.
 *
 * <p>Six resource methods used to catch this and call the same private
 * {@code busy(e)} helper. The status and the body belong to the refusal, not
 * to whichever endpoint happened to trigger it.</p>
 */
@Provider
public class SolverOccupeMapper implements ExceptionMapper<SolverBusyException> {

    @Override
    public Response toResponse(SolverBusyException occupe) {
        return Response.status(Response.Status.CONFLICT)
                .entity(SolverJobResource.JobView.withoutResult(occupe.getActiveJob()))
                .build();
    }
}
