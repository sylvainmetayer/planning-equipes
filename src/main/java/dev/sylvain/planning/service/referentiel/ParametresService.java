package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;

/**
 * The three admin-configurable parameter sets, the constraint toggles and the
 * per-edition constraint weights — everything the Données, Débogage and
 * Contraintes screens write that is not a referential row. Validation lives
 * in {@link ParametresValidator}.
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

    /**
     * Declares what the edition's créneaux are. Its own write, and not a field
     * of {@link #updateDecoupage}: the mode is decided on the Créneaux page
     * while the rest of these settings are edited on Paramètres, and a stale
     * Paramètres tab saving its payload would otherwise silently revert it.
     */
    public ParametresDecoupage updateModeGrille(ModeGrilleCreneaux mode) {
        ParametresDecoupage courants = getDecoupage();
        courants.setModeGrille(mode);
        repository.saveParametresDecoupage(courants);
        return courants;
    }

    public ParametresDecoupage updateDecoupage(ParametresDecoupage parametres) {
        ParametresValidator.checkDecoupage(parametres);
        // The mode is not this payload's business — see updateModeGrille.
        parametres.setModeGrille(getDecoupage().getModeGrille());
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
     * solve runs, not the reference data fed to it.
     */
    public ParametresSolveur updateSolveur(ParametresSolveur parametres) {
        ParametresValidator.checkParametresSolveur(parametres);
        repository.saveParametresSolveur(parametres);
        return parametres;
    }

    public ParametresNotifications getNotifications() {
        return repository.getParametresNotifications();
    }

    /**
     * Saves what the scheduled notifications may do on this edition (issues
     * #298, #299, #300). Not a problem fact, and deliberately not tracked by
     * {@link ReferenceDataChangeTracker}: it changes who gets written to at
     * night, never the data a solve reads.
     */
    public ParametresNotifications updateNotifications(ParametresNotifications parametres) {
        ParametresValidator.checkParametresNotifications(parametres);
        repository.saveParametresNotifications(parametres);
        return parametres;
    }

    public Set<String> disabledContraintes() {
        return repository.getContraintesDesactivees();
    }

    /**
     * Enables or disables a constraint for the next solve. Nothing else is
     * recorded: {@code constraint_toggle} is a state table, not a journal
     * (migration V39).
     *
     * <p>Disabling a legal constraint lets the solver return a plan with a
     * hard score of zero that nonetheless breaks the Code du travail, so the
     * UI confirms first (see {@code ConstraintCatalog.CATEGORIES_PROTEGEES}
     * and {@code LegalDisableDialog}). That confirmation is deliberately all
     * there is: with no authenticated user, an author column could only ever
     * hold the constant "ui" — which is what had V39 drop the traceability
     * columns of V16. What stands in for it is the state staying visible, on
     * the Contraintes screen and on the Solveur one.</p>
     */
    public void setContrainteActive(String nom, boolean actif) {
        repository.setContrainteActive(nom, actif);
        changeTracker.markModified();
    }

    /**
     * The weights this edition overrides, by constraint name. What is absent
     * keeps the deployment default read from {@code application.properties}
     * — see {@code PlanningService.readConfiguredWeights}.
     */
    public Map<String, Integer> constraintWeights() {
        return repository.getConstraintWeights();
    }

    /**
     * Sets one constraint's weight for this edition, or drops the override
     * when {@code poids} is {@code null} (back to the configured default).
     */
    public void setConstraintWeight(String nom, Integer poids) {
        if (poids != null) {
            ParametresValidator.checkConstraintWeight(poids);
        }
        repository.setConstraintWeight(nom, poids);
        changeTracker.markModified();
    }
}
