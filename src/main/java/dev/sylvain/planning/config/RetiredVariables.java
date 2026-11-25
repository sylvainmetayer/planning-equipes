package dev.sylvain.planning.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Says, at boot, that an environment variable the operator still sets no
 * longer does anything. A removed feature must not fail an upgrade nor go
 * quiet: the variable is read only to be named, and the application starts
 * either way. The list lives until 5.0, where the warning goes too.
 */
@ApplicationScoped
public class RetiredVariables {

    /** One entry per removed variable, with the sentence an operator needs to act on it. */
    record Retired(String name, String advice) {}

    private static final String HEADER_MODE = "Le mode en-tête de proxy est retiré en 4.0 : connectez-vous par un "
            + "fournisseur OIDC ou un compte de l'application (site/src/docs/installer/passer-a-la-4-0.md).";

    private static final String LAUNCHER = "Le lanceur de machine à la demande est retiré en 4.0 : déclarez votre "
            + "machine comme worker permanent avec SOLVER_WORKER_URL (site/src/docs/installer/passer-a-la-4-0.md).";

    static final List<Retired> RETIRED = List.of(
            new Retired("REMOTE_USER_ENABLED", HEADER_MODE),
            new Retired("REMOTE_USER_HEADER", HEADER_MODE),
            new Retired("REMOTE_USER_SECRET_HEADER", HEADER_MODE),
            new Retired("REMOTE_USER_SECRET", HEADER_MODE),
            new Retired("REMOTE_USER_ADMIN_EMAIL", HEADER_MODE),
            new Retired("SOLVER_WORKER_HOOK_URL", LAUNCHER),
            new Retired("SOLVER_WORKER_HOOK_SECRET", LAUNCHER),
            new Retired("SOLVER_WORKER_INSTANCE", LAUNCHER),
            new Retired("SOLVER_WORKER_START_TIMEOUT", LAUNCHER),
            new Retired("SOLVER_WORKER_IDLE_TIMEOUT", LAUNCHER));

    /** The retired variables {@code env} sets to a non-blank value, as warning lines. */
    static List<String> warnings(UnaryOperator<String> env) {
        return RETIRED.stream()
                .filter(r -> {
                    String value = env.apply(r.name());
                    return value != null && !value.isBlank();
                })
                .map(r -> "La variable " + r.name() + " n'a plus aucun effet. " + r.advice())
                .toList();
    }

    void warn(@Observes StartupEvent startup) {
        warnings(System::getenv).forEach(Log::warn);
    }
}
