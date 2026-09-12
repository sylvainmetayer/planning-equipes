package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.referentiel.JourneeTypeService.EtatJourneesTypes;
import dev.sylvain.planning.service.referentiel.JourneeTypeService.RapportApplication;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.VacationsLigne;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tools for the day templates of {@code JourneeTypeResource} (ADR 0032):
 * an assistant describes a kind of day once — « Nocturne : 09:00-12:00,
 * 12:00-13:00 R, 13:00-14:00 R, 14:00-00:00 » — assigns it to dates, previews
 * what applying would change on the grid, then applies. The same service as
 * the screen; a tool never re-implements a resource.
 */
@EditionCiblee
@Journalise
@ApplicationScoped
public class JourneeTypeMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(
            description =
                    "Liste les journées types de l'édition, le calendrier (quelle date suit quelle journée "
                            + "type) et les dates en écart : celles dont les créneaux ne correspondent plus à leur journée type.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView lister_journees_types(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.etatJourneesTypes());
    }

    @Tool(
            description =
                    "Crée ou remplace une journée type par son nom : ses vacations sur une ligne, « 09:00-12:00, "
                            + "12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 », R marquant un relais repas (sièges divisés par deux). "
                            + "Rien n'est écrit sur la grille : appeler affecter_journee_type puis materialiser_journees_types.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    JourneeTypeView definir_journee_type(
            @ToolArg(description = "Nom de la journée type, ex. « Jour normal »") String nom,
            @ToolArg(description = "Vacations, ex. « 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 »")
                    String vacations,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        JourneeType journeeType = new JourneeType(null, nom, VacationsLigne.parse(vacations));
        JourneeType existante = byName(nom);
        if (existante == null) {
            return toView(referenceDataService.createJourneeType(journeeType));
        }
        journeeType.setModifieLe(existante.getModifieLe());
        return toView(referenceDataService.updateJourneeType(existante.getId(), journeeType));
    }

    @Tool(
            description = "Supprime une journée type par son nom. Ses dates ne sont plus gouvernées ; les créneaux "
                    + "qu'elle a produits restent.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView supprimer_journee_type(
            @ToolArg(description = "Nom de la journée type") String nom,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteJourneeType(exigee(nom).getId());
        return toView(referenceDataService.etatJourneesTypes());
    }

    @Tool(
            description = "Affecte des dates à une journée type : une plage (bornes incluses) et/ou des dates "
                    + "précises. Les autres dates du calendrier sont conservées ; une date déjà affectée change de "
                    + "journée type. Rien n'est écrit sur la grille avant materialiser_journees_types.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView affecter_journee_type(
            @ToolArg(description = "Nom de la journée type") String nom,
            @ToolArg(description = "Première date de la plage (AAAA-MM-JJ)", required = false) String dateDebut,
            @ToolArg(description = "Dernière date de la plage (AAAA-MM-JJ), incluse", required = false) String dateFin,
            @ToolArg(description = "Dates précises (AAAA-MM-JJ)", required = false) List<String> dates,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        JourneeType journeeType = exigee(nom);
        Set<LocalDate> ciblees = McpArgs.dates(dates, "dates");
        LocalDate debut = McpArgs.date(dateDebut, "dateDebut");
        LocalDate fin = McpArgs.date(dateFin, "dateFin");
        if (debut != null || fin != null) {
            if (debut == null || fin == null || fin.isBefore(debut)) {
                throw new BusinessError.Invalid("Une plage demande dateDebut et dateFin, dateFin après dateDebut");
            }
            for (LocalDate date = debut; !date.isAfter(fin); date = date.plusDays(1)) {
                ciblees.add(date);
            }
        }
        if (ciblees.isEmpty()) {
            throw new BusinessError.Invalid("Aucune date : donnez une plage (dateDebut, dateFin) ou des dates");
        }
        Map<LocalDate, Long> calendrier = new LinkedHashMap<>();
        for (Affectation affectation : referenceDataService.etatJourneesTypes().calendrier()) {
            calendrier.put(affectation.date(), affectation.journeeTypeId());
        }
        for (LocalDate date : ciblees) {
            calendrier.put(date, journeeType.getId());
        }
        List<Affectation> reecrit = new ArrayList<>();
        calendrier.forEach((date, id) -> reecrit.add(new Affectation(date, id)));
        return toView(referenceDataService.setCalendrierJourneesTypes(reecrit));
    }

    @Tool(
            description = "Retire des dates du calendrier : elles ne sont plus gouvernées par aucune journée type, "
                    + "leurs créneaux restent tels quels.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView retirer_dates_journee_type(
            @ToolArg(description = "Dates à retirer (AAAA-MM-JJ)") List<String> dates,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Set<LocalDate> retirees = McpArgs.dates(dates, "dates");
        List<Affectation> reecrit = new ArrayList<>();
        for (Affectation affectation : referenceDataService.etatJourneesTypes().calendrier()) {
            if (!retirees.contains(affectation.date())) {
                reecrit.add(affectation);
            }
        }
        return toView(referenceDataService.setCalendrierJourneesTypes(reecrit));
    }

    @Tool(
            description = "Prévisualise ce qu'appliquer le calendrier changerait sur la grille — créneaux conservés, "
                    + "mis à jour, créés, supprimés (avec les sièges du planning qu'ils portent) — et le contrôle de la "
                    + "grille obtenue, sans RIEN écrire.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RapportApplicationView previsualiser_application_journees_types(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.previewJourneesTypes());
    }

    @Tool(
            description = "Applique le calendrier : un créneau identique garde son id et ses sièges, un créneau "
                    + "manquant est créé, un créneau que sa journée type ne nomme pas est supprimé AVEC ses sièges. Une "
                    + "date sans journée type n'est pas touchée. La grille est ensuite déclarée en VACATIONS. Appeler "
                    + "previsualiser_application_journees_types d'abord.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    RapportApplicationView materialiser_journees_types(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.applyJourneesTypes());
    }

    @Tool(
            description = "Montre les journées types que la grille actuelle implique — chaque date aux mêmes "
                    + "vacations est le même type de jour — sans RIEN écrire.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ReconnaissanceView previsualiser_reconnaissance_journees_types(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.previewReconnaissanceJourneesTypes());
    }

    @Tool(
            description = "Reconnaît les journées types que la grille actuelle implique et REMPLACE les journées "
                    + "types et le calendrier par ce résultat. Les créneaux ne sont pas touchés. Appeler "
                    + "previsualiser_reconnaissance_journees_types d'abord pour voir sans écrire.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    ReconnaissanceView reconnaitre_journees_types(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.reconnaitreJourneesTypes());
    }

    /* -------------------------------- Outils -------------------------------- */

    private JourneeType byName(String nom) {
        if (nom == null || nom.isBlank()) {
            throw new BusinessError.Invalid("nom est requis");
        }
        return referenceDataService.listJourneesTypes().stream()
                .filter(journeeType -> journeeType.getNom().equalsIgnoreCase(nom.strip()))
                .findFirst()
                .orElse(null);
    }

    private JourneeType exigee(String nom) {
        JourneeType journeeType = byName(nom);
        if (journeeType == null) {
            throw new BusinessError.NotFound("Journée type introuvable : " + nom);
        }
        return journeeType;
    }

    static JourneeTypeView toView(JourneeType journeeType) {
        return new JourneeTypeView(
                journeeType.getId(), journeeType.getNom(), VacationsLigne.format(journeeType.getVacations()));
    }

    static EtatView toView(EtatJourneesTypes etat) {
        return new EtatView(
                etat.journeesTypes().stream().map(JourneeTypeMcpTools::toView).toList(),
                etat.calendrier(),
                etat.datesEnEcart());
    }

    static ReconnaissanceView toView(Reconnaissance reconnaissance) {
        return new ReconnaissanceView(
                reconnaissance.journeesTypes().stream()
                        .map(JourneeTypeMcpTools::toView)
                        .toList(),
                reconnaissance.calendrier());
    }

    static RapportApplicationView toView(RapportApplication rapport) {
        return new RapportApplicationView(
                rapport.conserves(),
                rapport.misAJour(),
                rapport.crees(),
                rapport.supprimes(),
                rapport.creneauxSupprimes().stream()
                        .map(CreneauMcpTools::toView)
                        .toList(),
                rapport.postesSupprimes(),
                rapport.datesEnEcart(),
                rapport.aucunChangement(),
                rapport.modeADeclarer(),
                rapport.controle());
    }

    /** A template as a line an assistant can read back and resend. */
    public record JourneeTypeView(Long id, String nom, String vacations) {}

    public record EtatView(
            List<JourneeTypeView> journeesTypes, List<Affectation> calendrier, List<LocalDate> datesEnEcart) {}

    public record RapportApplicationView(
            int conserves,
            int misAJour,
            int crees,
            int supprimes,
            List<CreneauMcpTools.CreneauView> creneauxSupprimes,
            int postesSupprimes,
            List<LocalDate> datesEnEcart,
            boolean aucunChangement,
            boolean modeADeclarer,
            RapportGrille controle) {}

    public record ReconnaissanceView(List<JourneeTypeView> journeesTypes, List<Affectation> calendrier) {}
}
