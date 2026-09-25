package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.JoursFeries.PublicHoliday;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.JoursFeriesService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The French public holidays of a range of dates, named — for the screens
 * where a date is typed, so that opening a stand on 14 July is a choice and
 * not an oversight. Not partitioned by edition (a calendar fact), but behind
 * the admin login like the rest of {@code /api}.
 */
@Path("/jours-feries")
@Produces(MediaType.APPLICATION_JSON)
public class JoursFeriesResource {

    private final JoursFeriesService joursFeries;

    @Inject
    public JoursFeriesResource(JoursFeriesService joursFeries) {
        this.joursFeries = joursFeries;
    }

    /** The holidays from {@code debut} to {@code fin}, both included, in calendar order. */
    @GET
    public List<PublicHoliday> list(@QueryParam("debut") String debut, @QueryParam("fin") String fin) {
        return joursFeries.between(date("debut", debut), date("fin", fin));
    }

    static LocalDate date(String nom, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(valeur);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid(
                    "Paramètre « " + nom + " » illisible : " + valeur + " (attendu AAAA-MM-JJ)");
        }
    }
}
