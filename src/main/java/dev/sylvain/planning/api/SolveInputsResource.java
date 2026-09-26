package dev.sylvain.planning.api;

import dev.sylvain.planning.service.solve.SolveInputsService;
import dev.sylvain.planning.service.solve.SolveInputsService.SolveInputs;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * « Ce calcul tiendra compte de » (issues #704, #719): the inputs of the next
 * solve of the current edition, counted in one read for the Solveur page.
 * Read-only; each figure is a link, on screen, to the screen it counts.
 */
@Path("/solve/entrees")
@Produces(MediaType.APPLICATION_JSON)
public class SolveInputsResource {

    private final SolveInputsService inputs;

    @Inject
    public SolveInputsResource(SolveInputsService inputs) {
        this.inputs = inputs;
    }

    @GET
    public SolveInputs current() {
        return inputs.current();
    }
}
