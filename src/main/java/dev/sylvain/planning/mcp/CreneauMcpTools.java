package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for the time referential of {@code ReferenceDataResource}:
 * créneaux and the découpage that turns daily amplitudes into solvable
 * vacations, in place (issue #172: the edition holds one grid).
 */
@ApplicationScoped
public class CreneauMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste les créneaux de l'édition — ceux sur lesquels portera la prochaine résolution.")
    List<CreneauView> lister_creneaux() {
        return referenceDataService.listCreneaux().stream().map(CreneauMcpTools::toView).toList();
    }

    @Tool(description = "Crée un créneau.")
    CreneauView creer_creneau(
            @ToolArg(description = "Numéro de jour du festival (1 = premier jour)") int jour,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)") String heureFin) {
        Creneau creneau = new Creneau(null, jour, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), McpArgs.heure(heureFin, "heureFin"));
        return toView(referenceDataService.createCreneau(creneau));
    }

    @Tool(description = "Modifie un créneau. Seuls les champs fournis sont modifiés.")
    CreneauView modifier_creneau(
            @ToolArg(description = "Id du créneau") long id,
            @ToolArg(description = "Numéro de jour du festival", required = false) Integer jour,
            @ToolArg(description = "Date (AAAA-MM-JJ)", required = false) String date,
            @ToolArg(description = "Heure de début (HH:MM)", required = false) String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)", required = false) String heureFin) {
        Creneau creneau = trouverCreneau(id);
        if (jour != null) {
            creneau.setJour(jour);
        }
        if (date != null) {
            creneau.setDate(McpArgs.date(date, "date"));
        }
        if (heureDebut != null) {
            creneau.setHeureDebut(McpArgs.heure(heureDebut, "heureDebut"));
        }
        if (heureFin != null) {
            creneau.setHeureFin(McpArgs.heure(heureFin, "heureFin"));
        }
        return toView(referenceDataService.updateCreneau(id, creneau));
    }

    @Tool(description = "Supprime un créneau.")
    SuppressionResult supprimer_creneau(@ToolArg(description = "Id du créneau") long id) {
        referenceDataService.deleteCreneau(id);
        return new SuppressionResult(String.valueOf(id), true);
    }

    /* ------------------------------- Découpage ------------------------------ */

    @Tool(description = "Prévisualise le découpage : les vacations que les créneaux actuels de l'édition "
            + "(lus comme des amplitudes journalières) produiraient avec les paramètres de découpage courants. "
            + "Ne persiste rien.")
    List<CreneauView> previsualiser_decoupage() {
        return referenceDataService.previsualiserDecoupage().stream()
                .map(CreneauMcpTools::toView)
                .toList();
    }

    @Tool(description = "Génère le découpage EN PLACE : les créneaux actuels de l'édition (les amplitudes) sont "
            + "remplacés par les vacations générées, et le planning résolu est effacé avec eux. Pour re-découper "
            + "avec d'autres paramètres, ré-importer le scénario source.")
    List<CreneauView> generer_decoupage() {
        referenceDataService.genererDecoupage();
        return lister_creneaux();
    }

    private Creneau trouverCreneau(long id) {
        return referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null && creneau.getId() == id)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Créneau introuvable : " + id));
    }

    static CreneauView toView(Creneau creneau) {
        return new CreneauView(creneau.getId(), creneau.getJour(), creneau.getDate(),
                creneau.getHeureDebut(), creneau.getHeureFin());
    }

    public record CreneauView(Long id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin) {
    }
}
