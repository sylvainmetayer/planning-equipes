package dev.sylvain.planning.api;

import dev.sylvain.planning.service.ReferenceDataRepository.ProprietaireJeton;
import jakarta.enterprise.context.RequestScoped;

/**
 * The (édition, animateur) the request's espace token resolved to, filled by
 * {@link SessionEspaceFilter} so the resource never looks the token up twice.
 * Empty on the unguarded bootstrap routes (code request, session opening).
 */
@RequestScoped
public class ProprietaireJetonCourant {

    private ProprietaireJeton proprietaire;

    void definir(ProprietaireJeton proprietaire) {
        this.proprietaire = proprietaire;
    }

    /** {@code null} when no {@link SessionEspaceRequise} filter ran for this request. */
    public ProprietaireJeton valeur() {
        return proprietaire;
    }
}
