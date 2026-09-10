package dev.sylvain.planning.service;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.solve.SolverJobService;

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
    ConcurrentModificationGuard staleWrites;

    @Inject
    SolverJobService solverJobs;

    public List<Animateur> list() {
        return repository.listAnimateurs();
    }

    public Animateur create(Animateur animateur) {
        animateur.setId(Ids.required(animateur.getId(), "animateur id"));
        validate(animateur);
        repository.saveAnimateur(animateur, true);
        changeTracker.markModified();
        return animateur;
    }

    /**
     * Saves the animateur as edited.
     *
     * <p>Refused while a solve holds this edition's solver, for the same reason
     * as {@link #delete}: the landing persist rewrites {@code prenom},
     * {@code nom}, {@code date_naissance} and {@code manager} from the animateur
     * captured when the problem was built, and rewrites their competences and
     * off-days wholesale — so an edit made meanwhile would quietly revert
     * minutes later. See {@link SolverJobService#refuseIfSolving}.</p>
     *
     * <p>The bulk edit of the referential screen is this same method, once per
     * row ({@code ReferenceDataStore.saveMany} issues one
     * {@code PUT /api/animateurs/{id}} per animateur), so there is no
     * server-side batch to check once.</p>
     */
    public Animateur update(String id, Animateur animateur) {
        solverJobs.refuseIfSolving();
        if (!repository.animateurExists(id)) {
            throw new BusinessError.NotFound("Animateur not found: " + id);
        }
        animateur.setId(id);
        validate(animateur);
        repository.saveAnimateur(animateur, false);
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
            typologies.validateIds(animateur.getCompetences().keySet());
        }
        if (animateur.getSouhaits() != null) {
            typologies.validateIds(animateur.getSouhaits());
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

    /** See {@link AnimateurRepository#resolveAbonnementToken}. */
    public TokenOwner resolveAbonnementToken(String token) {
        return repository.resolveAbonnementToken(token);
    }

    /** See {@link AnimateurRepository#abonnementToken}. */
    public String abonnementToken(String id) {
        return repository.abonnementToken(id);
    }

    /**
     * Rotates an animateur's ICS subscription token. Independent of
     * {@link #regenerateToken}: the two credentials open different things, and
     * revoking a leaked calendar URL must not invalidate the espace link
     * printed on a PDF.
     */
    public String regenerateAbonnementToken(String id) {
        String token = repository.regenerateAbonnementToken(id);
        if (token == null) {
            throw new BusinessError.NotFound("Animateur inconnu : " + id);
        }
        return token;
    }
}
