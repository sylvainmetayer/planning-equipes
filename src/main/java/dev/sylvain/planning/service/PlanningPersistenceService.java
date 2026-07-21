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

        String upsertStand = "INSERT INTO stand (id, nom, effectif_min, effectif_max, reserve_majeurs) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min, "
                + "effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs";
        try (PreparedStatement ps = connection.prepareStatement(upsertStand)) {
            for (Stand stand : dedupById(stands, Stand::getId)) {
                ps.setString(1, stand.getId());
                ps.setString(2, stand.getNom());
                ps.setInt(3, stand.getEffectifMin());
                ps.setInt(4, stand.getEffectifMax());
                ps.setBoolean(5, stand.isReserveMajeurs());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String upsertCreneau = "INSERT INTO creneau (id, jour, date_creneau, heure_debut, heure_fin) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "jour = EXCLUDED.jour, date_creneau = EXCLUDED.date_creneau, "
                + "heure_debut = EXCLUDED.heure_debut, heure_fin = EXCLUDED.heure_fin";
        try (PreparedStatement ps = connection.prepareStatement(upsertCreneau)) {
            for (Creneau creneau : dedupById(creneaux, Creneau::getId)) {
                ps.setString(1, creneau.getId());
                ps.setInt(2, creneau.getJour());
                ps.setObject(3, creneau.getDate());
                ps.setObject(4, creneau.getHeureDebut());
                ps.setObject(5, creneau.getHeureFin());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String upsertAnimateur = "INSERT INTO animateur (id, prenom, nom, date_naissance, statut) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, "
                + "date_naissance = EXCLUDED.date_naissance, statut = EXCLUDED.statut";
        try (PreparedStatement ps = connection.prepareStatement(upsertAnimateur)) {
            List<Animateur> animateurs = planning.getAnimateurs() != null
                    ? planning.getAnimateurs()
                    : List.of();
            for (Animateur animateur : dedupById(animateurs, Animateur::getId)) {
                ps.setString(1, animateur.getId());
                ps.setString(2, animateur.getPrenom());
                ps.setString(3, animateur.getNom());
                ps.setObject(4, animateur.getDateNaissance());
                ps.setString(5, animateur.getStatut() != null ? animateur.getStatut().name() : null);
                ps.addBatch();
            }
            ps.executeBatch();
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
