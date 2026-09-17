package dev.sylvain.planning.service.export;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.ProductName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Exports and re-imports the whole business dataset as a plain SQL script, so a
 * blocking dataset can be shared, archived and replayed later for analysis.
 *
 * <p>
 * The dump is intentionally restricted to the business tables: the Flyway
 * history and any other table is never exported nor accepted on import. On
 * import only {@code DELETE}, {@code TRUNCATE} and {@code INSERT} statements
 * targeting those tables are executed, and the whole script runs in a single
 * transaction.
 *
 * <p>
 * Unlike everything else that reads reference data, the dump stays
 * <b>instance-wide</b>: it ignores {@code X-Edition-Id} and carries every
 * {@code edition}. The per-edition export is the scenario YAML, which does
 * follow the current edition.
 *
 * <p>
 * <b>It is still not a backup of the database.</b> What it restores is exactly
 * {@link #TABLES}: the referential, the plan and its publications, what the
 * animateurs declared, and the settings that shape a solve. Six tables stay
 * out, and {@link #DELIBERATELY_NOT_DUMPED} says why each one does — they
 * describe the machine, its current sessions, or what it has been asked to do
 * next, none of which is the dataset. {@code DatabaseDumpCoverageTest} keeps
 * the two lists between them covering every table that exists, so a new
 * migration cannot land in neither.
 *
 * <p>
 * The backup that does cover everything, journal and sessions included, is the
 * nightly {@code pg_dump} of ADR 0015, restored outside the application.
 */
@ApplicationScoped
public class DatabaseDumpService {

    /**
     * Business tables, ordered so that a sequential insert never breaks a
     * foreign key. Deletes are issued in the reverse order.
     */
    static final List<String> TABLES = List.of(
            "edition",
            "typologie",
            "emplacement",
            "animateur",
            "stand",
            "creneau",
            "animateur_competence",
            "animateur_jour_indispo",
            "stand_typologie",
            "creneau_stand_ouvert",
            "stand_indisponibilite",
            "stand_ouverture",
            "stand_horaire",
            "stand_horaire_fenetre",
            "journee_type",
            "journee_type_vacation",
            "journee_type_date",
            "poste_affectation",
            "contrainte_ad_hoc",
            "contrainte_animateur",
            "verrouillage_planning",
            // The review marks. They describe the dataset as surely as the locks
            // do — a restore that dropped them would hand the organiser back a
            // plan nobody had read.
            "validation_journee",
            "demande_echange",
            "planning_resolution",
            "parametres_legaux",
            "parametres_qualite",
            "parametres_solveur",
            "constraint_toggle",
            "ponderation_contrainte",
            // Settings, alongside the three already above: an edition's
            // collection window, exchange fair and notification schedule shape
            // what it does as surely as its legal parameters do.
            "parametres_collecte",
            "parametres_echange",
            "parametres_notifications",
            // What the animateurs themselves said. Wishes and declarations are
            // the input the plan is built from, not a by-product of it, and a
            // restore that dropped them handed the organiser an edition whose
            // plan no longer had a justification.
            "animateur_souhait",
            "declaration_disponibilite",
            "confirmation_planning",
            // The ledger of what the scheduled jobs have already sent. Read
            // JournalNotificationsRepository's javadoc for why this one is not
            // optional: it is "the only thing standing between them and a
            // mailbox full of duplicates". Left out of the dump, a restore
            // re-arms every reminder already delivered.
            "notification_planifiee",
            // The published plan, and who was told about it. Before
            // publication_destinataire, "already told" and "still to tell" are
            // the same thing.
            "plan_snapshot",
            "publication_destinataire",
            // Its edition_id carries no foreign key, so the dump's DELETE FROM
            // edition never reached it either: an operator restoring a dump
            // kept whatever history the target already had and lost the one
            // being restored, silently.
            //
            // Last in the list because it depends on nothing: deletes are
            // issued in reverse, so it goes first, and nothing references it.
            "kpi_historique");

    /**
     * The six tables deliberately left out, and why each one stays out.
     *
     * <p>They share a shape: none of them describes <em>the dataset</em>. They
     * describe the machine it runs on, or who is currently allowed to touch it,
     * or what it has been asked to do next. Replaying them would not restore an
     * edition, it would reach into the receiving instance.
     *
     * <ul>
     *   <li>{@code horloge_jour_j} — the server's clock. Importing a
     *       colleague's dump to reproduce a bug has no business moving the date
     *       you had frozen. (On a deployed instance the question does not even
     *       arise: {@code JourJClock} ignores the row outside dev mode.)</li>
     *   <li>{@code backup_settings} — this server's backup schedule and the
     *       outcome of its last attempt. Same nature as the clock.</li>
     *   <li>{@code espace_acces}, {@code espace_session} — code hashes,
     *       remaining attempts, live sessions. Restoring them would hand
     *       another instance credentials and reset a lockout counter.</li>
     *   <li>{@code solver_job} — the queue, replayed at startup. A restore
     *       would make the receiving instance run somebody else's solves.</li>
     *   <li>{@code journal_action} — the audit trail. This one is out for the
     *       opposite reason to all the others: including it would mean every
     *       import <em>erases</em> the local journal, since the dump deletes
     *       what it carries. An audit trail that an import can wipe is not
     *       one.</li>
     * </ul>
     */
    static final List<String> DELIBERATELY_NOT_DUMPED = List.of(
            "horloge_jour_j", "backup_settings", "espace_acces", "espace_session", "solver_job", "journal_action");

    private static final Set<String> ALLOWED_TABLES = Set.copyOf(TABLES);

    /**
     * Tables whose {@code id} is a DB-generated identity/serial column: a
     * replayed dump inserts explicit id values (see V11's comment on why the
     * column stays {@code GENERATED BY DEFAULT}), which never advances the
     * underlying sequence. Left unsynced, the next id generated by the app
     * could collide with one just imported.
     */
    private static final List<String> IDENTITY_TABLES = List.of(
            "creneau",
            "stand_indisponibilite",
            "stand_ouverture",
            "stand_horaire",
            "stand_horaire_fenetre",
            "journee_type",
            "journee_type_vacation",
            // BIGSERIAL since V48: left unsynced, the first measurement
            // written after an import would collide with an id the dump just
            // replayed.
            "kpi_historique",
            // BIGSERIAL too, V40 and V54, for the same reason.
            "plan_snapshot",
            "publication_destinataire");

    private static final Pattern STATEMENT_PATTERN =
            Pattern.compile("^(insert\\s+into|delete\\s+from|truncate\\s+table|truncate)\\s+([a-z_][a-z0-9_]*)");

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    /**
     * The dump rewrites the {@code edition} table itself, so the ids and the
     * default one this cache holds are those of the <i>previous</i> dataset.
     */
    @Inject
    EditionContext editionContext;

    /** Named in the header of the dump, so a script found later says which instance produced it. */
    @Inject
    ProductName productName;

    /**
     * Builds a self-contained SQL script that wipes and repopulates every
     * business table.
     */
    public String exportDump() {
        StringBuilder sql = new StringBuilder();
        sql.append("-- ").append(productName.value()).append(" database dump\n");
        sql.append("-- Generated at ").append(Instant.now()).append('\n');
        sql.append("-- Replay with the \"Import SQL\" admin action.\n\n");
        try (Connection connection = dataSource.getConnection()) {
            for (int i = TABLES.size() - 1; i >= 0; i--) {
                sql.append("DELETE FROM ").append(TABLES.get(i)).append(";\n");
            }
            sql.append('\n');
            for (String table : TABLES) {
                appendTable(connection, table, sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to export the database", e);
        }
        return sql.toString();
    }

    /**
     * Replays a dump previously produced by {@link #exportDump()} and returns
     * the number of executed statements. Any statement outside the allowed
     * verbs/tables aborts the whole import.
     */
    public int importDump(String script) {
        List<String> statements = splitStatements(script);
        if (statements.isEmpty()) {
            throw new BusinessError.Invalid("The SQL script does not contain any statement");
        }
        statements.forEach(DatabaseDumpService::checkStatementIsAllowed);
        int executed = scope.writeAndReturn("Failed to import the database", connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                resyncIdentitySequences(statement);
                return statements.size();
            } catch (SQLException e) {
                // Rolled back by the caller, which lets this one through unwrapped:
                // a rejected script is the operator's mistake (400), not a database failure.
                throw new BusinessError.Invalid("The SQL script could not be replayed: " + e.getMessage(), e);
            }
        });
        // The replayed dump brings its own editions: without this, every
        // subsequent request keeps resolving to the default edition of the
        // dataset that was just wiped, which no longer exists.
        editionContext.invaliderCache();
        return executed;
    }

    /**
     * Advances each identity/serial sequence past the highest id now in its
     * table, so the next row the app creates never collides with one just
     * replayed from the dump.
     */
    private void resyncIdentitySequences(Statement statement) throws SQLException {
        for (String table : IDENTITY_TABLES) {
            statement.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                    + "COALESCE((SELECT MAX(id) FROM " + table + "), 1), true)");
        }
    }

    private void appendTable(Connection connection, String table, StringBuilder sql) throws SQLException {
        // A table name cannot be bound as a parameter. {@code table} is one of
        // the hardcoded {@link #TABLES} entries, never user input — and the import
        // side additionally checks it against {@link #ALLOWED_TABLES}.
        try (Statement statement = connection.createStatement();
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData metaData = rows.getMetaData();
            int columnCount = metaData.getColumnCount();
            String columns = columnNames(metaData, columnCount);
            boolean empty = true;
            while (rows.next()) {
                empty = false;
                sql.append("INSERT INTO ")
                        .append(table)
                        .append(" (")
                        .append(columns)
                        .append(") VALUES (");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        sql.append(", ");
                    }
                    sql.append(literal(rows, metaData, i));
                }
                sql.append(");\n");
            }
            if (!empty) {
                sql.append('\n');
            }
        }
    }

    private String columnNames(ResultSetMetaData metaData, int columnCount) throws SQLException {
        StringBuilder columns = new StringBuilder();
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) {
                columns.append(", ");
            }
            columns.append(metaData.getColumnName(i));
        }
        return columns.toString();
    }

    private String literal(ResultSet rows, ResultSetMetaData metaData, int index) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null || rows.wasNull()) {
            return "NULL";
        }
        int type = metaData.getColumnType(index);
        return switch (type) {
            case Types.BOOLEAN, Types.BIT -> rows.getBoolean(index) ? "TRUE" : "FALSE";
            case Types.TINYINT,
                    Types.SMALLINT,
                    Types.INTEGER,
                    Types.BIGINT,
                    Types.DECIMAL,
                    Types.NUMERIC,
                    Types.DOUBLE,
                    Types.FLOAT,
                    Types.REAL -> value.toString();
            default -> quote(rows.getString(index));
        };
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Splits the script on semicolons, ignoring the ones inside quoted literals
     * and the {@code --} comment lines.
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (inComment) {
                if (c == '\n') {
                    inComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (!inString && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                inComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                // Doubled quotes escape a quote inside a literal.
                if (inString && i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
                current.append(c);
                continue;
            }
            if (c == ';' && !inString) {
                addStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    static void checkStatementIsAllowed(String statement) {
        String normalized = statement.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        var matcher = STATEMENT_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            throw new BusinessError.Invalid(
                    "Only INSERT, DELETE and TRUNCATE statements are allowed, found: " + preview(statement));
        }
        String table = matcher.group(2);
        if (!ALLOWED_TABLES.contains(table)) {
            throw new BusinessError.Invalid("Table not allowed in an imported dump: " + table);
        }
    }

    private static String preview(String statement) {
        return statement.length() <= 60 ? statement : statement.substring(0, 60) + "...";
    }
}
