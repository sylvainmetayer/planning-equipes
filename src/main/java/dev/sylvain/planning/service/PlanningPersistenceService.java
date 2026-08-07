package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Persists a solved {@link PlanningFestival} into PostgreSQL using plain JDBC
 * (the project only ships {@code quarkus-jdbc-postgresql}, no ORM). Reference
 * rows (stands, timeslots, animators) are up-serted first so the
 * {@code poste_affectation} foreign keys are satisfied, then the assignment
 * rows are fully rewritten to mirror the latest solution.
 */
@ApplicationScoped
public class PlanningPersistenceService {

    @Inject
    DataSource dataSource;

    @Inject
    ReferenceDataService referenceDataService;

    /**
     * Writes the whole solution to the database in a single transaction and
     * returns how many assignment rows were stored.
     */
    public int persist(PlanningFestival planning) {
        if (planning == null || planning.getPostes() == null) {
            return 0;
        }
        return inTransaction(connection -> {
            upsertReferenceData(connection, planning);
            int count = rewriteAssignments(connection, planning.getPostes());
            recordResolution(connection, planning);
            return count;
        }, "Failed to persist planning solution");
    }

    /**
     * Empties the database: wipes every planning table (stands, timeslots,
     * animators, assignments and constraints) without loading any scenario.
     * Typologies are kept: they are seeded by the Flyway migrations, not by a
     * scenario. Used by the "Reset BDD" admin action to start from scratch.
     */
    public void clearDatabase() {
        inTransaction(connection -> {
            clearPlanningTables(connection);
            return 0;
        }, "Failed to clear the database");
    }

    private void clearPlanningTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE TABLE poste_affectation, planning_resolution, contrainte_animateur, "
                    + "contrainte_ad_hoc, stand_typologie, animateur_competence, animateur_jour_indispo, "
                    + "stand, creneau, animateur "
                    + "CASCADE");
        }
    }

    private int inTransaction(TransactionalWork work, String errorMessage) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int rows = work.execute(connection);
                connection.commit();
                return rows;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    @FunctionalInterface
    private interface TransactionalWork {
        int execute(Connection connection) throws SQLException;
    }

    private void upsertReferenceData(Connection connection, PlanningFestival planning) throws SQLException {
        List<Stand> stands = new ArrayList<>();
        List<Creneau> creneaux = new ArrayList<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getStand() != null) {
                stands.add(poste.getStand());
            }
            if (poste.getCreneau() != null) {
                creneaux.add(poste.getCreneau());
            }
        }

        String upsertStand = "INSERT INTO stand (id, nom, effectif_min, effectif_max, reserve_majeurs) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min, "
                + "effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs";
        List<Stand> distinctStands = dedupById(stands, Stand::getId);
        try (PreparedStatement ps = connection.prepareStatement(upsertStand)) {
            for (Stand stand : distinctStands) {
                ps.setString(1, stand.getId());
                ps.setString(2, stand.getNom());
                ps.setInt(3, stand.getEffectifMin());
                ps.setInt(4, stand.getEffectifMax());
                ps.setBoolean(5, stand.isReserveMajeurs());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        rewriteStandTypologies(connection, distinctStands);
        rewriteStandIndisponibilites(connection, distinctStands);

        String upsertCreneau = "INSERT INTO creneau (id, date_creneau, heure_debut, heure_fin) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "date_creneau = EXCLUDED.date_creneau, "
                + "heure_debut = EXCLUDED.heure_debut, heure_fin = EXCLUDED.heure_fin";
        try (PreparedStatement ps = connection.prepareStatement(upsertCreneau)) {
            for (Creneau creneau : dedupById(creneaux, Creneau::getId)) {
                ps.setLong(1, creneau.getId());
                ps.setObject(2, creneau.getDate());
                ps.setObject(3, creneau.getHeureDebut());
                ps.setObject(4, creneau.getHeureFin());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String upsertAnimateur = "INSERT INTO animateur (id, prenom, nom, date_naissance, manager) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, "
                + "date_naissance = EXCLUDED.date_naissance, manager = EXCLUDED.manager";
        try (PreparedStatement ps = connection.prepareStatement(upsertAnimateur)) {
            List<Animateur> animateurs = planning.getAnimateurs() != null
                    ? planning.getAnimateurs()
                    : List.of();
            List<Animateur> distinctAnimateurs = dedupById(animateurs, Animateur::getId);
            for (Animateur animateur : distinctAnimateurs) {
                ps.setString(1, animateur.getId());
                ps.setString(2, animateur.getPrenom());
                ps.setString(3, animateur.getNom());
                ps.setObject(4, animateur.getDateNaissance());
                ps.setBoolean(5, animateur.isManager());
                ps.addBatch();
            }
            ps.executeBatch();
            rewriteAnimateurDetails(connection, distinctAnimateurs);
        }
    }

    private void rewriteStandTypologies(Connection connection, List<Stand> stands) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM stand_typologie WHERE stand_id = ?");
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO stand_typologie (stand_id, typologie) VALUES (?, ?)")) {
            for (Stand stand : stands) {
                delete.setString(1, stand.getId());
                delete.addBatch();
                if (stand.getTypologiesProposees() != null) {
                    for (TypologieJeu typologie : stand.getTypologiesProposees()) {
                        insert.setString(1, stand.getId());
                        insert.setString(2, typologie.name());
                        insert.addBatch();
                    }
                }
            }
            delete.executeBatch();
            insert.executeBatch();
        }
    }

    private void rewriteStandIndisponibilites(Connection connection, List<Stand> stands) throws SQLException {
        try (PreparedStatement delete = connection
                        .prepareStatement("DELETE FROM stand_indisponibilite WHERE stand_id = ?");
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO stand_indisponibilite (stand_id, date_indisponibilite, heure_debut, heure_fin, motif) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
            for (Stand stand : stands) {
                delete.setString(1, stand.getId());
                delete.addBatch();
                if (stand.getIndisponibilites() != null) {
                    for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
                        insert.setString(1, stand.getId());
                        insert.setObject(2, indispo.getDate());
                        insert.setObject(3, indispo.getHeureDebut());
                        insert.setObject(4, indispo.getHeureFin());
                        insert.setString(5, indispo.getMotif());
                        insert.addBatch();
                    }
                }
            }
            delete.executeBatch();
            insert.executeBatch();
        }
    }

    private void rewriteAnimateurDetails(Connection connection, List<Animateur> animateurs) throws SQLException {
        try (PreparedStatement deleteComp = connection
                        .prepareStatement("DELETE FROM animateur_competence WHERE animateur_id = ?");
                PreparedStatement insertComp = connection.prepareStatement(
                        "INSERT INTO animateur_competence (animateur_id, typologie, niveau) VALUES (?, ?, ?)");
                PreparedStatement deleteJour = connection
                        .prepareStatement("DELETE FROM animateur_jour_indispo WHERE animateur_id = ?");
                PreparedStatement insertJour = connection.prepareStatement(
                        "INSERT INTO animateur_jour_indispo (animateur_id, jour) VALUES (?, ?)")) {
            for (Animateur animateur : animateurs) {
                deleteComp.setString(1, animateur.getId());
                deleteComp.addBatch();
                deleteJour.setString(1, animateur.getId());
                deleteJour.addBatch();
                if (animateur.getCompetences() != null) {
                    for (Map.Entry<TypologieJeu, NiveauCompetence> entry : animateur.getCompetences().entrySet()) {
                        insertComp.setString(1, animateur.getId());
                        insertComp.setString(2, entry.getKey().name());
                        insertComp.setString(3, entry.getValue().name());
                        insertComp.addBatch();
                    }
                }
                if (animateur.getJoursIndisponibles() != null) {
                    for (LocalDate jour : animateur.getJoursIndisponibles()) {
                        insertJour.setString(1, animateur.getId());
                        insertJour.setObject(2, jour);
                        insertJour.addBatch();
                    }
                }
            }
            deleteComp.executeBatch();
            deleteJour.executeBatch();
            insertComp.executeBatch();
            insertJour.executeBatch();
        }
    }

    private int rewriteAssignments(Connection connection, List<PosteAffectation> postes) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM poste_affectation");
        }

        String insert = "INSERT INTO poste_affectation (id, stand_id, creneau_id, animateur_id) "
                + "VALUES (?, ?, ?, ?)";
        int count = 0;
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            for (PosteAffectation poste : postes) {
                if (poste.getStand() == null || poste.getCreneau() == null) {
                    continue;
                }
                ps.setString(1, poste.getId());
                ps.setString(2, poste.getStand().getId());
                ps.setLong(3, poste.getCreneau().getId());
                ps.setString(4, poste.getAnimateur() != null ? poste.getAnimateur().getId() : null);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    /**
     * Records which groupe de créneaux this solve was computed for, taken from
     * the (already-hydrated) créneau of the solved postes rather than
     * re-reading "the active group" from the database: that way the record
     * reflects the group actually solved even if it was changed while the
     * solve was running. Silently records {@code null} when the solved
     * postes carry no group (e.g. a YAML scenario solved without reference
     * data), rather than blocking persistence.
     */
    private void recordResolution(Connection connection, PlanningFestival planning) throws SQLException {
        String groupeId = planning.getPostes().stream()
                .map(PosteAffectation::getCreneau)
                .filter(Objects::nonNull)
                .map(Creneau::getGroupe)
                .filter(Objects::nonNull)
                .map(GroupeCreneau::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        String sql = "INSERT INTO planning_resolution (id, groupe_creneau_id, resolu_le) VALUES (1, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET groupe_creneau_id = EXCLUDED.groupe_creneau_id, "
                + "resolu_le = EXCLUDED.resolu_le";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupeId);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /**
     * The groupe de créneaux the last persisted solve was computed for, and
     * when it ran. {@code null} when nothing has been solved yet.
     */
    public PlanningResolution loadResolution() {
        String sql = "SELECT r.groupe_creneau_id, g.nom, r.resolu_le FROM planning_resolution r "
                + "LEFT JOIN groupe_creneau g ON g.id = r.groupe_creneau_id WHERE r.id = 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Timestamp resoluLe = rs.getTimestamp("resolu_le");
            return new PlanningResolution(rs.getString("groupe_creneau_id"), rs.getString("nom"),
                    resoluLe != null ? resoluLe.toInstant() : null);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load planning resolution", e);
        }
    }

    /** @param groupeCreneauId may be {@code null} if the group solved for was later deleted. */
    public record PlanningResolution(String groupeCreneauId, String groupeCreneauNom, Instant resoluLe) {
    }

    /**
     * Number of assignment rows currently stored, used to confirm persistence.
     */
    public int countPersistedAssignments() {
        String sql = "SELECT COUNT(*) FROM poste_affectation";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count persisted assignments", e);
        }
    }

    /**
     * Rebuilds the last persisted solution from the database. Read-only view
     * used by the calendars and the exports: it never triggers a solve, so
     * simply browsing the app cannot start a solver run. Returns an empty
     * planning (no postes) when nothing has been solved yet.
     */
    public PlanningFestival loadPersistedPlanning() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        Map<String, Animateur> animateursById = indexById(animateurs, Animateur::getId);
        Map<String, Stand> standsById = indexById(referenceDataService.listStands(), Stand::getId);
        Map<Long, Creneau> creneauxById = indexById(referenceDataService.listCreneaux(), Creneau::getId);

        List<PosteAffectation> postes = new ArrayList<>();
        String sql = "SELECT id, stand_id, creneau_id, animateur_id FROM poste_affectation ORDER BY id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Stand stand = standsById.get(rs.getString("stand_id"));
                Creneau creneau = creneauxById.get(rs.getLong("creneau_id"));
                if (stand == null || creneau == null) {
                    continue;
                }
                PosteAffectation poste = new PosteAffectation(rs.getString("id"), stand, creneau);
                String animateurId = rs.getString("animateur_id");
                if (animateurId != null) {
                    poste.setAnimateur(animateursById.get(animateurId));
                }
                postes.add(poste);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load persisted planning", e);
        }

        LocalDate dateDebut = postes.stream()
                .map(poste -> poste.getCreneau().getDate())
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        return new PlanningFestival(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
    }

    private <T, K> Map<K, T> indexById(List<T> items, java.util.function.Function<T, K> idFn) {
        Map<K, T> byId = new java.util.HashMap<>();
        for (T item : items) {
            K id = idFn.apply(item);
            if (id != null) {
                byId.put(id, item);
            }
        }
        return byId;
    }

    private <T, K> List<T> dedupById(List<T> items, java.util.function.Function<T, K> idFn) {
        List<T> result = new ArrayList<>();
        java.util.Set<K> seen = new java.util.HashSet<>();
        for (T item : items) {
            K id = idFn.apply(item);
            if (id != null && seen.add(id)) {
                result.add(item);
            }
        }
        return result;
    }
}
