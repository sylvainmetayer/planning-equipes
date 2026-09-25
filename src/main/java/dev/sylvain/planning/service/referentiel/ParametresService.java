package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.solve.SolveBudgetPolicy;
import dev.sylvain.planning.service.solve.SolverBudgetBounds;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /** The {@code planning.contraintes.*} block and the deployment weights: the defaults an unconfigured edition solves with. */
    private final Config config;

    /** The ceilings the operator set: a budget changed above them is refused when saved. */
    private final SolveBudgetPolicy budgetPolicy;

    @Inject
    public ParametresService(
            ParametresRepository repository,
            ReferenceDataChangeTracker changeTracker,
            Config config,
            SolveBudgetPolicy budgetPolicy) {
        this.budgetPolicy = budgetPolicy;
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

    /** The instance's defaults and ceilings, shown beside the edition's budget. */
    public SolverBudgetBounds solverBudgetBounds() {
        return budgetPolicy.bounds();
    }

    /**
     * Saves the edition's solve budget (Solveur page). Not a problem fact and
     * deliberately not tracked by {@link ReferenceDataChangeTracker}: it only
     * changes how long a solve runs, not the reference data fed to it.
     */
    public ParametresSolveur updateSolveur(ParametresSolveur parametres) {
        ParametresValidator.checkParametresSolveur(
                parametres, repository.getParametresSolveur(), budgetPolicy.bounds());
        repository.saveParametresSolveur(parametres);
        return parametres;
    }

    /**
     * Saves the budget a scenario file pins, keeping this edition's mail
     * switch — a file carries no mail setting. A value above the instance's
     * ceiling is stored as the file says, and launches run it capped with a
     * warning: see {@link ParametresValidator#checkImportedParametresSolveur}.
     */
    public ParametresSolveur importSolveur(ParametresSolveur parametres) {
        ParametresValidator.checkImportedParametresSolveur(parametres, budgetPolicy.bounds());
        ParametresSolveur kept = new ParametresSolveur(
                parametres.dureeResolutionSecondes(),
                parametres.plateauSecondes(),
                repository.getParametresSolveur().mailFinResolution());
        repository.saveParametresSolveur(kept);
        return kept;
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
     * Enables or disables a constraint for the next solve, and records the
     * change in the weight history when the effective state moved — asking
     * for the state already in force writes nothing.
     *
     * <p>Disabling a legal constraint lets the solver return a plan with a
     * hard score of zero that nonetheless breaks the Code du travail, so the
     * UI confirms first (see {@code ConstraintCatalog.CATEGORIES_PROTEGEES}
     * and {@code LegalDisableDialog}). That confirmation is deliberately all
     * there is: with no authenticated user, an author column could only ever
     * hold the constant "ui" — which is what had V39 drop the traceability
     * columns of V16. The history keeps an origin, never a person.</p>
     */
    public void setContrainteActive(String nom, boolean actif, WeightChangeOrigin origin) {
        ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(nom);
        // Asking for exactly what the catalogue already says drops the row
        // rather than pinning it: the table then holds the decisions somebody
        // took, and nothing else — the same convention as a constraint weight
        // reset to its default.
        boolean parDefaut = definition != null && definition.activeByDefault() == actif;
        repository.setEtatContrainte(nom, parDefaut ? null : actif, stored -> {
            boolean avant = stored != null ? stored : defaultActive(nom);
            return avant == actif ? null : WeightChange.of(nom, null, null, false, avant, actif, origin, null);
        });
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
     * when {@code poids} is {@code null} (back to the configured default), and
     * records the change in the weight history when the effective weight
     * moved: putting the same value back writes nothing, and so does dropping
     * an override equal to the default.
     */
    public void setConstraintWeight(String nom, Integer poids, WeightChangeOrigin origin) {
        if (poids != null) {
            ParametresValidator.checkConstraintWeight(poids);
        }
        int defaut = configuredWeight(nom);
        int apres = poids != null ? poids : defaut;
        repository.setConstraintWeight(nom, poids, stored -> {
            int avant = stored != null ? stored : defaut;
            return avant == apres ? null : WeightChange.of(nom, avant, apres, poids == null, null, null, origin, null);
        });
        changeTracker.markModified();
    }

    /**
     * Opens the history of a duplicated edition — the current one — with one
     * line per rule whose dosage it inherited from {@code sourceEditionId},
     * rather than with the source's whole history: those changes were made to
     * another edition.
     */
    public void recordInheritedDosage(String sourceEditionId) {
        Map<String, Integer> poids = repository.getConstraintWeights();
        Map<String, Boolean> etats = repository.getEtatsContraintes();
        List<WeightChange> lines = new ArrayList<>();
        for (String nom : new TreeSet<>(poids.keySet())) {
            int defaut = configuredWeight(nom);
            if (poids.get(nom) != defaut) {
                lines.add(WeightChange.of(
                        nom,
                        defaut,
                        poids.get(nom),
                        false,
                        null,
                        null,
                        WeightChangeOrigin.DUPLICATION,
                        sourceEditionId));
            }
        }
        for (String nom : new TreeSet<>(etats.keySet())) {
            boolean defaut = defaultActive(nom);
            if (etats.get(nom) != defaut) {
                lines.add(WeightChange.of(
                        nom,
                        null,
                        null,
                        false,
                        defaut,
                        etats.get(nom),
                        WeightChangeOrigin.DUPLICATION,
                        sourceEditionId));
            }
        }
        repository.recordHistory(lines);
    }

    /** The weight the next solve gives {@code nom} in this edition: its own, else the deployment's. */
    public int effectiveWeight(String nom) {
        Integer propre = repository.getConstraintWeights().get(nom);
        return propre != null ? propre : configuredWeight(nom);
    }

    /** Whether the next solve enforces {@code nom} in this edition: its own choice, else the catalogue's. */
    public boolean effectiveActive(String nom) {
        Boolean propre = repository.getEtatsContraintes().get(nom);
        return propre != null ? propre : defaultActive(nom);
    }

    /**
     * {@code planning.constraint-weights.<nom>}, 1 when unset — the same read
     * as {@code SolverConfiguration.readConfiguredWeights}, which the solve
     * applies.
     */
    public int configuredWeight(String nom) {
        return config.getOptionalValue("planning.constraint-weights." + nom, Integer.class)
                .orElse(1);
    }

    private static boolean defaultActive(String nom) {
        ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(nom);
        return definition == null || definition.activeByDefault();
    }
}
