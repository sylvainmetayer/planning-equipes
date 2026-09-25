package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.MargeAnalyzer.Mode;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.MargeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Day × timeslot margin — available animateurs minus seats to staff — for the
 * « Marge disponible » screen.
 *
 * <p>Read-only and <b>no solve involved</b>, like {@link StaffingResource} and
 * {@link FragiliteResource}: the « avant » mode reads the seats a solve would
 * have to fill, the « après » one the plan already persisted.</p>
 *
 * <p>Deliberately a route of its own rather than a section of
 * {@code /staffing}: that one answers « combien faut-il recruter » over the
 * whole event, where this one answers « à quel moment sommes-nous justes ».
 * Same seats, two different questions — and two payloads nothing would read
 * together.</p>
 */
@Path("/marge")
@Produces(MediaType.APPLICATION_JSON)
public class MargeResource {

    private final MargeService margeService;

    @Inject
    public MargeResource(MargeService margeService) {
        this.margeService = margeService;
    }

    /**
     * @param mode {@code avant} (the default) or {@code apres}; an unknown
     *             value falls back to {@code avant} rather than refusing the
     *             read, since the screen it feeds is opened from a link
     */
    @GET
    public RapportMarge analyze(@QueryParam("mode") @DefaultValue("avant") String mode) {
        return margeService.analyzeEdition("apres".equalsIgnoreCase(mode) ? Mode.APRES : Mode.AVANT);
    }
}
