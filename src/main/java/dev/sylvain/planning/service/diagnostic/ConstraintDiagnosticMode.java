package dev.sylvain.planning.service.diagnostic;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which implementation of {@link ConstraintDiagnosticService} runs.
 *
 * <p>Configured by {@value #CONFIG_PROPERTY}, and nowhere else: this enum and
 * {@link ConstraintDiagnosticService#of} are the whole switch, so no consumer
 * ever learns which one it is talking to.</p>
 *
 * <p>There is no automatic detection of the Enterprise edition, and that is the
 * point. The two implementations are not a full mode and a degraded mode to
 * fall back on — they produce the same analysis, which
 * {@code ConstraintDiagnosticServiceContractTest} enforces. So there is nothing
 * to detect and nothing for a screen to warn about; the property exists to run
 * the oracle side by side with the default, not to survive a missing
 * licence.</p>
 */
public enum ConstraintDiagnosticMode {

    /**
     * Through the solver's own score director. The default, and the only one
     * that works on Timefold 2.x without an Enterprise licence.
     */
    SCORE_DIRECTOR("score-director"),

    /**
     * Through {@code SolutionManager.analyze()}. Kept as the reference the
     * contract test compares against; throws on Timefold 2.x without a licence.
     */
    SOLUTION_MANAGER("solution-manager");

    public static final String CONFIG_PROPERTY = "planning.diagnostic.mode";

    /** What runs when {@value #CONFIG_PROPERTY} is absent, which is the normal case. */
    public static final ConstraintDiagnosticMode DEFAULT = SCORE_DIRECTOR;

    private final String configValue;

    ConstraintDiagnosticMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /**
     * @throws IllegalArgumentException naming the accepted values — a typo in a
     *         property is worth failing at startup for, rather than silently
     *         running something the operator did not ask for
     */
    public static ConstraintDiagnosticMode fromConfigValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.configValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(CONFIG_PROPERTY + " = \"" + value
                        + "\" is not one of " + Arrays.stream(values())
                                .map(ConstraintDiagnosticMode::configValue)
                                .collect(Collectors.joining(", "))));
    }
}
