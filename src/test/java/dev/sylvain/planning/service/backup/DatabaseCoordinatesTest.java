package dev.sylvain.planning.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code pg_dump} needs a host, a port and a database name, and the deployment
 * only states them inside the JDBC URL. Getting that wrong does not throw at
 * boot: it throws at four in the morning, on the one night the backup mattered.
 */
class DatabaseCoordinatesTest {

    @Test
    void readsHostPortAndDatabase() {
        DatabaseCoordinates coordinates = DatabaseCoordinates.parse("jdbc:postgresql://db.internal:6432/festival");

        assertThat(coordinates).isEqualTo(new DatabaseCoordinates("db.internal", 6432, "festival"));
    }

    @Test
    void defaultsThePortToTheStandardPostgresOne() {
        assertThat(DatabaseCoordinates.parse("jdbc:postgresql://postgres/festival")
                        .port())
                .isEqualTo(5432);
    }

    @Test
    void defaultsTheHostToLocalhostWhenTheUrlNamesNone() {
        assertThat(DatabaseCoordinates.parse("jdbc:postgresql:festival"))
                .isEqualTo(new DatabaseCoordinates("localhost", 5432, "festival"));
    }

    /** Dev services hand out a URL with options appended; they are none of pg_dump's business. */
    @Test
    void ignoresTheOptionsAppendedToTheUrl() {
        DatabaseCoordinates coordinates =
                DatabaseCoordinates.parse("jdbc:postgresql://localhost:32769/quarkus?loggerLevel=OFF&sslmode=disable");

        assertThat(coordinates).isEqualTo(new DatabaseCoordinates("localhost", 32769, "quarkus"));
    }

    @Test
    void refusesAUrlItCannotRead() {
        assertThatThrownBy(() -> DatabaseCoordinates.parse("jdbc:mysql://localhost:3306/festival"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:mysql");
    }
}
