package dev.sylvain.planning.scenario.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;

/**
 * Optional top-level {@code contraintes:} scenario section: how the catalogue
 * of solver rules is tuned for this event — which ones are switched off,
 * and what weight the others carry.
 *
 * <p>Both were the last settings a scenario could not carry. A file exported
 * from an edition that had disabled a rule, or raised the importance of
 * balanced workloads, re-imported elsewhere as if neither had happened: the
 * "same" scenario then solved a different problem, silently — exactly the hole
 * {@code parametresLegaux}/{@code parametresSolveur} were added to close.</p>
 *
 * @param desactivees names of the constraints (see {@code ConstraintCatalog})
 *                    the import must switch off
 * @param activees    names of the constraints the import must switch <b>on</b>
 *                    although the catalogue ships them off (see
 *                    {@code ConstraintCatalog.DESACTIVEES_PAR_DEFAUT}). What
 *                    neither list names goes back to the catalogue's default,
 *                    which is what "this is the tuning this scenario was
 *                    verified with" means once a rule can ship switched off
 * @param poids       weight per constraint name, applied to the target
 *                    edition. What the file does not name keeps the
 *                    deployment default from {@code application.properties}
 */
public record ContraintesDto(
        List<String> desactivees, List<String> activees, Map<String, @Min(1) @Max(100) Integer> poids) {}
