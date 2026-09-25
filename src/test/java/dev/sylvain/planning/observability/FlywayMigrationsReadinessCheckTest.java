package dev.sylvain.planning.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

/** The verdict alone, without a database: which Flyway states make the instance not ready. */
class FlywayMigrationsReadinessCheckTest {

    @Test
    void upToDateHistoryIsReady() {
        var response = FlywayMigrationsReadinessCheck.evaluate(
                new MigrationInfo[0], new MigrationInfo[] {migration(MigrationState.SUCCESS)});
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void aPendingMigrationIsNotReady() {
        var pending = migration(MigrationState.PENDING);
        var response = FlywayMigrationsReadinessCheck.evaluate(
                new MigrationInfo[] {pending}, new MigrationInfo[] {migration(MigrationState.SUCCESS), pending});
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData())
                .hasValueSatisfying(data -> assertThat(data).containsEntry("pending", 1L));
    }

    @Test
    void aFailedMigrationIsNotReady() {
        var response = FlywayMigrationsReadinessCheck.evaluate(
                new MigrationInfo[0],
                new MigrationInfo[] {migration(MigrationState.SUCCESS), migration(MigrationState.FAILED)});
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData())
                .hasValueSatisfying(data -> assertThat(data).containsEntry("failed", 1L));
    }

    @Test
    void aFailedMigrationFromANewerBinaryIsNotReadyEither() {
        var response = FlywayMigrationsReadinessCheck.evaluate(
                new MigrationInfo[0], new MigrationInfo[] {migration(MigrationState.FUTURE_FAILED)});
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
    }

    /**
     * The probe is public: a burst of anonymous calls must not turn into as
     * many reads of the history. Within the window the first verdict is
     * served again, and the history is read afresh once it has elapsed.
     */
    @Test
    void theVerdictIsCachedForAShortWindowThenReadAgain() {
        var reads = new AtomicInteger();
        var clock = new AtomicLong(1_000L);
        var check = new FlywayMigrationsReadinessCheck(null) {
            @Override
            HealthCheckResponse readHistory() {
                reads.incrementAndGet();
                return HealthCheckResponse.named(NAME).status(reads.get() == 1).build();
            }
        };
        check.nanoTime = clock::get;

        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        clock.addAndGet(FlywayMigrationsReadinessCheck.CACHE_TTL.toNanos() - 1);
        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(reads).hasValue(1);

        clock.addAndGet(1);
        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(reads).hasValue(2);
    }

    private static MigrationInfo migration(MigrationState state) {
        return (MigrationInfo) Proxy.newProxyInstance(
                MigrationInfo.class.getClassLoader(), new Class<?>[] {MigrationInfo.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getState")) {
                        return state;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
