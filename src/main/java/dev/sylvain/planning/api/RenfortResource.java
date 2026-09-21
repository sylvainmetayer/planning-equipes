package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.RenfortAnalyzer.RapportRenforts;
import dev.sylvain.planning.service.analyse.RenfortService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Where the bonus hours are — the renforts a stand declares above what its
 * windows ask for — for the « Renforts » screen (issue #505, ADR 0046).
 *
 * <p>Read-only and <b>no solve involved</b>, like {@link MargeResource} and
 * {@link StaffingResource}: the capacity comes from the seats a solve would
 * build, the hours actually taken from the plan already persisted.</p>
 *
 * <p>A route of its own rather than a section of {@code /staffing} or of
 * {@code /ouvertures}: those two answer « combien faut-il recruter » and
 * « quand chaque stand ouvre-t-il », where this one answers « si le budget se
 * réduit, où couper et qu'est-ce que couper fait gagner ». Same seats, a
 * different question, and a payload nothing would read together.</p>
 */
@Path("/renforts")
@Produces(MediaType.APPLICATION_JSON)
public class RenfortResource {

    @Inject
    RenfortService renfortService;

    @GET
    public RapportRenforts analyze() {
        return renfortService.analyzeEdition();
    }
}
