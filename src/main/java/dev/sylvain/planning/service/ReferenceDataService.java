package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
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
        return animateur;
    }

    public Animateur updateAnimateur(String id, Animateur animateur) {
        if (!repository.animateurExists(id)) {
            throw new NotFoundException("Animateur not found: " + id);
        }
        animateur.setId(id);
        repository.saveAnimateur(animateur);
        return animateur;
    }

    public void deleteAnimateur(String id) {
        repository.deleteAnimateur(id);
    }

    /* -------------------------------- Stands ------------------------------- */

    public List<Stand> listStands() {
        return repository == null ? List.of() : repository.listStands();
    }

    public Stand createStand(Stand stand) {
        stand.setId(requiredId(stand.getId(), "stand id"));
        repository.saveStand(stand);
        return stand;
    }

    public Stand updateStand(String id, Stand stand) {
        if (!repository.standExists(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        repository.saveStand(stand);
        return stand;
    }

    public void deleteStand(String id) {
        repository.deleteStand(id);
    }

    /* ------------------------------ Timeslots ------------------------------ */

    public List<Creneau> listCreneaux() {
        return repository == null ? List.of() : repository.listCreneaux();
    }

    public Creneau createCreneau(Creneau creneau) {
        creneau.setId(requiredId(creneau.getId(), "timeslot id"));
        repository.saveCreneau(creneau);
        return creneau;
    }

    public Creneau updateCreneau(String id, Creneau creneau) {
        if (!repository.creneauExists(id)) {
            throw new NotFoundException("Timeslot not found: " + id);
        }
        creneau.setId(id);
        repository.saveCreneau(creneau);
        return creneau;
    }

    public void deleteCreneau(String id) {
        repository.deleteCreneau(id);
    }

    /* ------------------------------ Typologies ----------------------------- */

    public List<TypologieItem> listTypologies() {
        return repository == null ? List.of() : repository.listTypologies();
    }

    public TypologieItem createTypologie(TypologieItem typologie) {
        String id = requiredId(typologie.id(), "typology id");
        TypologieItem created = new TypologieItem(id, typologie.label());
        repository.saveTypologie(created);
        return created;
    }

    public TypologieItem updateTypologie(String id, TypologieItem typologie) {
        if (!repository.typologieExists(id)) {
            throw new NotFoundException("Typology not found: " + id);
        }
        TypologieItem updated = new TypologieItem(id, typologie.label());
        repository.saveTypologie(updated);
        return updated;
    }

    public void deleteTypologie(String id) {
        repository.deleteTypologie(id);
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
        return contrainte;
    }

    public void deleteContrainteAdHoc(String id) {
        repository.deleteContrainte(id);
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
        }
    }

    private String requiredId(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        return id;
    }

    public record TypologieItem(String id, String label) {
        public TypologieItem {
            if (label == null || label.isBlank()) {
                label = id;
            }
        }
    }
}
