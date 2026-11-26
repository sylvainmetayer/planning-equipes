package dev.sylvain.planning.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Says, at boot, that an old {@code BRANDING_*} or {@code LEGAL_*} variable is
 * still being read and what replaces it. Unlike {@link RetiredVariables}, the
 * old name still works: it is dropped in 5.0, with this warning.
 */
@ApplicationScoped
public class RenamedVariables {

    void warn(@Observes StartupEvent startup) {
        GroupedVariables.warnings(System::getenv).forEach(Log::warn);
    }
}
