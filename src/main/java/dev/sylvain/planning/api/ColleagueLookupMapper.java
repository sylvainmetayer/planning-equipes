package dev.sylvain.planning.api;

import dev.sylvain.planning.service.espace.EspaceAnimateurService.TooManyColleagueLookups;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Too many distinct colleagues looked up in the window (see
 * {@code ColleagueLookupLimiter}): {@code 429}, with the delay left as
 * {@code Retry-After} and a sentence the espace shows as it is.
 *
 * <p>A mapper rather than a {@code try/catch} in the resource, so the route
 * keeps declaring the list it answers with — which is what the published
 * OpenAPI and {@code JsonContractTest} read.</p>
 */
@Provider
public class ColleagueLookupMapper implements ExceptionMapper<TooManyColleagueLookups> {

    @Override
    public Response toResponse(TooManyColleagueLookups refus) {
        return Response.status(429)
                .header(HttpHeaders.RETRY_AFTER, refus.secondsBeforeNextTry())
                .entity(new ValidationError(refus.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
