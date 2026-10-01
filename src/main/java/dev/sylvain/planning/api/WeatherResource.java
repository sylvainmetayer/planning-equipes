package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ParametresMeteo;
import dev.sylvain.planning.service.weather.WeatherAlertService;
import dev.sylvain.planning.service.weather.WeatherAlertService.WeatherSettingsView;
import dev.sylvain.planning.service.weather.WeatherAlertService.WeatherTestReport;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The weather alert of the edition (ADR 0074), as Paramètres › Édition sets
 * and tries it. Nothing here touches a consigne: the alert suggests, the
 * organiser applies.
 */
@Path("/parametres/meteo")
@Produces(MediaType.APPLICATION_JSON)
public class WeatherResource {

    private final WeatherAlertService weatherService;

    @Inject
    public WeatherResource(WeatherAlertService weatherService) {
        this.weatherService = weatherService;
    }

    /** The settings, what the last run saw, and whether anything may leave at all. */
    @GET
    public WeatherSettingsView get() {
        return weatherService.view();
    }

    /** Saves the settings; {@code modifieLe} is the precondition, {@code 409} when it no longer holds. */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public WeatherSettingsView update(ParametresMeteo parametres) {
        return weatherService.save(parametres);
    }

    /** Queries now and sums the forecast up per date; raises no alert, writes nothing. */
    @POST
    @Path("/test")
    public WeatherTestReport test() {
        return weatherService.test();
    }
}
