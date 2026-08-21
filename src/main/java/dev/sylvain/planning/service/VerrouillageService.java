package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.sylvain.planning.domain.VerrouillagePlanning;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The locks a solve must honour: what the operator has decided not to let the solver move. */
@ApplicationScoped
public class VerrouillageService {

    @Inject
    ReferenceDataRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    public List<VerrouillagePlanning> list() {
        return repository.listVerrouillages();
    }

    /**
     * Records a lock, rejecting a target that does not match the type or does
     * not exist. Locking an already locked target is a no-op, not an error.
     */
    public VerrouillagePlanning create(VerrouillagePlanning verrouillage) {
        if (verrouillage.getType() == null) {
            throw new ErreurMetier.Invalide("Type de verrouillage manquant");
        }
        normaliserCible(verrouillage);
        if (verrouillage.getId() == null || verrouillage.getId().isBlank()) {
            verrouillage.setId(UUID.randomUUID().toString());
        }
        if (verrouillage.getCreeLe() == null) {
            verrouillage.setCreeLe(Instant.now());
        }
        repository.saveVerrouillage(verrouillage);
        changeTracker.markModified();
        return verrouillage;
    }

    public void delete(String id) {
        repository.deleteVerrouillage(id);
        changeTracker.markModified();
    }

    /**
     * Keeps only the target column the type expects — a payload carrying two
     * targets would be ambiguous, and the check constraint would reject it with
     * a raw SQL error instead of a usable message.
     */
    private void normaliserCible(VerrouillagePlanning verrouillage) {
        switch (verrouillage.getType()) {
            case ANIMATEUR -> {
                verifierAnimateur(verrouillage.getAnimateurId());
                verrouillage.setStandId(null);
                verrouillage.setCreneauId(null);
                verrouillage.setJour(null);
            }
            case STAND -> {
                String standId = Identifiants.requis(verrouillage.getStandId(), "stand id");
                if (!repository.standExists(standId)) {
                    throw new ErreurMetier.Invalide("Stand inconnu : " + standId);
                }
                verrouillage.setAnimateurId(null);
                verrouillage.setCreneauId(null);
                verrouillage.setJour(null);
            }
            case CRENEAU -> {
                verifierCreneau(verrouillage.getCreneauId());
                verrouillage.setAnimateurId(null);
                verrouillage.setStandId(null);
                verrouillage.setJour(null);
            }
            case JOUR -> {
                if (verrouillage.getJour() == null) {
                    throw new ErreurMetier.Invalide("Missing jour");
                }
                verrouillage.setAnimateurId(null);
                verrouillage.setStandId(null);
                verrouillage.setCreneauId(null);
            }
            case ANIMATEUR_CRENEAU -> {
                verifierAnimateur(verrouillage.getAnimateurId());
                verifierCreneau(verrouillage.getCreneauId());
                verrouillage.setStandId(null);
                verrouillage.setJour(null);
            }
        }
    }

    private void verifierAnimateur(String animateurId) {
        String id = Identifiants.requis(animateurId, "animateur id");
        if (!repository.animateurExists(id)) {
            throw new ErreurMetier.Invalide("Animateur inconnu : " + id);
        }
    }

    private void verifierCreneau(Long creneauId) {
        if (creneauId == null) {
            throw new ErreurMetier.Invalide("Missing créneau id");
        }
        if (!repository.creneauExists(creneauId)) {
            throw new ErreurMetier.Invalide("Créneau inconnu : " + creneauId);
        }
    }
}
