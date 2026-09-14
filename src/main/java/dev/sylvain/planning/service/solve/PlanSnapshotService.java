package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.analyse.PlanningKpiService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Plan snapshots (issue #138): captures the persisted plan so a later solve
 * cannot destroy it, and puts it back on demand.
 *
 * <p>A snapshot stores the plan <b>denormalised</b> in a JSONB column, never a
 * copy of {@code poste_affectation} rows: those rows are foreign-keyed to
 * {@code creneau} with {@code ON DELETE CASCADE}, so a row copy would die with
 * the créneaux of the abandoned group — exactly the case snapshots exist
 * for.</p>
 *
 * <p>Restoring is therefore a re-resolution against today's referential, and it
 * can legitimately fail: {@link #restaurer} reports the ids that no longer
 * exist rather than silently dropping the seats naming them.</p>
 */
@ApplicationScoped
public class PlanSnapshotService {

    /**
     * How many <b>automatique</b> snapshots (the one taken before each solve) are
     * kept per edition. Hand-made ones are never purged: the user asked for
     * them. Five covers an afternoon of trial and error without letting the
     * JSONB column grow without bound — a 3 500-seat plan is roughly 1 MB.
     */
    @ConfigProperty(name = "planning.snapshots.automatiques-conservees", defaultValue = "5")
    int automatiquesConservees;

    /** Label of an automatique snapshot, in the server's zone — it names a moment for a human. */
    private static final DateTimeFormatter LIBELLE_AUTO_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    PlanningKpiService kpiService;

    @Inject
    SolverJobService solverJobs;

    /**
     * The CDI-managed mapper, not a bare {@code new ObjectMapper()}: it carries
     * the modules Quarkus registers (JSR-310 in particular), so a field of a
     * persisted record may be a {@code LocalDate}/{@code Instant} instead of
     * having to be flattened to a {@code String} to keep a bare mapper happy.
     */
    @Inject
    ObjectMapper objectMapper;

    /** One seat of a snapshotted plan, carrying everything needed to put it back. */
    public record AffectationSnapshot(
            String posteId,
            String standId,
            String creneauId,
            String animateurId,
            String heureDebutEffective,
            String heureFinEffective) {}

    /**
     * A snapshot without its content: what the management screen lists.
     *
     * @param editionId  edition the snapshot was captured in — carried because
     *                   the A/B comparator (issue #70) reads across editions,
     *                   the edition being the variant carrier since #172
     * @param editionNom display name of that edition, {@code null} once the
     *                   edition itself is gone (the row cascades with it, so
     *                   this only happens mid-deletion)
     * @param kpi        the plan's KPI at capture time (issue #70),
     *                   {@code null} on snapshots captured before they were
     *                   stored — the comparator then recomputes what it can
     * @param publieLe   moment this snapshot was communicated to the animateurs
     *                   (issue #245), {@code null} on a working snapshot —
     *                   which every snapshot is until someone publishes one
     * @param referenceModifieLe last mutation of <b>that snapshot's</b> edition
     *                   referential ({@code edition.reference_modifie_le},
     *                   issue #170), {@code null} when none is known. Read per
     *                   snapshot rather than for the current edition: the
     *                   comparator lists snapshots across editions
     * @param perime     true when the referential moved after the capture, so
     *                   the plan no longer describes today's data. Derived
     *                   here rather than in each caller — the screen, the MCP
     *                   view and the restore guard must not disagree on what
     *                   "périmé" means
     */
    @Schema(requiredProperties = {"automatique", "id", "nombreAffectations", "perime"})
    public record SnapshotMeta(
            long id,
            String libelle,
            boolean automatique,
            String score,
            int nombreAffectations,
            Instant creeLe,
            String editionId,
            String editionNom,
            PlanningKpiService.PlanningKpi kpi,
            Instant publieLe,
            Instant referenceModifieLe,
            boolean perime) {

        /**
         * A snapshot is stale as soon as the referential was written after it
         * was taken. Both dates missing means nothing can be said, and nothing
         * is: an unknown freshness is announced as fresh nowhere.
         */
        static boolean perime(Instant creeLe, Instant referenceModifieLe) {
            return creeLe != null && referenceModifieLe != null && referenceModifieLe.isAfter(creeLe);
        }
    }

    /** A snapshot with its content. */
    public record SnapshotDetail(SnapshotMeta meta, List<AffectationSnapshot> affectations) {}

    /**
     * Outcome of a restore attempt. {@code restaure} false means nothing was
     * written: the plan on screen is still the one that was there.
     *
     * @param referencesManquantes ids the snapshot names and the referential no
     *                             longer holds, prefixed by their kind
     *                             ({@code stand:…}, {@code creneau:…},
     *                             {@code animateur:…})
     * @param perime               true when the refusal is the staleness guard
     *                             of issue #170 — unlike the missing
     *                             references, this one is overridable: the plan
     *                             is restorable, it just no longer describes
     *                             today's referential
     * @param creeLe               capture time of the refused snapshot,
     *                             {@code null} outside a staleness refusal
     * @param referenceModifieLe   mutation that made it stale, {@code null}
     *                             outside a staleness refusal — the two dates
     *                             together are what lets the caller word
     *                             "modifié après la capture" rather than a
     *                             bare refusal
     */
    public record RestaurationResult(
            boolean restaure,
            int affectations,
            List<String> referencesManquantes,
            boolean perime,
            Instant creeLe,
            Instant referenceModifieLe) {

        static RestaurationResult ok(int affectations) {
            return new RestaurationResult(true, affectations, List.of(), false, null, null);
        }

        static RestaurationResult referencesPerdues(List<String> manquantes) {
            return new RestaurationResult(false, 0, manquantes, false, null, null);
        }

        static RestaurationResult perime(Instant creeLe, Instant referenceModifieLe) {
            return new RestaurationResult(false, 0, List.of(), true, creeLe, referenceModifieLe);
        }
    }

    /**
     * Captures the currently persisted plan. Returns {@code null} when there is
     * nothing to capture (no seat stored yet), so the automatique capture before
     * the very first solve is a no-op rather than an empty snapshot.
     */
    public SnapshotMeta capture(String libelle, boolean automatique) {
        List<AffectationSnapshot> affectations = readPersistedAffectations();
        if (affectations.isEmpty()) {
            return null;
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        String score = analysis == null ? null : analysis.diagnostic().score();
        return inserer(libelle, automatique, score, affectations, null);
    }

    /**
     * Captures the persisted plan <b>as published</b>: this is what the
     * animateurs have been sent, and from now on what their espace shows
     * (issue #245). Like {@link #capture}, returns {@code null} when there is
     * nothing to capture — publishing an empty plan would only tell people
     * they have no seat.
     *
     * <p>Never automatique: a published snapshot is the reference the espace
     * reads from, so it must not be in reach of the retention purge.</p>
     */
    public SnapshotMeta capturePubliee(String libelle) {
        List<AffectationSnapshot> affectations = readPersistedAffectations();
        if (affectations.isEmpty()) {
            return null;
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        String score = analysis == null ? null : analysis.diagnostic().score();
        return inserer(libelle, false, score, affectations, Instant.now());
    }

    private SnapshotMeta inserer(
            String libelle,
            boolean automatique,
            String score,
            List<AffectationSnapshot> affectations,
            Instant publieLe) {
        String contenu = writeContent(affectations);
        PlanningKpiService.PlanningKpi kpi = kpiCourant();
        String sql = """
 INSERT INTO plan_snapshot (edition_id, libelle, automatique, score, nombre_affectations, cree_le, contenu, kpi,
 publie_le)
 VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
 RETURNING id, cree_le""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, libelle);
            ps.setBoolean(3, automatique);
            ps.setString(4, score);
            ps.setInt(5, affectations.size());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setString(7, contenu);
            ps.setString(8, kpi == null ? null : writeKpi(kpi));
            ps.setTimestamp(9, publieLe == null ? null : Timestamp.from(publieLe));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Instant creeLe = rs.getTimestamp("cree_le").toInstant();
                EditionMeta edition = editionMeta(connection, editionId());
                SnapshotMeta meta = new SnapshotMeta(
                        rs.getLong("id"),
                        libelle,
                        automatique,
                        score,
                        affectations.size(),
                        creeLe,
                        editionId(),
                        edition.nom(),
                        kpi,
                        publieLe,
                        edition.referenceModifieLe(),
                        SnapshotMeta.perime(creeLe, edition.referenceModifieLe()));
                if (automatique) {
                    purgeAutomatic(connection);
                }
                return meta;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to capture plan snapshot", e);
        }
    }

    /**
     * The safety net: taken right before a solve overwrites the persisted plan,
     * labelled with the moment it was taken. Never fails the solve — a snapshot
     * that could not be written must not cost the user their run.
     *
     * <p>Returns what it captured so the caller can tell the user what the
     * solve replaced (issue #274), or {@code null} when there was nothing to
     * capture (first solve of an edition) or the capture failed. A caller must
     * therefore treat {@code null} as "no comparison to offer", never as an
     * error.</p>
     */
    public SnapshotMeta captureBeforeSolve() {
        try {
            return capture("Avant solve du " + LIBELLE_AUTO_FORMAT.format(ZonedDateTime.now()), true);
        } catch (RuntimeException e) {
            // Deliberately swallowed: see javadoc.
            return null;
        }
    }

    /**
     * Fills in the score of a snapshot captured without one (issue #274).
     *
     * <p>{@link #capture} reads it from the in-memory
     * {@link ConstraintAnalysisStore}, which is empty after a restart. When the
     * caller establishes it afterwards — by analysing the plan the snapshot
     * holds — writing it back keeps the snapshots screen consistent with what
     * the solve recap announced. Only ever fills a hole: an existing score is
     * the one recorded at capture time and stays.</p>
     */
    public void recordScore(long id, String score) {
        // Not prepareScoped: the SET clause claims placeholder 1, so the
        // edition_id predicate is bound explicitly.
        String sql = "UPDATE plan_snapshot SET score = ? WHERE edition_id = ? AND id = ? AND score IS NULL";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, score);
            ps.setString(2, editionId());
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record snapshot score", e);
        }
    }

    /** Columns every read below projects, so {@link #readMeta} always finds them. */
    private static final String COLONNES_META = "s.id, s.libelle, s.automatique, s.score, "
            + "s.nombre_affectations, s.cree_le, s.edition_id, s.publie_le, e.nom AS edition_nom, "
            + "e.reference_modifie_le";

    private static final String DEPUIS_SNAPSHOT =
            " FROM plan_snapshot s " + "LEFT JOIN edition e ON e.id = s.edition_id";

    public List<SnapshotMeta> list() {
        String sql = "SELECT " + COLONNES_META + ", s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.edition_id = ? ORDER BY s.cree_le DESC, s.id DESC";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            return readMetas(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list plan snapshots", e);
        }
    }

    /**
     * Every edition's snapshots, newest first — the one read that deliberately
     * ignores the edition scope. The A/B comparator (issue #70) exists to
     * confront a baseline with a variant, and since #172 a variant <b>is</b>
     * another edition: scoping this listing would hide exactly the pair the
     * user wants to compare. Nothing can be restored through it: restoring
     * stays edition-scoped ({@link #restaurer}).
     */
    public List<SnapshotMeta> listAllEditions() {
        String sql = "SELECT " + COLONNES_META + ", s.kpi" + DEPUIS_SNAPSHOT + " ORDER BY s.cree_le DESC, s.id DESC";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            return readMetas(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list plan snapshots of every edition", e);
        }
    }

    /** {@code null} when no snapshot of this edition carries that id. */
    public SnapshotDetail load(long id) {
        String sql = "SELECT " + COLONNES_META + ", s.contenu, s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.edition_id = ? AND s.id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setLong(2, id);
            return readDetail(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plan snapshot " + id, e);
        }
    }

    /**
     * Same as {@link #load(long)} but across editions, for the comparator —
     * see {@link #listAllEditions()} for why. Snapshot ids are unique
     * server-wide (a single {@code BIGSERIAL}), so an id identifies one
     * snapshot without its edition having to be named.
     */
    public SnapshotDetail loadAllEditions(long id) {
        String sql = "SELECT " + COLONNES_META + ", s.contenu, s.kpi" + DEPUIS_SNAPSHOT + " WHERE s.id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            return readDetail(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plan snapshot " + id, e);
        }
    }

    private List<SnapshotMeta> readMetas(PreparedStatement ps) throws SQLException {
        List<SnapshotMeta> snapshots = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                snapshots.add(readMeta(rs));
            }
        }
        return snapshots;
    }

    private SnapshotDetail readDetail(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new SnapshotDetail(readMeta(rs), readContent(rs.getString("contenu")));
        }
    }

    /**
     * @return true when a row was actually deleted.
     * @throws BusinessError.Conflict on a published snapshot: it is the plan
     *         the animateurs were sent and the one their espace reads
     *         (issue #245), so deleting it would take back what was said
     *         without telling anyone
     */
    public boolean delete(long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(
                        connection,
                        "DELETE FROM plan_snapshot WHERE edition_id = ? AND id = ? AND publie_le IS NULL")) {
            ps.setLong(2, id);
            if (ps.executeUpdate() > 0) {
                return true;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete plan snapshot " + id, e);
        }
        SnapshotMeta reste = meta(id);
        if (reste != null && reste.publieLe() != null) {
            throw new BusinessError.Conflict("Cet instantané est le plan publié : il ne peut pas être supprimé.");
        }
        return false;
    }

    /**
     * The edition's most recent published snapshot — what the animateurs have
     * been sent (issue #245). {@code null} when nothing has ever been
     * published, which is the state of every edition until an admin publishes
     * once.
     */
    public SnapshotMeta lastPublication() {
        String sql = "SELECT " + COLONNES_META + ", s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.edition_id = ? AND s.publie_le IS NOT NULL"
                + " ORDER BY s.publie_le DESC, s.id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            List<SnapshotMeta> metas = readMetas(ps);
            return metas.isEmpty() ? null : metas.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the last published plan snapshot", e);
        }
    }

    /** Same as {@link #lastPublication()}, content included. */
    public SnapshotDetail loadLastPublication() {
        String sql = "SELECT " + COLONNES_META + ", s.contenu, s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.edition_id = ? AND s.publie_le IS NOT NULL"
                + " ORDER BY s.publie_le DESC, s.id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            return readDetail(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the last published plan snapshot", e);
        }
    }

    /** Meta of one snapshot of this edition, content excluded; {@code null} when unknown. */
    private SnapshotMeta meta(long id) {
        String sql = "SELECT " + COLONNES_META + ", s.kpi" + DEPUIS_SNAPSHOT + " WHERE s.edition_id = ? AND s.id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setLong(2, id);
            List<SnapshotMeta> metas = readMetas(ps);
            return metas.isEmpty() ? null : metas.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read plan snapshot " + id, e);
        }
    }

    /**
     * Rewrites {@code poste_affectation} and {@code planning_resolution} from a
     * snapshot. Refuses — without writing anything — as soon as one referenced
     * stand, créneau or animateur has disappeared since the capture: a partial
     * restore would silently produce a plan nobody ever computed.
     *
     * <p>Refused while a solve holds this edition's solver (issue #313): the
     * solve persists its own result when it lands, which would overwrite the
     * plan just put back — silently, and with a constraint analysis describing
     * yet another plan. The Instantanés screen disables its button meanwhile,
     * but that guard covers only the operator in front of it; the REST call and
     * the MCP tool both come through here, so this is the guard that covers
     * everyone. See {@link SolverJobService#refuseIfSolving}.</p>
     *
     * <p>Refused too — and this one {@code forcer} overrides — when the
     * referential moved after the capture (issue #170). Nothing is broken
     * there: every id the snapshot names still exists, the plan simply predates
     * a change and putting it back would quietly undo it. So the refusal is a
     * question, not a wall; it lives here rather than in the screen for the
     * same reason as the one above.</p>
     *
     * @param forcer restore a stale snapshot anyway. Never widens anything
     *               else: a missing reference stays refused whatever this says
     */
    public RestaurationResult restaurer(long id, boolean forcer) {
        solverJobs.refuseIfSolving();
        SnapshotDetail detail = load(id);
        if (detail == null) {
            return null;
        }
        List<String> manquantes = referencesManquantes(detail.affectations());
        if (!manquantes.isEmpty()) {
            // Checked before staleness on purpose: a snapshot is often stale
            // *because* ids disappeared, and naming them is the actionable
            // message. Forcing past staleness still lands here.
            return RestaurationResult.referencesPerdues(manquantes);
        }
        if (!forcer && detail.meta().perime()) {
            return RestaurationResult.perime(
                    detail.meta().creeLe(), detail.meta().referenceModifieLe());
        }
        scope.write("Failed to restore plan snapshot " + id, connection -> {
            try (PreparedStatement ps =
                    scope.prepareScoped(connection, "DELETE FROM poste_affectation WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            String insert = """
 INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id,
 heure_debut_effective, heure_fin_effective)
 VALUES (?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = scope.prepareScoped(connection, insert)) {
                for (AffectationSnapshot affectation : detail.affectations()) {
                    ps.setString(2, affectation.posteId());
                    ps.setString(3, affectation.standId());
                    ps.setLong(4, Long.parseLong(affectation.creneauId()));
                    ps.setString(5, affectation.animateurId());
                    ps.setObject(6, heure(affectation.heureDebutEffective()));
                    ps.setObject(7, heure(affectation.heureFinEffective()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            String resolution = """
 INSERT INTO planning_resolution (edition_id, resolu_le)
 VALUES (?, ?)
 ON CONFLICT (edition_id)
 DO UPDATE SET resolu_le = EXCLUDED.resolu_le""";
            try (PreparedStatement ps = scope.prepareScoped(connection, resolution)) {
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        });
        // The restore rewrote the persisted plan outside of any solve, so the
        // stored constraint analysis now describes a plan that is gone.
        // Best-effort like captureBeforeSolve — it must not undo the restore.
        try {
            analysisStore.refreshFromPersistedPlan();
        } catch (RuntimeException e) {
            // Deliberately swallowed: a missing analysis is an empty screen,
            // the restore itself succeeded.
        }
        return RestaurationResult.ok(detail.affectations().size());
    }

    /* -------------------------------- Helpers ------------------------------ */

    private List<AffectationSnapshot> readPersistedAffectations() {
        String sql = """
 SELECT id, stand_id, creneau_id, animateur_id, heure_debut_effective, heure_fin_effective
 FROM poste_affectation
 WHERE edition_id = ?
 ORDER BY id""";
        List<AffectationSnapshot> affectations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                affectations.add(new AffectationSnapshot(
                        rs.getString("id"),
                        rs.getString("stand_id"),
                        String.valueOf(rs.getLong("creneau_id")),
                        rs.getString("animateur_id"),
                        text(rs.getObject("heure_debut_effective", LocalTime.class)),
                        text(rs.getObject("heure_fin_effective", LocalTime.class))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the persisted plan", e);
        }
        return affectations;
    }

    /** Ids the snapshot names that the referential no longer holds. */
    private List<String> referencesManquantes(List<AffectationSnapshot> affectations) {
        Set<String> stands = new LinkedHashSet<>();
        Set<String> creneaux = new LinkedHashSet<>();
        Set<String> animateurs = new LinkedHashSet<>();
        for (AffectationSnapshot affectation : affectations) {
            stands.add(affectation.standId());
            creneaux.add(affectation.creneauId());
            if (affectation.animateurId() != null) {
                animateurs.add(affectation.animateurId());
            }
        }
        List<String> manquantes = new ArrayList<>();
        manquantes.addAll(missing("stand", "stand", "id", stands, false));
        manquantes.addAll(missing("creneau", "creneau", "id", creneaux, true));
        manquantes.addAll(missing("animateur", "animateur", "id", animateurs, false));
        return manquantes;
    }

    /**
     * Ids of {@code table} that do not exist in the current edition, prefixed by
     * {@code kind}. Table and column names come from this class's own call
     * sites, never from user input.
     */
    private List<String> missing(String kind, String table, String colonne, Set<String> ids, boolean numerique) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> manquants = new ArrayList<>();
        String sql = "SELECT 1 FROM " + table + " WHERE edition_id = ? AND " + colonne + " = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            for (String id : ids) {
                if (numerique) {
                    ps.setLong(2, Long.parseLong(id));
                } else {
                    ps.setString(2, id);
                }
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        manquants.add(kind + ":" + id);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check snapshot references against " + table, e);
        }
        return manquants;
    }

    /** Drops the oldest automatique snapshots beyond the configured retention. */
    private void purgeAutomatic(Connection connection) throws SQLException {
        String sql = """
 DELETE FROM plan_snapshot
 WHERE edition_id = ?
 AND automatique
 AND publie_le IS NULL
 AND id NOT IN (SELECT id FROM plan_snapshot WHERE edition_id = ?
 AND automatique ORDER BY cree_le DESC, id DESC LIMIT ?)""";
        try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, editionId());
            ps.setInt(3, Math.max(1, automatiquesConservees));
            ps.executeUpdate();
        }
    }

    private SnapshotMeta readMeta(ResultSet rs) throws SQLException {
        Instant creeLe = instant(rs.getTimestamp("cree_le"));
        Instant referenceModifieLe = instant(rs.getTimestamp("reference_modifie_le"));
        return new SnapshotMeta(
                rs.getLong("id"),
                rs.getString("libelle"),
                rs.getBoolean("automatique"),
                rs.getString("score"),
                rs.getInt("nombre_affectations"),
                creeLe,
                rs.getString("edition_id"),
                rs.getString("edition_nom"),
                readKpi(rs.getString("kpi")),
                instant(rs.getTimestamp("publie_le")),
                referenceModifieLe,
                SnapshotMeta.perime(creeLe, referenceModifieLe));
    }

    /**
     * What a freshly inserted snapshot needs from its edition: the display name
     * and the last referential mutation the capture is judged against. Read on
     * the connection already open, and in one statement — the listings get both
     * from their {@code LEFT JOIN edition}, so the insert path must not answer
     * differently.
     */
    private record EditionMeta(String nom, Instant referenceModifieLe) {}

    private EditionMeta editionMeta(Connection connection, String editionId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT nom, reference_modifie_le FROM edition WHERE id = ?")) {
            ps.setString(1, editionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new EditionMeta(rs.getString("nom"), instant(rs.getTimestamp("reference_modifie_le")))
                        : new EditionMeta(null, null);
            }
        }
    }

    /**
     * KPI of the plan being captured, or {@code null} when they cannot be
     * computed: a capture must never fail because a metric did — the plan is
     * what the user asked to preserve, the KPI are a bonus the comparator
     * knows how to do without (degraded mode, issue #70).
     */
    private PlanningKpiService.PlanningKpi kpiCourant() {
        try {
            return kpiService.computeCurrent(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String writeKpi(PlanningKpiService.PlanningKpi kpi) {
        try {
            return objectMapper.writeValueAsString(kpi);
        } catch (Exception e) {
            return null;
        }
    }

    /** A KPI payload written by an older format is treated as absent, never as an error. */
    private PlanningKpiService.PlanningKpi readKpi(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanningKpiService.PlanningKpi.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeContent(List<AffectationSnapshot> affectations) {
        try {
            return objectMapper.writeValueAsString(affectations);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise plan snapshot", e);
        }
    }

    private List<AffectationSnapshot> readContent(String contenu) {
        try {
            return objectMapper.readValue(contenu, new TypeReference<List<AffectationSnapshot>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read plan snapshot content", e);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String text(LocalTime heure) {
        return heure == null ? null : heure.toString();
    }

    private static LocalTime heure(String text) {
        return text == null ? null : LocalTime.parse(text);
    }

    private String editionId() {
        return scope.editionId();
    }
}
