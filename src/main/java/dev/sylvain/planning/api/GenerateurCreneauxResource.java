package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Demande;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.EtatGenerateur;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Generation;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Source;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The one timeslot generator (ADR 0126): its templates (« gabarits ») and
 * their calendar, and the three operations on the grid — preview, apply,
 * withdraw — from the stands' hours, the templates or a recurrence. A
 * timeslot typed by hand stays {@code /api/creneaux}.
 */
@Path("/generateur-creneaux")
@Produces(MediaType.APPLICATION_JSON)
public class GenerateurCreneauxResource {

    private final GenerateurCreneaux generateur;

    @Inject
    public GenerateurCreneauxResource(GenerateurCreneaux generateur) {
        this.generateur = generateur;
    }

    /** Templates, calendar, and the dates whose timeslots no longer match their template. */
    @GET
    public EtatGenerateur etat() {
        return generateur.etat();
    }

    @POST
    @Path("/gabarits")
    @Consumes(MediaType.APPLICATION_JSON)
    public Gabarit createGabarit(Gabarit gabarit) {
        return generateur.create(gabarit);
    }

    @PUT
    @Path("/gabarits/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Gabarit updateGabarit(@PathParam("id") long id, Gabarit gabarit) {
        return generateur.update(id, gabarit);
    }

    /** The template goes and its dates are no longer governed; the timeslots it produced stay. */
    @DELETE
    @Path("/gabarits/{id}")
    public Response deleteGabarit(@PathParam("id") long id) {
        generateur.delete(id);
        return Response.noContent().build();
    }

    /** The calendar as a whole: a date left out is no longer governed by any template. */
    @PUT
    @Path("/calendrier")
    @Consumes(MediaType.APPLICATION_JSON)
    public EtatGenerateur setCalendrier(List<Affectation> calendrier) {
        generateur.setCalendrier(calendrier);
        return generateur.etat();
    }

    /**
     * One generation as the screen sends it: the source, and the part of the
     * request that source reads — {@code recurrence} for {@code RECURRENCE},
     * {@code derivation} for {@code STANDS}; the templates need nothing.
     */
    @Schema(requiredProperties = {"source"})
    public record DemandeGeneration(
            Source source, CreneauResource.RecurrenceRequest recurrence, CreneauResource.DerivationRequest derivation) {

        Demande demande() {
            return new Demande(
                    source,
                    recurrence == null ? null : recurrence.regle(),
                    derivation == null ? null : derivation.parametres(),
                    derivation != null && derivation.remplacer());
        }
    }

    private static DemandeGeneration required(DemandeGeneration demande) {
        if (demande == null) {
            throw new BusinessError.Invalid("source est requise : GABARITS, STANDS ou RECURRENCE");
        }
        return demande;
    }

    /** What the generation would produce, and the verdict on the resulting grid — nothing written. */
    @POST
    @Path("/apercu")
    @Consumes(MediaType.APPLICATION_JSON)
    public Generation preview(DemandeGeneration demande) {
        return generateur.preview(required(demande).demande());
    }

    /**
     * Writes the generation: the templates materialise their calendar (a
     * timeslot their template does not name, on a governed date, goes with its
     * seats), the stands' grid is added or replaces the grid, a recurrence
     * adds. {@code 409} while a solve runs or the timeslots are frozen.
     */
    @POST
    @Path("/application")
    @Consumes(MediaType.APPLICATION_JSON)
    public Generation apply(DemandeGeneration demande) {
        return generateur.apply(required(demande).demande());
    }

    /** The dates the generator should stop governing. */
    @Schema(requiredProperties = {"dates"})
    public record RetraitDates(Set<LocalDate> dates) {}

    /** Withdraws dates from the calendar: no template governs them any more, their timeslots stay. */
    @POST
    @Path("/retrait")
    @Consumes(MediaType.APPLICATION_JSON)
    public EtatGenerateur withdraw(RetraitDates retrait) {
        return generateur.withdraw(retrait == null ? Set.of() : retrait.dates());
    }

    /** The templates the current grid implies — nothing written. */
    @POST
    @Path("/reconnaissance/apercu")
    public Reconnaissance previewReconnaissance() {
        return generateur.previewReconnaissance();
    }

    /** Replaces every template and the whole calendar by what the grid implies; the timeslots are untouched. */
    @POST
    @Path("/reconnaissance")
    public Reconnaissance reconnaitre() {
        return generateur.reconnaitre();
    }
}
