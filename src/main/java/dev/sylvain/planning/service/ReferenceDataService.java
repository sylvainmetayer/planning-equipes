package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * Reference-data CRUD facade. Every mutation is written straight to PostgreSQL
 * through {@link ReferenceDataRepository}; there is no in-memory cache. Read and
 * write methods null-guard the repository so the non-CDI plain test (which
 * builds this service with {@code new}) stays green.
 */
@ApplicationScoped
public class ReferenceDataService {

    @Inject
    ReferenceDataRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    /** Kept for the non-CDI plain test which constructs and calls init() by hand. */
    void init() {
        // No-op: state lives in the database, seeded by Flyway migrations.
    }

    /* ------------------------------ Animateurs ----------------------------- */

    public List<Animateur> listAnimateurs() {
        return repository == null ? List.of() : repository.listAnimateurs();
    }

    public Animateur createAnimateur(Animateur animateur) {
        animateur.setId(requiredId(animateur.getId(), "animateur id"));
        repository.saveAnimateur(animateur);
        markModified();
        return animateur;
    }

    public Animateur updateAnimateur(String id, Animateur animateur) {
        if (!repository.animateurExists(id)) {
            throw new NotFoundException("Animateur not found: " + id);
        }
        animateur.setId(id);
        repository.saveAnimateur(animateur);
        markModified();
        return animateur;
    }

    public void deleteAnimateur(String id) {
        repository.deleteAnimateur(id);
        markModified();
    }

    /* -------------------------------- Stands ------------------------------- */

    public List<Stand> listStands() {
        return repository == null ? List.of() : repository.listStands();
    }

    public Stand createStand(Stand stand) {
        stand.setId(requiredId(stand.getId(), "stand id"));
        validateEffectifs(stand);
        repository.saveStand(stand);
        markModified();
        return stand;
    }

    public Stand updateStand(String id, Stand stand) {
        if (!repository.standExists(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        validateEffectifs(stand);
        repository.saveStand(stand);
        markModified();
        return stand;
    }

    private void validateEffectifs(Stand stand) {
        if (stand.getEffectifMin() > stand.getEffectifMax()) {
            throw new IllegalArgumentException(
                    "effectifMin (" + stand.getEffectifMin() + ") cannot be greater than effectifMax ("
                            + stand.getEffectifMax() + ")");
        }
    }

    public void deleteStand(String id) {
        repository.deleteStand(id);
        markModified();
    }

    /* ----------------------------- Emplacements ----------------------------- */

    public List<Emplacement> listEmplacements() {
        return repository == null ? List.of() : repository.listEmplacements();
    }

    public Emplacement createEmplacement(Emplacement emplacement) {
        emplacement.setId(requiredId(emplacement.getId(), "emplacement id"));
        validateCoordonnees(emplacement);
        repository.saveEmplacement(emplacement);
        markModified();
        return emplacement;
    }

    public Emplacement updateEmplacement(String id, Emplacement emplacement) {
        if (!repository.emplacementExists(id)) {
            throw new NotFoundException("Emplacement not found: " + id);
        }
        emplacement.setId(id);
        validateCoordonnees(emplacement);
        repository.saveEmplacement(emplacement);
        markModified();
        return emplacement;
    }

    public void deleteEmplacement(String id) {
        repository.deleteEmplacement(id);
        markModified();
    }

    private void validateCoordonnees(Emplacement emplacement) {
        Double latitude = emplacement.getLatitude();
        Double longitude = emplacement.getLongitude();
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }

    /* ------------------------------ Timeslots ------------------------------ */

    private static final String GROUPE_CRENEAU_DEFAUT_ID = "DEFAUT";

    public List<Creneau> listCreneaux() {
        return repository == null ? List.of() : repository.listCreneaux();
    }

    /** Timeslots of the currently active group only — what the solver builds its problem from. */
    public List<Creneau> listCreneauxGroupeActif() {
        return repository == null ? List.of() : repository.listCreneauxGroupeActif();
    }

    public Creneau createCreneau(Creneau creneau) {
        creneau.setId(null); // ignore any client-supplied id — the database always generates it
        defaultGroupeIfMissing(creneau);
        Creneau created = repository.insertCreneau(creneau);
        markModified();
        return created;
    }

    public Creneau updateCreneau(Long id, Creneau creneau) {
        if (!repository.creneauExists(id)) {
            throw new NotFoundException("Timeslot not found: " + id);
        }
        creneau.setId(id);
        defaultGroupeIfMissing(creneau);
        repository.updateCreneau(creneau);
        markModified();
        return creneau;
    }

    public void deleteCreneau(Long id) {
        repository.deleteCreneau(id);
        markModified();
    }

    /** Clients that don't send a group (older callers, tests) land in the default one. */
    private void defaultGroupeIfMissing(Creneau creneau) {
        if (creneau.getGroupe() == null || creneau.getGroupe().getId() == null) {
            creneau.setGroupe(new GroupeCreneau(GROUPE_CRENEAU_DEFAUT_ID, null, false));
        }
    }

    /* -------------------------- Timeslot groups ----------------------------- */

    public List<GroupeCreneau> listGroupesCreneaux() {
        return repository == null ? List.of() : repository.listGroupesCreneaux();
    }

    public GroupeCreneau createGroupeCreneau(GroupeCreneau groupe) {
        groupe.setId(requiredId(groupe.getId(), "timeslot group id"));
        if (groupe.getNom() == null || groupe.getNom().isBlank()) {
            throw new IllegalArgumentException("timeslot group name is required");
        }
        groupe.setActif(false);
        repository.saveGroupeCreneau(groupe);
        markModified();
        return groupe;
    }

    public GroupeCreneau updateGroupeCreneau(String id, GroupeCreneau groupe) {
        if (!repository.groupeCreneauExists(id)) {
            throw new NotFoundException("Timeslot group not found: " + id);
        }
        if (groupe.getNom() == null || groupe.getNom().isBlank()) {
            throw new IllegalArgumentException("timeslot group name is required");
        }
        groupe.setId(id);
        repository.saveGroupeCreneau(groupe);
        markModified();
        return groupe;
    }

    /**
     * Doesn't mark reference data as modified: switching the active group is
     * already surfaced precisely by the groupe de créneaux mismatch check (which
     * group the last solve ran for vs. the active one), so flagging it here too
     * would just be a redundant, less specific warning.
     */
    public void activerGroupeCreneau(String id) {
        if (!repository.groupeCreneauExists(id)) {
            throw new NotFoundException("Timeslot group not found: " + id);
        }
        repository.activerGroupeCreneau(id);
    }

    public void deleteGroupeCreneau(String id) {
        boolean actif = repository.listGroupesCreneaux().stream()
                .anyMatch(groupe -> groupe.getId().equals(id) && groupe.isActif());
        if (actif) {
            throw new IllegalArgumentException("Impossible de supprimer le groupe actif");
        }
        repository.deleteGroupeCreneau(id);
        markModified();
    }

    /* ------------------------------ Typologies ----------------------------- */

    public List<TypologieItem> listTypologies() {
        return repository == null ? List.of() : repository.listTypologies();
    }

    public TypologieItem createTypologie(TypologieItem typologie) {
        String id = requiredId(typologie.id(), "typology id");
        TypologieItem created = new TypologieItem(id, typologie.label());
        repository.saveTypologie(created);
        markModified();
        return created;
    }

    public TypologieItem updateTypologie(String id, TypologieItem typologie) {
        if (!repository.typologieExists(id)) {
            throw new NotFoundException("Typology not found: " + id);
        }
        TypologieItem updated = new TypologieItem(id, typologie.label());
        repository.saveTypologie(updated);
        markModified();
        return updated;
    }

    public void deleteTypologie(String id) {
        repository.deleteTypologie(id);
        markModified();
    }

    /* --------------------------- Ad hoc constraints ------------------------ */

    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return repository == null ? List.of() : repository.listContraintes();
    }

    public ContrainteAdHoc createContrainteAdHoc(ContrainteAdHoc contrainte) {
        contrainte.setId(requiredId(contrainte.getId(), "constraint id"));
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        repository.saveContrainte(contrainte);
        markModified();
        return contrainte;
    }

    public void deleteContrainteAdHoc(String id) {
        repository.deleteContrainte(id);
        markModified();
    }

    public List<ContrainteAdHoc> snapshotContraintes() {
        return repository == null ? List.of() : repository.listContraintes();
    }

    /**
     * Replaces the whole persisted reference dataset with the one carried by a
     * (sample or solved) planning, so it becomes editable through the CRUD
     * endpoints. Delegated to the repository in a single transaction.
     */
    public void importFromPlanning(PlanningFestival planning) {
        if (repository != null) {
            repository.importFromPlanning(planning);
            markModified();
        }
    }

    /* --------------------------- Legal parameters --------------------------- */

    public ParametresLegaux getParametresLegaux() {
        return repository == null ? new ParametresLegaux() : repository.getParametresLegaux();
    }

    /**
     * Saves the legal parameters, refusing anything above the ordre public
     * ceilings.
     *
     * <p>Art. <b>L3121-20</b> (48 h/week) is a disposition d'ordre public: no
     * agreement and no configuration may exceed it, short of an exceptional
     * administrative authorisation the application knows nothing about. Art.
     * <b>L3162-1</b> caps young workers at 35 h. A default value that is
     * correct protects nothing if the entry screen does not; before this check,
     * an administrator could store 100 h/week and the solver would happily
     * report a "valid" plan (hard score zero) that is plainly illegal.</p>
     *
     * <p>A <i>lower</i> value stays free: it is more protective than the law.</p>
     */
    public ParametresLegaux updateParametresLegaux(ParametresLegaux parametres) {
        verifierPlafond(parametres.getDureeHebdomadaireMaxMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMinutes",
                "la durée hebdomadaire maximale des majeurs ne peut pas dépasser 48 h "
                        + "(Code du travail art. L3121-20, disposition d'ordre public)");
        verifierPlafond(parametres.getDureeHebdomadaireMaxMineurMinutes(),
                ParametresLegaux.DUREE_HEBDOMADAIRE_MAX_MINEUR_MINUTES_PAR_DEFAUT,
                "dureeHebdomadaireMaxMineurMinutes",
                "la durée hebdomadaire maximale des mineurs ne peut pas dépasser 35 h "
                        + "(Code du travail art. L3162-1)");
        repository.saveParametresLegaux(parametres);
        markModified();
        return parametres;
    }

    private static void verifierPlafond(int valeurMinutes, int plafondMinutes, String champ, String message) {
        if (valeurMinutes <= 0) {
            throw new IllegalArgumentException(champ + " must be positive");
        }
        if (valeurMinutes > plafondMinutes) {
            throw new IllegalArgumentException(message);
        }
    }

    /* --------------------------- Constraint toggles -------------------------- */

    public java.util.Set<String> getContraintesDesactivees() {
        return repository == null ? java.util.Set.of() : repository.getContraintesDesactivees();
    }

    /**
     * Enables or disables a constraint for the next solve, recording the
     * reason and the author when it is being <b>disabled</b>.
     *
     * <p>Disabling a hard legal constraint lets the solver return a plan with a
     * hard score of zero that nonetheless breaks the Code du travail, so the
     * decision must leave a trace (constat C2 of the RH compliance audit).
     * <b>Known limit</b>: the application has no authentication, so
     * {@code utilisateurId} is whatever the client claims — exactly like
     * {@code ContrainteAdHoc.creeParUtilisateurId}. The reason and the
     * timestamp are real; the author is not proof of accountability.</p>
     */
    public void setContrainteActive(String nom, boolean actif, String motif, String utilisateurId) {
        if (repository != null) {
            repository.setContrainteActive(nom, actif, motif, utilisateurId);
            markModified();
        }
    }

    private String requiredId(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        return id;
    }

    private void markModified() {
        if (changeTracker != null) {
            changeTracker.markModified();
        }
    }

    public record TypologieItem(String id, String label) {
        public TypologieItem {
            if (label == null || label.isBlank()) {
                label = id;
            }
        }
    }
}
