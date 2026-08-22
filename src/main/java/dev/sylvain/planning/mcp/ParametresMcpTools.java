package dev.sylvain.planning.mcp;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresDecoupage.PauseCoverageStrategy;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
@ApplicationScoped
public class ParametresMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    /* ----------------------------- Legal parameters ------------------------- */

    @Tool(description = "Consulte les paramètres légaux appliqués par le solveur (durées maximales, pauses, repos).")
    ParametresLegauxView consulter_parametres_legaux(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresLegaux());
    }

    @Tool(description = "Modifie les paramètres légaux. Seuls les champs fournis sont modifiés. Les plafonds "
            + "d'ordre public (48 h hebdomadaires pour un majeur, 35 h pour un mineur) sont refusés au-delà.")
    ParametresLegauxView modifier_parametres_legaux(
            @ToolArg(description = "Durée hebdomadaire maximale d'un majeur, en minutes", required = false) Integer dureeHebdomadaireMaxMinutes,
            @ToolArg(description = "Durée hebdomadaire maximale d'un mineur, en minutes", required = false) Integer dureeHebdomadaireMaxMineurMinutes,
            @ToolArg(description = "Pause minimale entre deux vacations, en minutes", required = false) Integer pauseMinimaleEntreVacationsMinutes,
            @ToolArg(description = "Repos quotidien minimal, en minutes", required = false) Integer reposQuotidienMinimalMinutes,
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
        if (reposQuotidienMinimalMinutes != null) {
            parametres.setReposQuotidienMinimalMinutes(reposQuotidienMinimalMinutes);
        }
        return toView(referenceDataService.updateParametresLegaux(parametres));
    }

    /* --------------------------- Slicing parameters ------------------------- */

    @Tool(description = "Consulte les paramètres de découpage des amplitudes en vacations.")
    ParametresDecoupageView consulter_parametres_decoupage(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresDecoupage());
    }

    @Tool(description = "Modifie les paramètres de découpage. Seuls les champs fournis sont modifiés. "
            + "Prend effet au prochain découpage généré, pas sur les créneaux déjà produits.")
    ParametresDecoupageView modifier_parametres_decoupage(
            @ToolArg(description = "Durée cible d'une vacation, en minutes", required = false) Integer dureeVacationCibleMinutes,
            @ToolArg(description = "Durée minimale d'une vacation, en minutes", required = false) Integer dureeVacationMinMinutes,
            @ToolArg(description = "Durée maximale d'une vacation, en minutes", required = false) Integer dureeVacationMaxMinutes,
            @ToolArg(description = "Chevauchement entre deux vacations successives, en minutes", required = false) Integer dureeChevauchementMinutes,
            @ToolArg(description = "Durée de la pause repas, en minutes", required = false) Integer dureePauseRepasMinutes,
            @ToolArg(description = "Nombre de familles de décalage", required = false) Integer nombreFamillesDecalage,
            @ToolArg(description = "Décalage maximal entre familles, en minutes", required = false) Integer dureeDecalageMaxMinutes,
            @ToolArg(description = "Début de la fenêtre repas du midi (HH:MM)", required = false) String fenetreRepasMidiDebut,
            @ToolArg(description = "Fin de la fenêtre repas du midi (HH:MM)", required = false) String fenetreRepasMidiFin,
            @ToolArg(description = "Début de la fenêtre repas du soir (HH:MM)", required = false) String fenetreRepasSoirDebut,
            @ToolArg(description = "Fin de la fenêtre repas du soir (HH:MM)", required = false) String fenetreRepasSoirFin,
            @ToolArg(description = "Couverture pendant la pause : FERMETURE ou RELEVE", required = false) String strategieCouverturePendantPause,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ParametresDecoupage parametres = referenceDataService.getParametresDecoupage();
        if (dureeVacationCibleMinutes != null) {
            parametres.setDureeVacationCibleMinutes(dureeVacationCibleMinutes);
        }
        if (dureeVacationMinMinutes != null) {
            parametres.setDureeVacationMinMinutes(dureeVacationMinMinutes);
        }
        if (dureeVacationMaxMinutes != null) {
            parametres.setDureeVacationMaxMinutes(dureeVacationMaxMinutes);
        }
        if (dureeChevauchementMinutes != null) {
            parametres.setDureeChevauchementMinutes(dureeChevauchementMinutes);
        }
        if (dureePauseRepasMinutes != null) {
            parametres.setDureePauseRepasMinutes(dureePauseRepasMinutes);
        }
        if (nombreFamillesDecalage != null) {
            parametres.setNombreFamillesDecalage(nombreFamillesDecalage);
        }
        if (dureeDecalageMaxMinutes != null) {
            parametres.setDureeDecalageMaxMinutes(dureeDecalageMaxMinutes);
        }
        if (fenetreRepasMidiDebut != null) {
            parametres.setFenetreRepasMidiDebut(McpArgs.heure(fenetreRepasMidiDebut, "fenetreRepasMidiDebut"));
        }
        if (fenetreRepasMidiFin != null) {
            parametres.setFenetreRepasMidiFin(McpArgs.heure(fenetreRepasMidiFin, "fenetreRepasMidiFin"));
        }
        if (fenetreRepasSoirDebut != null) {
            parametres.setFenetreRepasSoirDebut(McpArgs.heure(fenetreRepasSoirDebut, "fenetreRepasSoirDebut"));
        }
        if (fenetreRepasSoirFin != null) {
            parametres.setFenetreRepasSoirFin(McpArgs.heure(fenetreRepasSoirFin, "fenetreRepasSoirFin"));
        }
        if (strategieCouverturePendantPause != null) {
            parametres.setStrategieCouverturePendantPause(
                    McpArgs.enumeration(PauseCoverageStrategy.class, strategieCouverturePendantPause,
                            "strategieCouverturePendantPause"));
        }
        return toView(referenceDataService.updateParametresDecoupage(parametres));
    }

    /* ---------------------------- Solver parameters ------------------------- */

    @Tool(description = "Consulte la durée de résolution par défaut du solveur, en secondes.")
    ParametresSolveurView consulter_parametres_solveur(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.getParametresSolveur());
    }

    @Tool(description = "Modifie la durée de résolution par défaut du solveur, en secondes (valeur strictement positive).")
    ParametresSolveurView modifier_parametres_solveur(
            @ToolArg(description = "Durée de résolution par défaut, en secondes") int dureeResolutionSecondes,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        // Only the duration is tunable here; the rest of the parameters is kept as is.
        ParametresSolveur actuels = referenceDataService.getParametresSolveur();
        return toView(referenceDataService.updateParametresSolveur(
                new ParametresSolveur(dureeResolutionSecondes, actuels.mailFinResolution())));
    }

    /* -------------------------- Contraintes ad hoc -------------------------- */

    @Tool(description = "Liste les contraintes ad hoc saisies au cas par cas (indisponibilité forcée, "
            + "incompatibilité entre animateurs, affectation forcée, affinité entre animateurs). "
            + "Les animateurs y sont désignés par id seul.")
    List<ContrainteAdHocView> lister_contraintes_ad_hoc(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listContraintesAdHoc().stream().map(ParametresMcpTools::toView).toList();
    }

    @Tool(description = "Crée une contrainte ad hoc. INDISPONIBILITE_FORCEE, INCOMPATIBILITE et "
            + "AFFECTATION_FORCEE sont évaluées par le solveur au même niveau HARD que les contraintes légales ; "
            + "AFFINITE est une récompense SOFT. INDISPONIBILITE_FORCEE : l'animateur ne peut pas être affecté "
            + "sur ce créneau. INCOMPATIBILITE : les animateurs listés ne peuvent pas être affectés au même stand "
            + "sur le même créneau. AFFECTATION_FORCEE : l'animateur doit être affecté à ce stand sur ce créneau. "
            + "AFFINITE : privilégier, sans l'imposer, les créneaux où les deux animateurs listés tiennent le "
            + "même stand ; refusée si la même paire est déjà déclarée incompatible (et réciproquement).")
    ContrainteAdHocView creer_contrainte_ad_hoc(
            @ToolArg(description = "Id de la contrainte (unique)") String id,
            @ToolArg(description = "Type : INDISPONIBILITE_FORCEE, INCOMPATIBILITE, AFFECTATION_FORCEE ou AFFINITE") String type,
            @ToolArg(description = "Ids des animateurs concernés") List<String> animateurIds,
            @ToolArg(description = "Id du créneau concerné", required = false) Long creneauId,
            @ToolArg(description = "Id du stand concerné", required = false) String standId,
            @ToolArg(description = "Raison, purement informative", required = false) String raison,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id,
                McpArgs.enumeration(TypeContrainteAdHoc.class, type, "type"));
        contrainte.setAnimateursConcernes(animateurs(animateurIds));
        if (creneauId != null) {
            contrainte.setCreneau(referenceDataService.listCreneaux().stream()
                    .filter(creneau -> creneau.getId() != null && creneau.getId().equals(creneauId))
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
        return toView(referenceDataService.createContrainteAdHoc(contrainte));
    }

    @Tool(description = "Supprime une contrainte ad hoc.")
    SuppressionResult supprimer_contrainte_ad_hoc(@ToolArg(description = "Id de la contrainte") String id,
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
        return new ParametresLegauxView(parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.getDureeHebdomadaireMaxMineurMinutes(),
                parametres.getPauseMinimaleEntreVacationsMinutes(),
                parametres.getReposQuotidienMinimalMinutes());
    }

    static ParametresDecoupageView toView(ParametresDecoupage parametres) {
        return new ParametresDecoupageView(parametres.getDureeVacationCibleMinutes(),
                parametres.getDureeVacationMinMinutes(), parametres.getDureeVacationMaxMinutes(),
                parametres.getDureeChevauchementMinutes(), parametres.getDureePauseRepasMinutes(),
                parametres.getNombreFamillesDecalage(), parametres.getDureeDecalageMaxMinutes(),
                parametres.getFenetreRepasMidiDebut(), parametres.getFenetreRepasMidiFin(),
                parametres.getFenetreRepasSoirDebut(), parametres.getFenetreRepasSoirFin(),
                parametres.getStrategieCouverturePendantPause());
    }

    static ParametresSolveurView toView(ParametresSolveur parametres) {
        return new ParametresSolveurView(parametres.dureeResolutionSecondes());
    }

    static ContrainteAdHocView toView(ContrainteAdHoc contrainte) {
        return new ContrainteAdHocView(contrainte.getId(), contrainte.getType(),
                contrainte.getAnimateursConcernes().stream().map(Animateur::getId).toList(),
                contrainte.getCreneau() == null ? null : contrainte.getCreneau().getId(),
                contrainte.getStand() == null ? null : contrainte.getStand().getId(),
                contrainte.getRaison(), contrainte.getCreeParUtilisateurId(), contrainte.getCreeLe());
    }

    public record ParametresLegauxView(int dureeHebdomadaireMaxMinutes, int dureeHebdomadaireMaxMineurMinutes,
            int pauseMinimaleEntreVacationsMinutes, int reposQuotidienMinimalMinutes) {
    }

    public record ParametresDecoupageView(int dureeVacationCibleMinutes, int dureeVacationMinMinutes,
            int dureeVacationMaxMinutes, int dureeChevauchementMinutes, int dureePauseRepasMinutes,
            int nombreFamillesDecalage, int dureeDecalageMaxMinutes, LocalTime fenetreRepasMidiDebut,
            LocalTime fenetreRepasMidiFin, LocalTime fenetreRepasSoirDebut, LocalTime fenetreRepasSoirFin,
            PauseCoverageStrategy strategieCouverturePendantPause) {
    }

    public record ParametresSolveurView(int dureeResolutionSecondes) {
    }

    public record ContrainteAdHocView(String id, TypeContrainteAdHoc type, List<String> animateurIds,
            Long creneauId, String standId, String raison, String creeParUtilisateurId, Instant creeLe) {
    }
}
