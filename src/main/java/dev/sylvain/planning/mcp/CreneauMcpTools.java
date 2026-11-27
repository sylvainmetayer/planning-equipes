package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.CreneauGridService.DiagnosticGrille;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.RegleRecurrence;
import dev.sylvain.planning.service.referentiel.GrilleDepuisFenetres;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WrittenCreneau;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for the time referential of {@code ReferenceDataResource}:
 * créneaux and the découpage that turns daily amplitudes into solvable
 * vacations, in place (issue #172: the edition holds one grid).
 *
 * <p>Beyond the unit CRUD, this is where a grid gets built from a sentence:
 * "de 9h à 12h et de 14h à 18h, all les jours sauf le week-end" is one
 * {@link RegleRecurrence}, not forty {@code creer_creneau} calls. Two
 * safeguards come with that leverage — a preview that writes nothing, and a
 * validation that names the mistakes a bulk write makes cheap to introduce
 * and expensive to spot (see {@link CreneauGridService}).</p>
 *
 * <p>One tool per object and per verb (ADR 0127): {@code supprimer_creneaux} removes by ids or by
 * filters, {@code diagnostiquer_creneaux} reads the grid under a {@code section}; the 3.x names stay
 * published as aliases until 5.0.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class CreneauMcpTools {

    /** Argument names, as a refusal quotes them back to the caller. */
    private static final String ARG_HEURE_DEBUT = "heureDebut";

    private static final String ARG_DATE_DEBUT = "dateDebut";
    private static final String ARG_DATE_FIN = "dateFin";

    private final ReferenceDataService referenceDataService;

    private final GenerateurCreneaux generateur;

    @Inject
    CreneauMcpTools(ReferenceDataService referenceDataService, GenerateurCreneaux generateur) {
        this.referenceDataService = referenceDataService;
        this.generateur = generateur;
    }

    @Tool(
            name = "lister_creneaux",
            description = "Liste les créneaux de l'édition — ceux sur lesquels portera la prochaine résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<CreneauView> listCreneaux(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return creneauxCourants();
    }

    @Tool(
            name = "creer_creneau",
            description = "Crée un créneau. Le numéro de jour n'est pas à fournir : il est recalculé pour toute "
                    + "l'édition à partir des dates.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    @WarnsWhileSolving
    WrittenCreneauView createCreneau(
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)") String heureFin,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Creneau creneau = new Creneau(
                null,
                0,
                McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, ARG_HEURE_DEBUT),
                McpArgs.heure(heureFin, "heureFin"));
        return written(referenceDataService.writeCreneau(creneau));
    }

    @Tool(
            name = "modifier_creneau",
            description = "Modifie un créneau. Seuls les champs fournis sont modifiés.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    WrittenCreneauView updateCreneau(
            @ToolArg(description = "Id du créneau") long id,
            @ToolArg(description = "Date (AAAA-MM-JJ)", required = false) String date,
            @ToolArg(description = "Heure de début (HH:MM)", required = false) String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)", required = false) String heureFin,
            @ToolArg(
                            description =
                                    "WriteStamp modifieLe lu avant la modification (précondition : refusé si la fiche a changé depuis ; omis, pas de contrôle)",
                            required = false)
                    String modifieLe,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Creneau creneau = findCreneau(id);
        if (modifieLe != null) {
            creneau.setModifieLe(McpArgs.instant(modifieLe, "modifieLe"));
        }
        if (date != null) {
            creneau.setDate(McpArgs.date(date, "date"));
        }
        if (heureDebut != null) {
            creneau.setHeureDebut(McpArgs.heure(heureDebut, ARG_HEURE_DEBUT));
        }
        if (heureFin != null) {
            creneau.setHeureFin(McpArgs.heure(heureFin, "heureFin"));
        }
        return written(referenceDataService.writeCreneau(id, creneau));
    }

    @Deprecated
    @Tool(
            name = "supprimer_creneau",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_creneaux (ids). Supprime un créneau.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult deleteCreneau(
            @ToolArg(description = "Id du créneau") long id,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        referenceDataService.deleteCreneau(id);
        return new SuppressionResult(String.valueOf(id), true);
    }

    /* ----------------------------- Grid: checks ------------------------------ */

    @Tool(
            name = "diagnostiquer_creneaux",
            description = "Lit la grille de créneaux en place, sous la section demandée, sans rien écrire. grille "
                    + "(défaut) : combien de vacations, sur quelles dates, avec quelle durée médiane, et quelles dates tombent un jour férié (joursFeries, "
                    + "avec leur libellé) ; le premier appel utile pour découvrir une édition. controle : ce qui cloche "
                    + "— doublons, trous dans une journée, vacations plus longues que le maximum légal, dates isolées, "
                    + "stands que personne ne pourra tenir, sous-effectif, et, pour "
                    + "information, VACATION_JOUR_FERIE : des vacations un jour férié alors que l'édition compte un "
                    + "mineur ce jour-là (seuls les majeurs y siègent). Deux créneaux qui se chevauchent le même jour "
                    + "sont normaux : ce sont deux vacations décalées.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    GrilleView diagnoseCreneaux(
            @ToolArg(description = "grille (défaut) ou controle", required = false) String section,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie = McpArgs.choix(section, "section", "grille", "grille", "controle");
        return "grille".equals(choisie)
                ? new GrilleView(choisie, diagnoseCreneauGrid(edition), null)
                : new GrilleView(choisie, null, validateCreneaux(edition));
    }

    /** One section of the grid's reading, the other left out. */
    public record GrilleView(
            String section,
            @JsonInclude(JsonInclude.Include.NON_NULL) DiagnosticGrille grille,
            @JsonInclude(JsonInclude.Include.NON_NULL) RapportGrille controle) {}

    @Deprecated
    @Tool(
            name = "diagnostiquer_grille_creneaux",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser diagnostiquer_creneaux (section grille). "
                    + "Décrit la grille de créneaux en place : combien de vacations, sur quelles dates, avec "
                    + "quelle durée médiane, et quelles "
                    + "dates tombent un jour férié (joursFeries, avec leur libellé). Le premier "
                    + "appel utile pour découvrir une édition, avant de créer des créneaux.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    DiagnosticGrille diagnoseCreneauGrid(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return referenceDataService.diagnoseGrille();
    }

    @Deprecated
    @Tool(
            name = "valider_creneaux",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser diagnostiquer_creneaux (section controle). "
                    + "Contrôle la cohérence de la grille de créneaux actuelle et signale ce qui cloche : "
                    + "doublons, trous dans une journée, vacations plus longues que le maximum légal, dates isolées, "
                    + "stands que personne ne pourra tenir, sous-effectif, et, pour "
                    + "information, VACATION_JOUR_FERIE : des vacations un jour férié alors que l'édition compte un "
                    + "mineur ce jour-là (seuls les majeurs y siègent). Deux créneaux "
                    + "qui se chevauchent le même jour sont normaux : ce sont deux vacations décalées.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    RapportGrille validateCreneaux(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return referenceDataService.controlerGrille();
    }

    /* ----------------------------- Grid: recurrence -------------------------- */

    @Computes
    @Tool(
            name = "previsualiser_creneaux_recurrents",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser previsualiser_generateur_creneaux (source recurrence). "
                            + "Prévisualise les créneaux qu'une règle récurrente produirait, et contrôle la grille qui "
                            + "en résulterait — sans RIEN écrire. À utiliser systématiquement avant creer_creneaux_recurrents : "
                            + "une règle qui se trompe d'une heure crée des dizaines de lignes d'un coup. "
                            + "Les fenêtres se saisissent « HH:MM-HH:MM », séparées par des virgules pour une journée en "
                            + "plusieurs morceaux (ex. « 09:00-12:00,14:00-18:00 » pour une coupure méridienne) ; contrairement "
                            + "aux horaires de stand, l'heure de fin est obligatoire. La portée vaut TOUS (toutes les dates de "
                            + "la plage), JOURS_SEMAINE (avec joursSemaine, ex. MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY pour "
                            + "« sauf le week-end »), PLAGE (identique à TOUS) ou DATES (avec dates). Les exclusions retirent "
                            + "des dates précises quel que soit le sélecteur.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    PrevisualisationRecurrence previewRecurringCreneaux(
            @ToolArg(description = "Fenêtres, ex. « 09:00-12:00,14:00-18:00 »") String fenetres,
            @ToolArg(description = "Portée : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String jours,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ), bornes incluses", required = false)
                    String dateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ), bornes incluses", required = false) String dateFin,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false)
                    List<String> joursSemaine,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> dates,
            @ToolArg(description = "Dates (AAAA-MM-JJ) à exclure quel que soit le sélecteur", required = false)
                    List<String> exclusions,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toRecurrenceView(generateur.preview(GenerateurCreneaux.Demande.recurrence(
                regle(jours, dateDebut, dateFin, joursSemaine, dates, exclusions, fenetres))));
    }

    @Tool(
            name = "creer_creneaux_recurrents",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser appliquer_generateur_creneaux (source recurrence). "
                            + "Crée les créneaux d'une règle récurrente et renvoie le contrôle de cohérence de la "
                            + "grille obtenue. Mêmes arguments que previsualiser_creneaux_recurrents, qu'il faut avoir appelé "
                            + "d'abord. Les créneaux existants ne sont pas touchés : la règle AJOUTE. Pour repartir de zéro, "
                            + "appeler supprimer_creneaux avant.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    @Deprecated
    PrevisualisationRecurrence createRecurringCreneaux(
            @ToolArg(description = "Fenêtres, ex. « 09:00-12:00,14:00-18:00 »") String fenetres,
            @ToolArg(description = "Portée : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String jours,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ), bornes incluses", required = false)
                    String dateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ), bornes incluses", required = false) String dateFin,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false)
                    List<String> joursSemaine,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> dates,
            @ToolArg(description = "Dates (AAAA-MM-JJ) à exclure quel que soit le sélecteur", required = false)
                    List<String> exclusions,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toRecurrenceView(generateur.apply(GenerateurCreneaux.Demande.recurrence(
                regle(jours, dateDebut, dateFin, joursSemaine, dates, exclusions, fenetres))));
    }

    /* ------------------------ Grid: derived from the stands ------------------------ */

    @Computes
    @Tool(
            name = "previsualiser_derivation_creneaux",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser previsualiser_generateur_creneaux (source stands). "
                            + "Prévisualise la grille de créneaux que les horaires des stands impliquent : chaque heure "
                            + "où un stand ouvre ou ferme est une coupure, chaque tranche entre deux coupures où au moins un stand "
                            + "est ouvert devient un créneau — sans RIEN écrire. À utiliser quand les horaires des stands existent "
                            + "déjà (règles saisies ou importées) et que la grille n'est pas encore faite. Seuls les jours qu'un "
                            + "stand déclare « ouvert sur ces fenêtres » comptent ; une fenêtre « jusqu'à la fermeture » finit à "
                            + "heureFermeture (00:00 pour minuit).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @Deprecated
    RapportDerivationMcp previewCreneauDerivation(
            @ToolArg(description = "Première date (AAAA-MM-JJ)") String dateDebut,
            @ToolArg(description = "Dernière date (AAAA-MM-JJ), incluse") String dateFin,
            @ToolArg(description = "Heure de fermeture des fenêtres ouvertes (HH:MM, 00:00 = minuit)")
                    String heureFermeture,
            @ToolArg(
                            description =
                                    "Durée minimale d'un créneau en minutes ; en deçà, la tranche rejoint sa voisine et le trou est refermé (défaut 15)",
                            required = false)
                    Integer dureeMinimaleMinutes,
            @ToolArg(
                            description =
                                    "true pour juger la grille dérivée seule, comme si elle remplaçait l'actuelle",
                            required = false)
                    Boolean remplacer,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.preview(GenerateurCreneaux.Demande.stands(
                parametresDerivation(dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes),
                Boolean.TRUE.equals(remplacer))));
    }

    @Tool(
            name = "generer_creneaux_depuis_stands",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser appliquer_generateur_creneaux (source stands). "
                    + "Écrit la grille de créneaux dérivée des horaires des stands — mêmes arguments que "
                    + "previsualiser_derivation_creneaux, qu'il faut avoir appelé d'abord. Par défaut les créneaux "
                    + "s'AJOUTENT à la grille ; remplacer=true remplace toute la grille et EFFACE le planning "
                    + "résolu.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    @Deprecated
    RapportDerivationMcp generateCreneauxFromStands(
            @ToolArg(description = "Première date (AAAA-MM-JJ)") String dateDebut,
            @ToolArg(description = "Dernière date (AAAA-MM-JJ), incluse") String dateFin,
            @ToolArg(description = "Heure de fermeture des fenêtres ouvertes (HH:MM, 00:00 = minuit)")
                    String heureFermeture,
            @ToolArg(
                            description =
                                    "Durée minimale d'un créneau en minutes ; en deçà, la tranche rejoint sa voisine et le trou est refermé (défaut 15)",
                            required = false)
                    Integer dureeMinimaleMinutes,
            @ToolArg(description = "true pour remplacer toute la grille (efface le planning résolu)", required = false)
                    Boolean remplacer,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.apply(GenerateurCreneaux.Demande.stands(
                parametresDerivation(dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes),
                Boolean.TRUE.equals(remplacer))));
    }

    static GrilleDepuisFenetres.Parametres parametresDerivation(
            String dateDebut, String dateFin, String heureFermeture, Integer dureeMinimaleMinutes) {
        return new GrilleDepuisFenetres.Parametres(
                McpArgs.date(dateDebut, ARG_DATE_DEBUT),
                McpArgs.date(dateFin, ARG_DATE_FIN),
                McpArgs.heure(heureFermeture, "heureFermeture"),
                dureeMinimaleMinutes == null ? GrilleDepuisFenetres.DUREE_MINIMALE_PAR_DEFAUT : dureeMinimaleMinutes);
    }

    private static RapportDerivationMcp toView(GenerateurCreneaux.Generation resultat) {
        return new RapportDerivationMcp(
                resultat.nombreGeneres(),
                resultat.creneaux().stream().map(CreneauMcpTools::toView).toList(),
                resultat.coupures(),
                resultat.joursSansFenetre(),
                resultat.controle());
    }

    private static PrevisualisationRecurrence toRecurrenceView(GenerateurCreneaux.Generation resultat) {
        return new PrevisualisationRecurrence(
                resultat.nombreGeneres(),
                resultat.creneaux().stream().map(CreneauMcpTools::toView).toList(),
                resultat.controle());
    }

    @Tool(
            name = "supprimer_creneaux",
            description = "Supprime des créneaux, avec leurs sièges : ceux que ids nomme, ou en une fois ceux que "
                    + "les filtres désignent — l'inverse de appliquer_generateur_creneaux, pour reprendre une règle qui "
                    + "s'est trompée. DESTRUCTIF. ids ou au moins un filtre est exigé, pas les deux ; pour vider toute "
                    + "la grille, passer explicitement all=true.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    BulkDeleteResult deleteCreneaux(
            @ToolArg(description = "Ids des créneaux à supprimer, sans filtre", required = false) List<Long> ids,
            @ToolArg(description = "Ne supprimer qu'à partir de cette date (AAAA-MM-JJ)", required = false)
                    String dateDebut,
            @ToolArg(description = "Ne supprimer que jusqu'à cette date (AAAA-MM-JJ)", required = false) String dateFin,
            @ToolArg(description = "Ne supprimer que les créneaux commençant à cette heure (HH:MM)", required = false)
                    String heureDebut,
            @ToolArg(
                            description =
                                    "Supprimer TOUS les créneaux de l'édition ; à ne passer que sur demande explicite",
                            required = false)
                    Boolean all,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        LocalDate debut = McpArgs.date(dateDebut, ARG_DATE_DEBUT);
        LocalDate fin = McpArgs.date(dateFin, ARG_DATE_FIN);
        LocalTime heure = McpArgs.heure(heureDebut, ARG_HEURE_DEBUT);
        boolean toutSupprimer = Boolean.TRUE.equals(all);
        if (ids != null && !ids.isEmpty()) {
            if (toutSupprimer || debut != null || fin != null || heure != null) {
                throw new BusinessError.Invalid("ids ou filtres, pas les deux");
            }
            return deleteByIds(ids);
        }
        if (!toutSupprimer && debut == null && fin == null && heure == null) {
            throw new BusinessError.Invalid("Aucun filtre fourni : préciser ids, dateDebut, dateFin ou heureDebut, "
                    + "ou passer all=true pour vider délibérément toute la grille.");
        }
        List<Creneau> cibles = referenceDataService.listCreneaux().stream()
                .filter(creneau -> toutSupprimer || correspond(creneau, debut, fin, heure))
                .toList();
        int supprimes = referenceDataService.deleteCreneaux(
                cibles.stream().map(Creneau::getId).toList());
        return new BulkDeleteResult(
                supprimes, referenceDataService.listCreneaux().size());
    }

    /** The créneaux {@code ids} names, all of them or none: an unknown id refuses the whole call. */
    private BulkDeleteResult deleteByIds(List<Long> ids) {
        Set<Long> connus =
                referenceDataService.listCreneaux().stream().map(Creneau::getId).collect(Collectors.toSet());
        List<Long> inconnus =
                ids.stream().filter(id -> !connus.contains(id)).distinct().toList();
        if (!inconnus.isEmpty()) {
            throw new BusinessError.Invalid("Créneaux introuvables : " + inconnus);
        }
        int supprimes = referenceDataService.deleteCreneaux(new LinkedHashSet<>(ids));
        return new BulkDeleteResult(
                supprimes, referenceDataService.listCreneaux().size());
    }

    private static boolean correspond(Creneau creneau, LocalDate debut, LocalDate fin, LocalTime heure) {
        if (debut != null && (creneau.getDate() == null || creneau.getDate().isBefore(debut))) {
            return false;
        }
        if (fin != null && (creneau.getDate() == null || creneau.getDate().isAfter(fin))) {
            return false;
        }
        return heure == null || heure.equals(creneau.getHeureDebut());
    }

    /* -------------------------------- Outils -------------------------------- */

    static RegleRecurrence regle(
            String jours,
            String dateDebut,
            String dateFin,
            List<String> joursSemaine,
            List<String> dates,
            List<String> exclusions,
            String fenetres) {
        return new RegleRecurrence(
                jours == null ? TypeJoursHoraire.TOUS : McpArgs.enumeration(TypeJoursHoraire.class, jours, "jours"),
                McpArgs.date(dateDebut, ARG_DATE_DEBUT),
                McpArgs.date(dateFin, ARG_DATE_FIN),
                McpArgs.joursSemaine(joursSemaine, "joursSemaine"),
                McpArgs.dates(dates, "dates"),
                McpArgs.dates(exclusions, "exclusions"),
                McpArgs.fenetres(fenetres, true));
    }

    private List<CreneauView> creneauxCourants() {
        return referenceDataService.listCreneaux().stream()
                .map(CreneauMcpTools::toView)
                .toList();
    }

    private Creneau findCreneau(long id) {
        return referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null && creneau.getId() == id)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Créneau introuvable : " + id));
    }

    private static WrittenCreneauView written(WrittenCreneau ecrit) {
        return new WrittenCreneauView(toView(ecrit.creneau()), WarningCodes.of(ecrit.avertissements()));
    }

    static CreneauView toView(Creneau creneau) {
        return new CreneauView(
                creneau.getId(),
                creneau.getJour(),
                creneau.getDate(),
                creneau.getHeureDebut(),
                creneau.getHeureFin(),
                creneau.getModifieLe());
    }

    /** A write and its warnings as codes — the REST {@code WrittenCreneau}, seen from MCP. */
    public record WrittenCreneauView(CreneauView creneau, List<String> avertissements)
            implements WarningCarrier<WrittenCreneauView> {

        @Override
        public WrittenCreneauView withWarning(String code) {
            return new WrittenCreneauView(creneau, WarningCodes.with(avertissements, code));
        }
    }

    public record CreneauView(
            Long id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin, Instant modifieLe) {}

    /** What a recurrence rule produced (or would produce), plus the resulting grid's verdict. */
    public record PrevisualisationRecurrence(int nombreGeneres, List<CreneauView> creneaux, RapportGrille controle) {}

    public record BulkDeleteResult(int supprimes, int restants) {}

    /** The grid the stands' hours imply (or what was just written of it), the cuts behind it, and the verdict. */
    public record RapportDerivationMcp(
            int nombreGeneres,
            List<CreneauView> creneaux,
            List<GrilleDepuisFenetres.Coupure> coupures,
            List<java.time.LocalDate> joursSansFenetre,
            RapportGrille controle) {}
}
