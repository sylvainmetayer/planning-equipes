package dev.sylvain.planning.service.export;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.schema.SchemaCompatibility;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

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
 * animateurs declared, and the settings that shape a solve. Nine tables stay
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
            // The id counters (ADR 0050): restored with the rows they numbered,
            // or the next creation would draw an id the dump just replayed.
            "compteur_identifiant",
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
            // The freeze of the referential: which families the organiser
            // declared ready. A restore without it would reopen them unasked.
            "gel_referentiel",
            // The edition's consignes (issue #4): a band closed on given dates,
            // the compensation chosen, and the créneaux they added — plus the
            // presets they are made from. They change which seats a solve is
            // given, so a restore without them would rebuild a nominal day the
            // organiser had closed. Parents first: the stands and créneaux they
            // point at are above.
            "consigne_edition",
            "consigne_edition_fenetre",
            "consigne_edition_ouverture",
            "consigne_edition_creneau",
            "prereglage_consigne",
            "prereglage_consigne_fenetre",
            // The weather alert's settings: thresholds and the presets they
            // suggest, so after the presets they point at (ADR 0074). Its
            // state, meteo_etat, is the machine's and stays out.
            "parametres_meteo",
            "demande_echange",
            "planning_resolution",
            "parametres_legaux",
            "parametres_qualite",
            "parametres_solveur",
            "constraint_toggle",
            "ponderation_contrainte",
            // The history of those two tables, values included: the reason a
            // dosage is what it is outlives the ninety days of the journal.
            "ponderation_contrainte_historique",
            // Settings, alongside the three already above: an edition's
            // collection window, exchange fair and notification schedule shape
            // what it does as surely as its legal parameters do.
            "parametres_collecte",
            "parametres_echange",
            "parametres_signalement",
            "parametres_notifications",
            "contact_organisation",
            // What the animateurs themselves said. Wishes and declarations are
            // the input the plan is built from, not a by-product of it, and a
            // restore that dropped them handed the organiser an edition whose
            // plan no longer had a justification.
            "animateur_souhait",
            "declaration_disponibilite",
            "declaration_coequipier",
            // « Je ne pourrai pas être là » (issue #533): what they reported,
            // and what the organisation decided about it.
            "signalement_absence",
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
            // Which of those mails reached their recipient: without it, a
            // restore says « envoyé » of nothing and hides every failure.
            "envoi_planning",
            // The same question for every mail to an animateur — what tells a
            // failed send from a silence on the Animateurs page, and what
            // keeps the reminders off an address the relay refused.
            "envoi_mail",
            // Its edition_id carries no foreign key, so the dump's DELETE FROM
            // edition never reached it either: an operator restoring a dump
            // kept whatever history the target already had and lost the one
            // being restored, silently.
            //
            // Last in the list because it depends on nothing: deletes are
            // issued in reverse, so it goes first, and nothing references it.
            "kpi_historique",
            // The frozen realised measure of finished editions (ADR 0070):
            // no foreign key either, for the same reason — it outlives the
            // edition it describes, and a restore must bring it back.
            "kpi_realise");

    /**
     * The twelve tables deliberately left out, and why each one stays out.
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
     *   <li>{@code lien_affichage_mural}, {@code lien_affichage_mural_emplacement}
     *       — the wall display links: token hashes, credentials of this
     *       instance like the espace sessions. A restore elsewhere would reopen
     *       screens nobody there handed out.</li>
     *   <li>{@code solver_job} — the queue, replayed at startup. A restore
     *       would make the receiving instance run somebody else's solves.</li>
     *   <li>{@code journal_action} — the audit trail. This one is out for the
     *       opposite reason to all the others: including it would mean every
     *       import <em>erases</em> the local journal, since the dump deletes
     *       what it carries. An audit trail that an import can wipe is not
     *       one.</li>
     *   <li>{@code verification_besoin} — the staffing checks the history
     *       names by id, left out with it for the same reason: an import must
     *       not erase the trail of trials an organiser came back to read.</li>
     *   <li>{@code version_applicative} — the application versions that
     *       opened <em>this</em> database, beside {@code flyway_schema_history},
     *       which no dump carries either. Replaying another instance's would
     *       make the receiving one claim versions it never ran.</li>
     *   <li>{@code meteo_etat} — what the last weather query of an edition
     *       saw on this instance: when it read, since when the service is
     *       unreachable. Another instance's would show a read it never made.</li>
     *   <li>{@code webhook}, {@code webhook_livraison} — the outgoing webhooks
     *       of this instance and the journal of their deliveries. Secrets
     *       (encrypted with a key the receiving instance does not hold) and the
     *       state of a machine, not the dataset: a restore elsewhere would make
     *       that instance post into somebody else's channels.</li>
     * </ul>
     */
    static final List<String> DELIBERATELY_NOT_DUMPED = List.of(
            "horloge_jour_j",
            "backup_settings",
            "espace_acces",
            "espace_session",
            "lien_affichage_mural",
            "lien_affichage_mural_emplacement",
            "solver_job",
            "journal_action",
            "verification_besoin",
            "version_applicative",
            "meteo_etat",
            "webhook",
            "webhook_livraison");

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
            "publication_destinataire",
            // BIGSERIAL since V110.
            "envoi_planning",
            // BIGSERIAL since V119.
            "envoi_mail",
            // BIGSERIAL since V111.
            "signalement_absence",
            // BIGSERIAL since V103.
            "ponderation_contrainte_historique");

    /**
     * Advances each identity/serial sequence past the highest id now in its
     * table, so the next row the app creates never collides with one just
     * replayed from the dump. One statement for every table, built once from
     * {@link #IDENTITY_TABLES}: a table name cannot be bound as a parameter,
     * and this list is the only thing ever concatenated into it.
     */
    private static final String RESYNC_IDENTITY_SEQUENCES = IDENTITY_TABLES.stream()
            .map(table -> "setval(pg_get_serial_sequence('" + table + "', 'id'), " + "COALESCE((SELECT MAX(id) FROM "
                    + table + "), 1), true)")
            .collect(Collectors.joining(", ", "SELECT ", ""));

    /**
     * The header line naming the schema a dump was taken at — the last
     * migration applied to its database. Written by {@link #exportDump()},
     * read back by {@link #importDump(String)}.
     */
    static final String SCHEMA_VERSION_HEADER = "-- Schema version: ";

    /** {@link #SCHEMA_VERSION_HEADER} as read back: a {@code V} before the number is tolerated. */
    private static final Pattern SCHEMA_VERSION_LINE =
            Pattern.compile("^--\\s*Schema version:\\s*V?([0-9]+(?:\\.[0-9]+)*)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern STATEMENT_PATTERN =
            Pattern.compile("^(insert\\s+into|delete\\s+from|truncate\\s+table|truncate)\\s+([a-z_][a-z0-9_]*)");

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    /**
     * The dump rewrites the {@code edition} table itself, so the ids and the
     * default one this cache holds are those of the <i>previous</i> dataset.
     */
    private final EditionContext editionContext;

    /** Named in the header of the dump, so a script found later says which instance produced it. */
    private final ProductName productName;

    /** The migration history: the schema a dump is taken at, and the one it is replayed on. */
    private final Flyway flyway;

    @Inject
    public DatabaseDumpService(
            DataSource dataSource,
            JdbcEditionScope scope,
            EditionContext editionContext,
            ProductName productName,
            Flyway flyway) {
        this.dataSource = dataSource;
        this.scope = scope;
        this.editionContext = editionContext;
        this.productName = productName;
        this.flyway = flyway;
    }

    /**
     * Builds a self-contained SQL script that wipes and repopulates every
     * business table. Its header names the schema it was taken at
     * ({@link #SCHEMA_VERSION_HEADER}): a dump is a list of rows shaped by the
     * migrations applied when it was taken, and a later migration that
     * changes a column's type leaves it unable to replay.
     */
    public String exportDump() {
        StringBuilder sql = new StringBuilder();
        sql.append("-- ").append(productName.value()).append(" database dump\n");
        sql.append("-- Generated at ").append(Instant.now()).append('\n');
        currentSchemaVersion()
                .ifPresent(version ->
                        sql.append(SCHEMA_VERSION_HEADER).append(version).append('\n'));
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
     * verbs/tables aborts the whole import, and so does a dump whose header
     * names another schema than this database's ({@link #checkSchemaVersion}).
     */
    public int importDump(String script) {
        List<String> statements = splitStatements(script);
        if (statements.isEmpty()) {
            throw new BusinessError.Invalid("The SQL script does not contain any statement");
        }
        checkSchemaVersion(script, currentSchemaVersion().orElse(null));
        statements.forEach(DatabaseDumpService::checkStatementIsAllowed);
        int executed = scope.writeAndReturn("Failed to import the database", connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.addBatch(sql);
                }
                statement.executeBatch();
                statement.execute(RESYNC_IDENTITY_SEQUENCES);
                for (String resync : RESYNC_ID_COUNTERS) {
                    statement.execute(resync);
                }
                return statements.size();
            } catch (SQLException e) {
                // Rolled back by the caller, which lets this one through unwrapped:
                // a rejected script is the operator's mistake (400), not a database failure.
                throw new BusinessError.Invalid("The SQL script could not be replayed: " + serverMessage(e), e);
            }
        });
        // The replayed dump brings its own editions: without this, every
        // subsequent request keeps resolving to the default edition of the
        // dataset that was just wiped, which no longer exists.
        editionContext.invaliderCache();
        return executed;
    }

    /**
     * The last migration applied to this database — what its tables look
     * like, whatever the binary ships ({@code ALLOW_SCHEMA_AHEAD}). Empty on
     * a history Flyway has not written yet.
     */
    private Optional<String> currentSchemaVersion() {
        MigrationVersion applied =
                SchemaCompatibility.assess(flyway.info().all()).latestApplied();
        return Optional.ofNullable(applied).map(MigrationVersion::getVersion);
    }

    /** The schema a dump's header names, read in its leading comment lines; empty when it names none. */
    static Optional<String> schemaVersionOf(String script) {
        for (String line : script.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("--")) {
                return Optional.empty();
            }
            Matcher version = SCHEMA_VERSION_LINE.matcher(trimmed);
            if (version.matches()) {
                return Optional.of(version.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * Refuses a dump taken at another schema than this database's. Its rows
     * are shaped by the migrations applied when it was taken: replayed on
     * another schema, an insert fails halfway on a column that changed type,
     * or worse, lands a value the migration since rewrote. Compared as
     * Flyway versions, so {@code 122} and {@code 122.0} are the same.
     *
     * <p>A script naming no schema is let through, as before: a dump exported
     * before the header existed, or a few statements written by hand. Its
     * replay is then the operator's call, and {@code docs/import-export.md}
     * says how to check it first.</p>
     */
    static void checkSchemaVersion(String script, String current) {
        Optional<String> dump = schemaVersionOf(script);
        if (dump.isEmpty() || current == null) {
            return;
        }
        if (!MigrationVersion.fromVersion(dump.get()).equals(MigrationVersion.fromVersion(current))) {
            throw new BusinessError.Invalid("Ce dump a été exporté au schéma V" + dump.get()
                    + " et cette base est au schéma V" + current + " : il ne se rejoue pas sur un autre schéma."
                    + " Rejouez-le sur une instance au schéma V" + dump.get()
                    + ", puis exportez-en un nouveau une fois cette instance mise à jour.");
        }
    }

    /**
     * The database's own sentence for a failed replay. A batch wraps it: the
     * statement that failed is the chained exception, and its message is the
     * one naming the table and the constraint.
     */
    private static String serverMessage(SQLException e) {
        SQLException cause = e.getNextException();
        return (cause != null ? cause : e).getMessage();
    }

    /**
     * The business ids are text drawn from counters (ADR 0050), which a
     * sequence resync cannot reach: the edition sequence is moved past the
     * highest E<n> replayed, and every per-edition counter is raised to the
     * highest number its referential holds — a dump whose counter rows were
     * edited out, or lag behind, must still never hand an id out twice. One
     * statement per referential, assembled once from the constants below and
     * never from input, like {@link #TABLES}.
     */
    private static final List<String> RESYNC_ID_COUNTERS = List.of(
            "SELECT setval('edition_numero_seq', "
                    + "COALESCE((SELECT MAX(CAST(substr(id, 2) AS BIGINT)) FROM edition WHERE id ~ '^E[1-9][0-9]{0,17}$'), 1), "
                    + "EXISTS (SELECT 1 FROM edition WHERE id ~ '^E[1-9][0-9]{0,17}$'))",
            counterResync("ANIMATEUR", "animateur", "A"),
            counterResync("STAND", "stand", "S"),
            counterResync("TYPOLOGIE", "typologie", "T"),
            counterResync("EMPLACEMENT", "emplacement", "L"),
            counterResync("CONTRAINTE", "contrainte_ad_hoc", "C"));

    private static String counterResync(String entite, String table, String prefixe) {
        return "INSERT INTO compteur_identifiant (edition_id, entite, dernier) "
                + "SELECT edition_id, '" + entite + "', MAX(CAST(substr(id, 2) AS BIGINT)) FROM " + table
                + " WHERE id ~ '^" + prefixe + "[1-9][0-9]{0,17}$' GROUP BY edition_id "
                + "ON CONFLICT (edition_id, entite) DO UPDATE "
                + "SET dernier = GREATEST(compteur_identifiant.dernier, EXCLUDED.dernier)";
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
        if (script == null) {
            return new ArrayList<>();
        }
        return new StatementSplitter(script).split();
    }

    /** One pass over a script, cutting it on the semicolons that end a statement. */
    private static final class StatementSplitter {

        private final String script;
        private final List<String> statements = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private boolean inString;
        private int position;

        StatementSplitter(String script) {
            this.script = script;
        }

        List<String> split() {
            while (position < script.length()) {
                step(script.charAt(position));
            }
            flush();
            return statements;
        }

        private void step(char c) {
            if (!inString && c == '-' && charAt(position + 1) == '-') {
                skipComment();
            } else if (c == '\'') {
                quote();
            } else if (c == ';' && !inString) {
                flush();
                position++;
            } else {
                current.append(c);
                position++;
            }
        }

        /** A {@code --} comment runs to the end of its line, which stands in for it as one blank. */
        private void skipComment() {
            int endOfLine = script.indexOf('\n', position);
            if (endOfLine < 0) {
                position = script.length();
            } else {
                current.append(' ');
                position = endOfLine + 1;
            }
        }

        /** Doubled quotes escape a quote inside a literal; a single one opens or closes it. */
        private void quote() {
            if (inString && charAt(position + 1) == '\'') {
                current.append("''");
                position += 2;
            } else {
                inString = !inString;
                current.append('\'');
                position++;
            }
        }

        private char charAt(int index) {
            return index < script.length() ? script.charAt(index) : '\0';
        }

        private void flush() {
            String statement = current.toString().trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
            current.setLength(0);
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
