package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.TypologieAnalyzer;
import dev.sylvain.planning.service.analyse.TypologieAnalyzer.RapportTypologies;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /api/planning/typologies}: the persisted plan read by typologie of
 * jeu — who actually holds each game, for how many seats and how many hours,
 * and how that compares with who the referential vets on it (issue #590).
 *
 * <p>A read-out, never a solve. An edition with nothing persisted answers every
 * typologie at zero rather than an error: « nobody holds the ambiance games »
 * is an answer, and one this view exists to give.</p>
 */
@Path("/planning/typologies")
public class TypologieAnalyseResource {

    @Inject
    TypologieAnalyzer typologieAnalyzer;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RapportTypologies rapport() {
        return typologieAnalyzer.rapport();
    }
}
