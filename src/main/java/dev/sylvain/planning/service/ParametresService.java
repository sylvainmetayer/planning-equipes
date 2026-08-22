package dev.sylvain.planning.service;

import java.util.Set;

import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The three admin-configurable parameter sets and the constraint toggles —
 * everything the Données and Débogage tabs write that is not a referential
 * row. Validation lives in {@link ParametresValidator}.
 */
@ApplicationScoped
public class ParametresService {

    @Inject
    ParametresRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    public ParametresLegaux getLegaux() {
        return repository.getParametresLegaux();
    }

    public ParametresLegaux updateLegaux(ParametresLegaux parametres) {
        ParametresValidator.checkParametresLegaux(parametres);
        repository.saveParametresLegaux(parametres);
        changeTracker.markModified();
        return parametres;
    }

    public ParametresDecoupage getDecoupage() {
        return repository.getParametresDecoupage();
    }

    public ParametresDecoupage updateDecoupage(ParametresDecoupage parametres) {
        ParametresValidator.checkDecoupage(parametres);
        repository.saveParametresDecoupage(parametres);
        changeTracker.markModified();
        return parametres;
    }

    public ParametresSolveur getSolveur() {
        return repository.getParametresSolveur();
    }

    /**
     * Saves the solver's default termination duration (Données tab). Not a
     * problem fact and deliberately not tracked by
     * {@link ReferenceDataChangeTracker}: it only changes how long a
     * solve/analyze runs, not the reference data fed to it.
     */
    public ParametresSolveur updateSolveur(ParametresSolveur parametres) {
        ParametresValidator.checkParametresSolveur(parametres);
        repository.saveParametresSolveur(parametres);
        return parametres;
    }

    public Set<String> disabledContraintes() {
        return repository.getContraintesDesactivees();
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
    public void setContrainteActive(String nom, boolean actif) {
        repository.setContrainteActive(nom, actif);
        changeTracker.markModified();
    }
}
