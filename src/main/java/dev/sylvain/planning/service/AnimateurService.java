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

    public List<Animateur> list() {
        return repository.listAnimateurs();
    }

    public Animateur create(Animateur animateur) {
        animateur.setId(Identifiants.requis(animateur.getId(), "animateur id"));
        valider(animateur);
        repository.saveAnimateur(animateur);
        changeTracker.markModified();
        return animateur;
    }

    public Animateur update(String id, Animateur animateur) {
        if (!repository.animateurExists(id)) {
            throw new NotFoundException("Animateur not found: " + id);
        }
        animateur.setId(id);
        valider(animateur);
        repository.saveAnimateur(animateur);
        changeTracker.markModified();
        return animateur;
    }

    public void delete(String id) {
        repository.deleteAnimateur(id);
        changeTracker.markModified();
    }

    /** Competences and wishes are both typologie ids, checked against the same referential. */
    private void valider(Animateur animateur) {
        if (animateur.getCompetences() != null) {
            typologies.validerIds(animateur.getCompetences().keySet());
        }
        if (animateur.getSouhaits() != null) {
            typologies.validerIds(animateur.getSouhaits());
        }
    }

    /** See {@link AnimateurRepository#resoudreJetonAnimateur}. */
    public ProprietaireJeton resoudreJeton(String jeton) {
        return repository.resoudreJetonAnimateur(jeton);
    }

    /**
     * Rotates an animateur's espace access token. Not a reference-data change:
     * the token changes nothing the solver reads.
     */
    public String regenererJeton(String id) {
        String jeton = repository.regenererJetonAnimateur(id);
        if (jeton == null) {
            throw new ErreurMetier.Introuvable("Animateur inconnu : " + id);
        }
        return jeton;
    }
}
