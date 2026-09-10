package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.journal.ActionJournalisee;
import dev.sylvain.planning.service.journal.CatalogueActions;
import dev.sylvain.planning.service.journal.EntreeJournal;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The history of what was done in the edition (issue #406).
 *
 * <p>Read-only, and it is the only shape this resource will ever have: a
 * journal one can edit is not a journal. Deleting is not offered either — what
 * bounds the table is its retention, applied by the nightly job, not a button.</p>
 *
 * <p><b>The identity is joined here, at read time</b>, exactly as
 * {@code AlerteService} does: the table stores an animateur id and nothing
 * else, so a fiche deleted since leaves a line that names nobody. That is the
 * correct outcome rather than a defect — it is what lets the history outlive
 * the people it describes without keeping their names.</p>
 */
@Path("/historique")
@Produces(MediaType.APPLICATION_JSON)
public class HistoriqueResource {

    @Inject
    JournalActionService journal;

    @Inject
    ReferenceDataService referenceData;

    @GET
    public List<EntreeHistoriqueView> list(@QueryParam("limite") Integer limite) {
        List<EntreeJournal> entrees = journal.list(limite);
        Map<String, String> noms = referenceData.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Animateur::nomAffiche, (a, b) -> a));
        return entrees.stream().map(entree -> view(entree, noms::get)).toList();
    }

    /** The inventory of actions, so the screen can offer a filter it did not invent. */
    @GET
    @Path("/actions")
    public List<ActionView> actions() {
        return CatalogueActions.actions().values().stream()
                .map(action -> new ActionView(
                        action.code(),
                        action.libelle(),
                        action.entite() == null ? null : action.entite().name()))
                .sorted((a, b) -> a.libelle().compareToIgnoreCase(b.libelle()))
                .toList();
    }

    private static EntreeHistoriqueView view(EntreeJournal entree, Function<String, String> nomDe) {
        ActionJournalisee action = CatalogueActions.actions().get(entree.action());
        return new EntreeHistoriqueView(
                entree.id(),
                entree.survenuLe(),
                entree.acteur().name(),
                entree.acteurId(),
                nomDe.apply(entree.acteurId()),
                entree.action(),
                action == null ? entree.action() : action.libelle(),
                entree.entite(),
                entree.entiteId(),
                "ANIMATEUR".equals(entree.entite()) ? nomDe.apply(entree.entiteId()) : null,
                entree.champs(),
                entree.resultat().name(),
                entree.statut());
    }

    /**
     * One line, ready to read.
     *
     * @param acteurNom  the actor's display name when they are an animateur
     *                   still on the roster, {@code null} otherwise — resolved
     *                   here and never stored
     * @param libelle    what the action says in French, from the catalogue
     * @param entiteNom  same as {@code acteurNom}, for what the action bore upon
     * @param champs     the field names an edit changed, never their values
     */
    @Schema(requiredProperties = {"id"})
    public record EntreeHistoriqueView(
            long id,
            Instant survenuLe,
            String acteur,
            String acteurId,
            String acteurNom,
            String action,
            String libelle,
            String entite,
            String entiteId,
            String entiteNom,
            List<String> champs,
            String resultat,
            Integer statut) {}

    /** One entry of the action inventory, for the screen's filter. */
    public record ActionView(String code, String libelle, String entite) {}
}
