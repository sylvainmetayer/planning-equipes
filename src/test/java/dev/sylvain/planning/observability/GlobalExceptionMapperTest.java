package dev.sylvain.planning.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** What the last-resort mapper treats as an alert, and what it does not. */
class GlobalExceptionMapperTest {

    private final GlobalExceptionMapper mapper = new GlobalExceptionMapper();

    /**
     * A client that closes the connection while its answer is written — a
     * download cancelled, a tab closed — breaks nothing: logged at DEBUG, kept
     * out of Sentry, where it used to land as a 500.
     */
    @Test
    void aClientClosingTheConnectionIsNotAnAlert() {
        IOException coupure = new IOException("Failed to write", new ClosedChannelException());

        assertThat(GlobalExceptionMapper.clientClosedConnection(coupure)).isTrue();
        assertThat(GlobalExceptionMapper.clientClosedConnection(new RuntimeException(coupure)))
                .isTrue();
        assertThat(GlobalExceptionMapper.clientClosedConnection(new IOException("Connection has been closed")))
                .isTrue();
    }

    @Test
    void anyOtherFailureStaysAnAlert() {
        assertThat(GlobalExceptionMapper.clientClosedConnection(new IOException("No space left on device")))
                .isFalse();
        assertThat(GlobalExceptionMapper.clientClosedConnection(new NullPointerException()))
                .isFalse();
        assertThat(mapper.toResponse(new NullPointerException()).getStatus()).isEqualTo(500);
    }

    @Test
    void aNulCharacterIsTheCallersError() {
        Response response = mapper.toResponse(new RuntimeException(new SQLException("invalid byte sequence", "22021")));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getEntity()).asString().contains(NulCharacterRefusal.MESSAGE);
    }
}
