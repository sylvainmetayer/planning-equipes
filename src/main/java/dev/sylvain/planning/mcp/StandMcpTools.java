package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.ReferenceDataService.TypologieItem;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for the "où et quoi" referentials of
 * {@code ReferenceDataResource}: stands (with their closure/opening windows),
 * emplacements and typologies de jeu.
 *
 * <p>Like every mutating tool of this package, updates are <em>merges</em>:
 * only the arguments actually provided are applied, the rest keeps its
 * persisted value. An assistant editing one attribute out of a sentence
 * ("passe le stand tir à l'arc en effort épuisant") should not have to restate
 * the whole entity — and silently resetting the fields it omitted would be a
 * data-loss bug, not a feature.
 */
@ApplicationScoped
public class StandMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    /* -------------------------------- Stands ------------------------------- */

    @Tool(description = "Liste les stands, avec leurs typologies proposées, effectifs requis, "
            + "réserve majeurs/premium, niveau d'effort, emplacement, fermetures et ouvertures.")
    List<StandView> lister_stands() {
        return referenceDataService.listStands().stream().map(StandMcpTools::toView).toList();
    }

    @Tool(description = "Consulte un stand par son id.")
    StandView consulter_stand(@ToolArg(description = "Id du stand") String id) {
        return toView(trouverStand(id));
    }

    @Tool(description = "Crée un stand. Les typologies proposées doivent exister dans le référentiel des typologies.")
    StandView creer_stand(
            @ToolArg(description = "Id du stand (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Ids de typologies de jeu proposées", required = false) List<String> typologiesProposees,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium (nécessite un animateur référent)", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(description = "Id de l'emplacement géographique", required = false) String emplacementId) {
        Stand stand = new Stand();
        stand.setId(id);
        stand.setNom(nom);
        stand.setTypologiesProposees(typologiesProposees == null ? new HashSet<>() : new HashSet<>(typologiesProposees));
        stand.setEffectifMin(effectifMin == null ? 1 : effectifMin);
        stand.setEffectifMax(effectifMax == null ? Math.max(1, stand.getEffectifMin()) : effectifMax);
        stand.setReserveMajeurs(Boolean.TRUE.equals(reserveMajeurs));
        stand.setPremium(Boolean.TRUE.equals(premium));
        stand.setNiveauEffort(niveauEffort == null ? NiveauEffort.NORMAL
                : McpArgs.enumeration(NiveauEffort.class, niveauEffort, "niveauEffort"));
        stand.setEmplacement(emplacementId == null ? null : trouverEmplacement(emplacementId));
        return toView(referenceDataService.createStand(stand));
    }

    @Tool(description = "Modifie un stand. Seuls les champs fournis sont modifiés ; les fermetures et ouvertures "
            + "se gèrent avec les outils dédiés.")
    StandView modifier_stand(
            @ToolArg(description = "Id du stand") String id,
            @ToolArg(description = "Nom affiché", required = false) String nom,
            @ToolArg(description = "Ids de typologies proposées (remplace la liste existante)", required = false) List<String> typologiesProposees,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(description = "Id de l'emplacement géographique", required = false) String emplacementId) {
        Stand stand = trouverStand(id);
        if (nom != null) {
            stand.setNom(nom);
        }
        if (typologiesProposees != null) {
            stand.setTypologiesProposees(new HashSet<>(typologiesProposees));
        }
        if (effectifMin != null) {
            stand.setEffectifMin(effectifMin);
        }
        if (effectifMax != null) {
            stand.setEffectifMax(effectifMax);
        }
        if (reserveMajeurs != null) {
            stand.setReserveMajeurs(reserveMajeurs);
        }
        if (premium != null) {
            stand.setPremium(premium);
        }
        if (niveauEffort != null) {
            stand.setNiveauEffort(McpArgs.enumeration(NiveauEffort.class, niveauEffort, "niveauEffort"));
        }
        if (emplacementId != null) {
            stand.setEmplacement(trouverEmplacement(emplacementId));
        }
        return toView(referenceDataService.updateStand(id, stand));
    }

    @Tool(description = "Supprime un stand.")
    SuppressionResult supprimer_stand(@ToolArg(description = "Id du stand") String id) {
        referenceDataService.deleteStand(id);
        return new SuppressionResult(id, true);
    }

    @Tool(description = "Ajoute une fermeture (indisponibilité) sur un stand : le stand ne peut pas être armé "
            + "entre ces heures ce jour-là. Une fenêtre ne peut pas chevaucher minuit — dans ce cas, en saisir deux.")
    StandView ajouter_fermeture_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)") String heureFin,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif) {
        Stand stand = trouverStand(standId);
        stand.getIndisponibilites().add(new IndisponibiliteStand(null, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), McpArgs.heure(heureFin, "heureFin"), motif));
        return toView(referenceDataService.updateStand(standId, stand));
    }

    @Tool(description = "Ajoute une ouverture sur un stand : ce jour-là, le stand n'est armé QUE sur cette plage. "
            + "Un même jour ne peut pas porter à la fois une fermeture et une ouverture.")
    StandView ajouter_ouverture_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM)") String heureFin,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif) {
        Stand stand = trouverStand(standId);
        stand.getOuvertures().add(new OuvertureStand(null, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), McpArgs.heure(heureFin, "heureFin"), motif));
        return toView(referenceDataService.updateStand(standId, stand));
    }

    @Tool(description = "Retire toutes les fermetures et ouvertures d'un stand pour une date donnée.")
    StandView effacer_plages_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date) {
        Stand stand = trouverStand(standId);
        LocalDate jour = McpArgs.date(date, "date");
        stand.getIndisponibilites().removeIf(indispo -> jour.equals(indispo.getDate()));
        stand.getOuvertures().removeIf(ouverture -> jour.equals(ouverture.getDate()));
        return toView(referenceDataService.updateStand(standId, stand));
    }

    /* ----------------------------- Emplacements ---------------------------- */

    @Tool(description = "Liste les emplacements géographiques (utilisés pour limiter les déplacements entre stands).")
    List<EmplacementView> lister_emplacements() {
        return referenceDataService.listEmplacements().stream().map(StandMcpTools::toView).toList();
    }

    @Tool(description = "Crée un emplacement géographique.")
    EmplacementView creer_emplacement(
            @ToolArg(description = "Id de l'emplacement (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Latitude (-90 à 90)", required = false) Double latitude,
            @ToolArg(description = "Longitude (-180 à 180)", required = false) Double longitude) {
        return toView(referenceDataService.createEmplacement(new Emplacement(id, nom, latitude, longitude)));
    }

    @Tool(description = "Modifie un emplacement. Seuls les champs fournis sont modifiés.")
    EmplacementView modifier_emplacement(
            @ToolArg(description = "Id de l'emplacement") String id,
            @ToolArg(description = "Nom affiché", required = false) String nom,
            @ToolArg(description = "Latitude", required = false) Double latitude,
            @ToolArg(description = "Longitude", required = false) Double longitude) {
        Emplacement emplacement = trouverEmplacement(id);
        if (nom != null) {
            emplacement.setNom(nom);
        }
        if (latitude != null) {
            emplacement.setLatitude(latitude);
        }
        if (longitude != null) {
            emplacement.setLongitude(longitude);
        }
        return toView(referenceDataService.updateEmplacement(id, emplacement));
    }

    @Tool(description = "Supprime un emplacement.")
    SuppressionResult supprimer_emplacement(@ToolArg(description = "Id de l'emplacement") String id) {
        referenceDataService.deleteEmplacement(id);
        return new SuppressionResult(id, true);
    }

    /* ------------------------------ Typologies ----------------------------- */

    @Tool(description = "Liste les typologies de jeu (référentiel CRUD auquel se réfèrent les compétences des "
            + "animateurs et les typologies proposées par les stands).")
    List<TypologieItem> lister_typologies() {
        return referenceDataService.listTypologies();
    }

    @Tool(description = "Crée une typologie de jeu.")
    TypologieItem creer_typologie(
            @ToolArg(description = "Id de la typologie (unique)") String id,
            @ToolArg(description = "Libellé affiché", required = false) String label) {
        return referenceDataService.createTypologie(new TypologieItem(id, label));
    }

    @Tool(description = "Renomme une typologie de jeu.")
    TypologieItem modifier_typologie(
            @ToolArg(description = "Id de la typologie") String id,
            @ToolArg(description = "Nouveau libellé") String label) {
        return referenceDataService.updateTypologie(id, new TypologieItem(id, label));
    }

    @Tool(description = "Supprime une typologie de jeu. Refusé tant qu'elle est référencée par un stand ou un animateur.")
    SuppressionResult supprimer_typologie(@ToolArg(description = "Id de la typologie") String id) {
        referenceDataService.deleteTypologie(id);
        return new SuppressionResult(id, true);
    }

    /* -------------------------------- Views -------------------------------- */

    private Stand trouverStand(String id) {
        return referenceDataService.listStands().stream()
                .filter(stand -> stand.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Stand introuvable : " + id));
    }

    private Emplacement trouverEmplacement(String id) {
        return referenceDataService.listEmplacements().stream()
                .filter(emplacement -> emplacement.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Emplacement introuvable : " + id));
    }

    static StandView toView(Stand stand) {
        List<PlageView> fermetures = new ArrayList<>();
        stand.getIndisponibilites().forEach(indispo -> fermetures.add(new PlageView(indispo.getDate(),
                indispo.getHeureDebut(), indispo.getHeureFin(), indispo.getMotif())));
        List<PlageView> ouvertures = new ArrayList<>();
        stand.getOuvertures().forEach(ouverture -> ouvertures.add(new PlageView(ouverture.getDate(),
                ouverture.getHeureDebut(), ouverture.getHeureFin(), ouverture.getMotif())));
        return new StandView(stand.getId(), stand.getNom(), stand.getTypologiesProposees(),
                stand.getEffectifMin(), stand.getEffectifMax(), stand.isReserveMajeurs(),
                stand.isPremium(), stand.getNiveauEffort(),
                stand.getEmplacement() == null ? null : stand.getEmplacement().getId(),
                fermetures, ouvertures);
    }

    static EmplacementView toView(Emplacement emplacement) {
        return new EmplacementView(emplacement.getId(), emplacement.getNom(), emplacement.getLatitude(),
                emplacement.getLongitude());
    }

    public record StandView(String id, String nom, Set<String> typologiesProposees, int effectifMin,
            int effectifMax, boolean reserveMajeurs, boolean premium, NiveauEffort niveauEffort,
            String emplacementId, List<PlageView> fermetures, List<PlageView> ouvertures) {
    }

    public record PlageView(LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
    }

    public record EmplacementView(String id, String nom, Double latitude, Double longitude) {
    }
}
