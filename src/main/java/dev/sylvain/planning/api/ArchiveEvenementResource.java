package dev.sylvain.planning.api;

import dev.sylvain.planning.service.export.ArchiveEvenementService;
import dev.sylvain.planning.service.export.ArchiveEvenementService.ArchiveAvailability;
import dev.sylvain.planning.service.export.ArchiveEvenementService.ArchivePart;
import dev.sylvain.planning.service.export.ArchiveEvenementService.ArchiveRequest;
import dev.sylvain.planning.service.export.ArchiveEvenementService.PreparedArchive;
import dev.sylvain.planning.service.export.FormatPlanning;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.util.EnumSet;
import java.util.Set;

/**
 * The end-of-event archive of the Exports screen: the files an edition leaves
 * behind, chosen part by part, in one ZIP — see {@link ArchiveEvenementService}.
 */
@Path("/exports/archive-evenement")
public class ArchiveEvenementResource {

    private final ArchiveEvenementService archiveService;

    @Inject
    public ArchiveEvenementResource(ArchiveEvenementService archiveService) {
        this.archiveService = archiveService;
    }

    /** Which parts would come out empty right now, so the screen greys them out. */
    @GET
    @Path("/disponibilite")
    @Produces(MediaType.APPLICATION_JSON)
    public ArchiveAvailability availability() {
        return archiveService.availability();
    }

    /**
     * The archive, streamed. Every part is opt-in, as on the referential
     * export: asking for nothing is a {@code 400} rather than a ZIP holding
     * its manifest alone.
     */
    @GET
    @Produces("application/zip")
    public Response export(
            @QueryParam("pdfGlobal") boolean pdfGlobal,
            @QueryParam("equite") boolean equite,
            @QueryParam("heures") boolean heures,
            @QueryParam("referentiels") boolean referentiels,
            @QueryParam("scenario") boolean scenario,
            @QueryParam("publication") boolean publication,
            @QueryParam("individuels") boolean individuels,
            @QueryParam("format") String format) {
        Set<ArchivePart> parts = EnumSet.noneOf(ArchivePart.class);
        add(parts, pdfGlobal, ArchivePart.PDF_GLOBAL);
        add(parts, equite, ArchivePart.EQUITE);
        add(parts, heures, ArchivePart.HEURES);
        add(parts, referentiels, ArchivePart.REFERENTIELS);
        add(parts, scenario, ArchivePart.SCENARIO);
        add(parts, publication, ArchivePart.PUBLICATION);
        add(parts, individuels, ArchivePart.INDIVIDUELS);
        PreparedArchive archive =
                archiveService.prepare(new ArchiveRequest(parts, FormatPlanning.fromParameter(format)));
        StreamingOutput body = output -> archive.writer().writeTo(output);
        return Response.ok(body)
                .type("application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archive.fileName() + "\"")
                .build();
    }

    private static void add(Set<ArchivePart> parts, boolean asked, ArchivePart part) {
        if (asked) {
            parts.add(part);
        }
    }
}
