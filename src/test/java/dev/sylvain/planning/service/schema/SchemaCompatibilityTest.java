package dev.sylvain.planning.service.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.schema.SchemaCompatibility.Migration;
import dev.sylvain.planning.service.schema.SchemaCompatibility.Outcome;
import dev.sylvain.planning.service.schema.SchemaCompatibility.Verdict;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/** The boot decision of {@link SchemaCompatibilityGuard}, and its place among the boot checks, without a database. */
class SchemaCompatibilityTest {

    private static Migration migration(String version, MigrationState state) {
        return new Migration(MigrationVersion.fromVersion(version), "migration " + version, state);
    }

    @Test
    void databaseAtTheBinarysLevelIsCompatible() {
        Verdict verdict = SchemaCompatibility.assess(List.of(
                migration("113", MigrationState.SUCCESS),
                migration("114", MigrationState.SUCCESS),
                migration("115", MigrationState.SUCCESS)));

        assertThat(verdict.isAhead()).isFalse();
        assertThat(verdict.latestKnown()).hasToString("115");
        assertThat(verdict.latestApplied()).hasToString("115");
        assertThat(SchemaCompatibility.decide(verdict, false)).isEqualTo(Outcome.COMPATIBLE);
    }

    /** Behind is Flyway's to catch up, and it has by the time the guard reads the history. */
    @Test
    void databaseBehindTheBinaryIsCompatible() {
        Verdict verdict = SchemaCompatibility.assess(
                List.of(migration("114", MigrationState.SUCCESS), migration("115", MigrationState.PENDING)));

        assertThat(verdict.isAhead()).isFalse();
        assertThat(verdict.latestKnown()).hasToString("115");
        assertThat(verdict.latestApplied()).hasToString("114");
        assertThat(SchemaCompatibility.decide(verdict, false)).isEqualTo(Outcome.COMPATIBLE);
    }

    /**
     * An interrupted migration the binary ships is FLYWAY_REPAIR_AT_START's
     * business: sending the operator to an older image would be the wrong way.
     */
    @Test
    void failedMigrationTheBinaryKnowsIsNotAhead() {
        Verdict verdict = SchemaCompatibility.assess(
                List.of(migration("114", MigrationState.SUCCESS), migration("115", MigrationState.FAILED)));

        assertThat(verdict.isAhead()).isFalse();
        assertThat(SchemaCompatibility.decide(verdict, false)).isEqualTo(Outcome.COMPATIBLE);
    }

    @Test
    void databaseAheadOfTheBinaryIsRefused() {
        Verdict verdict = SchemaCompatibility.assess(List.of(
                migration("115", MigrationState.SUCCESS),
                migration("117", MigrationState.FUTURE_SUCCESS),
                migration("116", MigrationState.FUTURE_SUCCESS)));

        assertThat(verdict.isAhead()).isTrue();
        assertThat(verdict.latestKnown()).hasToString("115");
        assertThat(verdict.latestApplied()).hasToString("117");
        assertThat(verdict.ahead()).extracting(m -> m.version().getVersion()).containsExactly("116", "117");
        assertThat(SchemaCompatibility.decide(verdict, false)).isEqualTo(Outcome.REFUSE);
    }

    @Test
    void failedFutureMigrationIsAheadToo() {
        Verdict verdict = SchemaCompatibility.assess(
                List.of(migration("115", MigrationState.SUCCESS), migration("116", MigrationState.FUTURE_FAILED)));

        assertThat(verdict.isAhead()).isTrue();
        assertThat(SchemaCompatibility.describe(verdict, "1.2.4", Optional.empty()))
                .contains("V116 (« migration 116 »), en échec");
    }

    @Test
    void escapeHatchTurnsTheRefusalIntoAWarning() {
        Verdict ahead = SchemaCompatibility.assess(
                List.of(migration("115", MigrationState.SUCCESS), migration("116", MigrationState.FUTURE_SUCCESS)));
        Verdict level = SchemaCompatibility.assess(List.of(migration("115", MigrationState.SUCCESS)));

        assertThat(SchemaCompatibility.decide(ahead, true)).isEqualTo(Outcome.WARN);
        assertThat(SchemaCompatibility.decide(level, true)).isEqualTo(Outcome.COMPATIBLE);
    }

    /** Versions compare as versions, not as strings: 9999 is above 115, 99 below it. */
    @Test
    void versionsAreComparedNumerically() {
        Verdict verdict = SchemaCompatibility.assess(List.of(
                migration("99", MigrationState.SUCCESS),
                migration("115", MigrationState.SUCCESS),
                migration("9999", MigrationState.FUTURE_SUCCESS)));

        assertThat(verdict.latestKnown()).hasToString("115");
        assertThat(verdict.latestApplied()).hasToString("9999");
    }

    /** A forced boot writes down what it knows, not the newer binary's migrations. */
    @Test
    void forcedBootRecordsTheLatestMigrationItKnows() {
        Verdict ahead = SchemaCompatibility.assess(
                List.of(migration("115", MigrationState.SUCCESS), migration("116", MigrationState.FUTURE_SUCCESS)));
        Verdict level = SchemaCompatibility.assess(List.of(migration("115", MigrationState.SUCCESS)));

        assertThat(SchemaCompatibility.recordedMigration(ahead, Outcome.WARN)).hasToString("115");
        assertThat(SchemaCompatibility.recordedMigration(level, Outcome.COMPATIBLE))
                .hasToString("115");
        assertThat(SchemaCompatibility.recordedMigration(
                        SchemaCompatibility.assess(List.of(
                                migration("114", MigrationState.SUCCESS), migration("115", MigrationState.PENDING))),
                        Outcome.COMPATIBLE))
                .hasToString("114");
    }

    /**
     * The refusal comes before every observer left at the default priority, the
     * record after all of them: a boot another check refuses must not be
     * written down as a version that opened the database.
     */
    @Test
    void guardDecidesFirstAndRecordsLast() throws NoSuchMethodException {
        int defaultPriority = Interceptor.Priority.APPLICATION + 500;

        assertThat(observerPriority("checkAtStartup")).isLessThan(defaultPriority);
        assertThat(observerPriority("recordAtStartup"))
                .isGreaterThan(defaultPriority)
                .isGreaterThan(Interceptor.Priority.LIBRARY_AFTER);
    }

    private static int observerPriority(String method) throws NoSuchMethodException {
        Parameter event = SchemaCompatibilityGuard.class.getDeclaredMethod(method, StartupEvent.class)
                .getParameters()[0];
        assertThat(event.isAnnotationPresent(Observes.class)).isTrue();
        return event.getAnnotation(Priority.class).value();
    }

    @Test
    void messageNamesTheVersionsAndTheWayOut() {
        Verdict verdict = SchemaCompatibility.assess(
                List.of(migration("115", MigrationState.SUCCESS), migration("116", MigrationState.FUTURE_SUCCESS)));

        String message = SchemaCompatibility.describe(verdict, "1.2.4", Optional.of("1.3.0"));

        assertThat(message)
                .contains("La version 1.2.4")
                .contains("jusqu'à V115")
                .contains("V116 (« migration 116 »)")
                .contains("appliquée par la version 1.3.0")
                .contains("APP_VERSION=1.3.0")
                .contains("scripts/restaurer.sh")
                .contains("docs/versioning.md § 2")
                .contains("ALLOW_SCHEMA_AHEAD=true");
    }

    @Test
    void messageSaysSoWhenTheMigratingVersionIsUnknown() {
        Verdict verdict = SchemaCompatibility.assess(
                List.of(migration("115", MigrationState.SUCCESS), migration("116", MigrationState.FUTURE_SUCCESS)));

        assertThat(SchemaCompatibility.describe(verdict, "1.2.4", Optional.empty()))
                .contains("une version de l'application que cette base n'a pas enregistrée")
                .contains("redéployer la version qui a migré la base");
    }
}
