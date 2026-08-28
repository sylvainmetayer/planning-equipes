package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/** CRUD of the animateur referential, plus the espace access token they are reached by. */
@ApplicationScoped
public class AnimateurService {

    @Inject
    AnimateurRepository repository;

    @Inject
    TypologieService typologies;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    SolverJobService solverJobs;

    public List<Animateur> list() {
        return repository.listAnimateurs();
    }

    public Animateur create(Animateur animateur) {
        animateur.setId(Ids.required(animateur.getId(), "animateur id"));
        validate(animateur);
        repository.saveAnimateur(animateur);
        changeTracker.markModified();
        return animateur;
    }

    public Animateur update(String id, Animateur animateur) {
        if (!repository.animateurExists(id)) {
            throw new NotFoundException("Animateur not found: " + id);
        }
        animateur.setId(id);
        validate(animateur);
        repository.saveAnimateur(animateur);
        changeTracker.markModified();
        return animateur;
    }

    /**
     * Removes the animateur, and vacates the seats they held (see
     * {@link AnimateurRepository#deleteAnimateur}).
     *
     * <p>Refused while a solve holds the solver: that solve built its problem
     * from the referential as it stood at its start, and persisting its result
     * would re-insert the animateur — personal data coming back on its own,
     * minutes later. See {@link SolverJobService#refuseIfSolving}.</p>
     */
    public void delete(String id) {
        solverJobs.refuseIfSolving();
        repository.deleteAnimateur(id);
        changeTracker.markModified();
    }

    /** Competences and wishes are both typologie ids, checked against the same referential. */
    private void validate(Animateur animateur) {
        if (animateur.getCompetences() != null) {
            typologies.validerIds(animateur.getCompetences().keySet());
        }
        if (animateur.getSouhaits() != null) {
            typologies.validerIds(animateur.getSouhaits());
        }
    }

    /** See {@link AnimateurRepository#resolveAnimateurToken}. */
    public TokenOwner resolveToken(String token) {
        return repository.resolveAnimateurToken(token);
    }

    /**
     * Rotates an animateur's espace access token. Not a reference-data change:
     * the token changes nothing the solver reads.
     */
    public String regenerateToken(String id) {
        String token = repository.regenerateAnimateurToken(id);
        if (token == null) {
            throw new BusinessError.NotFound("Animateur inconnu : " + id);
        }
        return token;
    }
}
