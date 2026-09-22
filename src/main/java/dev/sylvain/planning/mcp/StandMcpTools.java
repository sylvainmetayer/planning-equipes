package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.HoraireCompaction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.referentiel.WrittenStand;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
@RefusMetier
@Journalise
@ApplicationScoped
public class StandMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    /* -------------------------------- Stands ------------------------------- */

    @Tool(
            description = "Liste les stands, avec leurs typologies proposées, effectifs requis, "
                    + "réserve majeurs/premium, niveau d'effort, emplacement, horaires récurrents et plages datées "
                    + "(fermetures/ouvertures) qui les surchargent. Sans limite, renvoie tous les stands ; total dit "
                    + "toujours combien il y en a.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    StandsView lister_stands(
            @ToolArg(description = "Nombre maximum de stands renvoyés (défaut : tous)", required = false)
                    Integer limite,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<Stand> stands = referenceDataService.listStands();
        return new StandsView(
                stands.size(),
                stands.stream()
                        .limit(McpArgs.limite(limite, stands.size()))
                        .map(StandMcpTools::toView)
                        .toList());
    }

    @Tool(
            description = "Consulte un stand par son id.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    StandView consulter_stand(
            @ToolArg(description = "Id du stand") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(findStand(id));
    }

    @Tool(
            description = "Crée un stand. Les typologies proposées doivent exister dans le référentiel des typologies.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    WrittenStandView creer_stand(
            @ToolArg(description = "Id du stand (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Ids de typologies de jeu proposées", required = false)
                    List<String> typologiesProposees,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium (nécessite un animateur référent)", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(
                            description = "Emplacement géographique : son code (ex. « PAVILLON ») ou son id numérique",
                            required = false)
                    String emplacement,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = new Stand();
        stand.setId(id);
        stand.setNom(nom);
        stand.setTypologiesProposees(
                typologiesProposees == null ? new HashSet<>() : new HashSet<>(typologiesProposees));
        stand.setEffectifMin(effectifMin == null ? 1 : effectifMin);
        stand.setEffectifMax(effectifMax == null ? Math.max(1, stand.getEffectifMin()) : effectifMax);
        stand.setReserveMajeurs(Boolean.TRUE.equals(reserveMajeurs));
        stand.setPremium(Boolean.TRUE.equals(premium));
        stand.setNiveauEffort(
                niveauEffort == null
                        ? NiveauEffort.NORMAL
                        : McpArgs.enumeration(NiveauEffort.class, niveauEffort, "niveauEffort"));
        stand.setEmplacement(emplacement == null ? null : findEmplacement(emplacement));
        return written(referenceDataService.writeStand(stand));
    }

    @Tool(
            description = "Crée un stand ET tout ce dont il dépend en un seul appel : son emplacement, ses "
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
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    CreationStandComplet creer_stand_complet(
            @ToolArg(description = "Id du stand (unique)") String id,
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(description = "Ids de typologies de jeu proposées", required = false)
                    List<String> typologiesProposees,
            @ToolArg(description = "Créer les typologies absentes du référentiel au lieu d'échouer", required = false)
                    Boolean creerTypologiesManquantes,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium (nécessite un animateur référent)", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(
                            description = "Emplacement géographique : son code (ex. « PAVILLON ») ou son id numérique",
                            required = false)
                    String emplacement,
            @ToolArg(description = "Nom de l'emplacement, à créer s'il n'existe pas encore", required = false)
                    String emplacementNom,
            @ToolArg(description = "Latitude de l'emplacement créé", required = false) Double latitude,
            @ToolArg(description = "Longitude de l'emplacement créé", required = false) Double longitude,
            @ToolArg(
                            description =
                                    "Fenêtres d'ouverture, ex. « 10:00-12:00,14:00- » ; « @N » nomme l'effectif d'une "
                                            + "fenêtre (ex. « 10:00-12:00@2,14:00-@4 »)",
                            required = false)
                    String horaires,
            @ToolArg(description = "Portée des horaires : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false)
                    String horairesJours,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false)
                    List<String> horairesJoursSemaine,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ) si portée PLAGE", required = false)
                    String horairesDateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ) si portée PLAGE", required = false)
                    String horairesDateFin,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> horairesDates,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        List<TypologieItem> typologiesACreer =
                missingTypologies(typologiesProposees, Boolean.TRUE.equals(creerTypologiesManquantes));
        Emplacement emplacementACreer = missingEmplacement(emplacement, emplacementNom, latitude, longitude);

        Stand stand = new Stand();
        stand.setId(id);
        stand.setNom(nom);
        stand.setTypologiesProposees(
                typologiesProposees == null ? new HashSet<>() : new HashSet<>(typologiesProposees));
        stand.setEffectifMin(effectifMin == null ? 1 : effectifMin);
        stand.setEffectifMax(effectifMax == null ? Math.max(1, stand.getEffectifMin()) : effectifMax);
        stand.setReserveMajeurs(Boolean.TRUE.equals(reserveMajeurs));
        stand.setPremium(Boolean.TRUE.equals(premium));
        stand.setNiveauEffort(
                niveauEffort == null
                        ? NiveauEffort.NORMAL
                        : McpArgs.enumeration(NiveauEffort.class, niveauEffort, "niveauEffort"));
        stand.setEmplacement(
                emplacementACreer != null
                        ? emplacementACreer
                        : emplacement == null ? null : findEmplacement(emplacement));
        if (horaires != null && !horaires.isBlank()) {
            stand.getHoraires()
                    .add(horaireOuverture(
                            horaires,
                            horairesJours,
                            horairesJoursSemaine,
                            horairesDateDebut,
                            horairesDateFin,
                            horairesDates));
        }
        // One transaction for the three: a stand refused on its own validation
        // no longer leaves a typologie and an emplacement behind that the
        // answer — which never came — was meant to enumerate.
        WrittenStand ecrit = referenceDataService.writeStand(stand, typologiesACreer, emplacementACreer);
        return new CreationStandComplet(
                toView(ecrit.stand()),
                emplacementACreer == null ? null : emplacementACreer.getCode(),
                typologiesACreer.stream().map(TypologieItem::id).toList(),
                WarningCodes.of(ecrit.avertissements()));
    }

    /**
     * The typologies the stand names and the referential does not hold — to
     * be written with it, in the same transaction, when allowed. Opt-in
     * because refusing an unknown typologie is a feature: it is what turns
     * "NIJNA" into an error instead of into a second, near-identical entry
     * nobody notices until the solver finds no competent animateur for it.
     */
    private List<TypologieItem> missingTypologies(List<String> typologies, boolean autorise) {
        if (typologies == null || typologies.isEmpty() || !autorise) {
            return List.of();
        }
        Set<String> connues = referenceDataService.listTypologies().stream()
                .map(TypologieItem::id)
                .collect(Collectors.toCollection(HashSet::new));
        List<TypologieItem> manquantes = new ArrayList<>();
        for (String typologie : typologies) {
            if (connues.add(typologie)) {
                manquantes.add(new TypologieItem(typologie, typologie));
            }
        }
        return manquantes;
    }

    /**
     * The emplacement to write with the stand, or {@code null} when it exists
     * or was not named.
     *
     * <p>An emplacement to create is named by its <b>code</b> and never by an
     * id: the id is minted by the database (decision 0049, D1), so a
     * designation that is only a number can name an existing row but can never
     * describe one to create.</p>
     */
    private Emplacement missingEmplacement(String designation, String nom, Double latitude, Double longitude) {
        if (designation == null || designation.isBlank() || nom == null || nom.isBlank()) {
            return null;
        }
        if (findEmplacementOrNull(designation) != null) {
            return null;
        }
        if (isNumeric(designation)) {
            throw new NoSuchElementException("Emplacement introuvable : " + designation
                    + ". Un emplacement à créer se nomme par son code, pas par un id.");
        }
        Emplacement emplacement = new Emplacement(null, nom, latitude, longitude);
        emplacement.setCode(designation.trim());
        return emplacement;
    }

    private static HoraireStand horaireOuverture(
            String fenetres,
            String jours,
            List<String> joursSemaine,
            String dateDebut,
            String dateFin,
            List<String> dates) {
        HoraireStand horaire = new HoraireStand();
        horaire.setMode(ModeHoraire.OUVERTURE);
        horaire.setJours(
                jours == null
                        ? TypeJoursHoraire.TOUS
                        : McpArgs.enumeration(TypeJoursHoraire.class, jours, "horairesJours"));
        horaire.setJoursSemaine(McpArgs.joursSemaine(joursSemaine, "horairesJoursSemaine"));
        horaire.setDateDebut(McpArgs.date(dateDebut, "horairesDateDebut"));
        horaire.setDateFin(McpArgs.date(dateFin, "horairesDateFin"));
        horaire.setDates(new TreeSet<>(McpArgs.dates(dates, "horairesDates")));
        horaire.setFenetres(McpArgs.fenetres(fenetres, false, true));
        return horaire;
    }

    /**
     * @param emplacementCree  code of the emplacement created along the way, {@code null} if none
     * @param typologiesCreees ids of the typologies created along the way, empty if none
     */
    public record CreationStandComplet(
            StandView stand, String emplacementCree, List<String> typologiesCreees, List<String> avertissements) {}

    @Tool(
            description = "Modifie un stand. Seuls les champs fournis sont modifiés ; les fermetures et ouvertures "
                    + "se gèrent avec les outils dédiés.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    WrittenStandView modifier_stand(
            @ToolArg(description = "Id du stand") String id,
            @ToolArg(description = "Nom affiché", required = false) String nom,
            @ToolArg(description = "Ids de typologies proposées (remplace la liste existante)", required = false)
                    List<String> typologiesProposees,
            @ToolArg(description = "Nombre minimum d'animateurs par créneau", required = false) Integer effectifMin,
            @ToolArg(description = "Nombre maximum d'animateurs par créneau", required = false) Integer effectifMax,
            @ToolArg(description = "Réservé aux animateurs majeurs", required = false) Boolean reserveMajeurs,
            @ToolArg(description = "Stand premium", required = false) Boolean premium,
            @ToolArg(description = "Niveau d'effort : NORMAL ou EPUISANT", required = false) String niveauEffort,
            @ToolArg(
                            description = "Emplacement géographique : son code (ex. « PAVILLON ») ou son id numérique",
                            required = false)
                    String emplacement,
            @ToolArg(
                            description =
                                    "WriteStamp modifieLe lu avant la modification (précondition : refusé si la fiche a changé depuis ; omis, pas de contrôle)",
                            required = false)
                    String modifieLe,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(id);
        if (modifieLe != null) {
            stand.setModifieLe(McpArgs.instant(modifieLe, "modifieLe"));
        }
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
        if (emplacement != null) {
            stand.setEmplacement(findEmplacement(emplacement));
        }
        return written(referenceDataService.writeStand(id, stand));
    }

    @Tool(
            description = "Supprime un stand. Emporte aussi les postes du planning enregistré qui "
                    + "étaient ouverts sur ce stand : contrairement à un animateur, un poste ne peut pas "
                    + "survivre à son stand. Refusé (409) tant qu'une résolution est en cours sur cette édition, sinon elle "
                    + "réinsérerait le stand en enregistrant son résultat.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult supprimer_stand(
            @ToolArg(description = "Id du stand") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        referenceDataService.deleteStand(id);
        return new SuppressionResult(id, true);
    }

    @Tool(
            description =
                    "Ajoute une fermeture (indisponibilité) datée sur un stand : le stand ne peut pas être armé "
                            + "entre ces heures ce jour-là. Une fenêtre ne peut pas chevaucher minuit — dans ce cas, en saisir deux. "
                            + "Omettre heureFin ferme jusqu'à la fermeture du jour. Une plage datée prime sur les horaires "
                            + "récurrents du stand pour ce jour-là ; pour un motif qui se répète, préférer ajouter_horaire_stand.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    WrittenStandView ajouter_fermeture_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM) ; omise = jusqu'à la fermeture", required = false)
                    String heureFin,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        stand.getIndisponibilites()
                .add(new IndisponibiliteStand(
                        null,
                        McpArgs.date(date, "date"),
                        McpArgs.heure(heureDebut, "heureDebut"),
                        endTimeOrClosing(heureFin),
                        motif));
        return written(referenceDataService.writeStand(standId, stand));
    }

    @Tool(
            description =
                    "Ajoute une ouverture datée sur un stand : ce jour-là, le stand n'est armé QUE sur cette "
                            + "plage. Un même jour ne peut pas porter à la fois une fermeture et une ouverture. Omettre heureFin "
                            + "ouvre jusqu'à la fermeture du jour. Pour un motif qui se répète, préférer ajouter_horaire_stand.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    WrittenStandView ajouter_ouverture_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = "Heure de début (HH:MM)") String heureDebut,
            @ToolArg(description = "Heure de fin (HH:MM) ; omise = jusqu'à la fermeture", required = false)
                    String heureFin,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif,
            @ToolArg(
                            description = "Effectif à pourvoir sur cette ouverture (au moins 1) ; omis = l'effectif "
                                    + "minimum du stand",
                            required = false)
                    Integer effectif,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        stand.getOuvertures()
                .add(new OuvertureStand(
                        null,
                        McpArgs.date(date, "date"),
                        McpArgs.heure(heureDebut, "heureDebut"),
                        endTimeOrClosing(heureFin),
                        motif,
                        effectif));
        return written(referenceDataService.writeStand(standId, stand));
    }

    /** {@code null} — "until closing time" — for an omitted or empty end hour. */
    private static LocalTime endTimeOrClosing(String heureFin) {
        return heureFin == null || heureFin.isBlank() ? null : McpArgs.heure(heureFin, "heureFin");
    }

    @Tool(
            description = "Retire toutes les fermetures et ouvertures d'un stand pour une date donnée. Les horaires "
                    + "récurrents ne sont pas touchés : la date redevient donc gouvernée par eux, s'il en existe.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    WrittenStandView effacer_plages_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "Date (AAAA-MM-JJ)") String date,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        LocalDate jour = McpArgs.date(date, "date");
        stand.getIndisponibilites().removeIf(indispo -> jour.equals(indispo.getDate()));
        stand.getOuvertures().removeIf(ouverture -> jour.equals(ouverture.getDate()));
        return written(referenceDataService.writeStand(standId, stand));
    }

    @Tool(
            description = "Ajoute un horaire récurrent sur un stand : une seule règle au lieu d'une plage datée par "
                    + "jour d'événement. mode=OUVERTURE veut dire « fermé sauf sur ces fenêtres », mode=FERMETURE « ouvert "
                    + "sauf sur ces fenêtres ». La portée jours vaut TOUS (tous les jours), JOURS_SEMAINE (avec "
                    + "joursSemaine), PLAGE (avec dateDebut/dateFin) ou DATES (avec dates). Les fenêtres se saisissent "
                    + "« HH:MM-HH:MM », ou « HH:MM- » pour courir jusqu'à la fermeture du jour ; plusieurs fenêtres se "
                    + "séparent par une virgule (ex. « 10:00-12:00,14:00- » pour une coupure méridienne). Une plage datée "
                    + "existante prime toujours sur les règles, pour le jour qu'elle nomme.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    WrittenStandView ajouter_horaire_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = "OUVERTURE ou FERMETURE") String mode,
            @ToolArg(
                            description = "Fenêtres, ex. « 10:00-12:00,14:00- » ; un suffixe « @N » nomme l'effectif à "
                                    + "pourvoir sur la fenêtre (ex. « 10:00-12:00@2,14:00-@4 »), sans lui c'est l'effectif minimum "
                                    + "du stand")
                    String fenetres,
            @ToolArg(description = "Portée : TOUS, JOURS_SEMAINE, PLAGE ou DATES", required = false) String jours,
            @ToolArg(description = "Jours de la semaine (MONDAY…SUNDAY) si portée JOURS_SEMAINE", required = false)
                    List<String> joursSemaine,
            @ToolArg(description = "Début de la plage (AAAA-MM-JJ) si portée PLAGE", required = false) String dateDebut,
            @ToolArg(description = "Fin de la plage (AAAA-MM-JJ) si portée PLAGE", required = false) String dateFin,
            @ToolArg(description = "Dates (AAAA-MM-JJ) si portée DATES", required = false) List<String> dates,
            @ToolArg(description = "Motif, purement informatif", required = false) String motif,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        HoraireStand horaire = new HoraireStand();
        horaire.setMode(McpArgs.enumeration(ModeHoraire.class, mode, "mode"));
        horaire.setJours(
                jours == null ? TypeJoursHoraire.TOUS : McpArgs.enumeration(TypeJoursHoraire.class, jours, "jours"));
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
            horaire.setDates(dates.stream()
                    .map(date -> McpArgs.date(date, "dates"))
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
        horaire.setFenetres(McpArgs.fenetres(fenetres, false, true));
        horaire.setMotif(motif);
        stand.getHoraires().add(horaire);
        return written(referenceDataService.writeStand(standId, stand));
    }

    @Tool(
            description = "Retire tous les horaires récurrents d'un stand. Ses plages datées restent en place.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    WrittenStandView effacer_horaires_stand(
            @ToolArg(description = "Id du stand") String standId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Stand stand = findStand(standId);
        stand.getHoraires().clear();
        return written(referenceDataService.writeStand(standId, stand));
    }

    @Tool(
            description = "Réécrit les plages datées saisies à la main en horaires récurrents équivalents, pour "
                    + "tous les stands de l'édition : c'est la façon dont un jeu de données antérieur aux règles les "
                    + "rattrape. appliquer=false (défaut) est une simulation qui décrit exactement ce qui serait fait, "
                    + "stand par stand, sans rien écrire ; seul appliquer=true persiste.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    HoraireCompaction.RapportCompactage compacter_horaires_stands(
            @ToolArg(description = "Écrire vraiment le résultat (défaut : simulation)", required = false)
                    Boolean appliquer,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.compactHoraires(Boolean.TRUE.equals(appliquer));
    }

    /* ----------------------------- Emplacements ---------------------------- */

    @Tool(
            description = "Liste les emplacements géographiques (utilisés pour limiter les déplacements entre stands).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<EmplacementView> lister_emplacements(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listEmplacements().stream()
                .map(StandMcpTools::toView)
                .toList();
    }

    @Tool(
            description = "Crée un emplacement géographique.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    EmplacementView creer_emplacement(
            @ToolArg(description = "Nom affiché") String nom,
            @ToolArg(
                            description =
                                    "Code métier, unique dans l'édition (ex. « PAVILLON ») : c'est lui que portent les "
                                            + "fichiers CSV et les scénarios. Facultatif ; l'id, lui, est attribué par la base.",
                            required = false)
                    String code,
            @ToolArg(description = "Latitude (-90 à 90)", required = false) Double latitude,
            @ToolArg(description = "Longitude (-180 à 180)", required = false) Double longitude,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Emplacement emplacement = new Emplacement(null, nom, latitude, longitude);
        emplacement.setCode(code);
        return toView(referenceDataService.createEmplacement(emplacement));
    }

    @Tool(
            description = "Modifie un emplacement. Seuls les champs fournis sont modifiés.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EmplacementView modifier_emplacement(
            @ToolArg(description = "Emplacement géographique : son code (ex. « PAVILLON ») ou son id numérique")
                    String emplacement,
            @ToolArg(description = "Nom affiché", required = false) String nom,
            @ToolArg(description = "Code métier, unique dans l'édition ; « » l'efface", required = false) String code,
            @ToolArg(description = "Latitude", required = false) Double latitude,
            @ToolArg(description = "Longitude", required = false) Double longitude,
            @ToolArg(
                            description =
                                    "WriteStamp modifieLe lu avant la modification (précondition : refusé si la fiche a changé depuis ; omis, pas de contrôle)",
                            required = false)
                    String modifieLe,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Emplacement ecrit = findEmplacement(emplacement);
        if (modifieLe != null) {
            ecrit.setModifieLe(McpArgs.instant(modifieLe, "modifieLe"));
        }
        if (nom != null) {
            ecrit.setNom(nom);
        }
        if (code != null) {
            ecrit.setCode(code);
        }
        if (latitude != null) {
            ecrit.setLatitude(latitude);
        }
        if (longitude != null) {
            ecrit.setLongitude(longitude);
        }
        return toView(referenceDataService.updateEmplacement(ecrit.getId(), ecrit));
    }

    @Tool(
            description = "Supprime un emplacement.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult supprimer_emplacement(
            @ToolArg(description = "Emplacement géographique : son code (ex. « PAVILLON ») ou son id numérique")
                    String emplacement,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Emplacement cible = findEmplacement(emplacement);
        referenceDataService.deleteEmplacement(cible.getId());
        return new SuppressionResult(String.valueOf(cible.getId()), true);
    }

    /* ------------------------------ Typologies ----------------------------- */

    @Tool(
            description = "Liste les typologies de jeu (référentiel CRUD auquel se réfèrent les compétences des "
                    + "animateurs et les typologies proposées par les stands).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<TypologieItem> lister_typologies(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.listTypologies();
    }

    @Tool(
            description = "Crée une typologie de jeu.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    TypologieItem creer_typologie(
            @ToolArg(description = "Id de la typologie (unique)") String id,
            @ToolArg(description = "Libellé affiché", required = false) String label,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return referenceDataService.createTypologie(new TypologieItem(id, label));
    }

    @Tool(
            description = "Renomme une typologie de jeu.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    TypologieItem modifier_typologie(
            @ToolArg(description = "Id de la typologie") String id,
            @ToolArg(description = "Nouveau libellé") String label,
            @ToolArg(
                            description =
                                    "WriteStamp modifieLe lu avant la modification (précondition : refusé si la fiche a changé depuis ; omis, pas de contrôle)",
                            required = false)
                    String modifieLe,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        // The ninja flag is carried over: a rename must not demote the ninja
        // typologie. Same for the per-typologie cap (issue #594) and for the
        // organiser's note: an update replaces the whole row, and this tool
        // renames — it lifts no ceiling and erases no note somebody wrote on
        // the Typologies screen.
        boolean ninja = referenceDataService.typologieNinja().filter(id::equals).isPresent();
        TypologieItem actuelle = referenceDataService.listTypologies().stream()
                .filter(typologie -> typologie.id().equals(id))
                .findFirst()
                .orElse(null);
        Instant precondition = modifieLe == null ? null : McpArgs.instant(modifieLe, "modifieLe");
        return referenceDataService.updateTypologie(
                id,
                new TypologieItem(
                        id,
                        label,
                        ninja,
                        actuelle == null ? null : actuelle.maxCreneauxParAnimateur(),
                        actuelle == null ? null : actuelle.description(),
                        precondition));
    }

    @Tool(
            description =
                    "Supprime une typologie de jeu. Refusé tant qu'elle est référencée par un stand ou un animateur.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult supprimer_typologie(
            @ToolArg(description = "Id de la typologie") String id,
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

    /**
     * The emplacement a designation names: <b>its code first, its numeric id
     * second</b>, and an ambiguity refused rather than arbitrated (decision
     * 0049, D5). An assistant knows « PAVILLON »; the id is a number the
     * database minted, and a code that happens to read as one must not be
     * silently overtaken by it.
     */
    private Emplacement findEmplacement(String designation) {
        Emplacement trouve = findEmplacementOrNull(designation);
        if (trouve == null) {
            throw new NoSuchElementException("Emplacement introuvable : " + designation);
        }
        return trouve;
    }

    private Emplacement findEmplacementOrNull(String designation) {
        if (designation == null || designation.isBlank()) {
            return null;
        }
        String cherche = designation.trim();
        List<Emplacement> emplacements = referenceDataService.listEmplacements();
        Emplacement parCode = emplacements.stream()
                .filter(emplacement ->
                        emplacement.getCode() != null && emplacement.getCode().equalsIgnoreCase(cherche))
                .findFirst()
                .orElse(null);
        Emplacement parId = isNumeric(cherche)
                ? emplacements.stream()
                        .filter(emplacement ->
                                emplacement.getId() != null && emplacement.getId() == Long.parseLong(cherche))
                        .findFirst()
                        .orElse(null)
                : null;
        if (parCode != null && parId != null && !parCode.equals(parId)) {
            throw new BusinessError.Invalid("« " + cherche
                    + " » est à la fois le code d'un emplacement et l'id d'un autre : nommez-en un sans ambiguïté.");
        }
        return parCode != null ? parCode : parId;
    }

    private static boolean isNumeric(String valeur) {
        if (valeur.isEmpty() || valeur.length() > 18) {
            return false;
        }
        for (int i = 0; i < valeur.length(); i++) {
            if (!Character.isDigit(valeur.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static WrittenStandView written(WrittenStand ecrit) {
        return new WrittenStandView(toView(ecrit.stand()), WarningCodes.of(ecrit.avertissements()));
    }

    static StandView toView(Stand stand) {
        List<PlageView> fermetures = new ArrayList<>();
        stand.getIndisponibilites()
                .forEach(indispo -> fermetures.add(new PlageView(
                        indispo.getDate(), indispo.getHeureDebut(), indispo.getHeureFin(), indispo.getMotif(), null)));
        List<PlageView> ouvertures = new ArrayList<>();
        stand.getOuvertures()
                .forEach(ouverture -> ouvertures.add(new PlageView(
                        ouverture.getDate(),
                        ouverture.getHeureDebut(),
                        ouverture.getHeureFin(),
                        ouverture.getMotif(),
                        ouverture.getEffectif())));
        List<HoraireView> horaires = new ArrayList<>();
        stand.getHoraires()
                .forEach(horaire -> horaires.add(new HoraireView(
                        horaire.getMode(),
                        horaire.getJours(),
                        horaire.getJoursSemaine(),
                        horaire.getDateDebut(),
                        horaire.getDateFin(),
                        horaire.getDates(),
                        horaire.getFenetres().stream()
                                .map(fenetre -> new FenetreView(
                                        fenetre.getHeureDebut(), fenetre.getHeureFin(), fenetre.getEffectif()))
                                .toList(),
                        horaire.getMotif())));
        return new StandView(
                stand.getId(),
                stand.getNom(),
                stand.getTypologiesProposees(),
                stand.getEffectifMin(),
                stand.getEffectifMax(),
                stand.isReserveMajeurs(),
                stand.isPremium(),
                stand.getNiveauEffort(),
                stand.getEmplacement() == null ? null : stand.getEmplacement().getId(),
                stand.getEmplacement() == null ? null : stand.getEmplacement().getCode(),
                fermetures,
                ouvertures,
                horaires,
                stand.getModifieLe());
    }

    static EmplacementView toView(Emplacement emplacement) {
        return new EmplacementView(
                emplacement.getId(),
                emplacement.getCode(),
                emplacement.getNom(),
                emplacement.getLatitude(),
                emplacement.getLongitude(),
                emplacement.getModifieLe());
    }

    /** @param total stands in the edition, which may exceed the number returned */
    public record StandsView(int total, List<StandView> stands) {}

    /** A write and its warnings as codes — the REST {@code WrittenStand}, seen from MCP. */
    public record WrittenStandView(StandView stand, List<String> avertissements) {}

    public record StandView(
            String id,
            String nom,
            Set<String> typologiesProposees,
            int effectifMin,
            int effectifMax,
            boolean reserveMajeurs,
            boolean premium,
            NiveauEffort niveauEffort,
            Long emplacementId,
            String emplacementCode,
            List<PlageView> fermetures,
            List<PlageView> ouvertures,
            List<HoraireView> horaires,
            Instant modifieLe) {}

    /**
     * A dated exception window; a {@code null} {@code heureFin} means "until
     * closing time". {@code effectif} is the seats an opening names, {@code null}
     * for the stand's minimum — and always {@code null} on a closure.
     */
    public record PlageView(LocalDate date, LocalTime heureDebut, LocalTime heureFin, String motif, Integer effectif) {}

    /** A recurring rule: which days, and the windows those days carry. */
    public record HoraireView(
            ModeHoraire mode,
            TypeJoursHoraire jours,
            Set<DayOfWeek> joursSemaine,
            LocalDate dateDebut,
            LocalDate dateFin,
            Set<LocalDate> dates,
            List<FenetreView> fenetres,
            String motif) {}

    /**
     * One window of a rule; a {@code null} {@code heureFin} means "until closing
     * time", a {@code null} {@code effectif} the stand's minimum headcount.
     */
    public record FenetreView(LocalTime heureDebut, LocalTime heureFin, Integer effectif) {}

    public record EmplacementView(
            Long id, String code, String nom, Double latitude, Double longitude, Instant modifieLe) {}
}
