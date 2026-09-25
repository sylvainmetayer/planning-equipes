package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
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
import java.util.List;
import java.util.NoSuchElementException;

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

    @Inject
    ParametresMcpTools(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    /* ----------------------------- Legal parameters ------------------------- */

    @Tool(
            name = "consulter_parametres_legaux",
            description = "Consulte les paramètres légaux appliqués par le solveur (durées maximales, pauses, repos).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresLegauxView getParametresLegaux(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresLegaux());
    }

    @Tool(
            name = "modifier_parametres_legaux",
            description = "Modifie les paramètres légaux. Seuls les champs fournis sont modifiés. Les plafonds "
                    + "d'ordre public (48 h hebdomadaires pour un majeur, 35 h pour un mineur) sont refusés "
                    + "au-delà, et les planchers de pause (20 min pour un majeur, 30 pour un mineur) en deçà.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ParametresLegauxView updateParametresLegaux(
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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

    @Tool(
            name = "consulter_parametres_qualite",
            description = "Consulte les seuils de « Qualité d'organisation » de l'édition, lus par les règles "
                    + "moyennes dosables : emplacements distincts par jour, typologies distinctes par animateur, "
                    + "jours travaillés d'affilée, heures d'un service tardif et d'un service matinal avec le repos "
                    + "souhaité entre les deux, et le temps de trajet entre emplacements (vitesse de marche en km/h, "
                    + "facteur de détour, tolérance en minutes), et la tolérance d'une arrivée groupée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresQualiteView getParametresQualite(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return ParametresQualiteView.of(referenceDataService.getParametresQualite());
    }

    @Tool(
            name = "modifier_parametres_qualite",
            description = "Modifie les seuils de « Qualité d'organisation ». Seuls les champs fournis sont "
                    + "modifiés. Aucun n'est une obligation légale : ils règlent le confort du planning. Le temps de "
                    + "marche entre deux emplacements vaut distance à vol d'oiseau × facteurDetour ÷ "
                    + "vitesseMarcheKmH ; un battement entre deux postes peut en manquer toleranceTrajetMinutes "
                    + "avant que trajetInsuffisantEntrePostes ne compte quoi que ce soit.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ParametresQualiteView updateParametresQualite(
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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

    @Tool(
            name = "consulter_parametres_solveur",
            description = "Consulte le budget de calcul de l'édition : durée maximale d'une résolution et arrêt "
                    + "sur plateau (secondes sans amélioration d'un planning déjà faisable, 0 = jamais), null = défaut "
                    + "de l'instance ; et, sous instance, les défauts et les plafonds fixés par l'exploitant.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresSolveurView getParametresSolveur(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresSolveur(), referenceDataService.getSolverBudgetBounds());
    }

    @Tool(
            name = "modifier_parametres_solveur",
            description = "Modifie le budget de calcul de l'édition. Un argument absent garde sa valeur. Une "
                    + "valeur changée au-dessus du plafond de l'instance est refusée (voir "
                    + "consulter_parametres_solveur) ; une valeur gardée telle quelle ne l'est pas, même au-dessus, "
                    + "et tourne au plafond. Refusé aussi si le plateau dépasse la durée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    ParametresSolveurView updateParametresSolveur(
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        // The mail switch is not tunable here; it is kept as is.
        ParametresSolveur actuels = referenceDataService.getParametresSolveur();
        boolean defaut = Boolean.TRUE.equals(revenirAuDefaut);
        ParametresSolveur voulus = new ParametresSolveur(
                defaut
                        ? null
                        : dureeResolutionSecondes != null ? dureeResolutionSecondes : actuels.dureeResolutionSecondes(),
                defaut ? null : plateauSecondes != null ? plateauSecondes : actuels.plateauSecondes(),
                actuels.mailFinResolution());
        return toView(
                referenceDataService.updateParametresSolveur(voulus), referenceDataService.getSolverBudgetBounds());
    }

    /* ------------------------- Notification parameters ---------------------- */

    @Tool(
            name = "consulter_parametres_notifications",
            description = "Consulte ce que les notifications planifiées ont le droit de faire sur l'édition : "
                    + "l'interrupteur, l'heure du rappel de la veille, le délai avant relance des animateurs qui n'ont "
                    + "pas confirmé, et l'ancienneté à partir de laquelle une demande d'échange sans réponse est "
                    + "signalée. Une édition que personne n'a armée répond les valeurs par défaut, actives à faux.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresNotifications getParametresNotifications(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.getParametresNotifications();
    }

    @Tool(
            name = "modifier_parametres_notifications",
            description = "Modifie les paramètres des notifications planifiées. Seuls les champs fournis sont "
                    + "modifiés. actives=true ARME DES ENVOIS DE COURRIELS automatiques nocturnes (rappel de la veille, "
                    + "relance des non-confirmés, alerte sur les échanges sans réponse) : cet outil n'envoie rien "
                    + "lui-même, il autorise le planificateur à le faire. L'heure du rappel ne peut pas dépasser "
                    + "23:00 — plus tard, la tâche horaire passerait par-dessus et le rappel ne partirait jamais.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresNotifications updateParametresNotifications(
            @ToolArg(description = "Notifications planifiées actives ou non", required = false) Boolean actives,
            @ToolArg(description = "Heure du rappel de la veille (HH:MM), au plus tard 23:00", required = false)
                    String heureRappelVeille,
            @ToolArg(description = "Délai avant relance des non-confirmés, en heures", required = false)
                    Integer delaiRelanceHeures,
            @ToolArg(
                            description = "Ancienneté d'une demande d'échange sans réponse avant alerte, en jours",
                            required = false)
                    Integer ancienneteEchangeJours,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ParametresNotifications actuels = referenceDataService.getParametresNotifications();
        return referenceDataService.updateParametresNotifications(new ParametresNotifications(
                actives == null ? actuels.actives() : actives,
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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
            contrainte.setStand(referenceDataService.listStands().stream()
                    .filter(stand -> stand.getId().equals(standId))
                    .findFirst()
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
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteContrainteAdHoc(id);
        return new SuppressionResult(id, true);
    }

    private List<Animateur> animateurs(List<String> animateurIds) {
        List<Animateur> animateurs = new ArrayList<>();
        if (animateurIds == null) {
            return animateurs;
        }
        List<Animateur> connus = referenceDataService.listAnimateurs();
        for (String animateurId : animateurIds) {
            animateurs.add(connus.stream()
                    .filter(animateur -> animateur.getId().equals(animateurId))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Animateur introuvable : " + animateurId)));
        }
        return animateurs;
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
