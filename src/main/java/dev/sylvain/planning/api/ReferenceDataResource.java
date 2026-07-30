package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.ReferenceDataService.TypologieItem;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReferenceDataResource {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningService planningService;

    @GET
    @Path("/stands")
    public List<Stand> listStands() {
        return referenceDataService.listStands();
    }

    @POST
    @Path("/stands")
    public Stand createStand(Stand stand) {
        return referenceDataService.createStand(stand);
    }

    @PUT
    @Path("/stands/{id}")
    public Stand updateStand(@PathParam("id") String id, Stand stand) {
        return referenceDataService.updateStand(id, stand);
    }

    @DELETE
    @Path("/stands/{id}")
    public Response deleteStand(@PathParam("id") String id) {
        referenceDataService.deleteStand(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/creneaux")
    public List<Creneau> listCreneaux() {
        return referenceDataService.listCreneaux();
    }

    @POST
    @Path("/creneaux")
    public Creneau createCreneau(Creneau creneau) {
        return referenceDataService.createCreneau(creneau);
    }

    @PUT
    @Path("/creneaux/{id}")
    public Creneau updateCreneau(@PathParam("id") String id, Creneau creneau) {
        return referenceDataService.updateCreneau(id, creneau);
    }

    @DELETE
    @Path("/creneaux/{id}")
    public Response deleteCreneau(@PathParam("id") String id) {
        referenceDataService.deleteCreneau(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/animateurs")
    public List<Animateur> listAnimateurs() {
        return referenceDataService.listAnimateurs();
    }

    @POST
    @Path("/animateurs")
    public Animateur createAnimateur(Animateur animateur) {
        return referenceDataService.createAnimateur(animateur);
    }

    @PUT
    @Path("/animateurs/{id}")
    public Animateur updateAnimateur(@PathParam("id") String id, Animateur animateur) {
        return referenceDataService.updateAnimateur(id, animateur);
    }

    @DELETE
    @Path("/animateurs/{id}")
    public Response deleteAnimateur(@PathParam("id") String id) {
        referenceDataService.deleteAnimateur(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/typologies")
    public List<TypologieItem> listTypologies() {
        return referenceDataService.listTypologies();
    }

    @POST
    @Path("/typologies")
    public TypologieItem createTypologie(TypologieItem typologie) {
        return referenceDataService.createTypologie(typologie);
    }

    @PUT
    @Path("/typologies/{id}")
    public TypologieItem updateTypologie(@PathParam("id") String id, TypologieItem typologie) {
        return referenceDataService.updateTypologie(id, typologie);
    }

    @DELETE
    @Path("/typologies/{id}")
    public Response deleteTypologie(@PathParam("id") String id) {
        referenceDataService.deleteTypologie(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/reference-data/import")
    public Response importReferenceData(dev.sylvain.planning.domain.PlanningFestival planning) {
        referenceDataService.importFromPlanning(planning);
        return Response.noContent().build();
    }

    /**
     * Loads a scenario by name entirely server-side and imports its reference
     * data into the database. The scenario file is parsed on the backend, so
     * the (potentially large) planning never travels to the browser and back —
     * the client only sends the desired scenario name.
     */
    @POST
    @Path("/reference-data/import-scenario")
    @Consumes(MediaType.WILDCARD)
    public Response importScenario(@QueryParam("name") String name) {
        referenceDataService.importFromPlanning(planningService.construireExemple(name));
        return Response.noContent().build();
    }

    @GET
    @Path("/contraintes-ad-hoc")
    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return referenceDataService.listContraintesAdHoc();
    }

    @POST
    @Path("/contraintes-ad-hoc")
    public ContrainteAdHoc createContrainteAdHoc(ContrainteAdHoc contrainteAdHoc) {
        return referenceDataService.createContrainteAdHoc(contrainteAdHoc);
    }

    @DELETE
    @Path("/contraintes-ad-hoc/{id}")
    public Response deleteContrainteAdHoc(@PathParam("id") String id) {
        referenceDataService.deleteContrainteAdHoc(id);
        return Response.noContent().build();
    }
}
