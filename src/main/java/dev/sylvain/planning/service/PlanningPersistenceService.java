package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
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

    /**
     * Writes the whole solution to the database in a single transaction and
     * returns how many assignment rows were stored.
     */
    public int persist(PlanningFestival planning) {
        if (planning == null || planning.getPostes() == null) {
            return 0;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertReferenceData(connection, planning);
                int rows = rewriteAssignments(connection, planning.getPostes());
                connection.commit();
                return rows;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist planning solution", e);
        }
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

        for (Stand stand : dedupById(stands, Stand::getId)) {
            upsertStand(connection, stand);
        }

        for (Creneau creneau : dedupById(creneaux, Creneau::getId)) {
            upsertCreneau(connection, creneau);
        }

        List<Animateur> animateurs = planning.getAnimateurs() != null
                ? planning.getAnimateurs()
                : List.of();
        for (Animateur animateur : dedupById(animateurs, Animateur::getId)) {
            upsertAnimateur(connection, animateur);
        }
    }

    private void upsertStand(Connection connection, Stand stand) throws SQLException {
        String update = "UPDATE stand SET nom = ?, effectif_min = ?, effectif_max = ?, reserve_majeurs = ? WHERE id = ?";
        try (PreparedStatement updatePs = connection.prepareStatement(update)) {
            updatePs.setString(1, stand.getNom());
            updatePs.setInt(2, stand.getEffectifMin());
            updatePs.setInt(3, stand.getEffectifMax());
            updatePs.setBoolean(4, stand.isReserveMajeurs());
            updatePs.setString(5, stand.getId());
            if (updatePs.executeUpdate() == 0) {
                String insert = "INSERT INTO stand (id, nom, effectif_min, effectif_max, reserve_majeurs) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement insertPs = connection.prepareStatement(insert)) {
                    insertPs.setString(1, stand.getId());
                    insertPs.setString(2, stand.getNom());
                    insertPs.setInt(3, stand.getEffectifMin());
                    insertPs.setInt(4, stand.getEffectifMax());
                    insertPs.setBoolean(5, stand.isReserveMajeurs());
                    insertPs.executeUpdate();
                }
            }
        }
    }

    private void upsertCreneau(Connection connection, Creneau creneau) throws SQLException {
        String update = "UPDATE creneau SET jour = ?, date_creneau = ?, heure_debut = ?, heure_fin = ? WHERE id = ?";
        try (PreparedStatement updatePs = connection.prepareStatement(update)) {
            updatePs.setInt(1, creneau.getJour());
            updatePs.setObject(2, creneau.getDate());
            updatePs.setObject(3, creneau.getHeureDebut());
            updatePs.setObject(4, creneau.getHeureFin());
            updatePs.setString(5, creneau.getId());
            if (updatePs.executeUpdate() == 0) {
                String insert = "INSERT INTO creneau (id, jour, date_creneau, heure_debut, heure_fin) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement insertPs = connection.prepareStatement(insert)) {
                    insertPs.setString(1, creneau.getId());
                    insertPs.setInt(2, creneau.getJour());
                    insertPs.setObject(3, creneau.getDate());
                    insertPs.setObject(4, creneau.getHeureDebut());
                    insertPs.setObject(5, creneau.getHeureFin());
                    insertPs.executeUpdate();
                }
            }
        }
    }

    private void upsertAnimateur(Connection connection, Animateur animateur) throws SQLException {
        String update = "UPDATE animateur SET prenom = ?, nom = ?, date_naissance = ?, statut = ? WHERE id = ?";
        try (PreparedStatement updatePs = connection.prepareStatement(update)) {
            updatePs.setString(1, animateur.getPrenom());
            updatePs.setString(2, animateur.getNom());
            updatePs.setObject(3, animateur.getDateNaissance());
            updatePs.setString(4, animateur.getStatut() != null ? animateur.getStatut().name() : null);
            updatePs.setString(5, animateur.getId());
            if (updatePs.executeUpdate() == 0) {
                String insert = "INSERT INTO animateur (id, prenom, nom, date_naissance, statut) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement insertPs = connection.prepareStatement(insert)) {
                    insertPs.setString(1, animateur.getId());
                    insertPs.setString(2, animateur.getPrenom());
                    insertPs.setString(3, animateur.getNom());
                    insertPs.setObject(4, animateur.getDateNaissance());
                    insertPs.setString(5, animateur.getStatut() != null ? animateur.getStatut().name() : null);
                    insertPs.executeUpdate();
                }
            }
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
                ps.setString(3, poste.getCreneau().getId());
                ps.setString(4, poste.getAnimateur() != null ? poste.getAnimateur().getId() : null);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
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

    private interface IdAccessor<T> {
        String id(T item);
    }

    private <T> List<T> dedupById(List<T> items, IdAccessor<T> accessor) {
        List<T> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (T item : items) {
            String id = accessor.id(item);
            if (id != null && seen.add(id)) {
                result.add(item);
            }
        }
        return result;
    }
}
