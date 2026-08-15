package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.NoSuchElementException;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for the time referential of {@code ReferenceDataResource}:
 * créneaux, the groupes de créneaux they belong to (only the active one feeds
 * a solve), and the découpage that turns a group of daily amplitudes into a
 * group of solvable vacations.
 */
@ApplicationScoped
public class CreneauMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste les créneaux du groupe de créneaux actif — ceux sur lesquels portera la prochaine résolution.")
    List<CreneauView> lister_creneaux() {
        return referenceDataService.listCreneauxGroupeActif().stream().map(CreneauMcpTools::toView).toList();
    }

    @Tool(description = "Liste tous les créneaux, tous groupes confondus.")
    List<CreneauView> lister_tous_les_creneaux() {
        return referenceDataService.listCreneaux().stream().map(CreneauMcpTools::toView).toList();
    }

    @Tool(description = "Crée un créneau. Sans groupe précisé, le créneau atterrit dans le groupe DEFAUT.")
    CreneauView creer_creneau(
            @ToolArg(description = "Numéro de jour du festival (1 = premier jour)") int jour,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)") String heureFin,
            @ToolArg(description = "Id du groupe de créneaux", required = false) String groupeId) {
        Creneau creneau = new Creneau(null, jour, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), McpArgs.heure(heureFin, "heureFin"));
        if (groupeId != null) {
            creneau.setGroupe(trouverGroupe(groupeId));
        }
        return toView(referenceDataService.createCreneau(creneau));
    }

    @Tool(description = "Modifie un créneau. Seuls les champs fournis sont modifiés.")
    CreneauView modifier_creneau(
            @ToolArg(description = "Id du créneau") long id,
            @ToolArg(description = "Numéro de jour du festival", required = false) Integer jour,
            @ToolArg(description = "Date (AAAA-MM-JJ)", required = false) String date,
            @ToolArg(description = "Heure de début (HH:MM)", required = false) String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)", required = false) String heureFin,
            @ToolArg(description = "Id du groupe de créneaux", required = false) String groupeId) {
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
        if (groupeId != null) {
            creneau.setGroupe(trouverGroupe(groupeId));
        }
        return toView(referenceDataService.updateCreneau(id, creneau));
    }

    @Tool(description = "Supprime un créneau.")
    SuppressionResult supprimer_creneau(@ToolArg(description = "Id du créneau") long id) {
        referenceDataService.deleteCreneau(id);
        return new SuppressionResult(String.valueOf(id), true);
    }

    /* --------------------------- Groupes de créneaux ------------------------ */

    @Tool(description = "Liste les groupes de créneaux, avec le nombre de créneaux de chacun et lequel est actif.")
    List<GroupeCreneauView> lister_groupes_creneaux() {
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        return referenceDataService.listGroupesCreneaux().stream()
                .map(groupe -> toView(groupe, (int) creneaux.stream()
                        .filter(creneau -> creneau.getGroupe() != null
                                && groupe.getId().equals(creneau.getGroupe().getId()))
                        .count()))
                .toList();
    }

    @Tool(description = "Crée un groupe de créneaux (créé inactif : utiliser activer_groupe_creneaux ensuite).")
    GroupeCreneauView creer_groupe_creneaux(
            @ToolArg(description = "Id du groupe (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom) {
        return toView(referenceDataService.createGroupeCreneau(new GroupeCreneau(id, nom, false)), 0);
    }

    @Tool(description = "Renomme un groupe de créneaux.")
    GroupeCreneauView modifier_groupe_creneaux(
            @ToolArg(description = "Id du groupe") String id,
            @ToolArg(description = "Nouveau nom") String nom) {
        GroupeCreneau groupe = trouverGroupe(id);
        groupe.setNom(nom);
        return toView(referenceDataService.updateGroupeCreneau(id, groupe), null);
    }

    @Tool(description = "Active un groupe de créneaux pour les prochaines résolutions et désactive tous les autres.")
    GroupeCreneauView activer_groupe_creneaux(@ToolArg(description = "Id du groupe") String id) {
        referenceDataService.activerGroupeCreneau(id);
        return toView(trouverGroupe(id), null);
    }

    @Tool(description = "Supprime un groupe de créneaux et ses créneaux. Refusé sur le groupe actif.")
    SuppressionResult supprimer_groupe_creneaux(@ToolArg(description = "Id du groupe") String id) {
        referenceDataService.deleteGroupeCreneau(id);
        return new SuppressionResult(id, true);
    }

    /* ------------------------------- Découpage ------------------------------ */

    @Tool(description = "Prévisualise le découpage : les vacations qu'un groupe d'amplitudes journalières "
            + "produirait avec les paramètres de découpage courants. Ne persiste rien.")
    List<CreneauView> previsualiser_decoupage(
            @ToolArg(description = "Id du groupe source contenant les amplitudes") String groupeSourceId) {
        return referenceDataService.previsualiserDecoupage(groupeSourceId).stream()
                .map(CreneauMcpTools::toView)
                .toList();
    }

    @Tool(description = "Génère le découpage dans un groupe cible (créé s'il n'existe pas), en remplaçant "
            + "entièrement les créneaux de ce groupe cible. Le groupe source est laissé intact.")
    GroupeCreneauView generer_decoupage(
            @ToolArg(description = "Id du groupe source contenant les amplitudes") String groupeSourceId,
            @ToolArg(description = "Id du groupe cible") String groupeCibleId,
            @ToolArg(description = "Nom du groupe cible, requis s'il n'existe pas encore", required = false) String nomGroupeCible,
            @ToolArg(description = "Activer le groupe cible à l'issue de la génération", required = false) Boolean activerGroupeCible) {
        GroupeCreneau cible = referenceDataService.genererDecoupage(groupeSourceId, groupeCibleId, nomGroupeCible,
                Boolean.TRUE.equals(activerGroupeCible));
        return toView(cible, null);
    }

    private Creneau trouverCreneau(long id) {
        return referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null && creneau.getId() == id)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Créneau introuvable : " + id));
    }

    private GroupeCreneau trouverGroupe(String id) {
        return referenceDataService.listGroupesCreneaux().stream()
                .filter(groupe -> groupe.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Groupe de créneaux introuvable : " + id));
    }

    static CreneauView toView(Creneau creneau) {
        return new CreneauView(creneau.getId(), creneau.getJour(), creneau.getDate(),
                creneau.getHeureDebut(), creneau.getHeureFin(),
                creneau.getGroupe() == null ? null : creneau.getGroupe().getId());
    }

    static GroupeCreneauView toView(GroupeCreneau groupe, Integer nombreCreneaux) {
        return new GroupeCreneauView(groupe.getId(), groupe.getNom(), groupe.isActif(), groupe.getGroupeSourceId(),
                nombreCreneaux);
    }

    public record CreneauView(Long id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            String groupeId) {
    }

    /** @param nombreCreneaux null quand l'outil n'a pas eu à le compter (retour d'une mutation) */
    public record GroupeCreneauView(String id, String nom, boolean actif, String groupeSourceId,
            Integer nombreCreneaux) {
    }
}
