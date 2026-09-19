package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WrittenContrainteAdHoc;
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

    @Inject
    ReferenceDataService referenceDataService;

    /* ----------------------------- Legal parameters ------------------------- */

    @Tool(
            description = "Consulte les paramètres légaux appliqués par le solveur (durées maximales, pauses, repos).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresLegauxView consulter_parametres_legaux(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresLegaux());
    }

    @Tool(
            description = "Modifie les paramètres légaux. Seuls les champs fournis sont modifiés. Les plafonds "
                    + "d'ordre public (48 h hebdomadaires pour un majeur, 35 h pour un mineur) sont refusés "
                    + "au-delà, et les planchers de pause (20 min pour un majeur, 30 pour un mineur) en deçà.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresLegauxView modifier_parametres_legaux(
            @ToolArg(description = "Durée hebdomadaire maximale d'un majeur, en minutes", required = false)
                    Integer dureeHebdomadaireMaxMinutes,
            @ToolArg(description = "Durée hebdomadaire maximale d'un mineur, en minutes", required = false)
                    Integer dureeHebdomadaireMaxMineurMinutes,
            @ToolArg(description = "Pause minimale entre deux vacations, en minutes", required = false)
                    Integer pauseMinimaleEntreVacationsMinutes,
            @ToolArg(
                            description = "Durée maximale d'une vacation, en minutes : au-delà, le contrôle de "
                                    + "grille avertit qu'une coupure interne devient obligatoire (L3121-16)",
                            required = false)
                    Integer dureeVacationMaxMinutes,
            @ToolArg(description = "Repos quotidien minimal, en minutes", required = false)
                    Integer reposQuotidienMinimalMinutes,
            @ToolArg(
                            description = "Pause légale prise sur le poste, par relais entre collègues, plutôt que "
                                    + "comme un trou entre deux vacations (L3121-16 / L3162-3)",
                            required = false)
                    Boolean pauseSurPoste,
            @ToolArg(
                            description = "Durée de la pause légale d'un majeur, en minutes : au moins 20 "
                                    + "(L3121-16, d'ordre public). C'est elle qui est déduite des plafonds "
                                    + "quotidien et hebdomadaire quand la pause est prise sur le poste",
                            required = false)
                    Integer dureePauseMajeurMinutes,
            @ToolArg(
                            description =
                                    "Durée de la pause légale d'un mineur, en minutes : au moins 30 " + "(L3162-3)",
                            required = false)
                    Integer dureePauseMineurMinutes,
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
        if (pauseMinimaleEntreVacationsMinutes != null) {
            parametres.setPauseMinimaleEntreVacationsMinutes(pauseMinimaleEntreVacationsMinutes);
        }
        if (dureeVacationMaxMinutes != null) {
            parametres.setDureeVacationMaxMinutes(dureeVacationMaxMinutes);
        }
        if (reposQuotidienMinimalMinutes != null) {
            parametres.setReposQuotidienMinimalMinutes(reposQuotidienMinimalMinutes);
        }
        if (pauseSurPoste != null) {
            parametres.setPauseSurPoste(pauseSurPoste);
        }
        if (dureePauseMajeurMinutes != null) {
            parametres.setDureePauseMajeurMinutes(dureePauseMajeurMinutes);
        }
        if (dureePauseMineurMinutes != null) {
            parametres.setDureePauseMineurMinutes(dureePauseMineurMinutes);
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

    /* ---------------------------- Solver parameters ------------------------- */

    @Tool(
            description = "Consulte la durée de résolution par défaut du solveur, en secondes.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresSolveurView consulter_parametres_solveur(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresSolveur());
    }

    @Tool(
            description =
                    "Modifie la durée de résolution par défaut du solveur, en secondes (valeur strictement positive).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ParametresSolveurView modifier_parametres_solveur(
            @ToolArg(description = "Durée de résolution par défaut, en secondes") int dureeResolutionSecondes,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        // Only the duration is tunable here; the rest of the parameters is kept as is.
        ParametresSolveur actuels = referenceDataService.getParametresSolveur();
        return toView(referenceDataService.updateParametresSolveur(
                new ParametresSolveur(dureeResolutionSecondes, actuels.mailFinResolution())));
    }

    /* ------------------------- Notification parameters ---------------------- */

    @Tool(
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
    ParametresNotifications consulter_parametres_notifications(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.getParametresNotifications();
    }

    @Tool(
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
    ParametresNotifications modifier_parametres_notifications(
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
            description = "Liste les contraintes ad hoc saisies au cas par cas (indisponibilité forcée, "
                    + "incompatibilité entre animateurs, affectation forcée, affinité entre animateurs) : des règles posées "
                    + "avant le calcul pour placer ou écarter quelqu'un, à distinguer des verrouillages qui figent après coup. "
                    + "Les animateurs y sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ContrainteAdHocView> lister_contraintes_ad_hoc(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listContraintesAdHoc().stream()
                .map(ParametresMcpTools::toView)
                .toList();
    }

    @Tool(
            description = "Crée une contrainte ad hoc. INDISPONIBILITE_FORCEE, INCOMPATIBILITE et "
                    + "AFFECTATION_FORCEE sont évaluées par le solveur au même niveau HARD que les contraintes légales ; "
                    + "AFFINITE est une récompense SOFT. INDISPONIBILITE_FORCEE : l'animateur ne peut pas être affecté "
                    + "sur ce créneau. INCOMPATIBILITE : les animateurs listés ne peuvent pas être affectés au même stand "
                    + "sur le même créneau. AFFECTATION_FORCEE : l'animateur doit être affecté à ce stand sur ce créneau. "
                    + "AFFINITE : privilégier, sans l'imposer, les créneaux où les deux animateurs listés tiennent le "
                    + "même stand ; refusée si la même paire est déjà déclarée incompatible (et réciproquement).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    WrittenContrainteAdHocView creer_contrainte_ad_hoc(
            @ToolArg(description = "Id de la contrainte (unique)") String id,
            @ToolArg(description = "Type : INDISPONIBILITE_FORCEE, INCOMPATIBILITE, AFFECTATION_FORCEE ou AFFINITE")
                    String type,
            @ToolArg(description = "Ids des animateurs concernés") List<String> animateurIds,
            @ToolArg(description = "Id du créneau concerné", required = false) Long creneauId,
            @ToolArg(description = "Id du stand concerné", required = false) String standId,
            @ToolArg(description = "Raison, purement informative", required = false) String raison,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ContrainteAdHoc contrainte =
                new ContrainteAdHoc(id, McpArgs.enumeration(TypeContrainteAdHoc.class, type, "type"));
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
    public record WrittenContrainteAdHocView(ContrainteAdHocView contrainte, List<String> avertissements) {}

    @Tool(
            description = "Supprime une contrainte ad hoc.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult supprimer_contrainte_ad_hoc(
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
                parametres.getPauseMinimaleEntreVacationsMinutes(),
                parametres.getDureeVacationMaxMinutes(),
                parametres.getReposQuotidienMinimalMinutes(),
                parametres.isPauseSurPoste(),
                parametres.getDureePauseMajeurMinutes(),
                parametres.getDureePauseMineurMinutes(),
                parametres.getCoupureRepasMinutes(),
                parametres.getCoupureRepasMidiDebut(),
                parametres.getCoupureRepasMidiFin(),
                parametres.getCoupureRepasSoirDebut(),
                parametres.getCoupureRepasSoirFin(),
                parametres.getHeureDebutSoiree());
    }

    static ParametresSolveurView toView(ParametresSolveur parametres) {
        return new ParametresSolveurView(parametres.dureeResolutionSecondes());
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
            int pauseMinimaleEntreVacationsMinutes,
            int dureeVacationMaxMinutes,
            int reposQuotidienMinimalMinutes,
            boolean pauseSurPoste,
            int dureePauseMajeurMinutes,
            int dureePauseMineurMinutes,
            int coupureRepasMinutes,
            LocalTime coupureRepasMidiDebut,
            LocalTime coupureRepasMidiFin,
            LocalTime coupureRepasSoirDebut,
            LocalTime coupureRepasSoirFin,
            LocalTime heureDebutSoiree) {}

    public record ParametresSolveurView(int dureeResolutionSecondes) {}

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
