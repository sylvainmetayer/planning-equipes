package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.edition.EtatEditionService;
import dev.sylvain.planning.service.edition.EtatEditionView;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * MCP tools for the editions themselves — the partition every other tool of
 * this package works inside (issue #181).
 *
 * <p>Before these existed, an assistant could not answer "how many editions do
 * I have, and how many créneaux in each?": every tool worked in the default
 * edition without naming it, so a reading looked like the whole truth and a
 * write could land in the wrong edition unnoticed. {@code lister_editions}
 * answers the question, {@code edition_courante} names the edition the other
 * tools use when a call designates none, and the {@code edition} argument they
 * all carry designates another one for the duration of a single call.</p>
 *
 * <p>The volumetry {@code lister_editions} returns costs one query per edition
 * per counted référentiel. That is deliberate: an edition list without it
 * ("2025", "2026", "2026 canicule") gives an assistant nothing to recognise
 * the right one by, and editions are counted in units, not in thousands.</p>
 *
 * <p>{@code etat_edition} is the one tool here that works <em>inside</em> an
 * edition rather than on the list, hence the {@code @EditionCiblee} on the
 * class: its {@code edition} argument designates the edition to read, the
 * way every other tool's does. The other tools name their edition as a
 * plain argument, resolved by hand — the interceptor only reads
 * {@code @EditionArg}, so it leaves them alone.</p>
 */
@RefusMetier
@Journalise
@EditionCiblee
@ApplicationScoped
public class EditionMcpTools {

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    McpEditions editions;

    @Inject
    EtatEditionService etatEditionService;

    @Tool(
            description = "Liste les éditions (« Année 2025 », « Année 2026 », un plan canicule…) : "
                    + "l'édition est la partition dans laquelle vivent stands, animateurs, créneaux et paramètres, "
                    + "et deux éditions ne voient jamais les données l'une de l'autre. Chaque ligne indique laquelle "
                    + "est courante (celle utilisée par les autres outils quand aucune n'est précisée), laquelle est "
                    + "l'édition par défaut, et de quoi la reconnaître : nombre de créneaux, période couverte, "
                    + "nombre de stands et d'animateurs.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<EditionView> lister_editions() {
        String courante = editionContext.editionIdCourant();
        return editionService.listEditions().stream()
                .map(edition -> view(edition, courante))
                .toList();
    }

    @Tool(
            description = "Nomme l'édition dans laquelle travaillent tous les autres outils quand leur argument "
                    + "« edition » n'est pas précisé. À appeler avant toute écriture si l'utilisateur a plusieurs "
                    + "éditions : rien d'autre n'indique laquelle est en train d'être modifiée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView edition_courante() {
        String courante = editionContext.editionIdCourant();
        return view(editionService.editionCourante(), courante);
    }

    @Tool(
            description = "État de l'édition : la checklist du cycle, calculée — référentiels saisis, collecte "
                    + "des disponibilités, ouvertures des stands, besoin en animateurs, dernière résolution (et si "
                    + "les données ont bougé depuis, ou si une résolution est en cours), problèmes bloquants et "
                    + "avertissements, publication, accusés de réception, foire au planning. Chaque ligne porte un "
                    + "statut A_FAIRE, ATTENTION, INFO (des chiffres à lire, rien qui bloque) ou FAIT, et les "
                    + "chiffres qui le décident ; aucune donnée nominative. "
                    + "À appeler en premier pour savoir où en est l'organisateur, avant de choisir un outil plus fin.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatEditionView etat_edition(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return etatEditionService.etat();
    }

    @Tool(
            description = "Crée une édition vide. Pour repartir d'une édition existante (stands, animateurs, "
                    + "paramètres), utiliser dupliquer_edition à la place.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    EditionView creer_edition(
            @ToolArg(description = "Id de la nouvelle édition, repris tel quel dans les URLs (ex. « 2027 »)") String id,
            @ToolArg(description = "Nom affiché (ex. « Année 2027 »)") String nom) {
        return view(editionService.create(new Edition(id, nom, false, null)), editionContext.editionIdCourant());
    }

    @Tool(
            description = "Duplique une édition dans une nouvelle : stands, animateurs, typologies, emplacements, "
                    + "créneaux, contraintes et paramètres sont recopiés, jamais le planning résolu. C'est la façon de "
                    + "préparer une variante (« plan canicule ») sans toucher à l'originale : depuis l'issue #172, une "
                    + "variante EST une édition dupliquée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    EditionView dupliquer_edition(
            @ToolArg(description = "Édition à copier : son id ou son nom (voir lister_editions)") String source,
            @ToolArg(description = "Id de l'édition à créer") String id,
            @ToolArg(description = "Nom affiché de l'édition à créer") String nom) {
        String sourceId = requireEdition(source, "source");
        return view(
                editionService.duplicate(sourceId, new Edition(id, nom, false, null)),
                editionContext.editionIdCourant());
    }

    @Tool(
            description = "Renomme une édition. Seul le nom affiché change : l'id, lui, est repris dans les URLs "
                    + "et les configurations, il n'est pas modifiable.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView renommer_edition(
            @ToolArg(description = "Édition à renommer : son id ou son nom") String edition,
            @ToolArg(description = "Nouveau nom affiché") String nom) {
        String id = requireEdition(edition, "edition");
        return view(editionService.renommer(id, new Edition(id, nom, false, null)), editionContext.editionIdCourant());
    }

    @Tool(
            description = "Désigne l'édition par défaut : celle dans laquelle travaille tout appelant qui n'en "
                    + "précise aucune, y compris les outils MCP sans argument « edition ».",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView definir_edition_par_defaut(
            @ToolArg(description = "Édition à rendre par défaut : son id ou son nom") String edition) {
        String id = requireEdition(edition, "edition");
        editionService.setAsDefault(id);
        return view(find(id), editionContext.editionIdCourant());
    }

    @Tool(
            description = "Supprime une édition ET tout ce qu'elle contient : stands, animateurs, créneaux, "
                    + "contraintes, planning résolu. Destructif et irréversible, à ne lancer que sur demande explicite. "
                    + "L'édition par défaut, l'édition courante et la dernière édition restante sont refusées.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult supprimer_edition(
            @ToolArg(description = "Édition à supprimer : son id ou son nom") String edition) {
        String id = requireEdition(edition, "edition");
        editionService.delete(id);
        return new SuppressionResult(id, true);
    }

    /**
     * Resolves a designation the caller had to provide. Unlike the optional
     * {@code edition} argument of the other tools, a blank value here is a
     * missing argument, not "the current edition".
     */
    private String requireEdition(String edition, String champ) {
        if (edition == null || edition.isBlank()) {
            throw new BusinessError.Invalid(champ + " est requis : id ou nom de l'édition (voir lister_editions)");
        }
        return editions.solve(edition);
    }

    private Edition find(String id) {
        return editionService.listEditions().stream()
                .filter(edition -> edition.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Édition introuvable après écriture : " + id));
    }

    /** Counted inside the edition, hence the {@code executeIn}: the tool itself runs in another one. */
    private EditionView view(Edition edition, String editionCouranteId) {
        return editionContext.executeIn(edition.getId(), () -> {
            List<Creneau> creneaux = referenceDataService.listCreneaux();
            return new EditionView(
                    edition.getId(),
                    edition.getNom(),
                    edition.isDefaut(),
                    edition.getId().equals(editionCouranteId),
                    creneaux.size(),
                    firstDate(creneaux),
                    lastDate(creneaux),
                    referenceDataService.listStands().size(),
                    referenceDataService.listAnimateurs().size());
        });
    }

    private static LocalDate firstDate(List<Creneau> creneaux) {
        return dates(creneaux).min(Comparator.naturalOrder()).orElse(null);
    }

    private static LocalDate lastDate(List<Creneau> creneaux) {
        return dates(creneaux).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Stream<LocalDate> dates(List<Creneau> creneaux) {
        return creneaux.stream().map(Creneau::getDate).filter(Objects::nonNull);
    }

    /**
     * @param courante the edition the tools that name no {@code edition} work in
     * @param defaut   the edition every caller that names none falls back to (no HTTP header)
     */
    public record EditionView(
            String id,
            String nom,
            boolean defaut,
            boolean courante,
            int nombreCreneaux,
            LocalDate premiereDate,
            LocalDate derniereDate,
            int nombreStands,
            int nombreAnimateurs) {}
}
