package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.CreneauGridService;
import dev.sylvain.planning.service.CreneauGridService.DiagnosticGrille;
import dev.sylvain.planning.service.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.CreneauGridService.RegleRecurrence;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 */
@EditionCiblee
@ApplicationScoped
public class CreneauMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    CreneauGridService grilleCreneauxService;

    @Tool(description = "Liste les créneaux de l'édition — ceux sur lesquels portera la prochaine résolution.")
    List<CreneauView> lister_creneaux(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return creneauxCourants();
    }

    @Tool(description = "Crée un créneau. Le numéro de jour n'est pas à fournir : il est recalculé pour toute "
            + "l'édition à partir des dates.")
    CreneauView creer_creneau(
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)") String heureFin,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Creneau creneau = new Creneau(null, 0, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), McpArgs.heure(heureFin, "heureFin"));
        return toView(referenceDataService.createCreneau(creneau));
    }

    @Tool(description = "Modifie un créneau. Seuls les champs fournis sont modifiés.")
    CreneauView modifier_creneau(
            @ToolArg(description = "Id du créneau") long id,
            @ToolArg(description = "Date (AAAA-MM-JJ)", required = false) String date,
            @ToolArg(description = "Heure de début (HH:MM)", required = false) String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)", required = false) String heureFin,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Creneau creneau = findCreneau(id);
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
    SuppressionResult supprimer_creneau(@ToolArg(description = "Id du créneau") long id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteCreneau(id);
        return new SuppressionResult(String.valueOf(id), true);
    }

    /* ------------------------- Grid: mode and checks ------------------------- */

    @Tool(description = "Décrit la grille de créneaux en place : combien, sur quelles dates, et si ce sont des "
            + "AMPLITUDES journalières (à découper en vacations) ou des VACATIONS déjà solvables. Appeler cet "
            + "outil AVANT de créer des créneaux, pour ne pas mélanger les deux natures dans une même édition. "
            + "Le champ modeCertain dit si la réponse est prouvée par les données ou seulement probable : quand "
            + "il vaut false, demander confirmation à l'utilisateur plutôt que de supposer.")
    DiagnosticGrille diagnostiquer_grille_creneaux(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return CreneauGridService.diagnose(referenceDataService.listCreneaux(),
                referenceDataService.getParametresDecoupage());
    }

    @Tool(description = "Contrôle la cohérence de la grille de créneaux actuelle et signale ce qui cloche : "
            + "doublons, chevauchements, trous dans une journée, durées incohérentes, dates isolées, stands que "
            + "personne ne pourra armer, et sous-effectif. Le mode est OBLIGATOIRE parce qu'il change le verdict : "
            + "deux créneaux qui se chevauchent le même jour sont une erreur de saisie entre AMPLITUDES, et la "
            + "situation normale entre VACATIONS décalées. En cas de doute, appeler diagnostiquer_grille_creneaux "
            + "puis demander à l'utilisateur.")
    RapportGrille valider_creneaux(
            @ToolArg(description = "AMPLITUDES (journées à découper) ou VACATIONS (vacations finales)") String mode,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return validateGrid(referenceDataService.listCreneaux(), modeObligatoire(mode));
    }

    /* ----------------------------- Grid: recurrence -------------------------- */

    @Tool(description = "Prévisualise les créneaux qu'une règle récurrente produirait, et contrôle la grille qui "
            + "en résulterait — sans RIEN écrire. À utiliser systématiquement avant creer_creneaux_recurrents : "
            + "une règle qui se trompe d'une heure crée des dizaines de lignes d'un coup. "
            + "Les fenêtres se saisissent « HH:MM-HH:MM », séparées par des virgules pour une journée en "
            + "plusieurs morceaux (ex. « 09:00-12:00,14:00-18:00 » pour une coupure méridienne) ; contrairement "
            + "aux horaires de stand, l'heure de fin est obligatoire. La portée vaut TOUS (toutes les dates de "
            + "la plage), JOURS_SEMAINE (avec joursSemaine, ex. MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY pour "
            + "« sauf le week-end »), PLAGE (identique à TOUS) ou DATES (avec dates). Les exclusions retirent "
            + "des dates précises quel que soit le sélecteur.")
    PrevisualisationRecurrence previsualiser_creneaux_recurrents(
            @ToolArg(description = "AMPLITUDES ou VACATIONS — demander à l'utilisateur s'il ne l'a pas dit") String mode,
            @ToolArg(description = "Fenêtres, ex. « 09:00-12:00,14:00-18:00 »") String fenetres,
            @ToolArg(description = "Portée : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String jours,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ), bornes incluses", required = false) String dateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ), bornes incluses", required = false) String dateFin,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false) List<String> joursSemaine,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> dates,
            @ToolArg(description = "Dates (AAAA-MM-JJ) à exclure quel que soit le sélecteur", required = false) List<String> exclusions,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ModeGrilleCreneaux modeGrille = modeObligatoire(mode);
        List<Creneau> generes = CreneauGridService.generateRecurrence(
                regle(jours, dateDebut, dateFin, joursSemaine, dates, exclusions, fenetres));
        List<Creneau> resultante = new ArrayList<>(referenceDataService.listCreneaux());
        resultante.addAll(generes);
        return new PrevisualisationRecurrence(generes.size(), generes.stream().map(CreneauMcpTools::toView).toList(),
                validateGrid(resultante, modeGrille));
    }

    @Tool(description = "Crée les créneaux d'une règle récurrente et renvoie le contrôle de cohérence de la "
            + "grille obtenue. Mêmes arguments que previsualiser_creneaux_recurrents, qu'il faut avoir appelé "
            + "d'abord. Les créneaux existants ne sont pas touchés : la règle AJOUTE. Pour repartir de zéro, "
            + "appeler supprimer_creneaux avant.")
    PrevisualisationRecurrence creer_creneaux_recurrents(
            @ToolArg(description = "AMPLITUDES ou VACATIONS — demander à l'utilisateur s'il ne l'a pas dit") String mode,
            @ToolArg(description = "Fenêtres, ex. « 09:00-12:00,14:00-18:00 »") String fenetres,
            @ToolArg(description = "Portée : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String jours,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ), bornes incluses", required = false) String dateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ), bornes incluses", required = false) String dateFin,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false) List<String> joursSemaine,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> dates,
            @ToolArg(description = "Dates (AAAA-MM-JJ) à exclure quel que soit le sélecteur", required = false) List<String> exclusions,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ModeGrilleCreneaux modeGrille = modeObligatoire(mode);
        List<Creneau> generes = CreneauGridService.generateRecurrence(
                regle(jours, dateDebut, dateFin, joursSemaine, dates, exclusions, fenetres));
        List<Creneau> crees = referenceDataService.createCreneaux(generes);
        return new PrevisualisationRecurrence(crees.size(), crees.stream().map(CreneauMcpTools::toView).toList(),
                validateGrid(referenceDataService.listCreneaux(), modeGrille));
    }

    @Tool(description = "Supprime en une fois les créneaux que les filtres désignent — l'inverse de "
            + "creer_creneaux_recurrents, pour reprendre une règle qui s'est trompée. DESTRUCTIF. Au moins un "
            + "filtre est exigé ; pour vider toute la grille, passer explicitement tous=true.")
    BulkDeleteResult supprimer_creneaux(
            @ToolArg(description = "Ne supprimer qu'à partir de cette date (AAAA-MM-JJ)", required = false) String dateDebut,
            @ToolArg(description = "Ne supprimer que jusqu'à cette date (AAAA-MM-JJ)", required = false) String dateFin,
            @ToolArg(description = "Ne supprimer que les créneaux commençant à cette heure (HH:MM)", required = false) String heureDebut,
            @ToolArg(description = "Supprimer TOUS les créneaux de l'édition ; à ne passer que sur demande explicite", required = false) Boolean all,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        LocalDate debut = McpArgs.date(dateDebut, "dateDebut");
        LocalDate fin = McpArgs.date(dateFin, "dateFin");
        LocalTime heure = McpArgs.heure(heureDebut, "heureDebut");
        boolean toutSupprimer = Boolean.TRUE.equals(all);
        if (!toutSupprimer && debut == null && fin == null && heure == null) {
            throw new BusinessError.Invalid("Aucun filtre fourni : préciser dateDebut, dateFin ou heureDebut, "
                    + "ou passer tous=true pour vider délibérément toute la grille.");
        }
        List<Creneau> cibles = referenceDataService.listCreneaux().stream()
                .filter(creneau -> toutSupprimer || correspond(creneau, debut, fin, heure))
                .toList();
        int supprimes = referenceDataService.deleteCreneaux(cibles.stream().map(Creneau::getId).toList());
        return new BulkDeleteResult(supprimes, referenceDataService.listCreneaux().size());
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

    /* -------------------------------- Slicing ------------------------------- */

    @Tool(description = "Prévisualise le découpage : les vacations que les créneaux actuels de l'édition "
            + "(lus comme des amplitudes) produiraient avec les paramètres de découpage courants. "
            + "Ne persiste rien.")
    List<CreneauView> previsualiser_decoupage(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.previewDecoupage().stream()
                .map(CreneauMcpTools::toView)
                .toList();
    }

    @Tool(description = "Génère le découpage EN PLACE : les créneaux actuels de l'édition (les amplitudes) sont "
            + "remplacés par les vacations générées, et le planning résolu est effacé avec eux. Pour re-découper "
            + "avec d'autres paramètres, ré-importer le scénario source.")
    List<CreneauView> generer_decoupage(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.generateDecoupage();
        // Not lister_creneaux(edition): a self-invocation bypasses
        // EditionCibleeInterceptor, and this call already runs in the edition
        // it bound.
        return creneauxCourants();
    }

    /* -------------------------------- Outils -------------------------------- */

    /**
     * The mode has no default on purpose. Guessing it would make
     * {@code valider_creneaux} confidently wrong half the time — see
     * {@link ModeGrilleCreneaux} — so an omitted mode comes back as a question
     * the assistant can relay rather than as a silent assumption.
     */
    private static ModeGrilleCreneaux modeObligatoire(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new BusinessError.Invalid("mode est requis : AMPLITUDES (journées à découper en vacations) "
                    + "ou VACATIONS (vacations finales, solvables telles quelles). Demander à l'utilisateur "
                    + "laquelle des deux il veut ; diagnostiquer_grille_creneaux indique ce que contient déjà "
                    + "l'édition.");
        }
        return McpArgs.enumeration(ModeGrilleCreneaux.class, mode, "mode");
    }

    private static RegleRecurrence regle(String jours, String dateDebut, String dateFin, List<String> joursSemaine,
            List<String> dates, List<String> exclusions, String fenetres) {
        return new RegleRecurrence(
                jours == null ? TypeJoursHoraire.TOUS : McpArgs.enumeration(TypeJoursHoraire.class, jours, "jours"),
                McpArgs.date(dateDebut, "dateDebut"),
                McpArgs.date(dateFin, "dateFin"),
                McpArgs.joursSemaine(joursSemaine, "joursSemaine"),
                McpArgs.dates(dates, "dates"),
                McpArgs.dates(exclusions, "exclusions"),
                McpArgs.fenetres(fenetres, true));
    }

    private RapportGrille validateGrid(List<Creneau> creneaux, ModeGrilleCreneaux mode) {
        return grilleCreneauxService.validate(creneaux, referenceDataService.listSolvedStands(),
                referenceDataService.listAnimateurs(), mode, referenceDataService.getParametresDecoupage(),
                referenceDataService.getParametresLegaux());
    }

    private List<CreneauView> creneauxCourants() {
        return referenceDataService.listCreneaux().stream().map(CreneauMcpTools::toView).toList();
    }

    private Creneau findCreneau(long id) {
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

    /** What a recurrence rule produced (or would produce), plus the resulting grid's verdict. */
    public record PrevisualisationRecurrence(int nombreGeneres, List<CreneauView> creneaux, RapportGrille controle) {
    }

    public record BulkDeleteResult(int supprimes, int restants) {
    }
}
