package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.ActionJournalisee;
import dev.sylvain.planning.service.journal.CatalogueActions;
import dev.sylvain.planning.service.journal.EntreeJournal;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.journal.ReferenceDataChanges;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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

    /** How many recent lines the summary shows: enough to recognise what happened, not a page of history. */
    private static final int RECENT_LINES = 5;

    /** The one value of {@code nature}: the actions the catalogue flags as exports. */
    static final String NATURE_EXPORTS = "exports";

    @Inject
    JournalActionService journal;

    @Inject
    ReferenceDataService referenceData;

    /**
     * The edition's most recent lines, newest first.
     *
     * @param nature {@code exports} keeps only the files that left the
     *               application, selected in the database — so the whole
     *               retention is searched, not the last page of every kind;
     *               absent, every action. Anything else is refused rather
     *               than read as « all »: a filter silently dropped would
     *               answer a question nobody asked.
     */
    @GET
    public List<EntreeHistoriqueView> list(
            @QueryParam("limite") Integer limite,
            @QueryParam("nature") @Schema(enumeration = {NATURE_EXPORTS}) String nature) {
        List<EntreeJournal> entrees;
        if (nature == null || nature.isBlank()) {
            entrees = journal.list(limite);
        } else if (NATURE_EXPORTS.equals(nature)) {
            entrees = journal.listExports(limite);
        } else {
            throw new BusinessError.Invalid(
                    "Nature d'action inconnue : « " + nature + " » (attendu « " + NATURE_EXPORTS + " »).");
        }
        Map<String, String> noms = referenceData.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Animateur::nomAffiche, (a, b) -> a));
        return entrees.stream().map(entree -> view(entree, noms::get)).toList();
    }

    /**
     * What changed in the problem since {@code depuis} — the count per
     * referential family, and the five most recent lines.
     *
     * <p>Read by the solver screen under « des données de référence ont été
     * modifiées depuis cette résolution » to say <em>what</em> moved. The
     * moment comes from the caller, which already holds the résolution's own:
     * the history answers « depuis quand ? » for anyone asking, and does not
     * need to know what a persisted plan is.</p>
     */
    @GET
    @Path("/changements")
    public ChangementsView changements(@QueryParam("depuis") String depuis) {
        ReferenceDataChanges changements = journal.changesSince(instant(depuis), RECENT_LINES);
        Map<String, String> noms = changements.dernieres().isEmpty()
                ? Map.of()
                : referenceData.listAnimateurs().stream()
                        .collect(Collectors.toMap(Animateur::getId, Animateur::nomAffiche, (a, b) -> a));
        return new ChangementsView(
                changements.total(),
                changements.parEntite().stream()
                        .map(compte -> new CompteEntiteView(compte.entite().name(), compte.nombre()))
                        .toList(),
                changements.dernieres().stream()
                        .map(entree -> view(entree, noms::get))
                        .toList());
    }

    private static Instant instant(String depuis) {
        if (depuis == null || depuis.isBlank()) {
            throw new BusinessError.Invalid("Le moment « depuis » est obligatoire (attendu une date ISO-8601).");
        }
        try {
            return Instant.parse(depuis);
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid("Moment illisible : « " + depuis + " » (attendu une date ISO-8601).", e);
        }
    }

    /**
     * The summary the solver screen shows under its staleness hint.
     *
     * @param total     how many changes since, all families together
     * @param parEntite one entry per referential family touched
     * @param dernieres the most recent lines, newest first
     */
    @Schema(requiredProperties = {"total", "parEntite", "dernieres"})
    public record ChangementsView(int total, List<CompteEntiteView> parEntite, List<EntreeHistoriqueView> dernieres) {}

    /** How many times one family moved; the screen writes « 3 animateurs » from it. */
    @Schema(requiredProperties = {"entite", "nombre"})
    public record CompteEntiteView(String entite, int nombre) {}

    /** The inventory of actions, so the screen can offer a filter it did not invent. */
    @GET
    @Path("/actions")
    public List<ActionView> actions() {
        return CatalogueActions.actions().values().stream()
                .map(action -> new ActionView(
                        action.code(),
                        action.libelle(),
                        action.entite() == null ? null : action.entite().name(),
                        action.export()))
                .sorted((a, b) -> a.libelle().compareToIgnoreCase(b.libelle()))
                .toList();
    }

    private static EntreeHistoriqueView view(EntreeJournal entree, UnaryOperator<String> nomDe) {
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
     * @param champs     the field names an edit changed, or the names of what
     *                   an export took out — never their values
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

    /**
     * One entry of the action inventory, for the screen's filter.
     *
     * @param export whether the action is a file leaving the application —
     *               the catalogue's classification, which the screen reads
     *               rather than guessing from the code
     */
    @Schema(requiredProperties = {"code", "libelle", "export"})
    public record ActionView(String code, String libelle, String entite, boolean export) {}
}
