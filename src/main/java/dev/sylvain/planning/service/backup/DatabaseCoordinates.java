package dev.sylvain.planning.service.backup;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host, port and database name of the configured datasource, extracted from its
 * JDBC URL.
 *
 * <p>{@code pg_dump} is a client of its own: it does not borrow the pool's
 * connections, it opens one from a host/port/database triple. Those three
 * values only exist inside {@code quarkus.datasource.jdbc.url}, so they are
 * parsed back out of it rather than duplicated into three more environment
 * variables an operator could set out of step with the URL the application
 * itself uses.</p>
 *
 * @param host     the server to reach, {@code localhost} when the URL names none
 * @param port     the server port, 5432 when the URL names none
 * @param database the database to dump
 */
public record DatabaseCoordinates(String host, int port, String database) {

    private static final int DEFAULT_PORT = 5432;

    /** {@code jdbc:postgresql://host:port/database?options} — host, port and options all optional. */
    private static final Pattern URL = Pattern.compile(
            "^jdbc:postgresql:(?://(?<host>[^/:?]*)(?::(?<port>\\d+))?/)?(?<database>[^/?]+)(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Reads the coordinates out of a PostgreSQL JDBC URL.
     *
     * @throws IllegalStateException when the URL is not one this parser
     *         understands — a deployment problem, reported at the first backup
     *         rather than producing an empty file
     */
    public static DatabaseCoordinates parse(String jdbcUrl) {
        Matcher matcher = URL.matcher(jdbcUrl == null ? "" : jdbcUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Cannot read host, port and database out of the datasource URL: " + jdbcUrl);
        }
        String host = matcher.group("host");
        String port = matcher.group("port");
        return new DatabaseCoordinates(
                host == null || host.isBlank() ? "localhost" : host.toLowerCase(Locale.ROOT),
                port == null ? DEFAULT_PORT : Integer.parseInt(port),
                matcher.group("database"));
    }
}
