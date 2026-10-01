package dev.sylvain.planning.service.schema;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * {@link SchemaCompatibilityGuard}'s decision taken once more, right before
 * Flyway repairs its history — the one moment the guard itself comes too late.
 *
 * <p>{@code ignore-migration-patterns=*:future} keeps repair away from the
 * <em>successful</em> rows of a newer binary, but repair starts by deleting
 * every <em>failed</em> row, a future one included. With
 * {@code FLYWAY_REPAIR_AT_START=true}, Quarkus repairs before it migrates, and
 * both before {@code StartupEvent}: a newer binary's interrupted migration would
 * be gone from the history by the time the guard reads it, and the older
 * binary would boot silently on a half-migrated schema it does not know. This
 * callback refuses the repair instead — with the guard's own message — unless
 * {@code ALLOW_SCHEMA_AHEAD=true}, in which case the repair goes on with the
 * same warning: a forced boot is what the operator asked for.</p>
 *
 * <p>Registered through Quarkus's {@link FlywayConfigurationCustomizer}, so it
 * rides on every repair of the application's {@link Flyway}, not only the one
 * at start. It must not inject {@code Flyway} — the bean it is customising —
 * and reads the history through a copy of the configuration it is handed
 * instead: the same migrations, the same history table, its own connection.</p>
 */
@Singleton
public class SchemaCompatibilityRepairGuard implements FlywayConfigurationCustomizer, Callback {

    private final ApplicationVersionRepository versions;

    private final String binaryVersion;

    private final boolean allowAhead;

    @Inject
    public SchemaCompatibilityRepairGuard(
            ApplicationVersionRepository versions,
            @ConfigProperty(name = "quarkus.application.version") String binaryVersion,
            @ConfigProperty(name = "planning.schema.allow-ahead", defaultValue = "false") boolean allowAhead) {
        this.versions = versions;
        this.binaryVersion = binaryVersion;
        this.allowAhead = allowAhead;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        List<Callback> callbacks = new ArrayList<>(Arrays.asList(configuration.getCallbacks()));
        callbacks.add(this);
        configuration.callbacks(callbacks.toArray(Callback[]::new));
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_REPAIR;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    /** Flyway wraps what this throws in a {@code FlywayException} that keeps the message. */
    @Override
    public void handle(Event event, Context context) {
        check(history(context.getConfiguration()), allowAhead);
    }

    @Override
    public String getCallbackName() {
        return getClass().getSimpleName();
    }

    /** The escape hatch passed in, like {@link SchemaCompatibilityGuard#check(boolean)}, for the tests. */
    SchemaCompatibility.Outcome check(MigrationInfo[] history, boolean allowAhead) {
        return SchemaCompatibilityGuard.enforce(
                SchemaCompatibility.assess(history),
                allowAhead,
                binaryVersion,
                versions,
                "réparation de l'historique Flyway sur une base en avance, qui en efface les migrations en échec");
    }

    /**
     * {@code info()} on a Flyway built from the configuration being repaired,
     * without its callbacks: this one would otherwise be copied along, and the
     * read needs none of them.
     */
    private static MigrationInfo[] history(Configuration configuration) {
        return Flyway.configure(configuration.getClassLoader())
                .configuration(configuration)
                .callbacks(new Callback[0])
                .load()
                .info()
                .all();
    }
}
