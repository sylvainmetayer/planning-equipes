package dev.sylvain.planning.testing;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.callback.QuarkusTestAfterAllCallback;
import io.quarkus.test.junit.callback.QuarkusTestAfterTestExecutionCallback;
import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestContext;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Puts every edition back in preparation around each {@code @QuarkusTest}
 * method — before it, and right after it, ahead of its {@code @AfterEach} —
 * and once a class is done, ahead of the next one's {@code @BeforeAll}.
 *
 * <p>A publication moves its edition to {@code PUBLIEE}, which freezes the
 * structure (ADR 0115) — and so refuses the scenario import most tests start
 * with. The tests share the database and its default edition: a phase left
 * behind by one class would refuse the next one's fixture, in an order
 * nobody chose. Like the scenario reset itself, the phase is fixture state,
 * set back here once rather than in every class that publishes. A test about
 * the phase sets it in its own body; one that publishes and then resets
 * calls {@link #backToPreparation()} first. Registered through
 * {@code META-INF/services}, so no test can forget it.</p>
 */
public class PhaseReset
        implements QuarkusTestBeforeEachCallback, QuarkusTestAfterTestExecutionCallback, QuarkusTestAfterAllCallback {

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        backToPreparation();
    }

    @Override
    public void afterTestExecution(QuarkusTestMethodContext context) {
        backToPreparation();
    }

    @Override
    public void afterAll(QuarkusTestContext context) {
        backToPreparation();
    }

    /**
     * Puts every edition back in preparation now — for a test that publishes
     * and then resets or imports in the same method.
     */
    public static void backToPreparation() {
        DataSource dataSource = Arc.container().instance(DataSource.class).get();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE edition SET phase = 'PREPARATION', structure_debloquee = FALSE"
                    + " WHERE phase <> 'PREPARATION' OR structure_debloquee");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to put the editions back in preparation", e);
        }
    }
}
