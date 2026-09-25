package dev.sylvain.planning.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether this server may freeze its notion of today (see {@code JourJClock}).
 *
 * <p>Granted under {@code quarkus:dev}, as it always was, and on an instance
 * that asks for it explicitly with {@code HORLOGE_SIMULEE_AUTORISEE=true} — a
 * staging server, where the mode jour J screen and the espace animateur have to
 * be rehearsed on a real deployment, out of season.</p>
 *
 * <p>Not folded into {@link DevMode}: that one also decides whether the
 * interface links to the Dev UI, which a staging server has no business
 * offering. Off by default, and it has to stay off in production — see
 * {@code JourJClock} for what a frozen date does there, and for why the guard
 * sits on the read as well as on the write.</p>
 */
@ApplicationScoped
public class SimulatedClockPermission {

    private final DevMode devMode;

    private final boolean autoriseeParConfiguration;

    @Inject
    public SimulatedClockPermission(
            DevMode devMode,
            @ConfigProperty(name = "planning.horloge-simulee.autorisee", defaultValue = "false")
                    boolean autoriseeParConfiguration) {
        this.devMode = devMode;
        this.autoriseeParConfiguration = autoriseeParConfiguration;
    }

    public boolean isGranted() {
        return devMode.isActive() || autoriseeParConfiguration;
    }
}
