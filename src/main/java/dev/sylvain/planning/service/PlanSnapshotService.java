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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.PlanningPersistenceService.PlanningResolution;
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
    EditionContext editionContext;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** One seat of a snapshotted plan, carrying everything needed to put it back. */
    public record AffectationSnapshot(
            String posteId,
            String standId,
            String creneauId,
            String animateurId,
            String heureDebutEffective,
            String heureFinEffective) {
    }

    /** A snapshot without its content: what the management screen lists. */
    public record SnapshotMeta(
            long id,
            String libelle,
            boolean automatique,
            String groupeCreneauId,
            String groupeNom,
            String score,
            int nombreAffectations,
            Instant creeLe) {
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
     * @param groupeDifferent      true when the restore was refused because the
     *                             snapshot belongs to another groupe de
     *                             créneaux than the active one (overridable
     *                             with {@code forcer})
     * @param groupeSnapshotNom    display name of the snapshot's groupe, only
     *                             set with {@code groupeDifferent}
     * @param groupeActifNom       display name of the active groupe, idem
     */
    public record RestaurationResult(boolean restaure, int affectations, List<String> referencesManquantes,
            boolean groupeDifferent, String groupeSnapshotNom, String groupeActifNom) {

        static RestaurationResult ok(int affectations) {
            return new RestaurationResult(true, affectations, List.of(), false, null, null);
        }

        static RestaurationResult referencesPerdues(List<String> manquantes) {
            return new RestaurationResult(false, 0, manquantes, false, null, null);
        }

        static RestaurationResult autreGroupe(String groupeSnapshotNom, String groupeActifNom) {
            return new RestaurationResult(false, 0, List.of(), true, groupeSnapshotNom, groupeActifNom);
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
        PlanningResolution resolution = persistenceService.loadResolution();
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        String score = analysis == null ? null : analysis.diagnostic().score();
        return inserer(libelle, automatique,
                resolution == null ? null : resolution.groupeCreneauId(),
                resolution == null ? null : resolution.groupeCreneauNom(),
                score, affectations);
    }

    /**
     * Captures a solved plan straight from memory, without it ever touching
     * {@code poste_affectation} — how the "résoudre tous les groupes" queue
     * (issue #167) stores the result of a <b>non-active</b> group: the
     * persisted plan, the espace animateur and the exports keep serving the
     * active group's plan while the queue runs. The group is tagged
     * explicitly (unlike {@link #capturer}, which describes whatever plan is
     * persisted); the score comes from the diagnostic computed on this very
     * solution, never from {@link ConstraintAnalysisStore} — the store only
     * tracks the active group's analysis. Unassigned seats are skipped, like
     * everywhere else. Returns {@code null} for a solution with no assigned
     * seat (an empty or unsolvable group must not shadow a real snapshot).
     */
    public SnapshotMeta capturerDepuisSolution(PlanningFestival solved,
            String groupeId, String groupeNom, String score, String libelle) {
        List<AffectationSnapshot> affectations = new ArrayList<>();
        for (PosteAffectation poste : solved.getPostes()) {
            if (poste.getAnimateur() == null) {
                continue;
            }
            affectations.add(new AffectationSnapshot(
                    poste.getId(),
                    poste.getStand().getId(),
                    String.valueOf(poste.getCreneau().getId()),
                    poste.getAnimateur().getId(),
                    texte(poste.getHeureDebutEffective()),
                    texte(poste.getHeureFinEffective())));
        }
        if (affectations.isEmpty()) {
            return null;
        }
        return inserer(libelle, true, groupeId, groupeNom, score, affectations);
    }

    private SnapshotMeta inserer(String libelle, boolean automatique, String groupeId, String groupeNom, String score,
            List<AffectationSnapshot> affectations) {
        String contenu = ecrireContenu(affectations);
        String sql = "INSERT INTO plan_snapshot "
                + "(edition_id, libelle, automatique, groupe_creneau_id, groupe_nom, score, nombre_affectations, "
                + "cree_le, contenu) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id, cree_le";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setString(2, libelle);
            ps.setBoolean(3, automatique);
            ps.setString(4, groupeId);
            ps.setString(5, groupeNom);
            ps.setString(6, score);
            ps.setInt(7, affectations.size());
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.setString(9, contenu);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                SnapshotMeta meta = new SnapshotMeta(rs.getLong("id"), libelle, automatique,
                        groupeId, groupeNom, score, affectations.size(), rs.getTimestamp("cree_le").toInstant());
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

    public List<SnapshotMeta> lister() {
        String sql = "SELECT id, libelle, automatique, groupe_creneau_id, groupe_nom, score, nombre_affectations, "
                + "cree_le FROM plan_snapshot WHERE edition_id = ? ORDER BY cree_le DESC, id DESC";
        List<SnapshotMeta> snapshots = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                snapshots.add(lireMeta(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list plan snapshots", e);
        }
        return snapshots;
    }

    /**
     * The most recent snapshot captured for {@code groupeId}, with its content
     * — what the warm start (issue #86) seeds a solve from, and what the queue
     * of issue #167 keeps per group. {@code null} when the group never had one.
     */
    public SnapshotDetail dernierSnapshotDuGroupe(String groupeId) {
        String sql = "SELECT id, libelle, automatique, groupe_creneau_id, groupe_nom, score, nombre_affectations, "
                + "cree_le, contenu FROM plan_snapshot WHERE edition_id = ? AND groupe_creneau_id = ? "
                + "ORDER BY cree_le DESC, id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setString(2, groupeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SnapshotDetail(lireMeta(rs), lireContenu(rs.getString("contenu")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the latest snapshot of group " + groupeId, e);
        }
    }

    /**
     * A snapshot's assignments regrouped under the positional stand × créneau
     * key of {@link PlanningPersistenceService#cleStandCreneau} — the seed
     * format {@code PlanningService.seedDepuisAffectations} warm-starts from
     * (issue #86). Static and side-effect free, like the seeding it feeds.
     */
    public static Map<String, List<String>> animateursParStandCreneau(SnapshotDetail detail) {
        Map<String, List<String>> parStandCreneau = new LinkedHashMap<>();
        for (AffectationSnapshot affectation : detail.affectations()) {
            if (affectation.animateurId() == null) {
                continue;
            }
            parStandCreneau
                    .computeIfAbsent(PlanningPersistenceService.cleStandCreneau(
                            affectation.standId(), Long.parseLong(affectation.creneauId())), key -> new ArrayList<>())
                    .add(affectation.animateurId());
        }
        return parStandCreneau;
    }

    /** {@code null} when no snapshot of this edition carries that id. */
    public SnapshotDetail charger(long id) {
        String sql = "SELECT id, libelle, automatique, groupe_creneau_id, groupe_nom, score, nombre_affectations, "
                + "cree_le, contenu FROM plan_snapshot WHERE edition_id = ? AND id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SnapshotDetail(lireMeta(rs), lireContenu(rs.getString("contenu")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plan snapshot " + id, e);
        }
    }

    /** @return true when a row was actually deleted. */
    public boolean supprimer(long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
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
    public RestaurationResult restaurer(long id, boolean forcer) {
        SnapshotDetail detail = charger(id);
        if (detail == null) {
            return null;
        }
        List<String> manquantes = referencesManquantes(detail.affectations());
        if (!manquantes.isEmpty()) {
            return RestaurationResult.referencesPerdues(manquantes);
        }
        // Restoring a snapshot of ANOTHER groupe de créneaux silently replaces
        // the whole persisted planning with one computed for a different set of
        // créneaux — almost always a mistake, so it is refused unless the
        // caller explicitly forces it (the UI asks a second confirmation).
        GroupeActif groupeActif = groupeActif();
        String groupeSnapshot = detail.meta().groupeCreneauId();
        if (!forcer && groupeSnapshot != null && groupeActif != null
                && !groupeSnapshot.equals(groupeActif.id())) {
            return RestaurationResult.autreGroupe(
                    detail.meta().groupeNom() == null ? groupeSnapshot : detail.meta().groupeNom(),
                    groupeActif.nom() == null ? groupeActif.id() : groupeActif.nom());
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = prepareScoped(connection,
                        "DELETE FROM poste_affectation WHERE edition_id = ?")) {
                    ps.executeUpdate();
                }
                String insert = "INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id, "
                        + "heure_debut_effective, heure_fin_effective) VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = prepareScoped(connection, insert)) {
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
                String resolution = "INSERT INTO planning_resolution (edition_id, groupe_creneau_id, resolu_le) "
                        + "VALUES (?, ?, ?) ON CONFLICT (edition_id) DO UPDATE SET "
                        + "groupe_creneau_id = EXCLUDED.groupe_creneau_id, resolu_le = EXCLUDED.resolu_le";
                try (PreparedStatement ps = prepareScoped(connection, resolution)) {
                    ps.setString(2, detail.meta().groupeCreneauId());
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to restore plan snapshot " + id, e);
        }
        return RestaurationResult.ok(detail.affectations().size());
    }

    /* -------------------------------- Helpers ------------------------------ */

    private record GroupeActif(String id, String nom) {
    }

    /** The active groupe de créneaux, {@code null} when none is flagged. */
    private GroupeActif groupeActif() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT id, nom FROM groupe_creneau WHERE edition_id = ? AND actif");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new GroupeActif(rs.getString("id"), rs.getString("nom")) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the active groupe de créneaux", e);
        }
    }

    private List<AffectationSnapshot> lireAffectationsPersistees() {
        String sql = "SELECT id, stand_id, creneau_id, animateur_id, heure_debut_effective, heure_fin_effective "
                + "FROM poste_affectation WHERE edition_id = ? ORDER BY id";
        List<AffectationSnapshot> affectations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql);
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
                PreparedStatement ps = prepareScoped(connection, sql)) {
            for (String id : ids) {
                if (numerique) {
                    ps.setLong(2, Long.parseLong(id));
                } else {
                    ps.setString(2, id);
                }
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

    /**
     * Drops the oldest automatic snapshots beyond the configured retention —
     * except, since the "résoudre tous les groupes" queue (issue #167), the
     * most recent snapshot of each still-existing groupe de créneaux, whatever
     * its age: that snapshot <i>is</i> the group's pre-solved plan, the whole
     * point of the queue, and a queue over N groups would otherwise evict the
     * very results it just produced. A snapshot whose group was deleted loses
     * that protection and ages out normally.
     */
    private void purgerAutomatiques(Connection connection) throws SQLException {
        String sql = "DELETE FROM plan_snapshot WHERE edition_id = ? AND automatique AND id NOT IN ("
                + "SELECT id FROM plan_snapshot WHERE edition_id = ? AND automatique "
                + "ORDER BY cree_le DESC, id DESC LIMIT ?) AND id NOT IN ("
                + "SELECT DISTINCT ON (s.groupe_creneau_id) s.id FROM plan_snapshot s "
                + "JOIN groupe_creneau g ON g.edition_id = s.edition_id AND g.id = s.groupe_creneau_id "
                + "WHERE s.edition_id = ? "
                + "ORDER BY s.groupe_creneau_id, s.cree_le DESC, s.id DESC)";
        try (PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setString(2, editionId());
            ps.setInt(3, Math.max(1, automatiquesConservees));
            ps.setString(4, editionId());
            ps.executeUpdate();
        }
    }

    private SnapshotMeta lireMeta(ResultSet rs) throws SQLException {
        Timestamp creeLe = rs.getTimestamp("cree_le");
        return new SnapshotMeta(
                rs.getLong("id"),
                rs.getString("libelle"),
                rs.getBoolean("automatique"),
                rs.getString("groupe_creneau_id"),
                rs.getString("groupe_nom"),
                rs.getString("score"),
                rs.getInt("nombre_affectations"),
                creeLe == null ? null : creeLe.toInstant());
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
        return editionContext.editionIdCourant();
    }

    /** Same convention as the other repositories: edition bound to placeholder 1. */
    private PreparedStatement prepareScoped(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            ps.setString(1, editionId());
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }
}
