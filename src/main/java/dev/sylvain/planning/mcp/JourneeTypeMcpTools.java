package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Demande;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.EtatGenerateur;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.RapportApplication;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.referentiel.VacationsLigne;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The 3.x tools of the day templates (ADR 0032), kept until 5.0 under their
 * names and their answers: the templates are the generator's « gabarits » since
 * 4.0, and each tool delegates to it — {@link GenerateurCreneauxMcpTools} is
 * where they went (ADR 0126, {@code mcp-renommages.tsv}).
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class JourneeTypeMcpTools {

    private final GenerateurCreneaux generateur;

    @Inject
    JourneeTypeMcpTools(GenerateurCreneaux generateur) {
        this.generateur = generateur;
    }

    @Tool(
            name = "lister_journees_types",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser lister_gabarits. "
                            + "Liste les journées types de l'édition, le calendrier (quelle date suit quelle journée "
                            + "type) et les dates en écart : celles dont les créneaux ne correspondent plus à leur journée type.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    EtatView listJourneeTypes(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.etat());
    }

    @Tool(
            name = "definir_journee_type",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_gabarit. "
                            + "Crée ou remplace une journée type par son nom : ses vacations sur une ligne, « 09:00-12:00, "
                            + "12:00-13:00, 13:00-14:00, 14:00-20:00 ». "
                            + "Rien n'est écrit sur la grille : appeler affecter_journee_type puis materialiser_journees_types.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    JourneeTypeView defineJourneeType(
            @ToolArg(description = "Nom de la journée type, ex. « Jour normal »") String nom,
            @ToolArg(description = "Vacations, ex. « 09:00-12:00, 12:00-13:00, 13:00-14:00, 14:00-20:00 »")
                    String vacations,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Gabarit journeeType = new Gabarit(null, nom, VacationsLigne.parse(vacations));
        Gabarit existante = byName(nom);
        if (existante == null) {
            return toView(generateur.create(journeeType));
        }
        journeeType.setModifieLe(existante.getModifieLe());
        return toView(generateur.update(existante.getId(), journeeType));
    }

    @Tool(
            name = "supprimer_journee_type",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_gabarit (nom). "
                    + "Supprime une journée type par son nom. Ses dates ne sont plus gouvernées ; les créneaux "
                    + "qu'elle a produits restent.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    EtatView deleteJourneeType(
            @ToolArg(description = "Nom de la journée type") String nom,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        generateur.delete(exigee(nom).getId());
        return toView(generateur.etat());
    }

    @Tool(
            name = "affecter_journee_type",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser modifier_gabarit (dates). "
                    + "Affecte des dates à une journée type : une plage (bornes incluses) et/ou des dates "
                    + "précises. Les autres dates du calendrier sont conservées ; une date déjà affectée change de "
                    + "journée type. Rien n'est écrit sur la grille avant materialiser_journees_types.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    EtatView assignJourneeType(
            @ToolArg(description = "Nom de la journée type") String nom,
            @ToolArg(description = "Première date de la plage (AAAA-MM-JJ)", required = false) String dateDebut,
            @ToolArg(description = "Dernière date de la plage (AAAA-MM-JJ), incluse", required = false) String dateFin,
            @ToolArg(description = "Dates précises (AAAA-MM-JJ)", required = false) List<String> dates,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Set<LocalDate> ciblees = GenerateurCreneauxMcpTools.dates(dateDebut, dateFin, dates);
        generateur.assign(exigee(nom).getId(), ciblees);
        return toView(generateur.etat());
    }

    @Tool(
            name = "retirer_dates_journee_type",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_gabarit (dates). "
                    + "Retire des dates du calendrier : elles ne sont plus gouvernées par aucune journée type, "
                    + "leurs créneaux restent tels quels.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    EtatView removeJourneeTypeDates(
            @ToolArg(description = "Dates à retirer (AAAA-MM-JJ)") List<String> dates,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.withdraw(McpArgs.dates(dates, "dates")));
    }

    @Computes
    @Tool(
            name = "previsualiser_application_journees_types",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser previsualiser_generateur_creneaux (source gabarits). "
                            + "Prévisualise ce qu'appliquer le calendrier changerait sur la grille — créneaux conservés, "
                            + "mis à jour, créés, supprimés (avec les sièges du planning qu'ils portent) — et le contrôle de la "
                            + "grille obtenue, sans RIEN écrire.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    RapportApplicationView previewJourneeTypesApplication(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.preview(Demande.gabarits()).application());
    }

    @Tool(
            name = "materialiser_journees_types",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser appliquer_generateur_creneaux (source gabarits). "
                    + "Applique le calendrier : un créneau identique garde son id et ses sièges, un créneau "
                    + "manquant est créé, un créneau que sa journée type ne nomme pas est supprimé AVEC ses sièges. Une "
                    + "date sans journée type n'est pas touchée. La grille est ensuite déclarée en VACATIONS. Appeler "
                    + "previsualiser_application_journees_types d'abord.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    RapportApplicationView materializeJourneeTypes(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.apply(Demande.gabarits()).application());
    }

    @Computes
    @Tool(
            name = "previsualiser_reconnaissance_journees_types",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser previsualiser_generateur_creneaux (source grille). "
                            + "Montre les journées types que la grille actuelle implique — chaque date aux mêmes "
                            + "vacations est le même type de jour — sans RIEN écrire.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    ReconnaissanceView previewJourneeTypesRecognition(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.previewReconnaissance());
    }

    @Tool(
            name = "reconnaitre_journees_types",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser appliquer_generateur_creneaux (source grille). "
                    + "Reconnaît les journées types que la grille actuelle implique et REMPLACE les journées "
                    + "types et le calendrier par ce résultat. Les créneaux ne sont pas touchés. Appeler "
                    + "previsualiser_reconnaissance_journees_types d'abord pour voir sans écrire.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    ReconnaissanceView recognizeJourneeTypes(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.reconnaitre());
    }

    /* -------------------------------- Outils -------------------------------- */

    private Gabarit byName(String nom) {
        if (nom == null || nom.isBlank()) {
            throw new BusinessError.Invalid("nom est requis");
        }
        return generateur.list().stream()
                .filter(journeeType -> journeeType.getNom().equalsIgnoreCase(nom.strip()))
                .findFirst()
                .orElse(null);
    }

    private Gabarit exigee(String nom) {
        Gabarit journeeType = byName(nom);
        if (journeeType == null) {
            throw new BusinessError.NotFound("Journée type introuvable : " + nom);
        }
        return journeeType;
    }

    static JourneeTypeView toView(Gabarit journeeType) {
        return new JourneeTypeView(
                journeeType.getId(), journeeType.getNom(), VacationsLigne.format(journeeType.getVacations()));
    }

    static EtatView toView(EtatGenerateur etat) {
        return new EtatView(
                etat.gabarits().stream().map(JourneeTypeMcpTools::toView).toList(),
                AffectationView.of(etat.calendrier()),
                etat.datesEnEcart());
    }

    static ReconnaissanceView toView(Reconnaissance reconnaissance) {
        return new ReconnaissanceView(
                reconnaissance.gabarits().stream()
                        .map(JourneeTypeMcpTools::toView)
                        .toList(),
                AffectationView.of(reconnaissance.calendrier()));
    }

    static RapportApplicationView toView(RapportApplication rapport) {
        return new RapportApplicationView(
                rapport.conserves(),
                rapport.crees(),
                rapport.supprimes(),
                rapport.creneauxSupprimes().stream()
                        .map(CreneauMcpTools::toView)
                        .toList(),
                rapport.postesSupprimes(),
                rapport.datesEnEcart(),
                rapport.aucunChangement(),
                rapport.controle());
    }

    /** A template as a line an assistant can read back and resend. */
    public record JourneeTypeView(Long id, String nom, String vacations) {}

    /** One date of the calendar, under its 3.x keys. */
    public record AffectationView(LocalDate date, Long journeeTypeId) {

        static List<AffectationView> of(List<Affectation> calendrier) {
            return calendrier.stream()
                    .map(affectation -> new AffectationView(affectation.date(), affectation.gabaritId()))
                    .toList();
        }
    }

    public record EtatView(
            List<JourneeTypeView> journeesTypes, List<AffectationView> calendrier, List<LocalDate> datesEnEcart) {}

    public record RapportApplicationView(
            int conserves,
            int crees,
            int supprimes,
            List<CreneauMcpTools.CreneauView> creneauxSupprimes,
            int postesSupprimes,
            List<LocalDate> datesEnEcart,
            boolean aucunChangement,
            RapportGrille controle) {}

    public record ReconnaissanceView(List<JourneeTypeView> journeesTypes, List<AffectationView> calendrier) {}
}
