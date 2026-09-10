package dev.sylvain.planning.api;

import dev.sylvain.planning.service.solve.SolverJobService.SolverBusyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A solve was asked for — or the referential was written — while another solve
 * holds the solver: {@code 409}, with the job that holds the lock as the body,
 * since the client needs to know <i>which</i> run it is waiting on, not just
 * that it lost.
 *
 * <p>Six resource methods used to catch this and call the same private
 * {@code busy(e)} helper. The status and the body belong to the refusal, not
 * to whichever endpoint happened to trigger it.</p>
 *
 * <p>The body also carries a {@code message}, like every other refusal in this
 * API ({@link BusinessErrorMapper} and its {@link ValidationError}): the job
 * fields alone say nothing to the frontend, whose {@code toError} reads
 * {@code body.message} and falls back to "Échec de la requête (code 409)"
 * otherwise — a string it then repeats once per row in a bulk delete. Naming
 * the edition and what to do about it is what makes the refusal actionable,
 * which is the whole point of answering with the job rather than an empty
 * 409.</p>
 */
@Provider
public class SolverOccupeMapper implements ExceptionMapper<SolverBusyException> {

    @Override
    public Response toResponse(SolverBusyException occupe) {
        return Response.status(Response.Status.CONFLICT)
                .entity(SolverJobResource.JobView.conflit(occupe.getActiveJob(), message(occupe)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** French, like every user-facing message this API returns: the frontend shows it as-is. */
    private static String message(SolverBusyException occupe) {
        var job = occupe.getActiveJob();
        String edition = job.getEditionNom() == null || job.getEditionNom().isBlank()
                ? job.getEditionId()
                : job.getEditionNom();
        return "Une résolution est en cours sur l'édition « " + edition
                + " » : attendez qu'elle se termine, ou arrêtez-la depuis l'écran Solveur.";
    }
}
