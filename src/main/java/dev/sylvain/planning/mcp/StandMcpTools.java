package dev.sylvain.planning.mcp;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.TypologieItem;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for the "où et quoi" referentials of
 * {@code ReferenceDataResource}: stands (with their recurring horaires and the
 * dated closure/opening windows that override them), emplacements and
 * typologies de jeu.
 *
 * <p>Like every mutating tool of this package, updates are <em>merges</em>:
 * only the arguments actually provided are applied, the rest keeps its
 * persisted value. An assistant editing one attribute out of a sentence
 * ("passe le stand tir à l'arc en effort épuisant") should not have to restate
 * the whole entity — and silently resetting the fields it omitted would be a
 * data-loss bug, not a feature.
 */
@EditionCiblee
@ApplicationScoped
public class StandMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    /* -------------------------------- Stands ------------------------------- */

    @Tool(description = "Liste les stands, avec leurs typologies proposées, effectifs requis, "
            + "réserve majeurs/premium, niveau d'effort, emplacement, horaires récurrents et plages datées "
            + "(fermetures/ouvertures) qui les surchargent. Sans limite, renvoie tous les stands ; total dit "
            + "toujours combien il y en a.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    StandsView lister_stands(
            @ToolArg(description = "Nombre maximum de stands renvoyés (défaut : tous)", required = false) Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<Stand> stands = referenceDataService.listStands();
        return new StandsView(stands.size(), stands.stream()
                .limit(McpArgs.limite(limite, stands.size()))
                .map(StandMcpTools::toView)
                .toList());
    }

    @Tool(description = "Consulte un stand par son id.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    StandView consulter_stand(@ToolArg(description = "Id du stand") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(findStand(id));
    }

    @Tool(description = "Crée un stand. Les typologies proposées doivent exister dans le référentiel des typologies.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    StandView creer_stand(
            @ToolArg(description = "Id du stand (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Ids de typologies de jeu proposées", required = false) List<String> typologiesProposees,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium (nécessite un animateur référent)", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(description = "Id de l'emplacement géographique", required = false) String emplacementId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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
        stand.setEmplacement(emplacementId == null ? null : findEmplacement(emplacementId));
        return toView(referenceDataService.createStand(stand));
    }

    @Tool(description = "Crée un stand ET tout ce dont il dépend en un seul appel : son emplacement, ses "
            + "typologies, et ses horaires d'ouverture récurrents. Conçu pour la mise en place d'une édition, où "
            + "créer un stand demande sinon quatre allers-retours — et où creer_stand échoue tant que les "
            + "typologies citées n'existent pas. "
            + "L'emplacement est créé s'il n'existe pas ET que emplacementNom est fourni ; les typologies "
            + "manquantes ne sont créées que si creerTypologiesManquantes vaut true, sinon leur absence reste une "
            + "erreur (une typologie inventée sur une faute de frappe est un référentiel pollué). "
            + "horaires prend les fenêtres d'OUVERTURE au format « 10:00-12:00,14:00- » : le stand est alors fermé "
            + "en dehors, ce qui est la façon d'exprimer « ce stand n\u0027ouvre que de tant à tant ». Pour une règle "
            + "de fermeture, ou plusieurs règles de portées différentes, utiliser ajouter_horaire_stand. "
            + "La réponse énumère ce qui a été créé au passage, pour que rien ne soit créé à l\u0027insu de "
            + "l\u0027utilisateur.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    CreationStandComplet creer_stand_complet(
            @ToolArg(description = "Id du stand (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Ids de typologies de jeu proposées", required = false) List<String> typologiesProposees,
            @ToolArg(description = "Créer les typologies absentes du référentiel au lieu d'échouer", required = false) Boolean creerTypologiesManquantes,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium (nécessite un animateur référent)", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(description = "Id de l'emplacement géographique", required = false) String emplacementId,
            @ToolArg(description = "Nom de l'emplacement, à créer s'il n'existe pas encore", required = false) String emplacementNom,
            @ToolArg(description = "Latitude de l'emplacement créé", required = false) Double latitude,
            @ToolArg(description = "Longitude de l'emplacement créé", required = false) Double longitude,
            @ToolArg(description = "Fenêtres d'ouverture, ex. « 10:00-12:00,14:00- »", required = false) String horaires,
            @ToolArg(description = "Portée des horaires : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String horairesJours,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false) List<String> horairesJoursSemaine,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ) si portée PLAGE", required = false) String horairesDateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ) si portée PLAGE", required = false) String horairesDateFin,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> horairesDates,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<String> typologiesCreees = createMissingTypologies(typologiesProposees,
                Boolean.TRUE.equals(creerTypologiesManquantes));
        String emplacementCree = createMissingEmplacement(emplacementId, emplacementNom, latitude, longitude);

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
        stand.setEmplacement(emplacementId == null ? null : findEmplacement(emplacementId));
        if (horaires != null && !horaires.isBlank()) {
            stand.getHoraires().add(horaireOuverture(horaires, horairesJours, horairesJoursSemaine,
                    horairesDateDebut, horairesDateFin, horairesDates));
        }
        return new CreationStandComplet(toView(referenceDataService.createStand(stand)), emplacementCree,
                typologiesCreees);
    }

    /**
     * Creates the typologies the stand cites but the referential lacks, and
     * only those. Opt-in because {@code validateStand} rejecting an unknown
     * typologie is a feature: it is what turns "NIJNA" into an error instead
     * of into a second, near-identical entry nobody notices until the solver
     * finds no competent animateur for it.
     */
    private List<String> createMissingTypologies(List<String> typologies, boolean autorise) {
        if (typologies == null || typologies.isEmpty() || !autorise) {
            return List.of();
        }
        Set<String> connues = referenceDataService.listTypologies().stream()
                .map(TypologieItem::id)
                .collect(Collectors.toCollection(HashSet::new));
        List<String> creees = new ArrayList<>();
        for (String typologie : typologies) {
            if (connues.add(typologie)) {
                referenceDataService.createTypologie(new TypologieItem(typologie, typologie));
                creees.add(typologie);
            }
        }
        return creees;
    }

    /** @return the id of the emplacement created here, or {@code null} when none was. */
    private String createMissingEmplacement(String emplacementId, String nom, Double latitude, Double longitude) {
        if (emplacementId == null || emplacementId.isBlank() || nom == null || nom.isBlank()) {
            return null;
        }
        boolean exists = referenceDataService.listEmplacements().stream()
                .anyMatch(emplacement -> emplacementId.equals(emplacement.getId()));
        if (exists) {
            return null;
        }
        referenceDataService.createEmplacement(new Emplacement(emplacementId, nom, latitude, longitude));
        return emplacementId;
    }

    private static HoraireStand horaireOuverture(String fenetres, String jours, List<String> joursSemaine,
            String dateDebut, String dateFin, List<String> dates) {
        HoraireStand horaire = new HoraireStand();
        horaire.setMode(ModeHoraire.OUVERTURE);
        horaire.setJours(jours == null ? TypeJoursHoraire.TOUS
                : McpArgs.enumeration(TypeJoursHoraire.class, jours, "horairesJours"));
        horaire.setJoursSemaine(McpArgs.joursSemaine(joursSemaine, "horairesJoursSemaine"));
        horaire.setDateDebut(McpArgs.date(dateDebut, "horairesDateDebut"));
        horaire.setDateFin(McpArgs.date(dateFin, "horairesDateFin"));
        horaire.setDates(new TreeSet<>(McpArgs.dates(dates, "horairesDates")));
        horaire.setFenetres(McpArgs.fenetres(fenetres, false));
        return horaire;
    }

    /**
     * @param emplacementCree  id of the emplacement created along the way, {@code null} if none
     * @param typologiesCreees ids of the typologies created along the way, empty if none
     */
    public record CreationStandComplet(StandView stand, String emplacementCree, List<String> typologiesCreees) {
    }

    @Tool(description = "Modifie un stand. Seuls les champs fournis sont modifiés ; les fermetures et ouvertures "
            + "se gèrent avec les outils dédiés.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    StandView modifier_stand(
            @ToolArg(description = "Id du stand") String id,
            @ToolArg(description = "Nom affiché", required = false) String nom,
            @ToolArg(description = "Ids de typologies proposées (remplace la liste existante)", required = false) List<String> typologiesProposees,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(description = "Id de l'emplacement géographique", required = false) String emplacementId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(id);
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
            stand.setEmplacement(findEmplacement(emplacementId));
        }
        return toView(referenceDataService.updateStand(id, stand));
    }

    @Tool(description = "Supprime un stand.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    SuppressionResult supprimer_stand(@ToolArg(description = "Id du stand") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteStand(id);
        return new SuppressionResult(id, true);
    }

    @Tool(description = "Ajoute une fermeture (indisponibilité) datée sur un stand : le stand ne peut pas être armé "
            + "entre ces heures ce jour-là. Une fenêtre ne peut pas chevaucher minuit — dans ce cas, en saisir deux. "
            + "Omettre heureFin ferme jusqu'à la fermeture du jour. Une plage datée prime sur les horaires "
            + "récurrents du stand pour ce jour-là ; pour un motif qui se répète, préférer ajouter_horaire_stand.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    StandView ajouter_fermeture_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM) ; omise = jusqu'à la fermeture", required = false) String heureFin,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        stand.getIndisponibilites().add(new IndisponibiliteStand(null, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), endTimeOrClosing(heureFin), motif));
        return toView(referenceDataService.updateStand(standId, stand));
    }

    @Tool(description = "Ajoute une ouverture datée sur un stand : ce jour-là, le stand n'est armé QUE sur cette "
            + "plage. Un même jour ne peut pas porter à la fois une fermeture et une ouverture. Omettre heureFin "
            + "ouvre jusqu'à la fermeture du jour. Pour un motif qui se répète, préférer ajouter_horaire_stand.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    StandView ajouter_ouverture_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM) ; omise = jusqu'à la fermeture", required = false) String heureFin,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        stand.getOuvertures().add(new OuvertureStand(null, McpArgs.date(date, "date"),
                McpArgs.heure(heureDebut, "heureDebut"), endTimeOrClosing(heureFin), motif));
        return toView(referenceDataService.updateStand(standId, stand));
    }

    /** {@code null} — "until closing time" — for an omitted or empty end hour. */
    private static LocalTime endTimeOrClosing(String heureFin) {
        return heureFin == null || heureFin.isBlank() ? null : McpArgs.heure(heureFin, "heureFin");
    }

    @Tool(description = "Retire toutes les fermetures et ouvertures d'un stand pour une date donnée. Les horaires "
            + "récurrents ne sont pas touchés : la date redevient donc gouvernée par eux, s'il en existe.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false))
    StandView effacer_plages_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        LocalDate jour = McpArgs.date(date, "date");
        stand.getIndisponibilites().removeIf(indispo -> jour.equals(indispo.getDate()));
        stand.getOuvertures().removeIf(ouverture -> jour.equals(ouverture.getDate()));
        return toView(referenceDataService.updateStand(standId, stand));
    }

    @Tool(description = "Ajoute un horaire récurrent sur un stand : une seule règle au lieu d'une plage datée par "
            + "jour d'événement. mode=OUVERTURE veut dire « fermé sauf sur ces fenêtres », mode=FERMETURE « ouvert "
            + "sauf sur ces fenêtres ». La portée jours vaut TOUS (tous les jours), JOURS_SEMAINE (avec "
            + "joursSemaine), PLAGE (avec dateDebut/dateFin) ou DATES (avec dates). Les fenêtres se saisissent "
            + "« HH:MM-HH:MM », ou « HH:MM- » pour courir jusqu'à la fermeture du jour ; plusieurs fenêtres se "
            + "séparent par une virgule (ex. « 10:00-12:00,14:00- » pour une coupure méridienne). Une plage datée "
            + "existante prime toujours sur les règles, pour le jour qu'elle nomme.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    StandView ajouter_horaire_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "OUVERTURE ou FERMETURE") String mode,
            @ToolArg(description = "Fenêtres, ex. « 10:00-12:00,14:00- »") String fenetres,
            @ToolArg(description = "Portée : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String jours,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false) List<String> joursSemaine,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ) si portée PLAGE", required = false) String dateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ) si portée PLAGE", required = false) String dateFin,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> dates,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        HoraireStand horaire = new HoraireStand();
        horaire.setMode(McpArgs.enumeration(ModeHoraire.class, mode, "mode"));
        horaire.setJours(jours == null ? TypeJoursHoraire.TOUS
                : McpArgs.enumeration(TypeJoursHoraire.class, jours, "jours"));
        if (joursSemaine != null) {
            horaire.setJoursSemaine(joursSemaine.stream()
                    .map(jour -> McpArgs.enumeration(DayOfWeek.class, jour, "joursSemaine"))
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
        if (dateDebut != null) {
            horaire.setDateDebut(McpArgs.date(dateDebut, "dateDebut"));
        }
        if (dateFin != null) {
            horaire.setDateFin(McpArgs.date(dateFin, "dateFin"));
        }
        if (dates != null) {
            horaire.setDates(dates.stream().map(date -> McpArgs.date(date, "dates"))
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
        horaire.setFenetres(McpArgs.fenetres(fenetres, false));
        horaire.setMotif(motif);
        stand.getHoraires().add(horaire);
        return toView(referenceDataService.updateStand(standId, stand));
    }

    @Tool(description = "Retire tous les horaires récurrents d'un stand. Ses plages datées restent en place.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false))
    StandView effacer_horaires_stand(@ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        stand.getHoraires().clear();
        return toView(referenceDataService.updateStand(standId, stand));
    }

    /* ----------------------------- Emplacements ---------------------------- */

    @Tool(description = "Liste les emplacements géographiques (utilisés pour limiter les déplacements entre stands).",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    List<EmplacementView> lister_emplacements(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listEmplacements().stream().map(StandMcpTools::toView).toList();
    }

    @Tool(description = "Crée un emplacement géographique.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    EmplacementView creer_emplacement(
            @ToolArg(description = "Id de l'emplacement (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Latitude (-90 à 90)", required = false) Double latitude,
            @ToolArg(description = "Longitude (-180 à 180)", required = false) Double longitude,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(referenceDataService.createEmplacement(new Emplacement(id, nom, latitude, longitude)));
    }

    @Tool(description = "Modifie un emplacement. Seuls les champs fournis sont modifiés.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    EmplacementView modifier_emplacement(
            @ToolArg(description = "Id de l'emplacement") String id,
            @ToolArg(description = "Nom affiché", required = false) String nom,
            @ToolArg(description = "Latitude", required = false) Double latitude,
            @ToolArg(description = "Longitude", required = false) Double longitude,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Emplacement emplacement = findEmplacement(id);
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

    @Tool(description = "Supprime un emplacement.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    SuppressionResult supprimer_emplacement(@ToolArg(description = "Id de l'emplacement") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteEmplacement(id);
        return new SuppressionResult(id, true);
    }

    /* ------------------------------ Typologies ----------------------------- */

    @Tool(description = "Liste les typologies de jeu (référentiel CRUD auquel se réfèrent les compétences des "
            + "animateurs et les typologies proposées par les stands).",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    List<TypologieItem> lister_typologies(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listTypologies();
    }

    @Tool(description = "Crée une typologie de jeu.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    TypologieItem creer_typologie(
            @ToolArg(description = "Id de la typologie (unique)") String id,
            @ToolArg(description = "Libellé affiché", required = false) String label,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.createTypologie(new TypologieItem(id, label));
    }

    @Tool(description = "Renomme une typologie de jeu.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    TypologieItem modifier_typologie(
            @ToolArg(description = "Id de la typologie") String id,
            @ToolArg(description = "Nouveau libellé") String label,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.updateTypologie(id, new TypologieItem(id, label));
    }

    @Tool(description = "Supprime une typologie de jeu. Refusé tant qu'elle est référencée par un stand ou un animateur.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    SuppressionResult supprimer_typologie(@ToolArg(description = "Id de la typologie") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteTypologie(id);
        return new SuppressionResult(id, true);
    }

    /* -------------------------------- Views -------------------------------- */

    private Stand findStand(String id) {
        return referenceDataService.listStands().stream()
                .filter(stand -> stand.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Stand introuvable : " + id));
    }

    private Emplacement findEmplacement(String id) {
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
        List<HoraireView> horaires = new ArrayList<>();
        stand.getHoraires().forEach(horaire -> horaires.add(new HoraireView(horaire.getMode(), horaire.getJours(),
                horaire.getJoursSemaine(), horaire.getDateDebut(), horaire.getDateFin(), horaire.getDates(),
                horaire.getFenetres().stream()
                        .map(fenetre -> new FenetreView(fenetre.getHeureDebut(), fenetre.getHeureFin()))
                        .toList(),
                horaire.getMotif())));
        return new StandView(stand.getId(), stand.getNom(), stand.getTypologiesProposees(),
                stand.getEffectifMin(), stand.getEffectifMax(), stand.isReserveMajeurs(),
                stand.isPremium(), stand.getNiveauEffort(),
                stand.getEmplacement() == null ? null : stand.getEmplacement().getId(),
                fermetures, ouvertures, horaires);
    }

    static EmplacementView toView(Emplacement emplacement) {
        return new EmplacementView(emplacement.getId(), emplacement.getNom(), emplacement.getLatitude(),
                emplacement.getLongitude());
    }

    /** @param total stands in the edition, which may exceed the number returned */
    public record StandsView(int total, List<StandView> stands) {
    }

    public record StandView(String id, String nom, Set<String> typologiesProposees, int effectifMin,
            int effectifMax, boolean reserveMajeurs, boolean premium, NiveauEffort niveauEffort,
            String emplacementId, List<PlageView> fermetures, List<PlageView> ouvertures,
            List<HoraireView> horaires) {
    }

    /** A dated exception window; a {@code null} {@code heureFin} means "until closing time". */
    public record PlageView(LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif) {
    }

    /** A recurring rule: which days, and the windows those days carry. */
    public record HoraireView(ModeHoraire mode, TypeJoursHoraire jours, Set<DayOfWeek> joursSemaine,
            LocalDate dateDebut, LocalDate dateFin, Set<LocalDate> dates, List<FenetreView> fenetres,
            String motif) {
    }

    /** One window of a rule; a {@code null} {@code heureFin} means "until closing time". */
    public record FenetreView(LocalTime heureDebut, LocalTime heureFin) {
    }

    public record EmplacementView(String id, String nom, Double latitude, Double longitude) {
    }
}
