package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Demande;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.EtatGenerateur;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.Generation;
import dev.sylvain.planning.service.referentiel.GenerateurCreneaux.RapportApplication;
import dev.sylvain.planning.service.referentiel.GrilleDepuisFenetres;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.referentiel.VacationsLigne;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools of the one timeslot generator (ADR 0126): an assistant describes a
 * kind of day once as a template (« gabarit »), assigns it to dates, then
 * previews and applies a generation — from the templates, from the stands'
 * hours, or from a recurrence typed in one sentence. The twelve 3.x tools of
 * the day templates, the recurrence and the derivation are kept until 5.0 under
 * their names, in {@link JourneeTypeMcpTools} and {@link CreneauMcpTools}.
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class GenerateurCreneauxMcpTools {

    private static final String SOURCES = "gabarits, stands, recurrence ou grille";

    private static final String ARG_SOURCE = "Source : gabarits (le calendrier des gabarits), stands (les horaires "
            + "des stands), recurrence (des fenêtres répétées sur des dates) ou grille (les gabarits que la grille "
            + "actuelle implique)";

    private final GenerateurCreneaux generateur;

    @Inject
    GenerateurCreneauxMcpTools(GenerateurCreneaux generateur) {
        this.generateur = generateur;
    }

    @Tool(
            name = "lister_gabarits",
            description = "Liste les gabarits du générateur de créneaux (les journées types de la 3.x), le "
                    + "calendrier (quel gabarit gouverne quelle date) et les dates en écart : celles dont les créneaux "
                    + "ne correspondent plus à leur gabarit.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView listGabarits(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(generateur.etat());
    }

    @Tool(
            name = "modifier_gabarit",
            description = "Crée ou remplace un gabarit par son nom, et/ou lui affecte des dates. vacations, sur une "
                    + "ligne : « 09:00-12:00, 12:00-13:00, 13:00-14:00, 14:00-20:00 ». "
                    + "Les dates (une plage dateDebut-dateFin et/ou des dates précises) rejoignent le calendrier ; une "
                    + "date déjà affectée change de gabarit. Rien n'est écrit sur la grille avant "
                    + "appliquer_generateur_creneaux avec la source gabarits.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView modifyGabarit(
            @ToolArg(description = "Nom du gabarit, ex. « Jour normal »") String nom,
            @ToolArg(
                            description = "Vacations, ex. « 09:00-12:00, 12:00-13:00 R, 14:00-20:00 » ; absentes, le "
                                    + "gabarit existant garde les siennes",
                            required = false)
                    String vacations,
            @ToolArg(description = "Première date de la plage (AAAA-MM-JJ)", required = false) String dateDebut,
            @ToolArg(description = "Dernière date de la plage (AAAA-MM-JJ), incluse", required = false) String dateFin,
            @ToolArg(description = "Dates précises (AAAA-MM-JJ)", required = false) List<String> dates,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Set<LocalDate> ciblees = dates(dateDebut, dateFin, dates);
        if ((vacations == null || vacations.isBlank()) && ciblees.isEmpty()) {
            throw new BusinessError.Invalid("Rien à modifier : donnez des vacations, des dates, ou les deux");
        }
        Gabarit gabarit = byName(nom);
        if (vacations != null && !vacations.isBlank()) {
            gabarit = define(nom, vacations);
        } else if (gabarit == null) {
            throw new BusinessError.NotFound("Gabarit introuvable : " + nom + " (donnez ses vacations pour le créer)");
        }
        if (!ciblees.isEmpty()) {
            generateur.assign(gabarit.getId(), ciblees);
        }
        return toView(generateur.etat());
    }

    @Tool(
            name = "supprimer_gabarit",
            description = "Avec nom : supprime le gabarit, ses dates ne sont plus gouvernées. Avec dates : retire "
                    + "seulement ces dates du calendrier, quel que soit leur gabarit. Dans les deux cas les créneaux "
                    + "déjà produits restent tels quels.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatView deleteGabarit(
            @ToolArg(description = "Nom du gabarit à supprimer", required = false) String nom,
            @ToolArg(description = "Dates (AAAA-MM-JJ) à retirer du calendrier", required = false) List<String> dates,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        boolean parNom = nom != null && !nom.isBlank();
        boolean parDates = dates != null && !dates.isEmpty();
        if (parNom == parDates) {
            throw new BusinessError.Invalid("Donnez soit nom (supprimer le gabarit), soit dates (les retirer)");
        }
        if (parNom) {
            generateur.delete(required(nom).getId());
            return toView(generateur.etat());
        }
        return toView(generateur.withdraw(McpArgs.dates(dates, "dates")));
    }

    @Computes
    @Tool(
            name = "previsualiser_generateur_creneaux",
            description = "Prévisualise une génération de créneaux et contrôle la grille qui en résulterait, sans "
                    + "RIEN écrire. source gabarits : ce qu'appliquer le calendrier conserverait, mettrait à jour, "
                    + "créerait ou supprimerait (avec les sièges). source stands : la grille que les horaires des "
                    + "stands impliquent sur dateDebut-dateFin, une fenêtre « jusqu'à la fermeture » finissant à "
                    + "heureFermeture (00:00 = minuit). source recurrence : les fenêtres (« 09:00-12:00,14:00-18:00 », "
                    + "fin obligatoire) répétées selon jours (TOUS, JOURS_SEMAINE avec joursSemaine, PLAGE, DATES avec "
                    + "dates), moins les exclusions. source grille : les gabarits que la grille actuelle implique.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    GenerationView previewGeneration(
            @ToolArg(description = ARG_SOURCE) String source,
            @ToolArg(description = "recurrence : fenêtres, ex. « 09:00-12:00,14:00-18:00 »", required = false)
                    String fenetres,
            @ToolArg(description = "recurrence : portée TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false)
                    String jours,
            @ToolArg(description = "stands, recurrence : première date (AAAA-MM-JJ)", required = false)
                    String dateDebut,
            @ToolArg(description = "stands, recurrence : dernière date (AAAA-MM-JJ), incluse", required = false)
                    String dateFin,
            @ToolArg(description = "recurrence : jours de la semaine (MONDAY…SUNDAY)", required = false)
                    List<String> joursSemaine,
            @ToolArg(description = "recurrence : dates (AAAA-MM-JJ) de la portée DATES", required = false)
                    List<String> dates,
            @ToolArg(description = "recurrence : dates (AAAA-MM-JJ) à exclure", required = false)
                    List<String> exclusions,
            @ToolArg(description = "stands : heure de fermeture (HH:MM, 00:00 = minuit)", required = false)
                    String heureFermeture,
            @ToolArg(description = "stands : durée minimale d'un créneau en minutes (défaut 15)", required = false)
                    Integer dureeMinimaleMinutes,
            @ToolArg(description = "stands : true pour juger la grille dérivée seule", required = false)
                    Boolean remplacer,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        if (isGrid(source)) {
            return GenerationView.of(toView(generateur.previewReconnaissance()));
        }
        return toView(generateur.preview(demande(
                source,
                fenetres,
                jours,
                dateDebut,
                dateFin,
                joursSemaine,
                dates,
                exclusions,
                heureFermeture,
                dureeMinimaleMinutes,
                remplacer)));
    }

    @Tool(
            name = "appliquer_generateur_creneaux",
            description = "Écrit une génération de créneaux — mêmes arguments que previsualiser_generateur_creneaux, "
                    + "qu'il faut avoir appelé d'abord. source gabarits : un créneau identique garde son id et ses "
                    + "sièges, un créneau manquant est créé, un créneau que son gabarit ne nomme pas est supprimé AVEC "
                    + "ses sièges ; une date sans gabarit n'est pas touchée. source stands : les créneaux s'AJOUTENT, "
                    + "remplacer=true remplace toute la grille et EFFACE le planning résolu. source recurrence : les "
                    + "créneaux s'ajoutent. source grille : REMPLACE les gabarits et le calendrier par ce que la grille "
                    + "implique, sans toucher aux créneaux.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    GenerationView applyGeneration(
            @ToolArg(description = ARG_SOURCE) String source,
            @ToolArg(description = "recurrence : fenêtres, ex. « 09:00-12:00,14:00-18:00 »", required = false)
                    String fenetres,
            @ToolArg(description = "recurrence : portée TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false)
                    String jours,
            @ToolArg(description = "stands, recurrence : première date (AAAA-MM-JJ)", required = false)
                    String dateDebut,
            @ToolArg(description = "stands, recurrence : dernière date (AAAA-MM-JJ), incluse", required = false)
                    String dateFin,
            @ToolArg(description = "recurrence : jours de la semaine (MONDAY…SUNDAY)", required = false)
                    List<String> joursSemaine,
            @ToolArg(description = "recurrence : dates (AAAA-MM-JJ) de la portée DATES", required = false)
                    List<String> dates,
            @ToolArg(description = "recurrence : dates (AAAA-MM-JJ) à exclure", required = false)
                    List<String> exclusions,
            @ToolArg(description = "stands : heure de fermeture (HH:MM, 00:00 = minuit)", required = false)
                    String heureFermeture,
            @ToolArg(description = "stands : durée minimale d'un créneau en minutes (défaut 15)", required = false)
                    Integer dureeMinimaleMinutes,
            @ToolArg(
                            description = "stands : true pour remplacer toute la grille (efface le planning résolu)",
                            required = false)
                    Boolean remplacer,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        if (isGrid(source)) {
            return GenerationView.of(toView(generateur.reconnaitre()));
        }
        return toView(generateur.apply(demande(
                source,
                fenetres,
                jours,
                dateDebut,
                dateFin,
                joursSemaine,
                dates,
                exclusions,
                heureFermeture,
                dureeMinimaleMinutes,
                remplacer)));
    }

    /* -------------------------------- Outils -------------------------------- */

    private static boolean isGrid(String source) {
        return "grille".equalsIgnoreCase(source == null ? null : source.strip());
    }

    private static Demande demande(
            String source,
            String fenetres,
            String jours,
            String dateDebut,
            String dateFin,
            List<String> joursSemaine,
            List<String> dates,
            List<String> exclusions,
            String heureFermeture,
            Integer dureeMinimaleMinutes,
            Boolean remplacer) {
        if (source == null || source.isBlank()) {
            throw new BusinessError.Invalid("source est requise : " + SOURCES);
        }
        return switch (source.strip().toLowerCase(Locale.ROOT)) {
            case "gabarits" -> Demande.gabarits();
            case "stands" ->
                Demande.stands(
                        CreneauMcpTools.parametresDerivation(dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes),
                        Boolean.TRUE.equals(remplacer));
            case "recurrence" ->
                Demande.recurrence(
                        CreneauMcpTools.regle(jours, dateDebut, dateFin, joursSemaine, dates, exclusions, fenetres));
            default -> throw new BusinessError.Invalid("source inconnue : " + source + " (attendu : " + SOURCES + ")");
        };
    }

    /** A range and/or precise dates, as one set. */
    static Set<LocalDate> dates(String dateDebut, String dateFin, List<String> dates) {
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
        return ciblees;
    }

    /** Creates the template, or replaces the vacations of the one already named so. */
    private Gabarit define(String nom, String vacations) {
        Gabarit gabarit = new Gabarit(null, nom, VacationsLigne.parse(vacations));
        Gabarit existant = byName(nom);
        if (existant == null) {
            return generateur.create(gabarit);
        }
        gabarit.setModifieLe(existant.getModifieLe());
        return generateur.update(existant.getId(), gabarit);
    }

    private Gabarit byName(String nom) {
        if (nom == null || nom.isBlank()) {
            throw new BusinessError.Invalid("nom est requis");
        }
        return generateur.list().stream()
                .filter(gabarit -> gabarit.getNom().equalsIgnoreCase(nom.strip()))
                .findFirst()
                .orElse(null);
    }

    private Gabarit required(String nom) {
        Gabarit gabarit = byName(nom);
        if (gabarit == null) {
            throw new BusinessError.NotFound("Gabarit introuvable : " + nom);
        }
        return gabarit;
    }

    static GabaritView toView(Gabarit gabarit) {
        return new GabaritView(gabarit.getId(), gabarit.getNom(), VacationsLigne.format(gabarit.getVacations()));
    }

    private static List<DateView> calendar(List<Gabarit> gabarits, List<Affectation> calendrier) {
        Map<Long, String> noms = gabarits.stream().collect(Collectors.toMap(Gabarit::getId, Gabarit::getNom));
        return calendrier.stream()
                .map(affectation -> new DateView(affectation.date(), noms.get(affectation.gabaritId())))
                .toList();
    }

    static EtatView toView(EtatGenerateur etat) {
        return new EtatView(
                etat.gabarits().stream().map(GenerateurCreneauxMcpTools::toView).toList(),
                calendar(etat.gabarits(), etat.calendrier()),
                etat.datesEnEcart(),
                etat.datesSousConsigne());
    }

    static ReconnaissanceView toView(Reconnaissance reconnaissance) {
        return new ReconnaissanceView(
                reconnaissance.gabarits().stream()
                        .map(GenerateurCreneauxMcpTools::toView)
                        .toList(),
                calendar(reconnaissance.gabarits(), reconnaissance.calendrier()));
    }

    static ApplicationView toView(RapportApplication rapport) {
        return new ApplicationView(
                rapport.conserves(),
                rapport.crees(),
                rapport.supprimes(),
                rapport.creneauxSupprimes().stream()
                        .map(CreneauMcpTools::toView)
                        .toList(),
                rapport.postesSupprimes(),
                rapport.datesEnEcart(),
                rapport.aucunChangement());
    }

    private static GenerationView toView(Generation generation) {
        return new GenerationView(
                generation.source().name().toLowerCase(Locale.ROOT),
                generation.nombreGeneres(),
                generation.creneaux().stream().map(CreneauMcpTools::toView).toList(),
                generation.application() == null ? null : toView(generation.application()),
                generation.coupures(),
                generation.joursSansFenetre(),
                null,
                generation.controle());
    }

    /** A template as a line an assistant can read back and resend. */
    public record GabaritView(Long id, String nom, String vacations) {}

    /** One date of the calendar and the template that governs it, by name. */
    public record DateView(LocalDate date, String gabarit) {}

    public record EtatView(
            List<GabaritView> gabarits,
            List<DateView> calendrier,
            List<LocalDate> datesEnEcart,
            List<LocalDate> datesSousConsigne) {}

    public record ReconnaissanceView(List<GabaritView> gabarits, List<DateView> calendrier) {}

    public record ApplicationView(
            int conserves,
            int crees,
            int supprimes,
            List<CreneauMcpTools.CreneauView> creneauxSupprimes,
            int postesSupprimes,
            List<LocalDate> datesEnEcart,
            boolean aucunChangement) {}

    /**
     * What a generation produced or would. {@code application} for the
     * templates, {@code coupures} and {@code joursSansFenetre} for the stands,
     * {@code reconnaissance} for the grid.
     */
    public record GenerationView(
            String source,
            int nombreGeneres,
            List<CreneauMcpTools.CreneauView> creneaux,
            ApplicationView application,
            List<GrilleDepuisFenetres.Coupure> coupures,
            List<LocalDate> joursSansFenetre,
            ReconnaissanceView reconnaissance,
            RapportGrille controle) {

        static GenerationView of(ReconnaissanceView reconnaissance) {
            return new GenerationView("grille", 0, List.of(), null, List.of(), List.of(), reconnaissance, null);
        }
    }
}
