package dev.sylvain.planning.service;

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
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
     * How many <b>automatic</b> snapshots (the one taken before each solve) are
     * kept per edition. Hand-made ones are never purged: the user asked for
     * them. Five covers an afternoon of trial and error without letting the
     * JSONB column grow without bound — a 3 500-seat plan is roughly 1 MB.
     */
    @ConfigProperty(name = "planning.snapshots.automatiques-conservees", defaultValue = "5")
    int automatiquesConservees;

    /** Label of an automatic snapshot, in the server's zone — it names a moment for a human. */
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
    PlanningService planningService;

    @Inject
    PlanningKpiService kpiService;

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
            String heureFinEffective) {
    }

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
     */
    public record SnapshotMeta(
            long id,
            String libelle,
            boolean automatique,
            String score,
            int nombreAffectations,
            Instant creeLe,
            String editionId,
            String editionNom,
            PlanningKpiService.PlanningKpi kpi) {
    }

    /** A snapshot with its content. */
    public record SnapshotDetail(SnapshotMeta meta, List<AffectationSnapshot> affectations) {
    }

    /**
     * Outcome of a restore attempt. {@code restaure} false means nothing was
     * written: the plan on screen is still the one that was there.
     *
     * @param referencesManquantes ids the snapshot names and the referential no
     *                             longer holds, prefixed by their kind
     *                             ({@code stand:…}, {@code creneau:…},
     *                             {@code animateur:…})
     */
    public record RestaurationResult(boolean restaure, int affectations, List<String> referencesManquantes) {

        static RestaurationResult ok(int affectations) {
            return new RestaurationResult(true, affectations, List.of());
        }

        static RestaurationResult referencesPerdues(List<String> manquantes) {
            return new RestaurationResult(false, 0, manquantes);
        }
    }

    /**
     * Captures the currently persisted plan. Returns {@code null} when there is
     * nothing to capture (no seat stored yet), so the automatic capture before
     * the very first solve is a no-op rather than an empty snapshot.
     */
    public SnapshotMeta capturer(String libelle, boolean automatique) {
        List<AffectationSnapshot> affectations = lireAffectationsPersistees();
        if (affectations.isEmpty()) {
            return null;
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        String score = analysis == null ? null : analysis.diagnostic().score();
        return inserer(libelle, automatique, score, affectations);
    }

    private SnapshotMeta inserer(String libelle, boolean automatique, String score,
            List<AffectationSnapshot> affectations) {
        String contenu = ecrireContenu(affectations);
        PlanningKpiService.PlanningKpi kpi = kpiCourant();
        String sql = "INSERT INTO plan_snapshot "
                + "(edition_id, libelle, automatique, score, nombre_affectations, "
                + "cree_le, contenu, kpi) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) "
                + "RETURNING id, cree_le";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, libelle);
            ps.setBoolean(3, automatique);
            ps.setString(4, score);
            ps.setInt(5, affectations.size());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setString(7, contenu);
            ps.setString(8, kpi == null ? null : ecrireKpi(kpi));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                SnapshotMeta meta = new SnapshotMeta(rs.getLong("id"), libelle, automatique,
                        score, affectations.size(), rs.getTimestamp("cree_le").toInstant(),
                        editionId(), nomEdition(connection, editionId()), kpi);
                if (automatique) {
                    purgerAutomatiques(connection);
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
     */
    public void capturerAvantSolve() {
        try {
            capturer("Avant solve du " + LIBELLE_AUTO_FORMAT.format(ZonedDateTime.now()), true);
        } catch (RuntimeException e) {
            // Deliberately swallowed: see javadoc.
        }
    }

    /** Columns every read below projects, so {@link #lireMeta} always finds them. */
    private static final String COLONNES_META = "s.id, s.libelle, s.automatique, s.score, "
            + "s.nombre_affectations, s.cree_le, s.edition_id, e.nom AS edition_nom";

    private static final String DEPUIS_SNAPSHOT = " FROM plan_snapshot s "
            + "LEFT JOIN edition e ON e.id = s.edition_id";

    public List<SnapshotMeta> lister() {
        String sql = "SELECT " + COLONNES_META + ", s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.edition_id = ? ORDER BY s.cree_le DESC, s.id DESC";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            return lireMetas(ps);
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
    public List<SnapshotMeta> listerToutesEditions() {
        String sql = "SELECT " + COLONNES_META + ", s.kpi" + DEPUIS_SNAPSHOT
                + " ORDER BY s.cree_le DESC, s.id DESC";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            return lireMetas(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list plan snapshots of every edition", e);
        }
    }

    /** {@code null} when no snapshot of this edition carries that id. */
    public SnapshotDetail charger(long id) {
        String sql = "SELECT " + COLONNES_META + ", s.contenu, s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.edition_id = ? AND s.id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setLong(2, id);
            return lireDetail(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plan snapshot " + id, e);
        }
    }

    /**
     * Same as {@link #charger(long)} but across editions, for the comparator —
     * see {@link #listerToutesEditions()} for why. Snapshot ids are unique
     * server-wide (a single {@code BIGSERIAL}), so an id identifies one
     * snapshot without its edition having to be named.
     */
    public SnapshotDetail chargerToutesEditions(long id) {
        String sql = "SELECT " + COLONNES_META + ", s.contenu, s.kpi" + DEPUIS_SNAPSHOT
                + " WHERE s.id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            return lireDetail(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plan snapshot " + id, e);
        }
    }

    private List<SnapshotMeta> lireMetas(PreparedStatement ps) throws SQLException {
        List<SnapshotMeta> snapshots = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                snapshots.add(lireMeta(rs));
            }
        }
        return snapshots;
    }

    private SnapshotDetail lireDetail(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new SnapshotDetail(lireMeta(rs), lireContenu(rs.getString("contenu")));
        }
    }

    /** @return true when a row was actually deleted. */
    public boolean supprimer(long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "DELETE FROM plan_snapshot WHERE edition_id = ? AND id = ?")) {
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete plan snapshot " + id, e);
        }
    }

    /**
     * Rewrites {@code poste_affectation} and {@code planning_resolution} from a
     * snapshot. Refuses — without writing anything — as soon as one referenced
     * stand, créneau or animateur has disappeared since the capture: a partial
     * restore would silently produce a plan nobody ever computed.
     */
    public RestaurationResult restaurer(long id) {
        SnapshotDetail detail = charger(id);
        if (detail == null) {
            return null;
        }
        List<String> manquantes = referencesManquantes(detail.affectations());
        if (!manquantes.isEmpty()) {
            return RestaurationResult.referencesPerdues(manquantes);
        }
        scope.ecrire("Failed to restore plan snapshot " + id, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM poste_affectation WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            String insert = "INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id, "
                    + "heure_debut_effective, heure_fin_effective) VALUES (?, ?, ?, ?, ?, ?, ?)";
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
            String resolution = "INSERT INTO planning_resolution (edition_id, resolu_le) "
                    + "VALUES (?, ?) ON CONFLICT (edition_id) DO UPDATE SET resolu_le = EXCLUDED.resolu_le";
            try (PreparedStatement ps = scope.prepareScoped(connection, resolution)) {
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
        });
        // The restore rewrote the persisted plan outside of any solve, so the
        // stored constraint analysis now describes a plan that is gone.
        // Cleared first, then re-derived from the restored plan: a failed
        // re-analysis leaves "no analysis yet", never a stale lie.
        // Best-effort like capturerAvantSolve — it must not undo the restore.
        analysisStore.effacer();
        try {
            analysisStore.record(planningService.diagnostiquerPlanPersiste());
        } catch (RuntimeException e) {
            // Deliberately swallowed: see comment above.
        }
        return RestaurationResult.ok(detail.affectations().size());
    }

    /* -------------------------------- Helpers ------------------------------ */

    private List<AffectationSnapshot> lireAffectationsPersistees() {
        String sql = "SELECT id, stand_id, creneau_id, animateur_id, heure_debut_effective, heure_fin_effective "
                + "FROM poste_affectation WHERE edition_id = ? ORDER BY id";
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
                        texte(rs.getObject("heure_debut_effective", LocalTime.class)),
                        texte(rs.getObject("heure_fin_effective", LocalTime.class))));
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
        manquantes.addAll(absents("stand", "stand", "id", stands, false));
        manquantes.addAll(absents("creneau", "creneau", "id", creneaux, true));
        manquantes.addAll(absents("animateur", "animateur", "id", animateurs, false));
        return manquantes;
    }

    /**
     * Ids of {@code table} that do not exist in the current edition, prefixed by
     * {@code kind}. Table and column names come from this class's own call
     * sites, never from user input.
     */
    private List<String> absents(String kind, String table, String colonne, Set<String> ids, boolean numerique) {
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

    /** Drops the oldest automatic snapshots beyond the configured retention. */
    private void purgerAutomatiques(Connection connection) throws SQLException {
        String sql = "DELETE FROM plan_snapshot WHERE edition_id = ? AND automatique AND id NOT IN ("
                + "SELECT id FROM plan_snapshot WHERE edition_id = ? AND automatique "
                + "ORDER BY cree_le DESC, id DESC LIMIT ?)";
        try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, editionId());
            ps.setInt(3, Math.max(1, automatiquesConservees));
            ps.executeUpdate();
        }
    }

    private SnapshotMeta lireMeta(ResultSet rs) throws SQLException {
        Timestamp creeLe = rs.getTimestamp("cree_le");
        return new SnapshotMeta(
                rs.getLong("id"),
                rs.getString("libelle"),
                rs.getBoolean("automatique"),
                rs.getString("score"),
                rs.getInt("nombre_affectations"),
                creeLe == null ? null : creeLe.toInstant(),
                rs.getString("edition_id"),
                rs.getString("edition_nom"),
                lireKpi(rs.getString("kpi")));
    }

    /** Display name of an edition, read on the connection already open. */
    private String nomEdition(Connection connection, String editionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT nom FROM edition WHERE id = ?")) {
            ps.setString(1, editionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("nom") : null;
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
            return kpiService.calculerCourant(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String ecrireKpi(PlanningKpiService.PlanningKpi kpi) {
        try {
            return objectMapper.writeValueAsString(kpi);
        } catch (Exception e) {
            return null;
        }
    }

    /** A KPI payload written by an older format is treated as absent, never as an error. */
    private PlanningKpiService.PlanningKpi lireKpi(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanningKpiService.PlanningKpi.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String ecrireContenu(List<AffectationSnapshot> affectations) {
        try {
            return objectMapper.writeValueAsString(affectations);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise plan snapshot", e);
        }
    }

    private List<AffectationSnapshot> lireContenu(String contenu) {
        try {
            return objectMapper.readValue(contenu, new TypeReference<List<AffectationSnapshot>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read plan snapshot content", e);
        }
    }

    private static String texte(LocalTime heure) {
        return heure == null ? null : heure.toString();
    }

    private static LocalTime heure(String texte) {
        return texte == null ? null : LocalTime.parse(texte);
    }

    private String editionId() {
        return scope.editionId();
    }
}
