package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.consigne.ConsigneService.ApercuJour;
import dev.sylvain.planning.service.consigne.ConsigneService.ApercuLevee;
import dev.sylvain.planning.service.consigne.ConsigneService.Demande;
import dev.sylvain.planning.service.consigne.ConsigneService.EtatConsignes;
import dev.sylvain.planning.service.consigne.ConsigneService.Preselection;
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
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The edition's consignes (issue #4): a band every stand is shut on for one
 * date, the stands reopened in compensation, and the presets they are made
 * from.
 *
 * <p>Every write is preceded by a preview the screen shows first, and refused
 * on a date already begun: what was worked stays as it was worked.</p>
 */
@Path("/consignes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConsigneResource {

    @Inject
    ConsigneService consignes;

    /** The consignes, the presets and today's date, in one read. */
    @GET
    public EtatConsignes etat() {
        return consignes.etat();
    }

    /** A date and a band, to read the stands against. */
    @Schema(requiredProperties = {"date", "fermetureDebut"})
    public record DemandePreselection(LocalDate date, LocalTime fermetureDebut, LocalTime fermetureFin) {}

    /** What the band takes from each stand of one date, and which are proposed ticked. Writes nothing. */
    @POST
    @Path("/preselection")
    public Preselection preselection(DemandePreselection demande) {
        if (demande == null) {
            throw new BusinessError.Invalid("Demande manquante");
        }
        return consignes.preselection(demande.date(), demande.fermetureDebut(), demande.fermetureFin());
    }

    /** What laying the consigne down would do, date by date. Writes nothing. */
    @POST
    @Path("/apercu")
    public List<ApercuJour> apercu(Demande demande) {
        return consignes.apercu(requiredDemande(demande));
    }

    /**
     * Lays the consigne down on every date of the request — replacing the one
     * a date already carries — and returns the preview's figures.
     */
    @POST
    public List<ApercuJour> poser(Demande demande) {
        return consignes.poser(requiredDemande(demande));
    }

    /** The dates to lift. */
    @Schema(requiredProperties = {"dates"})
    public record Levee(List<LocalDate> dates) {}

    /** What lifting would do. Writes nothing. */
    @POST
    @Path("/levee/apercu")
    public List<ApercuLevee> apercuLevee(Levee levee) {
        return consignes.apercuLevee(requiredDates(levee));
    }

    /** Lifts the consigne of the given dates — days to come only. */
    @POST
    @Path("/levee")
    public Response lever(Levee levee) {
        consignes.lever(requiredDates(levee));
        return Response.noContent().build();
    }

    /* -------------------------------- presets -------------------------------- */

    @GET
    @Path("/prereglages")
    public List<PrereglageConsigne> prereglages() {
        return consignes.listPrereglages();
    }

    /** Creates a preset; an id given is kept, a missing one is minted. */
    @POST
    @Path("/prereglages")
    public PrereglageConsigne createPrereglage(PrereglageConsigne prereglage) {
        return consignes.savePrereglage(prereglage);
    }

    /** Replaces a preset. */
    @PUT
    @Path("/prereglages/{id}")
    public PrereglageConsigne updatePrereglage(@PathParam("id") String id, PrereglageConsigne prereglage) {
        if (prereglage == null) {
            throw new BusinessError.Invalid("Préréglage manquant");
        }
        return consignes.savePrereglage(new PrereglageConsigne(
                id,
                prereglage.nom(),
                prereglage.fermetureDebut(),
                prereglage.fermetureFin(),
                prereglage.motif(),
                prereglage.fenetres(),
                null));
    }

    @DELETE
    @Path("/prereglages/{id}")
    public Response deletePrereglage(@PathParam("id") String id) {
        consignes.deletePrereglage(id);
        return Response.noContent().build();
    }

    private static Demande requiredDemande(Demande demande) {
        if (demande == null) {
            throw new BusinessError.Invalid("Demande manquante");
        }
        return demande;
    }

    private static List<LocalDate> requiredDates(Levee levee) {
        if (levee == null || levee.dates() == null || levee.dates().isEmpty()) {
            throw new BusinessError.Invalid("Aucune date à lever");
        }
        return levee.dates();
    }
}
