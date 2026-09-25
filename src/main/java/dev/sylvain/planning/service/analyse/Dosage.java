package dev.sylvain.planning.service.analyse;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The weighting a solve ran under, taken when it was <b>launched</b> — what
 * the solver was actually handed — and stored with its KPI line and its plan.
 * Two plans scored under different dosages are not comparable at equal
 * weights, and this is what says so.
 *
 * <p>Only what departs from a default is kept, so an edition nobody retuned
 * carries an empty dosage, and a rule added to the catalogue later reads as
 * « default » in every older line.</p>
 *
 * @param weights         effective weight of every rule the edition (or the
 *                        scenario it solved) set away from the deployment's
 * @param instanceWeights the deployment's own weight of every rule it sets
 *                        away from 1 — what tells « the instance default
 *                        changed » apart from « the edition retuned »
 * @param disabled        rules switched off though the catalogue ships them on
 * @param enabled         rules switched on though the catalogue ships them off
 */
@Schema(requiredProperties = {"weights", "instanceWeights", "disabled", "enabled"})
public record Dosage(
        Map<String, Integer> weights,
        Map<String, Integer> instanceWeights,
        List<String> disabled,
        List<String> enabled) {

    public Dosage {
        weights = weights == null ? Map.of() : new TreeMap<>(weights);
        instanceWeights = instanceWeights == null ? Map.of() : new TreeMap<>(instanceWeights);
        disabled = disabled == null ? List.of() : disabled.stream().sorted().toList();
        enabled = enabled == null ? List.of() : enabled.stream().sorted().toList();
    }

    /** Nothing retuned, nothing switched away from the catalogue. */
    @JsonIgnore
    public boolean isDefault() {
        return weights.isEmpty() && disabled.isEmpty() && enabled.isEmpty();
    }

    /** Whether two solves ran under the same effective weighting, the deployment's layer included. */
    public boolean sameAs(Dosage other) {
        return other != null && equals(other);
    }
}
