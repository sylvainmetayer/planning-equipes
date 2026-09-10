package dev.sylvain.planning.service.referentiel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.sylvain.planning.domain.VerrouillageTarget;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.Ids;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;

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
            throw new BusinessError.Invalid("Type de verrouillage manquant");
        }
        verrouillage.apply(validatedTarget(verrouillage));
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
     * The target the request body describes, validated: the type must name
     * something, and that something must exist.
     *
     * <p>It writes nothing itself. {@link VerrouillagePlanning#apply} is
     * what lays it back into the columns, and is therefore the only place that
     * resets the others to {@code null} — a payload carrying two targets would
     * be ambiguous, and the {@code CHECK} constraint would refuse it with a raw
     * SQL error instead of a readable message.</p>
     */
    private VerrouillageTarget validatedTarget(VerrouillagePlanning verrouillage) {
        return switch (verrouillage.getType()) {
            case ANIMATEUR -> new VerrouillageTarget.OnAnimateur(
                    animateurExistant(verrouillage.getAnimateurId()));
            case STAND -> new VerrouillageTarget.OnStand(
                    standExistant(verrouillage.getStandId()));
            case CRENEAU -> new VerrouillageTarget.OnCreneau(
                    creneauExistant(verrouillage.getCreneauId()));
            case JOUR -> {
                if (verrouillage.getJour() == null) {
                    throw new BusinessError.Invalid("Missing jour");
                }
                yield new VerrouillageTarget.OnJour(verrouillage.getJour());
            }
            case ANIMATEUR_CRENEAU -> new VerrouillageTarget.OnAnimateurAndCreneau(
                    animateurExistant(verrouillage.getAnimateurId()),
                    creneauExistant(verrouillage.getCreneauId()));
        };
    }

    private String animateurExistant(String animateurId) {
        String id = Ids.required(animateurId, "animateur id");
        if (!animateurs.animateurExists(id)) {
            throw new BusinessError.Invalid("Animateur inconnu : " + id);
        }
        return id;
    }

    private String standExistant(String standId) {
        String id = Ids.required(standId, "stand id");
        if (!stands.standExists(id)) {
            throw new BusinessError.Invalid("Stand inconnu : " + id);
        }
        return id;
    }

    private long creneauExistant(Long creneauId) {
        if (creneauId == null) {
            throw new BusinessError.Invalid("Missing créneau id");
        }
        if (!creneaux.creneauExists(creneauId)) {
            throw new BusinessError.Invalid("Créneau inconnu : " + creneauId);
        }
        return creneauId;
    }
}
