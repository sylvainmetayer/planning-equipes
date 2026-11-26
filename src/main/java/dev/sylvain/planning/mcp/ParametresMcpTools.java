package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WrittenContrainteAdHoc;
import dev.sylvain.planning.service.solve.SolverBudgetBounds;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for the tuning knobs of {@code ReferenceDataResource}: legal
 * parameters (enforced by the solver), découpage parameters (used when
 * generating vacations) and the solver's default termination duration, plus
 * the ad hoc constraints an operator adds case by case.
 *
 * <p>Ad hoc constraints reference animateurs, so they are reported here by id
 * only, like everything else animateur-related in this package.
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ParametresMcpTools {

    private final ReferenceDataService referenceDataService;

    private final CurrentAction currentAction;

    @Inject
    ParametresMcpTools(ReferenceDataService referenceDataService, CurrentAction currentAction) {
        this.referenceDataService = referenceDataService;
        this.currentAction = currentAction;
    }

    private static final String SECTIONS = "legal, organisation, notifications, instance";

    @Tool(
            name = "consulter_parametres",
            description = "Consulte une section des paramètres de l'édition : « legal » (durées maximales, pauses, "
                    + "repos, coupure repas), « organisation » (les seuils de qualité d'organisation lus par les règles "
                    + "moyennes dosables : emplacements et typologies distincts, jours d'affilée, services tardif et "
                    + "matinal, temps de trajet, arrivée groupée), « notifications » (heure du rappel de la veille, "
                    + "délai de relance, ancienneté d'un échange sans réponse ; rien ne part d'une édition qui n'est "
                    + "pas l'édition active, voir lister_editions) ou « instance » (le budget de calcul : durée "
                    + "maximale d'une résolution et arrêt sur plateau, null = défaut de l'instance, avec les défauts "
                    + "et plafonds de l'exploitant).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresSectionView getParametres(
            @ToolArg(description = "La section : " + SECTIONS) String section,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return new ParametresSectionView(
                section,
                switch (section(section)) {
                    case "legal" -> getParametresLegaux(edition);
                    case "organisation" -> ParametresQualiteView.of(referenceDataService.getParametresQualite());
                    case "notifications" -> referenceDataService.getParametresNotifications();
                    default ->
                        toView(
                                referenceDataService.getParametresSolveur(),
                                referenceDataService.getSolverBudgetBounds());
                });
    }

    @Tool(
            name = "modifier_parametres",
            description = "Modifie une section des paramètres de l'édition (voir consulter_parametres). Seuls les "
                    + "champs fournis sont modifiés, et un champ qui n'est pas de la section demandée est refusé. "
                    + "« legal » : les plafonds d'ordre public (48 h hebdomadaires pour un majeur, 35 h pour un "
                    + "mineur) sont refusés au-delà, et les planchers de pause (20 min pour un majeur, 30 pour un "
                    + "mineur) en deçà. « organisation » : aucun seuil n'est une obligation légale, ils règlent le "
                    + "confort du planning ; le temps de marche entre deux emplacements vaut distance à vol d'oiseau "
                    + "× facteurDetour ÷ vitesseMarcheKmH, et un battement entre deux postes peut en manquer "
                    + "toleranceTrajetMinutes avant que trajetInsuffisantEntrePostes ne compte quoi que ce soit. "
                    + "« notifications » : cet outil n'envoie rien, les envois de nuit ne partent que de l'édition "
                    + "active (activer_edition) ; l'heure du rappel ne peut pas dépasser 23:00. « instance » : une "
                    + "valeur changée au-dessus du plafond de l'instance est refusée, une valeur gardée telle "
                    + "quelle ne l'est pas, même au-dessus, et tourne au plafond ; refusé aussi si le plateau "
                    + "dépasse la durée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ParametresSectionView updateParametres(
            @ToolArg(description = "La section : " + SECTIONS) String section,
            @ToolArg(description = "Durée hebdomadaire maximale d'un majeur, en minutes", required = false)
                    Integer dureeHebdomadaireMaxMinutes,
            @ToolArg(description = "Durée hebdomadaire maximale d'un mineur, en minutes", required = false)
                    Integer dureeHebdomadaireMaxMineurMinutes,
            @ToolArg(
                            description = "Durée maximale d'une vacation, en minutes : au-delà, le contrôle de "
                                    + "grille avertit que la vacation contiendra une pause à relayer (L3121-16)",
                            required = false)
                    Integer dureeVacationMaxMinutes,
            @ToolArg(description = "Repos quotidien minimal, en minutes", required = false)
                    Integer reposQuotidienMinimalMinutes,
            @ToolArg(
                            description = "Durée de la pause légale, en minutes : au moins 20 (L3121-16, d'ordre "
                                    + "public), portée à 30 pour un mineur (L3162-3). Une pause due est soit un "
                                    + "trou d'au moins cette durée dans la grille, soit relayée par un collègue "
                                    + "du même stand ; elle est déduite des plafonds quotidien et hebdomadaire "
                                    + "et de tous les compteurs d'heures",
                            required = false)
                    Integer dureePauseMinutes,
            @ToolArg(description = "Durée de la coupure repas, en minutes", required = false)
                    Integer coupureRepasMinutes,
            @ToolArg(description = "Début de la fenêtre de la coupure repas du midi (HH:MM)", required = false)
                    String coupureRepasMidiDebut,
            @ToolArg(description = "Fin de la fenêtre de la coupure repas du midi (HH:MM)", required = false)
                    String coupureRepasMidiFin,
            @ToolArg(description = "Début de la fenêtre de la coupure repas du soir (HH:MM)", required = false)
                    String coupureRepasSoirDebut,
            @ToolArg(description = "Fin de la fenêtre de la coupure repas du soir (HH:MM)", required = false)
                    String coupureRepasSoirFin,
            @ToolArg(
                            description = "Heure à partir de laquelle un poste compte des heures de soirée "
                                    + "dans le tableau d'équité (HH:MM)",
                            required = false)
                    String heureDebutSoiree,
            @ToolArg(description = "Emplacements distincts par jour et par animateur, au moins 1", required = false)
                    Integer maxEmplacementsDistinctsParJour,
            @ToolArg(description = "Typologies distinctes par animateur sur l'édition, au moins 1", required = false)
                    Integer typologiesDistinctesMax,
            @ToolArg(description = "Jours travaillés d'affilée, au moins 1", required = false)
                    Integer joursConsecutifsMax,
            @ToolArg(description = "Heure d'un service tardif (HH:MM)", required = false) String heureServiceTardif,
            @ToolArg(description = "Heure d'un service matinal (HH:MM)", required = false) String heureServiceMatinal,
            @ToolArg(description = "Repos souhaité après un service tardif, en minutes", required = false)
                    Integer reposSouhaiteApresServiceTardifMinutes,
            @ToolArg(description = "Vitesse de marche, en km/h (au plus 15)", required = false) Double vitesseMarcheKmH,
            @ToolArg(description = "Facteur de détour, entre 1 et 5", required = false) Double facteurDetour,
            @ToolArg(description = "Tolérance de trajet, en minutes (0 à 120)", required = false)
                    Integer toleranceTrajetMinutes,
            @ToolArg(
                            description = "Tolérance d'une arrivée groupée, en minutes (0 à 240) : l'écart "
                                    + "d'arrivée ou de départ toléré entre les membres d'un covoiturage",
                            required = false)
                    Integer toleranceArriveeGroupeeMinutes,
            @ToolArg(
                            description = "Durée maximale d'une résolution, en secondes (strictement positive)",
                            required = false)
                    Integer dureeResolutionSecondes,
            @ToolArg(
                            description = "Arrêt si le planning, déjà faisable, ne s'améliore plus depuis ce nombre "
                                    + "de secondes ; 0 = jamais",
                            required = false)
                    Integer plateauSecondes,
            @ToolArg(description = "true : durée et plateau reviennent au défaut de l'instance", required = false)
                    Boolean revenirAuDefaut,
            @ToolArg(description = "Heure du rappel de la veille (HH:MM), au plus tard 23:00", required = false)
                    String heureRappelVeille,
            @ToolArg(description = "Délai avant relance des non-confirmés, en heures", required = false)
                    Integer delaiRelanceHeures,
            @ToolArg(
                            description = "Ancienneté d'une demande d'échange sans réponse avant alerte, en jours",
                            required = false)
                    Integer ancienneteEchangeJours,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("dureeHebdomadaireMaxMinutes", dureeHebdomadaireMaxMinutes);
        given.put("dureeHebdomadaireMaxMineurMinutes", dureeHebdomadaireMaxMineurMinutes);
        given.put("dureeVacationMaxMinutes", dureeVacationMaxMinutes);
        given.put("reposQuotidienMinimalMinutes", reposQuotidienMinimalMinutes);
        given.put("dureePauseMinutes", dureePauseMinutes);
        given.put("coupureRepasMinutes", coupureRepasMinutes);
        given.put("coupureRepasMidiDebut", coupureRepasMidiDebut);
        given.put("coupureRepasMidiFin", coupureRepasMidiFin);
        given.put("coupureRepasSoirDebut", coupureRepasSoirDebut);
        given.put("coupureRepasSoirFin", coupureRepasSoirFin);
        given.put("heureDebutSoiree", heureDebutSoiree);
        given.put("maxEmplacementsDistinctsParJour", maxEmplacementsDistinctsParJour);
        given.put("typologiesDistinctesMax", typologiesDistinctesMax);
        given.put("joursConsecutifsMax", joursConsecutifsMax);
        given.put("heureServiceTardif", heureServiceTardif);
        given.put("heureServiceMatinal", heureServiceMatinal);
        given.put("reposSouhaiteApresServiceTardifMinutes", reposSouhaiteApresServiceTardifMinutes);
        given.put("vitesseMarcheKmH", vitesseMarcheKmH);
        given.put("facteurDetour", facteurDetour);
        given.put("toleranceTrajetMinutes", toleranceTrajetMinutes);
        given.put("toleranceArriveeGroupeeMinutes", toleranceArriveeGroupeeMinutes);
        given.put("dureeResolutionSecondes", dureeResolutionSecondes);
        given.put("plateauSecondes", plateauSecondes);
        given.put("revenirAuDefaut", revenirAuDefaut);
        given.put("heureRappelVeille", heureRappelVeille);
        given.put("delaiRelanceHeures", delaiRelanceHeures);
        given.put("ancienneteEchangeJours", ancienneteEchangeJours);
        String chosen = section(section);
        // The journal line names the section, as the REST route of that section does.
        switch (chosen) {
            case "legal" -> currentAction.action("PARAMETRES_LEGAUX_MODIFIES");
            case "organisation" -> currentAction.action("PARAMETRES_QUALITE_MODIFIES");
            case "notifications" -> currentAction.action("PARAMETRES_NOTIFICATIONS_MODIFIES");
            default -> currentAction.action("PARAMETRES_SOLVEUR_MODIFIES");
        }
        given.values().removeIf(Objects::isNull);
        given.keySet().stream()
                .filter(name -> !OWN_ARGUMENTS.get(chosen).contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new BusinessError.Invalid("L'argument « " + name + " » n'est pas de la section « " + chosen
                            + " » : voir la" + " description de l'outil, ou passez par la section qui le porte.");
                });
        return new ParametresSectionView(
                chosen,
                switch (chosen) {
                    case "legal" ->
                        updateParametresLegaux(
                                dureeHebdomadaireMaxMinutes,
                                dureeHebdomadaireMaxMineurMinutes,
                                dureeVacationMaxMinutes,
                                reposQuotidienMinimalMinutes,
                                dureePauseMinutes,
                                coupureRepasMinutes,
                                coupureRepasMidiDebut,
                                coupureRepasMidiFin,
                                coupureRepasSoirDebut,
                                coupureRepasSoirFin,
                                heureDebutSoiree,
                                edition);
                    case "organisation" ->
                        updateParametresQualite(
                                maxEmplacementsDistinctsParJour,
                                typologiesDistinctesMax,
                                joursConsecutifsMax,
                                heureServiceTardif,
                                heureServiceMatinal,
                                reposSouhaiteApresServiceTardifMinutes,
                                vitesseMarcheKmH,
                                facteurDetour,
                                toleranceTrajetMinutes,
                                toleranceArriveeGroupeeMinutes,
                                edition);
                    case "notifications" ->
                        updateParametresNotifications(
                                heureRappelVeille, delaiRelanceHeures, ancienneteEchangeJours, edition);
                    default ->
                        updateParametresSolveur(dureeResolutionSecondes, plateauSecondes, revenirAuDefaut, edition);
                });
    }

    /** What each section accepts, for the refusal of a field that belongs to another. */
    private static final Map<String, Set<String>> OWN_ARGUMENTS = Map.of(
            "legal",
                    Set.of(
                            "dureeHebdomadaireMaxMinutes",
                            "dureeHebdomadaireMaxMineurMinutes",
                            "dureeVacationMaxMinutes",
                            "reposQuotidienMinimalMinutes",
                            "dureePauseMinutes",
                            "coupureRepasMinutes",
                            "coupureRepasMidiDebut",
                            "coupureRepasMidiFin",
                            "coupureRepasSoirDebut",
                            "coupureRepasSoirFin",
                            "heureDebutSoiree"),
            "organisation",
                    Set.of(
                            "maxEmplacementsDistinctsParJour",
                            "typologiesDistinctesMax",
                            "joursConsecutifsMax",
                            "heureServiceTardif",
                            "heureServiceMatinal",
                            "reposSouhaiteApresServiceTardifMinutes",
                            "vitesseMarcheKmH",
                            "facteurDetour",
                            "toleranceTrajetMinutes",
                            "toleranceArriveeGroupeeMinutes"),
            "notifications", Set.of("heureRappelVeille", "delaiRelanceHeures", "ancienneteEchangeJours"),
            "instance", Set.of("dureeResolutionSecondes", "plateauSecondes", "revenirAuDefaut"));

    private static String section(String section) {
        String chosen = section == null ? "" : section.strip().toLowerCase(java.util.Locale.ROOT);
        if (!OWN_ARGUMENTS.containsKey(chosen)) {
            throw new BusinessError.Invalid("Section inconnue : « " + section + " ». Sections : " + SECTIONS + ".");
        }
        return chosen;
    }

    /**
     * One section as the tools answer it. A section whose view carries
     * warnings ({@link WarningCarrier}) keeps them inside it; notifications,
     * which no solve reads, never carry one.
     */
    public record ParametresSectionView(String section, Object parametres)
            implements WarningCarrier<ParametresSectionView> {

        @Override
        public ParametresSectionView withWarning(String code) {
            return parametres instanceof WarningCarrier<?> carrier
                    ? new ParametresSectionView(section, carrier.withWarning(code))
                    : this;
        }
    }

    /* ----------------------------- Per-section helpers ------------------------- */

    ParametresLegauxView getParametresLegaux(String edition) {
        return toView(referenceDataService.getParametresLegaux());
    }

    ParametresLegauxView updateParametresLegaux(
            Integer dureeHebdomadaireMaxMinutes,
            Integer dureeHebdomadaireMaxMineurMinutes,
            Integer dureeVacationMaxMinutes,
            Integer reposQuotidienMinimalMinutes,
            Integer dureePauseMinutes,
            Integer coupureRepasMinutes,
            String coupureRepasMidiDebut,
            String coupureRepasMidiFin,
            String coupureRepasSoirDebut,
            String coupureRepasSoirFin,
            String heureDebutSoiree,
            String edition) {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        if (dureeHebdomadaireMaxMinutes != null) {
            parametres.setDureeHebdomadaireMaxMinutes(dureeHebdomadaireMaxMinutes);
        }
        if (dureeHebdomadaireMaxMineurMinutes != null) {
            parametres.setDureeHebdomadaireMaxMineurMinutes(dureeHebdomadaireMaxMineurMinutes);
        }
        if (dureeVacationMaxMinutes != null) {
            parametres.setDureeVacationMaxMinutes(dureeVacationMaxMinutes);
        }
        if (reposQuotidienMinimalMinutes != null) {
            parametres.setReposQuotidienMinimalMinutes(reposQuotidienMinimalMinutes);
        }
        if (dureePauseMinutes != null) {
            parametres.setDureePauseMinutes(dureePauseMinutes);
        }
        if (coupureRepasMinutes != null) {
            parametres.setCoupureRepasMinutes(coupureRepasMinutes);
        }
        if (coupureRepasMidiDebut != null) {
            parametres.setCoupureRepasMidiDebut(McpArgs.heure(coupureRepasMidiDebut, "coupureRepasMidiDebut"));
        }
        if (coupureRepasMidiFin != null) {
            parametres.setCoupureRepasMidiFin(McpArgs.heure(coupureRepasMidiFin, "coupureRepasMidiFin"));
        }
        if (coupureRepasSoirDebut != null) {
            parametres.setCoupureRepasSoirDebut(McpArgs.heure(coupureRepasSoirDebut, "coupureRepasSoirDebut"));
        }
        if (coupureRepasSoirFin != null) {
            parametres.setCoupureRepasSoirFin(McpArgs.heure(coupureRepasSoirFin, "coupureRepasSoirFin"));
        }
        if (heureDebutSoiree != null) {
            parametres.setHeureDebutSoiree(McpArgs.heure(heureDebutSoiree, "heureDebutSoiree"));
        }
        return toView(referenceDataService.updateParametresLegaux(parametres));
    }

    /* ---------------------------- Quality parameters ------------------------ */

    ParametresQualiteView getParametresQualite(String edition) {
        return ParametresQualiteView.of(referenceDataService.getParametresQualite());
    }

    ParametresQualiteView updateParametresQualite(
            Integer maxEmplacementsDistinctsParJour,
            Integer typologiesDistinctesMax,
            Integer joursConsecutifsMax,
            String heureServiceTardif,
            String heureServiceMatinal,
            Integer reposSouhaiteApresServiceTardifMinutes,
            Double vitesseMarcheKmH,
            Double facteurDetour,
            Integer toleranceTrajetMinutes,
            Integer toleranceArriveeGroupeeMinutes,
            String edition) {
        ParametresQualite actuels = referenceDataService.getParametresQualite();
        ParametresQualite nouveaux = new ParametresQualite(
                maxEmplacementsDistinctsParJour == null
                        ? actuels.maxEmplacementsDistinctsParJour()
                        : maxEmplacementsDistinctsParJour,
                heureServiceTardif == null
                        ? actuels.heureServiceTardif()
                        : McpArgs.heure(heureServiceTardif, "heureServiceTardif"),
                heureServiceMatinal == null
                        ? actuels.heureServiceMatinal()
                        : McpArgs.heure(heureServiceMatinal, "heureServiceMatinal"),
                reposSouhaiteApresServiceTardifMinutes == null
                        ? actuels.reposSouhaiteApresServiceTardifMinutes()
                        : reposSouhaiteApresServiceTardifMinutes,
                typologiesDistinctesMax == null ? actuels.typologiesDistinctesMax() : typologiesDistinctesMax,
                joursConsecutifsMax == null ? actuels.joursConsecutifsMax() : joursConsecutifsMax,
                vitesseMarcheKmH == null ? actuels.vitesseMarcheKmH() : vitesseMarcheKmH,
                facteurDetour == null ? actuels.facteurDetour() : facteurDetour,
                toleranceTrajetMinutes == null ? actuels.toleranceTrajetMinutes() : toleranceTrajetMinutes,
                toleranceArriveeGroupeeMinutes == null
                        ? actuels.toleranceArriveeGroupeeMinutes()
                        : toleranceArriveeGroupeeMinutes);
        return ParametresQualiteView.of(referenceDataService.updateParametresQualite(nouveaux));
    }

    /** The quality thresholds, and the codes of what the write raised. */
    public record ParametresQualiteView(
            int maxEmplacementsDistinctsParJour,
            int typologiesDistinctesMax,
            int joursConsecutifsMax,
            LocalTime heureServiceTardif,
            LocalTime heureServiceMatinal,
            int reposSouhaiteApresServiceTardifMinutes,
            double vitesseMarcheKmH,
            double facteurDetour,
            int toleranceTrajetMinutes,
            int toleranceArriveeGroupeeMinutes,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<ParametresQualiteView> {

        static ParametresQualiteView of(ParametresQualite parametres) {
            return new ParametresQualiteView(
                    parametres.maxEmplacementsDistinctsParJour(),
                    parametres.typologiesDistinctesMax(),
                    parametres.joursConsecutifsMax(),
                    parametres.heureServiceTardif(),
                    parametres.heureServiceMatinal(),
                    parametres.reposSouhaiteApresServiceTardifMinutes(),
                    parametres.vitesseMarcheKmH(),
                    parametres.facteurDetour(),
                    parametres.toleranceTrajetMinutes(),
                    parametres.toleranceArriveeGroupeeMinutes(),
                    List.of());
        }

        @Override
        public ParametresQualiteView withWarning(String code) {
            return new ParametresQualiteView(
                    maxEmplacementsDistinctsParJour,
                    typologiesDistinctesMax,
                    joursConsecutifsMax,
                    heureServiceTardif,
                    heureServiceMatinal,
                    reposSouhaiteApresServiceTardifMinutes,
                    vitesseMarcheKmH,
                    facteurDetour,
                    toleranceTrajetMinutes,
                    toleranceArriveeGroupeeMinutes,
                    WarningCodes.with(avertissements, code));
        }
    }

    /* ---------------------------- Solver parameters ------------------------- */

    ParametresSolveurView getParametresSolveur(String edition) {
        return toView(referenceDataService.getParametresSolveur(), referenceDataService.getSolverBudgetBounds());
    }

    ParametresSolveurView updateParametresSolveur(
            Integer dureeResolutionSecondes, Integer plateauSecondes, Boolean revenirAuDefaut, String edition) {
        // The mail switch is not tunable here; it is kept as is.
        ParametresSolveur actuels = referenceDataService.getParametresSolveur();
        boolean defaut = Boolean.TRUE.equals(revenirAuDefaut);
        ParametresSolveur voulus = new ParametresSolveur(
                defaut ? null : givenOr(dureeResolutionSecondes, actuels.dureeResolutionSecondes()),
                defaut ? null : givenOr(plateauSecondes, actuels.plateauSecondes()),
                actuels.mailFinResolution());
        return toView(
                referenceDataService.updateParametresSolveur(voulus), referenceDataService.getSolverBudgetBounds());
    }

    /* ------------------------- Notification parameters ---------------------- */

    ParametresNotifications getParametresNotifications(String edition) {
        return referenceDataService.getParametresNotifications();
    }

    ParametresNotifications updateParametresNotifications(
            String heureRappelVeille, Integer delaiRelanceHeures, Integer ancienneteEchangeJours, String edition) {
        ParametresNotifications actuels = referenceDataService.getParametresNotifications();
        return referenceDataService.updateParametresNotifications(new ParametresNotifications(
                heureRappelVeille == null
                        ? actuels.heureRappelVeille()
                        : McpArgs.heure(heureRappelVeille, "heureRappelVeille"),
                delaiRelanceHeures == null ? actuels.delaiRelanceHeures() : delaiRelanceHeures,
                ancienneteEchangeJours == null ? actuels.ancienneteEchangeJours() : ancienneteEchangeJours));
    }

    /* -------------------------- Contraintes ad hoc -------------------------- */

    @Tool(
            name = "lister_contraintes_ad_hoc",
            description = "Liste les contraintes ad hoc saisies au cas par cas (indisponibilité forcée, "
                    + "incompatibilité entre animateurs, affectation forcée, affinité entre animateurs, arrivée groupée) : "
                    + "des règles posées "
                    + "avant le calcul pour placer ou écarter quelqu'un, à distinguer des verrouillages qui figent après coup. "
                    + "Les animateurs y sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ContrainteAdHocView> listContraintesAdHoc(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return referenceDataService.listContraintesAdHoc().stream()
                .map(ParametresMcpTools::toView)
                .toList();
    }

    @Tool(
            name = "creer_contrainte_ad_hoc",
            description = "Crée une contrainte ad hoc. INDISPONIBILITE_FORCEE, INCOMPATIBILITE et "
                    + "AFFECTATION_FORCEE sont évaluées par le solveur au même niveau HARD que les contraintes légales ; "
                    + "AFFINITE est une récompense SOFT. INDISPONIBILITE_FORCEE : l'animateur ne peut pas être affecté "
                    + "sur ce créneau. INCOMPATIBILITE : les animateurs listés ne peuvent pas être affectés au même stand "
                    + "sur le même créneau. AFFECTATION_FORCEE : l'animateur doit être affecté à ce stand sur ce créneau. "
                    + "AFFINITE : privilégier, sans l'imposer, les créneaux où les deux animateurs listés tiennent le "
                    + "même stand ; refusée si la même paire est déjà déclarée incompatible (et réciproquement). "
                    + "ARRIVEE_GROUPEE (SOFT) : 2 à 4 animateurs qui arrivent et repartent ensemble (covoiturage), "
                    + "sans créneau ni stand ; refusée si deux de ses membres sont déclarés incompatibles. "
                    + "Son id (C suivi d'un nombre) est attribué par l'application et figure dans la réponse.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    @WarnsWhileSolving
    WrittenContrainteAdHocView createContrainteAdHoc(
            @ToolArg(
                            description = "Type : INDISPONIBILITE_FORCEE, INCOMPATIBILITE, AFFECTATION_FORCEE, "
                                    + "AFFINITE ou ARRIVEE_GROUPEE")
                    String type,
            @ToolArg(description = "Ids des animateurs concernés") List<String> animateurIds,
            @ToolArg(description = "Id du créneau concerné", required = false) Long creneauId,
            @ToolArg(description = "Id du stand concerné", required = false) String standId,
            @ToolArg(description = "Raison, purement informative", required = false) String raison,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        ContrainteAdHoc contrainte =
                new ContrainteAdHoc(null, McpArgs.enumeration(TypeContrainteAdHoc.class, type, "type"));
        contrainte.setAnimateursConcernes(animateurs(animateurIds));
        if (creneauId != null) {
            contrainte.setCreneau(referenceDataService.listCreneaux().stream()
                    .filter(creneau ->
                            creneau.getId() != null && creneau.getId().equals(creneauId))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Créneau introuvable : " + creneauId)));
        }
        if (standId != null) {
            contrainte.setStand(referenceDataService
                    .findStand(standId)
                    .orElseThrow(() -> new NoSuchElementException("Stand introuvable : " + standId)));
        }
        contrainte.setRaison(raison);
        contrainte.setCreeParUtilisateurId("mcp");
        WrittenContrainteAdHoc ecrite = referenceDataService.writeContrainteAdHoc(contrainte);
        return new WrittenContrainteAdHocView(toView(ecrite.contrainte()), WarningCodes.of(ecrite.avertissements()));
    }

    /** The exception written, and the codes of what it raised — the sentence stays on the screen. */
    public record WrittenContrainteAdHocView(ContrainteAdHocView contrainte, List<String> avertissements)
            implements WarningCarrier<WrittenContrainteAdHocView> {

        @Override
        public WrittenContrainteAdHocView withWarning(String code) {
            return new WrittenContrainteAdHocView(contrainte, WarningCodes.with(avertissements, code));
        }
    }

    @Tool(
            name = "supprimer_contrainte_ad_hoc",
            description = "Supprime une contrainte ad hoc. Refusé pour une ARRIVEE_GROUPEE issue d'une "
                    + "demande de covoiturage validée : elle s'annule depuis l'onglet Covoiturage de l'écran "
                    + "Disponibilités, qui prévient le groupe.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    @WarnsWhileSolving
    SuppressionResult deleteContrainteAdHoc(
            @ToolArg(description = "Id de la contrainte") String id,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        referenceDataService.deleteContrainteAdHoc(id);
        return new SuppressionResult(id, true);
    }

    /** The value the caller gave, or the current one when it gave none. */
    private static Integer givenOr(Integer given, Integer current) {
        return given != null ? given : current;
    }

    private List<Animateur> animateurs(List<String> animateurIds) {
        if (animateurIds == null) {
            return new ArrayList<>();
        }
        List<Animateur> connus = referenceDataService.listAnimateurs();
        return animateurIds.stream()
                .map(animateurId -> connus.stream()
                        .filter(animateur -> animateur.getId().equals(animateurId))
                        .findFirst()
                        .orElseThrow(() -> new NoSuchElementException("Animateur introuvable : " + animateurId)))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    static ParametresLegauxView toView(ParametresLegaux parametres) {
        return new ParametresLegauxView(
                parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.getDureeHebdomadaireMaxMineurMinutes(),
                parametres.getDureeVacationMaxMinutes(),
                parametres.getReposQuotidienMinimalMinutes(),
                parametres.getDureePauseMinutes(),
                parametres.getCoupureRepasMinutes(),
                parametres.getCoupureRepasMidiDebut(),
                parametres.getCoupureRepasMidiFin(),
                parametres.getCoupureRepasSoirDebut(),
                parametres.getCoupureRepasSoirFin(),
                parametres.getHeureDebutSoiree());
    }

    static ParametresSolveurView toView(ParametresSolveur parametres, SolverBudgetBounds bounds) {
        return new ParametresSolveurView(
                parametres.dureeResolutionSecondes(), parametres.plateauSecondes(), bounds, List.of());
    }

    static ContrainteAdHocView toView(ContrainteAdHoc contrainte) {
        return new ContrainteAdHocView(
                contrainte.getId(),
                contrainte.getType(),
                contrainte.getAnimateursConcernes().stream()
                        .map(Animateur::getId)
                        .toList(),
                contrainte.getCreneau() == null ? null : contrainte.getCreneau().getId(),
                contrainte.getStand() == null ? null : contrainte.getStand().getId(),
                TextesLibres.renseigne(contrainte.getRaison()),
                contrainte.getCreeParUtilisateurId(),
                contrainte.getCreeLe());
    }

    public record ParametresLegauxView(
            int dureeHebdomadaireMaxMinutes,
            int dureeHebdomadaireMaxMineurMinutes,
            int dureeVacationMaxMinutes,
            int reposQuotidienMinimalMinutes,
            int dureePauseMinutes,
            int coupureRepasMinutes,
            LocalTime coupureRepasMidiDebut,
            LocalTime coupureRepasMidiFin,
            LocalTime coupureRepasSoirDebut,
            LocalTime coupureRepasSoirFin,
            LocalTime heureDebutSoiree,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<ParametresLegauxView> {

        ParametresLegauxView(
                int dureeHebdomadaireMaxMinutes,
                int dureeHebdomadaireMaxMineurMinutes,
                int dureeVacationMaxMinutes,
                int reposQuotidienMinimalMinutes,
                int dureePauseMinutes,
                int coupureRepasMinutes,
                LocalTime coupureRepasMidiDebut,
                LocalTime coupureRepasMidiFin,
                LocalTime coupureRepasSoirDebut,
                LocalTime coupureRepasSoirFin,
                LocalTime heureDebutSoiree) {
            this(
                    dureeHebdomadaireMaxMinutes,
                    dureeHebdomadaireMaxMineurMinutes,
                    dureeVacationMaxMinutes,
                    reposQuotidienMinimalMinutes,
                    dureePauseMinutes,
                    coupureRepasMinutes,
                    coupureRepasMidiDebut,
                    coupureRepasMidiFin,
                    coupureRepasSoirDebut,
                    coupureRepasSoirFin,
                    heureDebutSoiree,
                    List.of());
        }

        @Override
        public ParametresLegauxView withWarning(String code) {
            return new ParametresLegauxView(
                    dureeHebdomadaireMaxMinutes,
                    dureeHebdomadaireMaxMineurMinutes,
                    dureeVacationMaxMinutes,
                    reposQuotidienMinimalMinutes,
                    dureePauseMinutes,
                    coupureRepasMinutes,
                    coupureRepasMidiDebut,
                    coupureRepasMidiFin,
                    coupureRepasSoirDebut,
                    coupureRepasSoirFin,
                    heureDebutSoiree,
                    WarningCodes.with(avertissements, code));
        }
    }

    /**
     * The edition's budget — {@code null} follows the instance — and the
     * instance's defaults and ceilings.
     */
    public record ParametresSolveurView(
            Integer dureeResolutionSecondes,
            Integer plateauSecondes,
            SolverBudgetBounds instance,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<ParametresSolveurView> {

        @Override
        public ParametresSolveurView withWarning(String code) {
            return new ParametresSolveurView(
                    dureeResolutionSecondes, plateauSecondes, instance, WarningCodes.with(avertissements, code));
        }
    }

    /** Whether a reason was given, never what it says: see {@link TextesLibres}. */
    public record ContrainteAdHocView(
            String id,
            TypeContrainteAdHoc type,
            List<String> animateurIds,
            Long creneauId,
            String standId,
            boolean raisonRenseignee,
            String creeParUtilisateurId,
            Instant creeLe) {}
}
