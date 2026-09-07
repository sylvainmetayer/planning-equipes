package dev.sylvain.planning.service.journal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.sylvain.planning.service.journal.ActionJournalisee.Entite;

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

    private CatalogueActions() {
    }

    private static final Map<String, ActionJournalisee> ACTIONS = new LinkedHashMap<>();

    private static void action(String code, String libelle, Entite entite) {
        ACTIONS.put(code, new ActionJournalisee(code, libelle, entite));
    }

    static {
        /* ------------------------ Animateurs ------------------------ */
        action("ANIMATEUR_CREE", "Animateur ajouté", Entite.ANIMATEUR);
        action("ANIMATEUR_MODIFIE", "Fiche animateur modifiée", Entite.ANIMATEUR);
        action("ANIMATEUR_SUPPRIME", "Animateur supprimé", Entite.ANIMATEUR);
        action("ANIMATEUR_JETON_REGENERE", "Lien d'espace régénéré", Entite.ANIMATEUR);
        action("ANIMATEURS_IMPORTES", "Animateurs importés depuis un fichier", Entite.ANIMATEUR);

        /* -------------------------- Stands -------------------------- */
        action("STAND_CREE", "Stand ajouté", Entite.STAND);
        action("STAND_MODIFIE", "Stand modifié", Entite.STAND);
        action("STAND_SUPPRIME", "Stand supprimé", Entite.STAND);
        action("STAND_HORAIRES_COMPACTES", "Horaires de stands compactés", Entite.STAND);
        action("STAND_HORAIRE_AJOUTE", "Horaire de stand ajouté", Entite.STAND);
        action("STAND_HORAIRES_EFFACES", "Horaires de stand effacés", Entite.STAND);
        action("STAND_PLAGE_AJOUTEE", "Plage d'ouverture ou de fermeture ajoutée", Entite.STAND);
        action("STAND_PLAGES_EFFACEES", "Plages d'un stand effacées", Entite.STAND);
        action("STANDS_IMPORTES", "Grille de stands importée", Entite.STAND);
        action("OUVERTURES_SAISIES", "Grille des ouvertures enregistrée", Entite.STAND);

        /* ------------------------ Timeslots ------------------------- */
        action("CRENEAU_CREE", "Créneau ajouté", Entite.CRENEAU);
        action("CRENEAU_MODIFIE", "Créneau modifié", Entite.CRENEAU);
        action("CRENEAU_SUPPRIME", "Créneau supprimé", Entite.CRENEAU);
        action("CRENEAUX_SUPPRIMES", "Créneaux supprimés en lot", Entite.CRENEAU);
        action("CRENEAUX_RECURRENTS_CREES", "Créneaux récurrents générés", Entite.CRENEAU);
        action("CRENEAUX_DERIVES", "Créneaux dérivés des horaires des stands", Entite.CRENEAU);
        action("DECOUPAGE_GENERE", "Découpage des amplitudes en vacations", Entite.CRENEAU);

        /* -------------- Locations and game categories --------------- */
        action("EMPLACEMENT_CREE", "Emplacement ajouté", Entite.EMPLACEMENT);
        action("EMPLACEMENT_MODIFIE", "Emplacement modifié", Entite.EMPLACEMENT);
        action("EMPLACEMENT_SUPPRIME", "Emplacement supprimé", Entite.EMPLACEMENT);
        action("TYPOLOGIE_CREEE", "Typologie de jeu ajoutée", Entite.TYPOLOGIE);
        action("TYPOLOGIE_MODIFIEE", "Typologie de jeu modifiée", Entite.TYPOLOGIE);
        action("TYPOLOGIE_SUPPRIMEE", "Typologie de jeu supprimée", Entite.TYPOLOGIE);

        /* --------------- Ad hoc adjustments and locks --------------- */
        action("AJUSTEMENT_CREE", "Ajustement manuel ajouté", Entite.AJUSTEMENT);
        action("AJUSTEMENT_SUPPRIME", "Ajustement manuel supprimé", Entite.AJUSTEMENT);
        action("VERROU_POSE", "Verrouillage posé", Entite.VERROUILLAGE);
        action("VERROU_RETIRE", "Verrouillage retiré", Entite.VERROUILLAGE);

        /* ------------------------- Editions ------------------------- */
        action("EDITION_CREEE", "Édition créée", Entite.EDITION);
        action("EDITION_RENOMMEE", "Édition renommée", Entite.EDITION);
        action("EDITION_DUPLIQUEE", "Édition dupliquée", Entite.EDITION);
        action("EDITION_PAR_DEFAUT", "Édition par défaut changée", Entite.EDITION);
        action("EDITION_SUPPRIMEE", "Édition supprimée", Entite.EDITION);

        /* ------------------- The planning itself -------------------- */
        action("SOLVE_LANCE", "Résolution lancée", Entite.PLANNING);
        action("SOLVE_INCREMENTAL_LANCE", "Replanification incrémentale lancée", Entite.PLANNING);
        action("SOLVE_ARRETE", "Résolution arrêtée", Entite.PLANNING);
        action("JOB_SUPPRIME", "Tâche de résolution retirée", Entite.PLANNING);
        action("PLANNING_REINITIALISE", "Données de référence effacées", Entite.PLANNING);
        action("AFFECTATION_DEPLACEE", "Affectation déplacée à la main", Entite.PLANNING);
        action("AFFECTATION_POSEE", "Poste attribué à la main", Entite.PLANNING);
        action("ABSENCE_ENREGISTREE", "Absence déclarée en mode jour J", Entite.PLANNING);
        action("ABSENCE_ANNULEE", "Absence levée en mode jour J", Entite.PLANNING);

        /* -------------- Snapshots, publication, sends --------------- */
        action("INSTANTANE_CAPTURE", "Instantané du planning capturé", Entite.INSTANTANE);
        action("INSTANTANE_RESTAURE", "Planning restauré depuis un instantané", Entite.INSTANTANE);
        action("INSTANTANE_SUPPRIME", "Instantané supprimé", Entite.INSTANTANE);
        action("PLANNING_PUBLIE", "Planning publié aux animateurs", Entite.PLANNING);
        action("PLANNING_ENVOYE", "Planning envoyé à un animateur", Entite.ANIMATEUR);
        action("MAIL_TEST_ENVOYE", "Mail de test envoyé", Entite.PARAMETRES);
        action("KPI_SUPPRIME", "Ligne d'historique KPI supprimée", Entite.PLANNING);

        /* ------------------------- Exports -------------------------- */
        action("EXPORT_PDF", "Plannings exportés en PDF", Entite.PLANNING);
        action("EXPORT_PDF_ANIMATEUR", "Planning d'un animateur exporté en PDF", Entite.ANIMATEUR);
        action("EXPORT_ICS", "Plannings exportés en calendrier", Entite.PLANNING);
        action("EXPORT_ICS_ANIMATEUR", "Planning d'un animateur exporté en calendrier", Entite.ANIMATEUR);
        action("EXPORT_ARCHIVE", "Archive complète des plannings exportée", Entite.PLANNING);
        action("EXPORT_HEURES", "Heures exportées en CSV", Entite.PLANNING);
        action("EXPORT_BASE", "Base de données exportée", Entite.SAUVEGARDE);

        /* ------------------ Imports and scenarios ------------------- */
        action("SCENARIO_IMPORTE", "Scénario importé", Entite.PLANNING);
        action("DONNEES_IMPORTEES", "Données de référence importées", Entite.PLANNING);
        action("BASE_IMPORTEE", "Base de données restaurée depuis un fichier", Entite.SAUVEGARDE);

        /* ------------------------- Settings ------------------------- */
        action("PARAMETRES_LEGAUX_MODIFIES", "Paramètres légaux modifiés", Entite.PARAMETRES);
        action("PARAMETRES_DECOUPAGE_MODIFIES", "Paramètres de découpage modifiés", Entite.PARAMETRES);
        action("MODE_GRILLE_MODIFIE", "Mode de la grille des créneaux changé", Entite.PARAMETRES);
        action("PARAMETRES_SOLVEUR_MODIFIES", "Paramètres du solveur modifiés", Entite.PARAMETRES);
        action("PARAMETRES_NOTIFICATIONS_MODIFIES", "Paramètres de notifications modifiés", Entite.PARAMETRES);
        action("CONTRAINTE_ACTIVEE", "Contrainte activée", Entite.PARAMETRES);
        action("CONTRAINTE_DESACTIVEE", "Contrainte désactivée", Entite.PARAMETRES);
        action("CONTRAINTE_PONDEREE", "Poids d'une contrainte modifié", Entite.PARAMETRES);
        action("SAUVEGARDE_BASCULEE", "Sauvegarde nocturne suspendue ou reprise", Entite.SAUVEGARDE);
        action("DATE_JOUR_J_FORCEE", "Date du jour forcée (débogage)", Entite.PARAMETRES);
        action("CLE_MCP_REVELEE", "Clé MCP révélée", Entite.PARAMETRES);

        /* ---------- Availability, swaps, espace animateur ----------- */
        action("COLLECTE_CONFIGUREE", "Fenêtre de collecte des disponibilités configurée", Entite.DISPONIBILITE);
        action("DECLARATION_APPLIQUEE", "Déclaration de disponibilités appliquée", Entite.DISPONIBILITE);
        action("DECLARATION_REFUSEE", "Déclaration de disponibilités refusée", Entite.DISPONIBILITE);
        action("DECLARATION_SOUMISE", "Disponibilités déclarées depuis l'espace", Entite.DISPONIBILITE);
        action("FOIRE_CONFIGUREE", "Foire au planning configurée", Entite.ECHANGE);
        action("ECHANGE_ACCEPTE", "Demande d'échange acceptée", Entite.ECHANGE);
        action("ECHANGE_REFUSE", "Demande d'échange refusée", Entite.ECHANGE);
        action("ECHANGE_SOUMIS", "Demande d'échange soumise depuis l'espace", Entite.ECHANGE);
        action("ECHANGE_ACCORDE", "Échange accepté par le collègue sollicité", Entite.ECHANGE);
        action("ECHANGE_DECLINE", "Échange décliné par le collègue sollicité", Entite.ECHANGE);
        action("PLANNING_CONFIRME", "Planning confirmé depuis l'espace", Entite.ANIMATEUR);
        action("ABONNEMENT_CREE", "Abonnement au calendrier activé", Entite.ANIMATEUR);
        action("ABONNEMENT_ANNULE", "Abonnement au calendrier annulé", Entite.ANIMATEUR);
        action("CODE_ESPACE_DEMANDE", "Code d'accès à l'espace demandé", Entite.ANIMATEUR);
        action("SESSION_ESPACE_OUVERTE", "Session d'espace ouverte", Entite.ANIMATEUR);
        action("DECONNEXION", "Déconnexion", Entite.PARAMETRES);

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

    private static void route(String cle, String code) {
        ROUTES.put(cle, code);
    }

    static {
        route("AnimateurResource#createAnimateur", "ANIMATEUR_CREE");
        route("AnimateurResource#updateAnimateur", "ANIMATEUR_MODIFIE");
        route("AnimateurResource#deleteAnimateur", "ANIMATEUR_SUPPRIME");
        route("AnimateurResource#regenerateAnimateurToken", "ANIMATEUR_JETON_REGENERE");
        route("AnimateurResource#importCsvAnimateurs", "ANIMATEURS_IMPORTES");

        route("StandResource#createStand", "STAND_CREE");
        route("StandResource#updateStand", "STAND_MODIFIE");
        route("StandResource#deleteStand", "STAND_SUPPRIME");
        route("StandResource#compactHoraires", "STAND_HORAIRES_COMPACTES");
        route("StandResource#importGrille", "STANDS_IMPORTES");
        route("OuvertureStandsResource#saisir", "OUVERTURES_SAISIES");

        route("CreneauResource#createCreneau", "CRENEAU_CREE");
        route("CreneauResource#updateCreneau", "CRENEAU_MODIFIE");
        route("CreneauResource#deleteCreneau", "CRENEAU_SUPPRIME");
        route("CreneauResource#createRecurrence", "CRENEAUX_RECURRENTS_CREES");
        route("CreneauResource#applyDerivation", "CRENEAUX_DERIVES");
        route("DecoupageResource#generateDecoupage", "DECOUPAGE_GENERE");

        route("EmplacementResource#createEmplacement", "EMPLACEMENT_CREE");
        route("EmplacementResource#updateEmplacement", "EMPLACEMENT_MODIFIE");
        route("EmplacementResource#deleteEmplacement", "EMPLACEMENT_SUPPRIME");
        route("TypologieResource#createTypologie", "TYPOLOGIE_CREEE");
        route("TypologieResource#updateTypologie", "TYPOLOGIE_MODIFIEE");
        route("TypologieResource#deleteTypologie", "TYPOLOGIE_SUPPRIMEE");

        route("ContrainteAdHocResource#createContrainteAdHoc", "AJUSTEMENT_CREE");
        route("ContrainteAdHocResource#deleteContrainteAdHoc", "AJUSTEMENT_SUPPRIME");
        route("VerrouillageResource#create", "VERROU_POSE");
        route("VerrouillageResource#delete", "VERROU_RETIRE");

        route("EditionResource#create", "EDITION_CREEE");
        route("EditionResource#rename", "EDITION_RENOMMEE");
        route("EditionResource#duplicate", "EDITION_DUPLIQUEE");
        route("EditionResource#setAsDefault", "EDITION_PAR_DEFAUT");
        route("EditionResource#delete", "EDITION_SUPPRIMEE");

        route("PlanningResource#solve", "SOLVE_LANCE");
        route("PlanningResource#reset", "PLANNING_REINITIALISE");
        route("SolverJobResource#solveAsync", "SOLVE_LANCE");
        route("SolverJobResource#solveFromReferenceData", "SOLVE_LANCE");
        route("SolverJobResource#solveIncremental", "SOLVE_INCREMENTAL_LANCE");
        route("SolverJobResource#cancelJob", "SOLVE_ARRETE");
        route("SolverJobResource#deleteJob", "JOB_SUPPRIME");
        route("AffectationExplanationResource#applyDeplacement", "AFFECTATION_DEPLACEE");
        route("AffectationExplanationResource#applyReparation", "AFFECTATION_POSEE");
        route("JourJResource#recordAbsence", "ABSENCE_ENREGISTREE");
        route("JourJResource#cancelAbsence", "ABSENCE_ANNULEE");

        route("PlanSnapshotResource#capture", "INSTANTANE_CAPTURE");
        route("PlanSnapshotResource#restore", "INSTANTANE_RESTAURE");
        route("PlanSnapshotResource#delete", "INSTANTANE_SUPPRIME");
        route("PublicationResource#publier", "PLANNING_PUBLIE");
        route("EnvoiPlanningResource#sendToOneAnimateur", "PLANNING_ENVOYE");
        route("DebugResource#sendTestMail", "MAIL_TEST_ENVOYE");
        route("KpiResource#delete", "KPI_SUPPRIME");

        route("PlanningExportResource#exportAllPdfZip", "EXPORT_PDF");
        route("PlanningExportResource#exportAnimateurPdf", "EXPORT_PDF_ANIMATEUR");
        route("PlanningExportResource#exportAllIcsZip", "EXPORT_ICS");
        route("PlanningExportResource#exportAnimateurIcs", "EXPORT_ICS_ANIMATEUR");
        route("PlanningExportResource#exportAllBundleZip", "EXPORT_ARCHIVE");
        route("PlanningHoursResource#exportCsv", "EXPORT_HEURES");
        // The one GET that is journalled: a dump carries every animateur's
        // fiche, minors included, and leaving with it is an act.
        route("DatabaseResource#exportDatabase", "EXPORT_BASE");
        route("DatabaseResource#importDump", "BASE_IMPORTEE");
        route("ReferenceDataResource#importReferenceData", "DONNEES_IMPORTEES");
        route("ReferenceDataResource#importScenario", "SCENARIO_IMPORTE");
        route("ReferenceDataResource#importScenarioFile", "SCENARIO_IMPORTE");

        route("ParametresResource#updateParametresLegaux", "PARAMETRES_LEGAUX_MODIFIES");
        route("ParametresResource#updateParametresDecoupage", "PARAMETRES_DECOUPAGE_MODIFIES");
        route("ParametresResource#updateModeGrille", "MODE_GRILLE_MODIFIE");
        route("ParametresResource#updateParametresSolveur", "PARAMETRES_SOLVEUR_MODIFIES");
        route("ParametresResource#updateParametresNotifications", "PARAMETRES_NOTIFICATIONS_MODIFIES");
        route("ConstraintResource#setActif", "CONTRAINTE_ACTIVEE");
        route("ConstraintResource#setPoids", "CONTRAINTE_PONDEREE");
        route("BackupResource#setActive", "SAUVEGARDE_BASCULEE");
        route("DebugResource#setDateJourJ", "DATE_JOUR_J_FORCEE");
        route("McpResource#reveal", "CLE_MCP_REVELEE");
        route("AuthResource#logout", "DECONNEXION");

        route("DeclarationDisponibiliteResource#configure", "COLLECTE_CONFIGUREE");
        route("DeclarationDisponibiliteResource#apply", "DECLARATION_APPLIQUEE");
        route("DeclarationDisponibiliteResource#refuse", "DECLARATION_REFUSEE");
        route("DemandeEchangeResource#configure", "FOIRE_CONFIGUREE");
        route("DemandeEchangeResource#accept", "ECHANGE_ACCEPTE");
        route("DemandeEchangeResource#refuse", "ECHANGE_REFUSE");

        route("EspaceAnimateurResource#confirmerPlanning", "PLANNING_CONFIRME");
        route("EspaceAnimateurResource#submit", "ECHANGE_SOUMIS");
        route("EspaceAnimateurResource#grantReceivedDemande", "ECHANGE_ACCORDE");
        route("EspaceAnimateurResource#declineReceivedDemande", "ECHANGE_DECLINE");
        route("EspaceAnimateurResource#declarer", "DECLARATION_SOUMISE");
        route("EspaceAnimateurResource#regenerateAbonnementToken", "ABONNEMENT_CREE");
        route("EspaceAnimateurResource#cancel", "ABONNEMENT_ANNULE");
        route("EspaceAnimateurResource#requestCode", "CODE_ESPACE_DEMANDE");
        route("EspaceAnimateurResource#openSession", "SESSION_ESPACE_OUVERTE");
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
        outil("creer_animateur", "ANIMATEUR_CREE");
        outil("modifier_animateur", "ANIMATEUR_MODIFIE");
        outil("supprimer_animateur", "ANIMATEUR_SUPPRIME");

        outil("creer_stand", "STAND_CREE");
        outil("creer_stand_complet", "STAND_CREE");
        outil("modifier_stand", "STAND_MODIFIE");
        outil("supprimer_stand", "STAND_SUPPRIME");
        outil("ajouter_fermeture_stand", "STAND_PLAGE_AJOUTEE");
        outil("ajouter_ouverture_stand", "STAND_PLAGE_AJOUTEE");
        outil("effacer_plages_stand", "STAND_PLAGES_EFFACEES");
        outil("ajouter_horaire_stand", "STAND_HORAIRE_AJOUTE");
        outil("effacer_horaires_stand", "STAND_HORAIRES_EFFACES");
        outil("compacter_horaires_stands", "STAND_HORAIRES_COMPACTES");

        outil("creer_creneau", "CRENEAU_CREE");
        outil("modifier_creneau", "CRENEAU_MODIFIE");
        outil("supprimer_creneau", "CRENEAU_SUPPRIME");
        outil("supprimer_creneaux", "CRENEAUX_SUPPRIMES");
        outil("creer_creneaux_recurrents", "CRENEAUX_RECURRENTS_CREES");
        outil("generer_creneaux_depuis_stands", "CRENEAUX_DERIVES");
        outil("generer_decoupage", "DECOUPAGE_GENERE");

        outil("creer_emplacement", "EMPLACEMENT_CREE");
        outil("modifier_emplacement", "EMPLACEMENT_MODIFIE");
        outil("supprimer_emplacement", "EMPLACEMENT_SUPPRIME");
        outil("creer_typologie", "TYPOLOGIE_CREEE");
        outil("modifier_typologie", "TYPOLOGIE_MODIFIEE");
        outil("supprimer_typologie", "TYPOLOGIE_SUPPRIMEE");

        outil("creer_contrainte_ad_hoc", "AJUSTEMENT_CREE");
        outil("supprimer_contrainte_ad_hoc", "AJUSTEMENT_SUPPRIME");
        outil("verrouiller", "VERROU_POSE");
        outil("deverrouiller", "VERROU_RETIRE");

        outil("creer_edition", "EDITION_CREEE");
        outil("renommer_edition", "EDITION_RENOMMEE");
        outil("dupliquer_edition", "EDITION_DUPLIQUEE");
        outil("definir_edition_par_defaut", "EDITION_PAR_DEFAUT");
        outil("supprimer_edition", "EDITION_SUPPRIMEE");

        outil("lancer_solveur", "SOLVE_LANCE");
        outil("resoudre_incremental", "SOLVE_INCREMENTAL_LANCE");
        outil("arreter_solveur", "SOLVE_ARRETE");
        outil("supprimer_job", "JOB_SUPPRIME");
        outil("reinitialiser_donnees", "PLANNING_REINITIALISE");
        outil("deplacer_affectation", "AFFECTATION_DEPLACEE");
        outil("affecter_poste", "AFFECTATION_POSEE");

        outil("capturer_instantane", "INSTANTANE_CAPTURE");
        outil("restaurer_instantane", "INSTANTANE_RESTAURE");
        outil("supprimer_instantane", "INSTANTANE_SUPPRIME");
        outil("publier_planning", "PLANNING_PUBLIE");
        outil("envoyer_planning_animateur", "PLANNING_ENVOYE");
        outil("supprimer_kpi_historique", "KPI_SUPPRIME");

        outil("importer_scenario", "SCENARIO_IMPORTE");
        outil("importer_scenario_yaml", "SCENARIO_IMPORTE");

        outil("modifier_parametres_legaux", "PARAMETRES_LEGAUX_MODIFIES");
        outil("modifier_parametres_decoupage", "PARAMETRES_DECOUPAGE_MODIFIES");
        outil("modifier_parametres_solveur", "PARAMETRES_SOLVEUR_MODIFIES");
        outil("modifier_parametres_notifications", "PARAMETRES_NOTIFICATIONS_MODIFIES");
        outil("activer_contrainte", "CONTRAINTE_ACTIVEE");
        outil("desactiver_contrainte", "CONTRAINTE_DESACTIVEE");
        outil("modifier_poids_contrainte", "CONTRAINTE_PONDEREE");

        outil("modifier_sauvegardes", "SAUVEGARDE_BASCULEE");

        outil("configurer_collecte_disponibilites", "COLLECTE_CONFIGUREE");
        outil("appliquer_declaration_disponibilite", "DECLARATION_APPLIQUEE");
        outil("refuser_declaration_disponibilite", "DECLARATION_REFUSEE");
        outil("configurer_foire_echanges", "FOIRE_CONFIGUREE");
        outil("accepter_demande_echange", "ECHANGE_ACCEPTE");
        outil("refuser_demande_echange", "ECHANGE_REFUSE");
    }

    /**
     * Write-shaped entry points that are <b>not</b> actions, each with the
     * reason. Almost all of them are {@code POST}s only because they take a
     * body: they compute an answer and write nothing.
     */
    private static final Map<String, String> SANS_TRACE = new LinkedHashMap<>();

    private static void untracked(String cle, String motif) {
        SANS_TRACE.put(cle, motif);
    }

    static {
        untracked("AffectationExplanationResource#explain", "explique une affectation, n'écrit rien");
        untracked("AffectationExplanationResource#simulateSwap", "simulation, n'écrit rien");
        untracked("AffectationExplanationResource#suggererReparations", "suggestions, n'écrit rien");
        untracked("AffectationExplanationResource#simulateDeplacement", "simulation, n'écrit rien");
        untracked("AnimateurResource#analyseCsvAnimateurs", "analyse préalable d'un fichier, n'écrit rien");
        untracked("StandResource#analyseGrille", "analyse préalable d'un fichier, n'écrit rien");
        untracked("CreneauResource#previewRecurrence", "prévisualisation, n'écrit rien");
        untracked("CreneauResource#previewDerivation", "prévisualisation, n'écrit rien");
        untracked("ConstraintResource#diagnose", "relit l'analyse enregistrée, n'écrit rien");
        untracked("PlanningHoursResource#compute", "calcule les heures d'un planning envoyé, n'écrit rien");
        untracked("ReferenceDataResource#fileScenarioTarget", "lit un fichier pour en annoncer la cible");
        untracked("ReferenceDataResource#validateScenarioFile", "valide un fichier, n'écrit rien");
        untracked("JourJResource#suggestions", "suggestions de remplacement, n'écrit rien");
        untracked("DebugResource#throwTestException", "lève une exception pour vérifier la remontée d'erreurs");
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

    static Map<String, String> routes() {
        return Map.copyOf(ROUTES);
    }

    static Map<String, String> outils() {
        return Map.copyOf(OUTILS);
    }
}
