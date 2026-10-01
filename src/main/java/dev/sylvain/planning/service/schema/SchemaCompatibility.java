package dev.sylvain.planning.service.schema;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;

/**
 * The pure half of {@link SchemaCompatibilityGuard}: is the database
 * <b>ahead</b> of this binary, and what does the operator read when it is.
 *
 * <p>Ahead means one applied migration at least that the binary does not ship
 * and whose version is above the last one it does — Flyway's
 * {@link MigrationState#FUTURE_SUCCESS} and {@link MigrationState#FUTURE_FAILED}.
 * Nothing else counts, and the two neighbours are deliberately left to Flyway:
 * a <em>pending</em> migration is a database behind the binary, which
 * {@code migrate-at-start} has already caught up by the time this runs; a
 * <em>failed</em> one the binary knows is an interrupted migration, which
 * Flyway refuses on its own and {@code FLYWAY_REPAIR_AT_START} clears. Telling
 * an operator to roll back their image in either case would send them the
 * wrong way.</p>
 *
 * <p>Static and free of Flyway's runtime, so every case is tested without a
 * database — the states are Flyway's own public enum.</p>
 */
public final class SchemaCompatibility {

    private SchemaCompatibility() {}

    /** One row of Flyway's {@code info()}, reduced to what the decision reads. */
    public record Migration(MigrationVersion version, String description, MigrationState state) {

        public Migration {
            Objects.requireNonNull(state, "state");
        }

        static Migration of(MigrationInfo info) {
            return new Migration(info.getVersion(), info.getDescription(), info.getState());
        }

        boolean versioned() {
            return version != null;
        }

        boolean future() {
            return state == MigrationState.FUTURE_SUCCESS || state == MigrationState.FUTURE_FAILED;
        }

        String label() {
            String name = "V" + version + " (« " + description + " »)";
            return state == MigrationState.FUTURE_FAILED ? name + ", en échec" : name;
        }
    }

    /**
     * What the database holds next to what the binary ships.
     *
     * @param latestKnown   the highest migration this binary ships, {@code null} when it ships none
     * @param latestApplied the highest migration recorded in the history, {@code null} on an empty one
     * @param ahead         the applied migrations this binary does not know, in version order
     */
    public record Verdict(MigrationVersion latestKnown, MigrationVersion latestApplied, List<Migration> ahead) {

        public boolean isAhead() {
            return !ahead.isEmpty();
        }
    }

    /** What the boot does with a verdict. */
    public enum Outcome {
        /** Nothing to say: same schema, or one Flyway has just caught up. */
        COMPATIBLE,
        /** Ahead, and nobody said they knew: the boot stops. */
        REFUSE,
        /** Ahead, and {@code ALLOW_SCHEMA_AHEAD=true}: the boot goes on, loudly. */
        WARN
    }

    /** Reads Flyway's {@code info().all()}. */
    public static Verdict assess(MigrationInfo[] all) {
        return assess(Arrays.stream(all).map(Migration::of).toList());
    }

    public static Verdict assess(List<Migration> all) {
        MigrationVersion latestKnown = all.stream()
                .filter(Migration::versioned)
                .filter(migration -> migration.state().isResolved())
                .map(Migration::version)
                .max(Comparator.naturalOrder())
                .orElse(null);
        MigrationVersion latestApplied = all.stream()
                .filter(Migration::versioned)
                .filter(migration -> migration.state().isApplied())
                .map(Migration::version)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<Migration> ahead = all.stream()
                .filter(Migration::versioned)
                .filter(Migration::future)
                .sorted(Comparator.comparing(Migration::version))
                .toList();
        return new Verdict(latestKnown, latestApplied, ahead);
    }

    public static Outcome decide(Verdict verdict, boolean allowAhead) {
        if (!verdict.isAhead()) {
            return Outcome.COMPATIBLE;
        }
        return allowAhead ? Outcome.WARN : Outcome.REFUSE;
    }

    /**
     * The migration a boot writes down next to its version in
     * {@code version_applicative}: the latest applied one, except on a forced
     * boot, where it is the latest this binary knows. The future rows were
     * applied by a newer version; naming them on the forced binary's line would
     * make it, for {@code ApplicationVersionRepository.firstVersionAt}, the
     * version that migrated the database — the very answer the refusal message
     * reads.
     *
     * @return {@code null} on an empty history, or a binary that ships nothing
     */
    public static MigrationVersion recordedMigration(Verdict verdict, Outcome outcome) {
        return outcome == Outcome.WARN ? verdict.latestKnown() : verdict.latestApplied();
    }

    /**
     * The sentence the operator reads, in the log and in the boot failure.
     * French, like every other refusal to boot: it is read by whoever runs the
     * instance, not by whoever wrote it.
     *
     * @param binaryVersion {@code quarkus.application.version}
     * @param appliedBy     the application version that first started on the
     *                      latest applied migration, when the history knows it
     */
    public static String describe(Verdict verdict, String binaryVersion, Optional<String> appliedBy) {
        String migrations = verdict.ahead().stream().map(Migration::label).collect(Collectors.joining(", "));
        String known = verdict.latestKnown() == null ? "aucune" : "V" + verdict.latestKnown();
        String by = appliedBy
                .map(version -> "la version " + version + " de l'application")
                .orElse("une version de l'application que cette base n'a pas enregistrée");
        String redeploy = appliedBy
                .map(version -> "redéployer la version " + version + " (APP_VERSION=" + version + ")")
                .orElse("redéployer la version qui a migré la base");
        return "La base est EN AVANCE sur ce binaire. La version " + binaryVersion
                + " de l'application connaît les migrations jusqu'à " + known
                + " ; la base porte " + migrations + ", sa dernière migration appliquée étant V"
                + verdict.latestApplied() + ", appliquée par " + by + ". "
                + "Démarrer ferait travailler ce code sur un schéma qu'il ne connaît pas, et le schéma ne se "
                + "migre pas à l'envers. Deux issues (docs/versioning.md § 2) : " + redeploy
                + ", ou restaurer une sauvegarde antérieure à la montée de version avec scripts/restaurer.sh "
                + "(docs/exploitation.md § 5). ALLOW_SCHEMA_AHEAD=true force le démarrage malgré tout, "
                + "à repasser à false ensuite.";
    }
}
