package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;

/**
 * The three admin-configurable parameter sets, the constraint toggles and the
 * per-edition constraint weights — everything the Données, Débogage and
 * Contraintes screens write that is not a referential row. Validation lives
 * in {@link ParametresValidator}.
 */
@ApplicationScoped
public class ParametresService {

    private final ParametresRepository repository;

    private final ReferenceDataChangeTracker changeTracker;

    /** Only for the {@code planning.contraintes.*} block: the defaults an unconfigured edition solves with. */
    private final Config config;

    @Inject
    public ParametresService(ParametresRepository repository, ReferenceDataChangeTracker changeTracker, Config config) {
        this.repository = repository;
        this.changeTracker = changeTracker;
        this.config = config;
    }

    public ParametresLegaux getLegaux() {
        return repository.getParametresLegaux();
    }

    public ParametresLegaux updateLegaux(ParametresLegaux parametres) {
        ParametresValidator.checkParametresLegaux(parametres);
        repository.saveParametresLegaux(parametres);
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

    /**
     * The quality thresholds of this edition — its row, or the deployment's
     * configuration while it has none (issue #591).
     */
    public ParametresQualite qualite() {
        return repository.getParametresQualite(ParametresQualiteDefaults.of(config));
    }

    /**
     * Saves the quality thresholds of this edition. Tracked as a reference
     * change: unlike the notification settings, these <b>are</b> read by a
     * solve, so a plan computed before the change no longer describes the
     * problem the edition now poses.
     */
    public ParametresQualite updateQualite(ParametresQualite parametres) {
        ParametresValidator.checkParametresQualite(parametres);
        repository.saveParametresQualite(parametres);
        changeTracker.markModified();
        return parametres;
    }

    /**
     * Every constraint the next solve will <b>not</b> enforce: the ones this
     * edition switched off, plus the ones the catalogue ships off that nobody
     * asked for (see {@code ConstraintCatalog.DESACTIVEES_PAR_DEFAUT}). One
     * set, whatever the reason — callers that only want to know what applies
     * have no business re-deriving the default.
     */
    public Set<String> disabledContraintes() {
        Map<String, Boolean> etats = repository.getEtatsContraintes();
        Set<String> desactivees = new LinkedHashSet<>();
        for (ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            if (Boolean.FALSE.equals(etats.getOrDefault(definition.name(), definition.activeByDefault()))) {
                desactivees.add(definition.name());
            }
        }
        // A row whose name left the catalogue (renamed rule, older database)
        // is kept: it says something was switched off, and dropping it here
        // would quietly claim the opposite.
        etats.forEach((nom, actif) -> {
            if (Boolean.FALSE.equals(actif) && !ConstraintCatalog.PAR_NOM.containsKey(nom)) {
                desactivees.add(nom);
            }
        });
        return desactivees;
    }

    /**
     * What this edition explicitly chose, per constraint name — without the
     * catalogue's defaults filled in. This is what the solver is handed, so a
     * default the catalogue changes tomorrow reaches every edition that never
     * took a position, and only those.
     */
    public Map<String, Boolean> etatsContraintes() {
        return repository.getEtatsContraintes();
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
        ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(nom);
        // Asking for exactly what the catalogue already says drops the row
        // rather than pinning it: the table then holds the decisions somebody
        // took, and nothing else — the same convention as a constraint weight
        // reset to its default.
        boolean parDefaut = definition != null && definition.activeByDefault() == actif;
        repository.setEtatContrainte(nom, parDefaut ? null : actif);
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
