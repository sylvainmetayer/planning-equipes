package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Demande;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.EtatGenerateur;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.RapportApplication;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
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
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The 3.x routes of the day templates (ADR 0032), kept until 5.0 under their
 * old paths and their old payloads: the templates are the generator's
 * « gabarits » since 4.0 ({@link GenerateurCreneauxResource}, ADR 0126), and
 * every route here delegates to it. Only the CSV import of the templates is not
 * an alias — it lives here until the CSV imports go.
 */
@Path("/journees-types")
@Produces(MediaType.APPLICATION_JSON)
public class JourneeTypeResource {

    private final GenerateurCreneaux generateur;

    private final ReferenceDataService referenceDataService;

    @Inject
    public JourneeTypeResource(GenerateurCreneaux generateur, ReferenceDataService referenceDataService) {
        this.generateur = generateur;
        this.referenceDataService = referenceDataService;
    }

    /** One date of the calendar, as 3.x named it. */
    public record AffectationJourneeType(LocalDate date, Long journeeTypeId) {

        static List<AffectationJourneeType> of(List<Affectation> calendrier) {
            return calendrier.stream()
                    .map(affectation -> new AffectationJourneeType(affectation.date(), affectation.gabaritId()))
                    .toList();
        }
    }

    /** The generator's state under its 3.x keys. */
    @Schema(requiredProperties = {"journeesTypes", "calendrier", "datesEnEcart", "datesSousConsigne"})
    public record EtatJourneesTypes(
            List<Gabarit> journeesTypes,
            List<AffectationJourneeType> calendrier,
            List<LocalDate> datesEnEcart,
            List<LocalDate> datesSousConsigne) {

        static EtatJourneesTypes of(EtatGenerateur etat) {
            return new EtatJourneesTypes(
                    etat.gabarits(),
                    AffectationJourneeType.of(etat.calendrier()),
                    etat.datesEnEcart(),
                    etat.datesSousConsigne());
        }
    }

    /** A recognition under its 3.x keys. */
    public record ReconnaissanceJourneesTypes(List<Gabarit> journeesTypes, List<AffectationJourneeType> calendrier) {

        static ReconnaissanceJourneesTypes of(Reconnaissance reconnaissance) {
            return new ReconnaissanceJourneesTypes(
                    reconnaissance.gabarits(), AffectationJourneeType.of(reconnaissance.calendrier()));
        }
    }

    /** Templates, calendar, and the dates whose créneaux no longer match their template. */
    @GET
    @Deprecated
    public EtatJourneesTypes etat() {
        return EtatJourneesTypes.of(generateur.etat());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Deprecated
    public Gabarit create(Gabarit journeeType) {
        return generateur.create(journeeType);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Deprecated
    public Gabarit update(@PathParam("id") long id, Gabarit journeeType) {
        return generateur.update(id, journeeType);
    }

    /** The template goes and its dates are no longer governed; the créneaux it produced stay. */
    @DELETE
    @Path("/{id}")
    @Deprecated
    public Response delete(@PathParam("id") long id) {
        generateur.delete(id);
        return Response.noContent().build();
    }

    /** The calendar as a whole: a date left out is no longer governed by any template. */
    @PUT
    @Path("/calendrier")
    @Consumes(MediaType.APPLICATION_JSON)
    @Deprecated
    public EtatJourneesTypes setCalendrier(List<AffectationJourneeType> calendrier) {
        generateur.setCalendrier(calendrier.stream()
                .map(affectation -> new Affectation(affectation.date(), affectation.journeeTypeId()))
                .toList());
        return EtatJourneesTypes.of(generateur.etat());
    }

    /** What applying the calendar would change, créneau by créneau, and the verdict on the result — nothing written. */
    @POST
    @Path("/application/apercu")
    @Deprecated
    public RapportApplication previewApplication() {
        return generateur.preview(Demande.gabarits()).application();
    }

    /**
     * Materialises the calendar: kept créneaux keep their id and seats, missing
     * ones are created, unnamed ones on governed dates are removed with their
     * seats.
     */
    @POST
    @Path("/application")
    @Deprecated
    public RapportApplication apply() {
        return generateur.apply(Demande.gabarits()).application();
    }

    /** The templates the current grid implies — nothing written. */
    @POST
    @Path("/reconnaissance/apercu")
    @Deprecated
    public ReconnaissanceJourneesTypes previewReconnaissance() {
        return ReconnaissanceJourneesTypes.of(generateur.previewReconnaissance());
    }

    /** Replaces every template and the whole calendar by what the grid implies. */
    @POST
    @Path("/reconnaissance")
    @Deprecated
    public ReconnaissanceJourneesTypes reconnaitre() {
        return ReconnaissanceJourneesTypes.of(generateur.reconnaitre());
    }
}
