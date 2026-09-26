package dev.sylvain.planning.service.journal;

import dev.sylvain.planning.service.journal.ActionJournalisee.Entite;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every action the application knows how to write down, and how it reads in
 * French — the business inventory of the history, the same way
 * {@code ConstraintCatalog} is the business inventory of the solver's rules.
 *
 * <p>Three maps, and the split is the point. {@link #ACTIONS} says what an
 * action <em>is</em>; {@link #ROUTES} and {@link #OUTILS} say which entry
 * point performs it — the REST resource method, and the MCP tool. The same
 * action reached both ways writes the same line, because an organiser reading
 * the history should not have to know whether a stand was created from a
 * screen or by an assistant.</p>
 *
 * <p>{@link #SANS_TRACE} is the fourth list and the one that keeps the other
 * three honest: a {@code POST} that computes and writes nothing — a preview, a
 * simulation, a dry-run analysis — is <b>not</b> an action, and saying so here
 * with a reason is what allows {@code JournalCoverageStructurelleTest} to
 * fail on any write entry point that is in neither list. Nothing can be left
 * out by simply forgetting it.</p>
 */
public final class CatalogueActions {

    private CatalogueActions() {}

    // The action codes, each named in more than one map below: a typo is then a compile error.
    private static final String ANIMATEUR_CREE = "ANIMATEUR_CREE";
    private static final String ANIMATEUR_MODIFIE = "ANIMATEUR_MODIFIE";
    private static final String ANIMATEUR_SUPPRIME = "ANIMATEUR_SUPPRIME";
    private static final String ANIMATEUR_JETON_REGENERE = "ANIMATEUR_JETON_REGENERE";
    private static final String ANIMATEURS_IMPORTES = "ANIMATEURS_IMPORTES";
    private static final String TYPOLOGIES_IMPORTEES = "TYPOLOGIES_IMPORTEES";
    private static final String EMPLACEMENTS_IMPORTES = "EMPLACEMENTS_IMPORTES";
    private static final String STANDS_IMPORTES = "STANDS_IMPORTES";
    private static final String ANIMATEUR_COMPETENCES_GRILLE = "ANIMATEUR_COMPETENCES_GRILLE";
    private static final String ANIMATEURS_RELANCES = "ANIMATEURS_RELANCES";
    private static final String STAND_CREE = "STAND_CREE";
    private static final String STAND_MODIFIE = "STAND_MODIFIE";
    private static final String STAND_SUPPRIME = "STAND_SUPPRIME";
    private static final String STAND_HORAIRES_COMPACTES = "STAND_HORAIRES_COMPACTES";
    private static final String STAND_HORAIRE_AJOUTE = "STAND_HORAIRE_AJOUTE";
    private static final String STAND_HORAIRES_EFFACES = "STAND_HORAIRES_EFFACES";
    private static final String STAND_PLAGE_AJOUTEE = "STAND_PLAGE_AJOUTEE";
    private static final String STAND_PLAGES_EFFACEES = "STAND_PLAGES_EFFACEES";
    private static final String OUVERTURES_SAISIES = "OUVERTURES_SAISIES";
    private static final String CRENEAU_CREE = "CRENEAU_CREE";
    private static final String CRENEAU_MODIFIE = "CRENEAU_MODIFIE";
    private static final String CRENEAU_SUPPRIME = "CRENEAU_SUPPRIME";
    private static final String CRENEAUX_SUPPRIMES = "CRENEAUX_SUPPRIMES";
    private static final String CRENEAUX_RECURRENTS_CREES = "CRENEAUX_RECURRENTS_CREES";
    private static final String CRENEAUX_DERIVES = "CRENEAUX_DERIVES";
    private static final String CRENEAUX_IMPORTES = "CRENEAUX_IMPORTES";
    private static final String JOURNEE_TYPE_CREEE = "JOURNEE_TYPE_CREEE";
    private static final String JOURNEE_TYPE_MODIFIEE = "JOURNEE_TYPE_MODIFIEE";
    private static final String JOURNEE_TYPE_DEFINIE = "JOURNEE_TYPE_DEFINIE";
    private static final String JOURNEE_TYPE_SUPPRIMEE = "JOURNEE_TYPE_SUPPRIMEE";
    private static final String CALENDRIER_JOURNEES_TYPES_MODIFIE = "CALENDRIER_JOURNEES_TYPES_MODIFIE";
    private static final String JOURNEES_TYPES_APPLIQUEES = "JOURNEES_TYPES_APPLIQUEES";
    private static final String JOURNEES_TYPES_RECONNUES = "JOURNEES_TYPES_RECONNUES";
    private static final String JOURNEES_TYPES_IMPORTEES = "JOURNEES_TYPES_IMPORTEES";
    private static final String EMPLACEMENT_CREE = "EMPLACEMENT_CREE";
    private static final String EMPLACEMENT_MODIFIE = "EMPLACEMENT_MODIFIE";
    private static final String EMPLACEMENT_SUPPRIME = "EMPLACEMENT_SUPPRIME";
    private static final String TYPOLOGIE_CREEE = "TYPOLOGIE_CREEE";
    private static final String TYPOLOGIE_MODIFIEE = "TYPOLOGIE_MODIFIEE";
    private static final String TYPOLOGIE_SUPPRIMEE = "TYPOLOGIE_SUPPRIMEE";
    private static final String AJUSTEMENT_CREE = "AJUSTEMENT_CREE";
    private static final String AJUSTEMENT_SUPPRIME = "AJUSTEMENT_SUPPRIME";
    private static final String VERROU_POSE = "VERROU_POSE";
    private static final String VERROU_RETIRE = "VERROU_RETIRE";
    private static final String JOURNEE_VALIDEE = "JOURNEE_VALIDEE";
    private static final String JOURNEE_VALIDATION_RETIREE = "JOURNEE_VALIDATION_RETIREE";
    private static final String CONSIGNE_POSEE = "CONSIGNE_POSEE";
    private static final String CONSIGNE_LEVEE = "CONSIGNE_LEVEE";
    private static final String PREREGLAGE_CONSIGNE_ENREGISTRE = "PREREGLAGE_CONSIGNE_ENREGISTRE";
    private static final String PREREGLAGE_CONSIGNE_SUPPRIME = "PREREGLAGE_CONSIGNE_SUPPRIME";
    private static final String EDITION_CREEE = "EDITION_CREEE";
    private static final String EDITION_RENOMMEE = "EDITION_RENOMMEE";
    private static final String EDITION_DUPLIQUEE = "EDITION_DUPLIQUEE";
    private static final String EDITION_PAR_DEFAUT = "EDITION_PAR_DEFAUT";
    private static final String EDITION_SUPPRIMEE = "EDITION_SUPPRIMEE";
    private static final String GEL_POSE = "GEL_POSE";
    private static final String GEL_LEVE = "GEL_LEVE";
    private static final String SOLVE_LANCE = "SOLVE_LANCE";
    private static final String SOLVE_INCREMENTAL_LANCE = "SOLVE_INCREMENTAL_LANCE";
    private static final String SOLVE_ARRETE = "SOLVE_ARRETE";
    private static final String JOB_SUPPRIME = "JOB_SUPPRIME";
    private static final String PLANNING_REINITIALISE = "PLANNING_REINITIALISE";
    private static final String AFFECTATION_DEPLACEE = "AFFECTATION_DEPLACEE";
    private static final String AFFECTATION_POSEE = "AFFECTATION_POSEE";
    private static final String ABSENCE_ENREGISTREE = "ABSENCE_ENREGISTREE";
    private static final String ABSENCE_ANNULEE = "ABSENCE_ANNULEE";
    private static final String INSTANTANE_CAPTURE = "INSTANTANE_CAPTURE";
    private static final String INSTANTANE_RESTAURE = "INSTANTANE_RESTAURE";
    private static final String INSTANTANE_SUPPRIME = "INSTANTANE_SUPPRIME";
    private static final String PLANNING_PUBLIE = "PLANNING_PUBLIE";
    private static final String PLANNING_ENVOYE = "PLANNING_ENVOYE";
    private static final String MAIL_TEST_ENVOYE = "MAIL_TEST_ENVOYE";
    private static final String KPI_SUPPRIME = "KPI_SUPPRIME";
    private static final String EXPORT_PDF = "EXPORT_PDF";
    private static final String EXPORT_PDF_ANIMATEUR = "EXPORT_PDF_ANIMATEUR";
    private static final String EXPORT_ICS = "EXPORT_ICS";
    private static final String EXPORT_ICS_ANIMATEUR = "EXPORT_ICS_ANIMATEUR";
    private static final String EXPORT_ARCHIVE = "EXPORT_ARCHIVE";
    private static final String EXPORT_HEURES = "EXPORT_HEURES";
    private static final String EXPORT_BASE = "EXPORT_BASE";
    private static final String EXPORT_PDF_GLOBAL = "EXPORT_PDF_GLOBAL";
    private static final String EXPORT_REFERENTIELS = "EXPORT_REFERENTIELS";
    private static final String EXPORT_SCENARIO = "EXPORT_SCENARIO";
    private static final String EXPORT_EQUITE = "EXPORT_EQUITE";
    private static final String EXPORT_RELECTURE = "EXPORT_RELECTURE";
    private static final String EXPORT_INTENDANCE = "EXPORT_INTENDANCE";
    private static final String EXPORT_ARCHIVE_EVENEMENT = "EXPORT_ARCHIVE_EVENEMENT";
    private static final String EXPORT_FORMATION = "EXPORT_FORMATION";
    private static final String SCENARIO_IMPORTE = "SCENARIO_IMPORTE";
    private static final String BASE_IMPORTEE = "BASE_IMPORTEE";
    private static final String PARAMETRES_LEGAUX_MODIFIES = "PARAMETRES_LEGAUX_MODIFIES";
    private static final String PARAMETRES_QUALITE_MODIFIES = "PARAMETRES_QUALITE_MODIFIES";
    private static final String PARAMETRES_SOLVEUR_MODIFIES = "PARAMETRES_SOLVEUR_MODIFIES";
    private static final String PARAMETRES_NOTIFICATIONS_MODIFIES = "PARAMETRES_NOTIFICATIONS_MODIFIES";
    private static final String CONTRAINTE_ACTIVEE = "CONTRAINTE_ACTIVEE";
    private static final String CONTRAINTE_DESACTIVEE = "CONTRAINTE_DESACTIVEE";
    private static final String CONTRAINTE_PONDEREE = "CONTRAINTE_PONDEREE";
    private static final String SAUVEGARDE_BASCULEE = "SAUVEGARDE_BASCULEE";
    private static final String DATE_JOUR_J_FORCEE = "DATE_JOUR_J_FORCEE";
    private static final String CLE_MCP_REVELEE = "CLE_MCP_REVELEE";
    private static final String AFFICHAGE_MURAL_CREE = "AFFICHAGE_MURAL_CREE";
    private static final String AFFICHAGE_MURAL_REVOQUE = "AFFICHAGE_MURAL_REVOQUE";
    private static final String COLLECTE_CONFIGUREE = "COLLECTE_CONFIGUREE";
    private static final String DECLARATION_APPLIQUEE = "DECLARATION_APPLIQUEE";
    private static final String DECLARATION_REFUSEE = "DECLARATION_REFUSEE";
    private static final String COVOITURAGE_VALIDE = "COVOITURAGE_VALIDE";
    private static final String COVOITURAGE_ECARTE = "COVOITURAGE_ECARTE";
    private static final String COVOITURAGE_ANNULE = "COVOITURAGE_ANNULE";
    private static final String COVOITURAGE_DEMANDE = "COVOITURAGE_DEMANDE";
    private static final String DECLARATION_SOUMISE = "DECLARATION_SOUMISE";
    private static final String FOIRE_CONFIGUREE = "FOIRE_CONFIGUREE";
    private static final String ECHANGE_ACCEPTE = "ECHANGE_ACCEPTE";
    private static final String ECHANGE_REFUSE = "ECHANGE_REFUSE";
    private static final String ECHANGE_SOUMIS = "ECHANGE_SOUMIS";
    private static final String ECHANGE_ACCORDE = "ECHANGE_ACCORDE";
    private static final String ECHANGE_DECLINE = "ECHANGE_DECLINE";
    private static final String PLANNING_CONFIRME = "PLANNING_CONFIRME";
    private static final String ABONNEMENT_CREE = "ABONNEMENT_CREE";
    private static final String ABONNEMENT_ANNULE = "ABONNEMENT_ANNULE";
    private static final String CODE_ESPACE_DEMANDE = "CODE_ESPACE_DEMANDE";
    private static final String SESSION_ESPACE_OUVERTE = "SESSION_ESPACE_OUVERTE";
    private static final String DECONNEXION = "DECONNEXION";
    private static final String TELECHARGEMENT_ESPACE_PDF = "TELECHARGEMENT_ESPACE_PDF";
    private static final String TELECHARGEMENT_ESPACE_ICS = "TELECHARGEMENT_ESPACE_ICS";

    // Reasons shared by several untracked entry points.
    private static final String ANALYSE_FICHIER = "analyse préalable d'un fichier, n'écrit rien";
    private static final String FICHIER_EXEMPLE = "télécharge un fichier d'exemple";
    private static final String PREVISUALISATION = "prévisualisation, n'écrit rien";
    private static final String SIMULATION = "simulation, n'écrit rien";

    private static final Map<String, ActionJournalisee> ACTIONS = new LinkedHashMap<>();

    private static void action(String code, String libelle, Entite entite) {
        ACTIONS.put(code, new ActionJournalisee(code, libelle, entite, false, false));
    }

    /**
     * Same, for an action that changes <b>what a solve would be given</b>: a
     * referential, an ad hoc adjustment, a lock, a parameter, a rule. Those
     * are the ones that make an already-persisted plan out of date, and the
     * two factories are what lets the solver screen say <em>what</em> changed
     * under « des données de référence ont été modifiées » rather than only
     * that something did. An export, a send, a snapshot or a solve itself
     * stays on {@link #action}: it moves the plan or leaves with a copy of it,
     * it does not move the problem.
     */
    private static void changesData(String code, String libelle, Entite entite) {
        ACTIONS.put(code, new ActionJournalisee(code, libelle, entite, true, false));
    }

    /**
     * Same, for a file leaving the application: an export from the
     * administration, a download from an espace. Never {@code changesData}
     * — leaving with a copy moves no problem — and flagged {@code export},
     * which is what the history's « Exports » filter selects on.
     */
    private static void export(String code, String libelle, Entite entite) {
        ACTIONS.put(code, new ActionJournalisee(code, libelle, entite, false, true));
    }

    static {
        /* ------------------------ Animateurs ------------------------ */
        changesData(ANIMATEUR_CREE, "Animateur ajouté", Entite.ANIMATEUR);
        changesData(ANIMATEUR_MODIFIE, "Fiche animateur modifiée", Entite.ANIMATEUR);
        changesData(ANIMATEUR_SUPPRIME, "Animateur supprimé", Entite.ANIMATEUR);
        action(ANIMATEUR_JETON_REGENERE, "Lien d'espace régénéré", Entite.ANIMATEUR);
        changesData(ANIMATEURS_IMPORTES, "Animateurs importés depuis un fichier", Entite.ANIMATEUR);
        changesData(TYPOLOGIES_IMPORTEES, "Typologies importées depuis un fichier", Entite.TYPOLOGIE);
        changesData(EMPLACEMENTS_IMPORTES, "Emplacements importés depuis un fichier", Entite.EMPLACEMENT);
        changesData(STANDS_IMPORTES, "Stands importés depuis un fichier", Entite.STAND);
        changesData(ANIMATEUR_COMPETENCES_GRILLE, "Grille des compétences enregistrée", Entite.ANIMATEUR);
        action(ANIMATEURS_RELANCES, "Animateurs relancés à la main", Entite.ANIMATEUR);

        /* -------------------------- Stands -------------------------- */
        changesData(STAND_CREE, "Stand ajouté", Entite.STAND);
        changesData(STAND_MODIFIE, "Stand modifié", Entite.STAND);
        changesData(STAND_SUPPRIME, "Stand supprimé", Entite.STAND);
        changesData(STAND_HORAIRES_COMPACTES, "Horaires de stands compactés", Entite.STAND);
        changesData(STAND_HORAIRE_AJOUTE, "Horaire de stand ajouté", Entite.STAND);
        changesData(STAND_HORAIRES_EFFACES, "Horaires de stand effacés", Entite.STAND);
        changesData(STAND_PLAGE_AJOUTEE, "Plage d'ouverture ou de fermeture ajoutée", Entite.STAND);
        changesData(STAND_PLAGES_EFFACEES, "Plages d'un stand effacées", Entite.STAND);
        changesData(STANDS_IMPORTES, "Grille de stands importée", Entite.STAND);
        changesData(OUVERTURES_SAISIES, "Grille des ouvertures enregistrée", Entite.STAND);

        /* ------------------------ Timeslots ------------------------- */
        changesData(CRENEAU_CREE, "Créneau ajouté", Entite.CRENEAU);
        changesData(CRENEAU_MODIFIE, "Créneau modifié", Entite.CRENEAU);
        changesData(CRENEAU_SUPPRIME, "Créneau supprimé", Entite.CRENEAU);
        changesData(CRENEAUX_SUPPRIMES, "Créneaux supprimés en lot", Entite.CRENEAU);
        changesData(CRENEAUX_RECURRENTS_CREES, "Créneaux récurrents générés", Entite.CRENEAU);
        changesData(CRENEAUX_DERIVES, "Créneaux dérivés des horaires des stands", Entite.CRENEAU);
        changesData(CRENEAUX_IMPORTES, "Créneaux importés depuis un fichier", Entite.CRENEAU);
        // Day templates (ADR 0032): a template or its calendar changes nothing
        // the solver reads until applied; applying does, recognising does not.
        action(JOURNEE_TYPE_CREEE, "Journée type ajoutée", Entite.CRENEAU);
        action(JOURNEE_TYPE_MODIFIEE, "Journée type modifiée", Entite.CRENEAU);
        action(JOURNEE_TYPE_DEFINIE, "Journée type définie", Entite.CRENEAU);
        action(JOURNEE_TYPE_SUPPRIMEE, "Journée type supprimée", Entite.CRENEAU);
        action(CALENDRIER_JOURNEES_TYPES_MODIFIE, "Calendrier des journées types modifié", Entite.CRENEAU);
        changesData(JOURNEES_TYPES_APPLIQUEES, "Calendrier des journées types appliqué aux créneaux", Entite.CRENEAU);
        action(JOURNEES_TYPES_RECONNUES, "Journées types reconnues depuis les créneaux", Entite.CRENEAU);
        action(JOURNEES_TYPES_IMPORTEES, "Journées types importées depuis un fichier", Entite.CRENEAU);

        /* -------------- Locations and game categories --------------- */
        changesData(EMPLACEMENT_CREE, "Emplacement ajouté", Entite.EMPLACEMENT);
        changesData(EMPLACEMENT_MODIFIE, "Emplacement modifié", Entite.EMPLACEMENT);
        changesData(EMPLACEMENT_SUPPRIME, "Emplacement supprimé", Entite.EMPLACEMENT);
        changesData(TYPOLOGIE_CREEE, "Typologie de jeu ajoutée", Entite.TYPOLOGIE);
        changesData(TYPOLOGIE_MODIFIEE, "Typologie de jeu modifiée", Entite.TYPOLOGIE);
        changesData(TYPOLOGIE_SUPPRIMEE, "Typologie de jeu supprimée", Entite.TYPOLOGIE);

        /* --------------- Ad hoc adjustments and locks --------------- */
        changesData(AJUSTEMENT_CREE, "Ajustement manuel ajouté", Entite.AJUSTEMENT);
        changesData(AJUSTEMENT_SUPPRIME, "Ajustement manuel supprimé", Entite.AJUSTEMENT);
        changesData(VERROU_POSE, "Verrouillage posé", Entite.VERROUILLAGE);
        changesData(VERROU_RETIRE, "Verrouillage retiré", Entite.VERROUILLAGE);
        // A reading changes nothing a solve is given — it says a human went
        // over that day — so neither of these is `changesData`, even when the
        // validation lays a lock down: that lock writes its own VERROU_POSE.
        action(JOURNEE_VALIDEE, "Journée relue et acceptée", Entite.PLANNING);
        action(JOURNEE_VALIDATION_RETIREE, "Validation de journée retirée", Entite.PLANNING);
        // A consigne (issue #4) changes which seats a solve is given — a band
        // closed for every stand, openings chosen, créneaux added to the grid
        // — hence changesData. A preset changes nothing until a consigne is
        // made from it.
        changesData(CONSIGNE_POSEE, "Consigne d'édition posée ou modifiée", Entite.PLANNING);
        changesData(CONSIGNE_LEVEE, "Consigne d'édition levée", Entite.PLANNING);
        action(PREREGLAGE_CONSIGNE_ENREGISTRE, "Préréglage de consigne enregistré", Entite.PLANNING);
        action(PREREGLAGE_CONSIGNE_SUPPRIME, "Préréglage de consigne supprimé", Entite.PLANNING);

        /* ------------------------- Editions ------------------------- */
        action(EDITION_CREEE, "Édition créée", Entite.EDITION);
        action(EDITION_RENOMMEE, "Édition renommée", Entite.EDITION);
        action(EDITION_DUPLIQUEE, "Édition dupliquée", Entite.EDITION);
        action(EDITION_PAR_DEFAUT, "Édition par défaut changée", Entite.EDITION);
        action(EDITION_SUPPRIMEE, "Édition supprimée", Entite.EDITION);
        // The freeze of the referential (ADR 0052) moves no data a solve
        // reads: it only refuses the writes to come, hence action().
        action(GEL_POSE, "Gel du référentiel posé", Entite.EDITION);
        action(GEL_LEVE, "Gel du référentiel levé", Entite.EDITION);

        /* ------------------- The planning itself -------------------- */
        action(SOLVE_LANCE, "Résolution lancée", Entite.PLANNING);
        action(SOLVE_INCREMENTAL_LANCE, "Replanification incrémentale lancée", Entite.PLANNING);
        action(SOLVE_ARRETE, "Résolution arrêtée", Entite.PLANNING);
        action(JOB_SUPPRIME, "Tâche de résolution retirée", Entite.PLANNING);
        changesData(PLANNING_REINITIALISE, "Données de référence effacées", Entite.PLANNING);
        action(AFFECTATION_DEPLACEE, "Affectation déplacée à la main", Entite.PLANNING);
        action(AFFECTATION_POSEE, "Poste attribué à la main", Entite.PLANNING);
        action(ABSENCE_ENREGISTREE, "Absence déclarée en mode jour J", Entite.PLANNING);
        action(ABSENCE_ANNULEE, "Absence levée en mode jour J", Entite.PLANNING);

        /* -------------- Snapshots, publication, sends --------------- */
        action(INSTANTANE_CAPTURE, "Instantané du planning capturé", Entite.INSTANTANE);
        action(INSTANTANE_RESTAURE, "Planning restauré depuis un instantané", Entite.INSTANTANE);
        action(INSTANTANE_SUPPRIME, "Instantané supprimé", Entite.INSTANTANE);
        action(PLANNING_PUBLIE, "Planning publié aux animateurs", Entite.PLANNING);
        action("PUBLICATION_DIFFEREE", "Message de publication différé pour une personne", Entite.ANIMATEUR);
        action(PLANNING_ENVOYE, "Planning envoyé à un animateur", Entite.ANIMATEUR);
        action(MAIL_TEST_ENVOYE, "Mail de test envoyé", Entite.PARAMETRES);
        action(KPI_SUPPRIME, "Ligne d'historique KPI supprimée", Entite.PLANNING);

        /* ------------------------- Exports -------------------------- */
        // Every download that leaves with somebody's data writes a line, the
        // GETs included: « qui a sorti la liste des bénévoles, et quand » is
        // the question the history must answer for as long as it keeps rows.
        // Declared through `export`, never `action`: the flag it sets is what
        // the history's « Exports » filter selects on.
        export(EXPORT_PDF, "Plannings exportés en PDF", Entite.PLANNING);
        export(EXPORT_PDF_ANIMATEUR, "Planning d'un animateur exporté en PDF", Entite.ANIMATEUR);
        export(EXPORT_ICS, "Plannings exportés en calendrier", Entite.PLANNING);
        export(EXPORT_ICS_ANIMATEUR, "Planning d'un animateur exporté en calendrier", Entite.ANIMATEUR);
        export(EXPORT_ARCHIVE, "Archive complète des plannings exportée", Entite.PLANNING);
        export(EXPORT_HEURES, "Heures exportées en CSV", Entite.PLANNING);
        export(EXPORT_BASE, "Base de données exportée", Entite.SAUVEGARDE);
        export(EXPORT_PDF_GLOBAL, "Planning global exporté en PDF", Entite.PLANNING);
        export(EXPORT_REFERENTIELS, "Référentiels exportés en CSV", Entite.PLANNING);
        export(EXPORT_SCENARIO, "Scénario de l'édition exporté en YAML", Entite.PLANNING);
        export(EXPORT_EQUITE, "Rapport d'équité exporté en CSV", Entite.PLANNING);
        export(EXPORT_RELECTURE, "Relecture avant envoi exportée en CSV", Entite.PLANNING);
        // The whole grid, every animateur at once: it bears on the edition,
        // not on one fiche.
        export(EXPORT_INTENDANCE, "Intendance des repas exportée en CSV", Entite.PLANNING);
        // Several of the exports above in one ZIP: one line, naming the parts it
        // carried, rather than one per file nobody downloaded on its own.
        export(EXPORT_ARCHIVE_EVENEMENT, "Archive de fin d'événement exportée", Entite.PLANNING);
        export(EXPORT_FORMATION, "Plan de formation exporté en CSV", Entite.PLANNING);

        /* ------------------ Imports and scenarios ------------------- */
        changesData(SCENARIO_IMPORTE, "Scénario importé", Entite.PLANNING);
        changesData(BASE_IMPORTEE, "Base de données restaurée depuis un fichier", Entite.SAUVEGARDE);

        /* ------------------------- Settings ------------------------- */
        changesData(PARAMETRES_LEGAUX_MODIFIES, "Paramètres légaux modifiés", Entite.PARAMETRES);
        changesData(PARAMETRES_QUALITE_MODIFIES, "Paramètres de qualité modifiés", Entite.PARAMETRES);
        changesData(PARAMETRES_SOLVEUR_MODIFIES, "Paramètres du solveur modifiés", Entite.PARAMETRES);
        action(PARAMETRES_NOTIFICATIONS_MODIFIES, "Paramètres de notifications modifiés", Entite.PARAMETRES);
        changesData(CONTRAINTE_ACTIVEE, "Contrainte activée", Entite.PARAMETRES);
        changesData(CONTRAINTE_DESACTIVEE, "Contrainte désactivée", Entite.PARAMETRES);
        changesData(CONTRAINTE_PONDEREE, "Poids d'une contrainte modifié", Entite.PARAMETRES);
        action(SAUVEGARDE_BASCULEE, "Sauvegarde nocturne suspendue ou reprise", Entite.SAUVEGARDE);
        action(DATE_JOUR_J_FORCEE, "Date du jour forcée (débogage)", Entite.PARAMETRES);
        action(CLE_MCP_REVELEE, "Clé MCP révélée", Entite.PARAMETRES);
        // A link that opens a screen without a session is a credential handed
        // out: who made it and who closed it belong in the history. The screen's
        // own reads, once a minute, do not — see MuralResource.
        action(AFFICHAGE_MURAL_CREE, "Lien d'affichage mural créé", Entite.PARAMETRES);
        action(AFFICHAGE_MURAL_REVOQUE, "Lien d'affichage mural révoqué", Entite.PARAMETRES);

        /* ---------- Availability, swaps, espace animateur ----------- */
        action(COLLECTE_CONFIGUREE, "Fenêtre de collecte des disponibilités configurée", Entite.DISPONIBILITE);
        changesData(DECLARATION_APPLIQUEE, "Déclaration de disponibilités appliquée", Entite.DISPONIBILITE);
        action(DECLARATION_REFUSEE, "Déclaration de disponibilités refusée", Entite.DISPONIBILITE);
        changesData(COVOITURAGE_VALIDE, "Arrivée groupée validée", Entite.DISPONIBILITE);
        action(COVOITURAGE_ECARTE, "Covoiturage écarté", Entite.DISPONIBILITE);
        changesData(COVOITURAGE_ANNULE, "Arrivée groupée annulée", Entite.DISPONIBILITE);
        action(COVOITURAGE_DEMANDE, "Covoiturage demandé depuis l'espace", Entite.DISPONIBILITE);
        action(DECLARATION_SOUMISE, "Disponibilités déclarées depuis l'espace", Entite.DISPONIBILITE);
        action(FOIRE_CONFIGUREE, "Foire au planning configurée", Entite.ECHANGE);
        action(ECHANGE_ACCEPTE, "Demande d'échange acceptée", Entite.ECHANGE);
        action(ECHANGE_REFUSE, "Demande d'échange refusée", Entite.ECHANGE);
        action(ECHANGE_SOUMIS, "Demande d'échange soumise depuis l'espace", Entite.ECHANGE);
        action(ECHANGE_ACCORDE, "Échange accepté par le collègue sollicité", Entite.ECHANGE);
        action(ECHANGE_DECLINE, "Échange décliné par le collègue sollicité", Entite.ECHANGE);
        action(PLANNING_CONFIRME, "Planning confirmé depuis l'espace", Entite.ANIMATEUR);
        action(ABONNEMENT_CREE, "Abonnement au calendrier activé", Entite.ANIMATEUR);
        action(ABONNEMENT_ANNULE, "Abonnement au calendrier annulé", Entite.ANIMATEUR);
        action(CODE_ESPACE_DEMANDE, "Code d'accès à l'espace demandé", Entite.ANIMATEUR);
        action(SESSION_ESPACE_OUVERTE, "Session d'espace ouverte", Entite.ANIMATEUR);
        action(DECONNEXION, "Déconnexion", Entite.PARAMETRES);
        // One line per explicit download, never per page shown: « a-t-il bien
        // récupéré son planning ? » without turning the history into an
        // access log. The calendar subscription stays out — see SANS_TRACE.
        export(TELECHARGEMENT_ESPACE_PDF, "Planning téléchargé en PDF depuis l'espace", Entite.ANIMATEUR);
        export(TELECHARGEMENT_ESPACE_ICS, "Planning téléchargé en calendrier depuis l'espace", Entite.ANIMATEUR);

        /* ------------------ The application itself ------------------ */
        // The nightly sends are the only thing the application does on its own
        // that belongs to one edition, which is what a row needs. The backup
        // and the history purge are server-wide: filing them under whichever
        // edition happened to be current would be an invented fact, and both
        // already report themselves — the backup on the Paramètres screen, the
        // purge in the log. `chaqueActionEstAtteignable` keeps this honest by
        // failing on any entry no entry point can reach.
        action("NOTIFICATIONS_ENVOYEES", "Envois automatiques de nuit", Entite.PLANNING);
    }

    /**
     * REST entry points, keyed {@code SimpleClassName#methodName}. The key is
     * the method itself rather than its path: a route renamed in
     * {@code @Path} keeps its line, and a method renamed breaks the structural
     * test rather than silently stopping to journal.
     */
    private static final Map<String, String> ROUTES = new LinkedHashMap<>();

    /**
     * Routes recorded only when the caller proved who they are — see
     * {@link #recordedOnlyWhenProven(String)}.
     */
    private static final Set<String> SI_PROUVE = new LinkedHashSet<>();

    private static void route(String cle, String code) {
        ROUTES.put(cle, code);
    }

    static {
        route("AnimateurResource#createAnimateur", ANIMATEUR_CREE);
        route("AnimateurResource#updateAnimateur", ANIMATEUR_MODIFIE);
        route("AnimateurResource#deleteAnimateur", ANIMATEUR_SUPPRIME);
        route("AnimateurResource#regenerateAnimateurToken", ANIMATEUR_JETON_REGENERE);
        route("AnimateurResource#importCsvAnimateurs", ANIMATEURS_IMPORTES);
        route("AnimateurResource#saveCompetencesGrid", ANIMATEUR_COMPETENCES_GRILLE);
        route("AnimateurResource#relancer", ANIMATEURS_RELANCES);

        route("StandResource#createStand", STAND_CREE);
        route("StandResource#updateStand", STAND_MODIFIE);
        route("StandResource#deleteStand", STAND_SUPPRIME);
        route("StandResource#compactHoraires", STAND_HORAIRES_COMPACTES);
        route("StandResource#importGrille", STANDS_IMPORTES);
        route("OuvertureStandsResource#saisir", OUVERTURES_SAISIES);

        route("CreneauResource#createCreneau", CRENEAU_CREE);
        route("CreneauResource#updateCreneau", CRENEAU_MODIFIE);
        route("CreneauResource#deleteCreneau", CRENEAU_SUPPRIME);
        route("CreneauResource#createRecurrence", CRENEAUX_RECURRENTS_CREES);
        route("CreneauResource#applyDerivation", CRENEAUX_DERIVES);
        route("CreneauResource#importCsv", CRENEAUX_IMPORTES);
        route("JourneeTypeResource#create", JOURNEE_TYPE_CREEE);
        route("JourneeTypeResource#update", JOURNEE_TYPE_MODIFIEE);
        route("JourneeTypeResource#delete", JOURNEE_TYPE_SUPPRIMEE);
        route("JourneeTypeResource#setCalendrier", CALENDRIER_JOURNEES_TYPES_MODIFIE);
        route("JourneeTypeResource#apply", JOURNEES_TYPES_APPLIQUEES);
        route("JourneeTypeResource#reconnaitre", JOURNEES_TYPES_RECONNUES);
        route("JourneeTypeResource#importCsv", JOURNEES_TYPES_IMPORTEES);

        route("TypologieResource#importCsv", TYPOLOGIES_IMPORTEES);
        route("EmplacementResource#importCsv", EMPLACEMENTS_IMPORTES);
        route("StandResource#importCsv", STANDS_IMPORTES);

        route("EmplacementResource#createEmplacement", EMPLACEMENT_CREE);
        route("EmplacementResource#updateEmplacement", EMPLACEMENT_MODIFIE);
        route("EmplacementResource#deleteEmplacement", EMPLACEMENT_SUPPRIME);
        route("TypologieResource#createTypologie", TYPOLOGIE_CREEE);
        route("TypologieResource#updateTypologie", TYPOLOGIE_MODIFIEE);
        route("TypologieResource#deleteTypologie", TYPOLOGIE_SUPPRIMEE);

        route("ContrainteAdHocResource#createContrainteAdHoc", AJUSTEMENT_CREE);
        route("ContrainteAdHocResource#deleteContrainteAdHoc", AJUSTEMENT_SUPPRIME);
        route("VerrouillageResource#create", VERROU_POSE);
        route("VerrouillageResource#delete", VERROU_RETIRE);
        route("ValidationJourneeResource#accept", JOURNEE_VALIDEE);
        route("ValidationJourneeResource#withdraw", JOURNEE_VALIDATION_RETIREE);
        route("ConsigneResource#poser", CONSIGNE_POSEE);
        route("ConsigneResource#lever", CONSIGNE_LEVEE);
        route("ConsigneResource#createPrereglage", PREREGLAGE_CONSIGNE_ENREGISTRE);
        route("ConsigneResource#updatePrereglage", PREREGLAGE_CONSIGNE_ENREGISTRE);
        route("ConsigneResource#deletePrereglage", PREREGLAGE_CONSIGNE_SUPPRIME);

        route("EditionResource#create", EDITION_CREEE);
        route("EditionResource#rename", EDITION_RENOMMEE);
        route("EditionResource#duplicate", EDITION_DUPLIQUEE);
        route("EditionResource#setAsDefault", EDITION_PAR_DEFAUT);
        route("EditionResource#delete", EDITION_SUPPRIMEE);
        route("EditionResource#freeze", GEL_POSE);
        route("EditionResource#lift", GEL_LEVE);

        route("PlanningResource#solve", SOLVE_LANCE);
        route("PlanningResource#reset", PLANNING_REINITIALISE);
        route("SolverJobResource#solveAsync", SOLVE_LANCE);
        route("SolverJobResource#solveFromReferenceData", SOLVE_LANCE);
        route("SolverJobResource#solveIncremental", SOLVE_INCREMENTAL_LANCE);
        route("SolverJobResource#cancelJob", SOLVE_ARRETE);
        route("SolverJobResource#deleteJob", JOB_SUPPRIME);
        route("AffectationExplanationResource#applyDeplacement", AFFECTATION_DEPLACEE);
        route("AffectationExplanationResource#applyReparation", AFFECTATION_POSEE);
        route("AffectationExplanationResource#applyPlacement", AFFECTATION_POSEE);
        route("JourJResource#recordAbsence", ABSENCE_ENREGISTREE);
        route("JourJResource#cancelAbsence", ABSENCE_ANNULEE);

        route("PlanSnapshotResource#capture", INSTANTANE_CAPTURE);
        route("PlanSnapshotResource#restore", INSTANTANE_RESTAURE);
        route("PlanSnapshotResource#delete", INSTANTANE_SUPPRIME);
        route("PublicationResource#publier", PLANNING_PUBLIE);
        route("EnvoiPlanningResource#sendToOneAnimateur", PLANNING_ENVOYE);
        route("DebugResource#sendTestMail", MAIL_TEST_ENVOYE);
        route("KpiResource#delete", KPI_SUPPRIME);

        route("PlanningExportResource#exportAllPdfZip", EXPORT_PDF);
        route("PlanningExportResource#exportAnimateurPdf", EXPORT_PDF_ANIMATEUR);
        route("PlanningExportResource#exportAllIcsZip", EXPORT_ICS);
        route("PlanningExportResource#exportAnimateurIcs", EXPORT_ICS_ANIMATEUR);
        route("PlanningExportResource#exportAllBundleZip", EXPORT_ARCHIVE);
        route("PlanningHoursResource#exportCsv", EXPORT_HEURES);
        // Downloads are GETs, and journalled all the same: a dump, an archive
        // of the referentials or a nominative CSV carries people's data, and
        // leaving with it is an act. JournalCoverageStructurelleTest holds
        // every GET that answers a file to this list or to SANS_TRACE.
        route("DatabaseResource#export", EXPORT_BASE);
        route("PlanningExportResource#exportGlobalPdf", EXPORT_PDF_GLOBAL);
        route("ReferenceDataResource#exportCsv", EXPORT_REFERENTIELS);
        route("PlanningResource#exportScenario", EXPORT_SCENARIO);
        route("EquiteResource#exportCsv", EXPORT_EQUITE);
        route("PublicationResource#exportCsv", EXPORT_RELECTURE);
        route("PauseResource#exportIntendance", EXPORT_INTENDANCE);
        route("ArchiveEvenementResource#export", EXPORT_ARCHIVE_EVENEMENT);
        route("FormationResource#exportCsv", EXPORT_FORMATION);
        route("DatabaseResource#importDump", BASE_IMPORTEE);
        route("ReferenceDataResource#importScenario", SCENARIO_IMPORTE);
        route("ReferenceDataResource#importScenarioFile", SCENARIO_IMPORTE);

        route("ParametresResource#updateParametresLegaux", PARAMETRES_LEGAUX_MODIFIES);
        route("ParametresResource#updateParametresQualite", PARAMETRES_QUALITE_MODIFIES);
        route("ParametresResource#updateParametresSolveur", PARAMETRES_SOLVEUR_MODIFIES);
        route("ParametresResource#updateParametresNotifications", PARAMETRES_NOTIFICATIONS_MODIFIES);
        route("ConstraintResource#setActif", CONTRAINTE_ACTIVEE);
        route("ConstraintResource#setPoids", CONTRAINTE_PONDEREE);
        route("BackupResource#setActive", SAUVEGARDE_BASCULEE);
        route("DebugResource#setDateJourJ", DATE_JOUR_J_FORCEE);
        route("McpResource#reveal", CLE_MCP_REVELEE);
        route("AffichageMuralResource#create", AFFICHAGE_MURAL_CREE);
        route("AffichageMuralResource#revoke", AFFICHAGE_MURAL_REVOQUE);
        route("AuthResource#logout", DECONNEXION);

        route("DeclarationDisponibiliteResource#configure", COLLECTE_CONFIGUREE);
        route("DeclarationDisponibiliteResource#apply", DECLARATION_APPLIQUEE);
        route("DeclarationDisponibiliteResource#refuse", DECLARATION_REFUSEE);
        route("DeclarationDisponibiliteResource#validateCarpool", COVOITURAGE_VALIDE);
        route("DeclarationDisponibiliteResource#setCarpoolAside", COVOITURAGE_ECARTE);
        route("DeclarationDisponibiliteResource#cancelCarpool", COVOITURAGE_ANNULE);
        route("DemandeEchangeResource#configure", FOIRE_CONFIGUREE);
        route("DemandeEchangeResource#accept", ECHANGE_ACCEPTE);
        route("DemandeEchangeResource#refuse", ECHANGE_REFUSE);

        route("EspaceAnimateurResource#confirmerPlanning", PLANNING_CONFIRME);
        route("EspaceAnimateurResource#submit", ECHANGE_SOUMIS);
        route("EspaceAnimateurResource#grantReceivedDemande", ECHANGE_ACCORDE);
        route("EspaceAnimateurResource#declineReceivedDemande", ECHANGE_DECLINE);
        route("EspaceAnimateurResource#declarer", DECLARATION_SOUMISE);
        route("EspaceAnimateurResource#requestCarpool", COVOITURAGE_DEMANDE);
        route("EspaceAnimateurResource#regenerateAbonnementToken", ABONNEMENT_CREE);
        route("EspaceAnimateurResource#cancel", ABONNEMENT_ANNULE);
        route("EspaceAnimateurResource#requestCode", CODE_ESPACE_DEMANDE);
        route("EspaceAnimateurResource#openSession", SESSION_ESPACE_OUVERTE);
        routeWhenProven("EspaceAnimateurResource#planningPdf", TELECHARGEMENT_ESPACE_PDF);
        routeWhenProven("EspaceAnimateurResource#planningIcs", TELECHARGEMENT_ESPACE_ICS);
    }

    /**
     * A {@code GET} on an open route, journalled only once the caller proved
     * who they are. A write refused there is worth a line — somebody tried to
     * change something — but a download is a read anyone can repeat for
     * free: an unknown token (404) or a missing session (401) would let any
     * visitor fill the table with lines naming nobody, unthrottled. Its
     * successes, and the refusals met by a proven animateur, still write.
     */
    private static void routeWhenProven(String cle, String code) {
        route(cle, code);
        SI_PROUVE.add(cle);
    }

    /**
     * MCP tools, keyed by their tool name — which is what an assistant calls
     * and what the operator would recognise.
     */
    private static final Map<String, String> OUTILS = new LinkedHashMap<>();

    private static void outil(String nom, String code) {
        OUTILS.put(nom, code);
    }

    static {
        outil("creer_animateur", ANIMATEUR_CREE);
        outil("modifier_animateur", ANIMATEUR_MODIFIE);
        outil("supprimer_animateur", ANIMATEUR_SUPPRIME);

        outil("creer_stand", STAND_CREE);
        outil("creer_stand_complet", STAND_CREE);
        outil("modifier_stand", STAND_MODIFIE);
        outil("supprimer_stand", STAND_SUPPRIME);
        outil("ajouter_fermeture_stand", STAND_PLAGE_AJOUTEE);
        outil("ajouter_ouverture_stand", STAND_PLAGE_AJOUTEE);
        outil("effacer_plages_stand", STAND_PLAGES_EFFACEES);
        outil("ajouter_horaire_stand", STAND_HORAIRE_AJOUTE);
        outil("effacer_horaires_stand", STAND_HORAIRES_EFFACES);
        outil("compacter_horaires_stands", STAND_HORAIRES_COMPACTES);

        outil("creer_creneau", CRENEAU_CREE);
        outil("modifier_creneau", CRENEAU_MODIFIE);
        outil("supprimer_creneau", CRENEAU_SUPPRIME);
        outil("supprimer_creneaux", CRENEAUX_SUPPRIMES);
        outil("creer_creneaux_recurrents", CRENEAUX_RECURRENTS_CREES);
        outil("generer_creneaux_depuis_stands", CRENEAUX_DERIVES);
        outil("definir_journee_type", JOURNEE_TYPE_DEFINIE);
        outil("supprimer_journee_type", JOURNEE_TYPE_SUPPRIMEE);
        outil("affecter_journee_type", CALENDRIER_JOURNEES_TYPES_MODIFIE);
        outil("retirer_dates_journee_type", CALENDRIER_JOURNEES_TYPES_MODIFIE);
        outil("materialiser_journees_types", JOURNEES_TYPES_APPLIQUEES);
        outil("reconnaitre_journees_types", JOURNEES_TYPES_RECONNUES);

        outil("creer_emplacement", EMPLACEMENT_CREE);
        outil("modifier_emplacement", EMPLACEMENT_MODIFIE);
        outil("supprimer_emplacement", EMPLACEMENT_SUPPRIME);
        outil("creer_typologie", TYPOLOGIE_CREEE);
        outil("modifier_typologie", TYPOLOGIE_MODIFIEE);
        outil("supprimer_typologie", TYPOLOGIE_SUPPRIMEE);

        outil("creer_contrainte_ad_hoc", AJUSTEMENT_CREE);
        outil("supprimer_contrainte_ad_hoc", AJUSTEMENT_SUPPRIME);
        outil("verrouiller", VERROU_POSE);
        outil("deverrouiller", VERROU_RETIRE);
        outil("ajouter_validation_journee", JOURNEE_VALIDEE);
        outil("retirer_validation_journee", JOURNEE_VALIDATION_RETIREE);
        outil("appliquer_consigne", CONSIGNE_POSEE);
        outil("lever_consigne", CONSIGNE_LEVEE);
        outil("definir_prereglage_consigne", PREREGLAGE_CONSIGNE_ENREGISTRE);
        outil("supprimer_prereglage_consigne", PREREGLAGE_CONSIGNE_SUPPRIME);

        outil("creer_edition", EDITION_CREEE);
        outil("renommer_edition", EDITION_RENOMMEE);
        outil("dupliquer_edition", EDITION_DUPLIQUEE);
        outil("definir_edition_par_defaut", EDITION_PAR_DEFAUT);
        outil("supprimer_edition", EDITION_SUPPRIMEE);
        outil("figer_referentiel", GEL_POSE);
        outil("lever_gel", GEL_LEVE);

        outil("lancer_solveur", SOLVE_LANCE);
        outil("resoudre_incremental", SOLVE_INCREMENTAL_LANCE);
        outil("arreter_solveur", SOLVE_ARRETE);
        outil("supprimer_job", JOB_SUPPRIME);
        outil("reinitialiser_donnees", PLANNING_REINITIALISE);
        outil("deplacer_affectation", AFFECTATION_DEPLACEE);
        outil("affecter_poste", AFFECTATION_POSEE);

        outil("capturer_instantane", INSTANTANE_CAPTURE);
        outil("restaurer_instantane", INSTANTANE_RESTAURE);
        outil("supprimer_instantane", INSTANTANE_SUPPRIME);
        outil("publier_planning", PLANNING_PUBLIE);
        outil("envoyer_planning_animateur", PLANNING_ENVOYE);
        outil("relancer_animateurs", ANIMATEURS_RELANCES);
        outil("supprimer_kpi_historique", KPI_SUPPRIME);

        outil("importer_scenario", SCENARIO_IMPORTE);
        outil("importer_scenario_yaml", SCENARIO_IMPORTE);

        outil("modifier_parametres_legaux", PARAMETRES_LEGAUX_MODIFIES);
        outil("modifier_parametres_qualite", PARAMETRES_QUALITE_MODIFIES);
        outil("modifier_parametres_solveur", PARAMETRES_SOLVEUR_MODIFIES);
        outil("modifier_parametres_notifications", PARAMETRES_NOTIFICATIONS_MODIFIES);
        outil("activer_contrainte", CONTRAINTE_ACTIVEE);
        outil("desactiver_contrainte", CONTRAINTE_DESACTIVEE);
        outil("modifier_poids_contrainte", CONTRAINTE_PONDEREE);

        outil("modifier_sauvegardes", SAUVEGARDE_BASCULEE);

        outil("configurer_collecte_disponibilites", COLLECTE_CONFIGUREE);
        outil("appliquer_declaration_disponibilite", DECLARATION_APPLIQUEE);
        outil("refuser_declaration_disponibilite", DECLARATION_REFUSEE);
        outil("configurer_foire_echanges", FOIRE_CONFIGUREE);
        outil("accepter_demande_echange", ECHANGE_ACCEPTE);
        outil("refuser_demande_echange", ECHANGE_REFUSE);
    }

    /**
     * Write-shaped entry points and downloads that are <b>not</b> actions,
     * each with the reason. Almost all of them are {@code POST}s only because
     * they take a body: they compute an answer and write nothing. The
     * downloads here carry nobody's data — a template file — or are fetched
     * by a machine rather than asked for by a person.
     */
    private static final Map<String, String> SANS_TRACE = new LinkedHashMap<>();

    private static void untracked(String cle, String motif) {
        SANS_TRACE.put(cle, motif);
    }

    static {
        untracked("AffectationExplanationResource#explain", "explique une affectation, n'écrit rien");
        untracked("AffectationExplanationResource#simulateSwap", SIMULATION);
        untracked("AffectationExplanationResource#suggererReparations", "suggestions, n'écrit rien");
        untracked("AffectationExplanationResource#simulateDeplacement", SIMULATION);
        untracked("AnimateurResource#analyseCsvAnimateurs", ANALYSE_FICHIER);
        untracked("StandResource#analyseGrille", ANALYSE_FICHIER);
        untracked("TypologieResource#analyseCsv", ANALYSE_FICHIER);
        untracked("EmplacementResource#analyseCsv", ANALYSE_FICHIER);
        untracked("StandResource#analyseCsv", ANALYSE_FICHIER);
        untracked("CreneauResource#analyseCsv", ANALYSE_FICHIER);
        untracked("JourneeTypeResource#analyseCsv", ANALYSE_FICHIER);
        untracked("TypologieResource#exempleCsv", FICHIER_EXEMPLE);
        untracked("EmplacementResource#exempleCsv", FICHIER_EXEMPLE);
        untracked("StandResource#exempleCsv", FICHIER_EXEMPLE);
        untracked("StandResource#exempleGrille", FICHIER_EXEMPLE);
        untracked("CreneauResource#exempleCsv", FICHIER_EXEMPLE);
        untracked("JourneeTypeResource#exempleCsv", FICHIER_EXEMPLE);
        untracked("AnimateurResource#exempleCsvAnimateurs", "télécharge un fichier d'exemple, sans personne dedans");
        // A calendar client re-reads the feed every few hours on its own: one
        // line per sync would be noise, not a trace, and nobody chose to
        // download anything. What the owner did — subscribing, cancelling —
        // is journalled on the espace routes that do it.
        untracked(
                "AbonnementIcsResource#planningIcs",
                "relu par l'agenda de l'animateur toutes les quelques heures, sans geste de sa part");
        untracked("CreneauResource#previewRecurrence", PREVISUALISATION);
        untracked("CreneauResource#previewDerivation", PREVISUALISATION);
        untracked("JourneeTypeResource#previewApplication", PREVISUALISATION);
        untracked("JourneeTypeResource#previewReconnaissance", PREVISUALISATION);
        untracked("ConsigneResource#preselection", "lit les stands contre une bande, n'écrit rien");
        untracked("ConsigneResource#apercu", PREVISUALISATION);
        untracked("ConsigneResource#apercuLevee", PREVISUALISATION);
        untracked("previsualiser_reconnaissance_journees_types", PREVISUALISATION);
        untracked("ConstraintResource#diagnose", "relit l'analyse enregistrée, n'écrit rien");
        untracked("PlanningHoursResource#compute", "calcule les heures d'un planning envoyé, n'écrit rien");
        untracked("ReferenceDataResource#fileScenarioTarget", "lit un fichier pour en annoncer la cible");
        untracked("ReferenceDataResource#validateScenarioFile", "valide un fichier, n'écrit rien");
        untracked("JourJResource#suggestions", "suggestions de remplacement, n'écrit rien");
        untracked("AffichageMuralResource#qrCode", "dessine le QR code d'une adresse, n'écrit rien");
        untracked("DebugResource#throwTestException", "lève une exception pour vérifier la remontée d'erreurs");
    }

    /**
     * Whether {@code cle} is recorded only when the request proved its
     * caller's identity (a live espace session): a refusal met without that
     * proof writes nothing.
     */
    public static boolean recordedOnlyWhenProven(String cle) {
        return cle != null && SI_PROUVE.contains(cle);
    }

    /** The codes of every export, for the history's server-side « Exports » filter. */
    public static Set<String> exportCodes() {
        return ACTIONS.values().stream()
                .filter(ActionJournalisee::export)
                .map(ActionJournalisee::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** What {@code cle} does, {@code empty} when it is not a journalled action. */
    public static Optional<ActionJournalisee> forRoute(String cle) {
        return Optional.ofNullable(ROUTES.get(cle)).map(ACTIONS::get);
    }

    /** Same, for an MCP tool called by its name. */
    public static Optional<ActionJournalisee> forTool(String nom) {
        return Optional.ofNullable(OUTILS.get(nom)).map(ACTIONS::get);
    }

    /**
     * The action a request named itself, for a route whose method serves
     * several — see {@link CurrentAction#action(String)}. An unknown code
     * answers empty, so the route's own action stands rather than nothing
     * being recorded.
     */
    public static Optional<ActionJournalisee> forCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(ACTIONS.get(code));
    }

    /** An action fired by the application itself, off any request. */
    public static ActionJournalisee systeme(String code) {
        ActionJournalisee action = ACTIONS.get(code);
        if (action == null) {
            throw new IllegalArgumentException("Unknown journalled action: " + code);
        }
        return action;
    }

    /** Why an entry point that looks like a write is not journalled, {@code empty} when it is. */
    public static Optional<String> untrackedReason(String cle) {
        return Optional.ofNullable(SANS_TRACE.get(cle));
    }

    /** Every action of the inventory, for the screen's filter and for the tests. */
    public static Map<String, ActionJournalisee> actions() {
        return Map.copyOf(ACTIONS);
    }

    /**
     * The codes of every action that changes what a solve would be given — see
     * {@link #donnees}. Read by the journal to answer « qu'est-ce qui a bougé
     * depuis cette résolution ? », so an action declared with the wrong
     * factory does not show there.
     */
    public static Set<String> codesChangingData() {
        return ACTIONS.values().stream()
                .filter(ActionJournalisee::changesData)
                .map(ActionJournalisee::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    static Map<String, String> routes() {
        return Map.copyOf(ROUTES);
    }

    static Map<String, String> outils() {
        return Map.copyOf(OUTILS);
    }

    static Set<String> routesWhenProven() {
        return Set.copyOf(SI_PROUVE);
    }

    static Map<String, String> untracked() {
        return Map.copyOf(SANS_TRACE);
    }
}
