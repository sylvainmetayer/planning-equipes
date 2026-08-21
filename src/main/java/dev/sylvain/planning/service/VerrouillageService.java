package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.sylvain.planning.domain.CibleVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The locks a solve must honour: what the operator has decided not to let the solver move. */
@ApplicationScoped
public class VerrouillageService {

    @Inject
    VerrouillageRepository repository;

    /** The three referentials a lock can point at — probed, never written, from here. */
    @Inject
    AnimateurRepository animateurs;

    @Inject
    StandRepository stands;

    @Inject
    CreneauRepository creneaux;

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
        verrouillage.appliquer(cibleValidee(verrouillage));
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
     * La cible que le corps de requête décrit, validée : le type doit désigner
     * quelque chose, et ce quelque chose doit exister.
     *
     * <p>Elle n'écrit rien elle-même. C'est {@link VerrouillagePlanning#appliquer}
     * qui la repose dans les colonnes, et qui est donc le seul endroit à
     * remettre les autres à {@code null} — un payload portant deux cibles
     * serait ambigu, et la contrainte {@code CHECK} le refuserait avec une
     * erreur SQL brute au lieu d'un message lisible.</p>
     */
    private CibleVerrouillage cibleValidee(VerrouillagePlanning verrouillage) {
        return switch (verrouillage.getType()) {
            case ANIMATEUR -> new CibleVerrouillage.SurAnimateur(
                    animateurExistant(verrouillage.getAnimateurId()));
            case STAND -> new CibleVerrouillage.SurStand(
                    standExistant(verrouillage.getStandId()));
            case CRENEAU -> new CibleVerrouillage.SurCreneau(
                    creneauExistant(verrouillage.getCreneauId()));
            case JOUR -> {
                if (verrouillage.getJour() == null) {
                    throw new ErreurMetier.Invalide("Missing jour");
                }
                yield new CibleVerrouillage.SurJour(verrouillage.getJour());
            }
            case ANIMATEUR_CRENEAU -> new CibleVerrouillage.SurAnimateurEtCreneau(
                    animateurExistant(verrouillage.getAnimateurId()),
                    creneauExistant(verrouillage.getCreneauId()));
        };
    }

    private String animateurExistant(String animateurId) {
        String id = Identifiants.requis(animateurId, "animateur id");
        if (!animateurs.animateurExists(id)) {
            throw new ErreurMetier.Invalide("Animateur inconnu : " + id);
        }
        return id;
    }

    private String standExistant(String standId) {
        String id = Identifiants.requis(standId, "stand id");
        if (!stands.standExists(id)) {
            throw new ErreurMetier.Invalide("Stand inconnu : " + id);
        }
        return id;
    }

    private long creneauExistant(Long creneauId) {
        if (creneauId == null) {
            throw new ErreurMetier.Invalide("Missing créneau id");
        }
        if (!creneaux.creneauExists(creneauId)) {
            throw new ErreurMetier.Invalide("Créneau inconnu : " + creneauId);
        }
        return creneauId;
    }
}
