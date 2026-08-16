package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Groupe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Direct JDBC access to the {@code groupe} table — the editions the whole
 * reference model is partitioned into (see {@code docs/groupes.md}).
 *
 * <p>Deliberately separate from {@link ReferenceDataRepository}: that one reads
 * and writes <i>inside</i> a group and gets its scope from
 * {@link GroupeContext}, this one manipulates the scopes themselves and must
 * therefore stay group-agnostic.</p>
 */
@ApplicationScoped
public class GroupeRepository {

    /** Group seeded by V30, and the fallback for any caller designating none. */
    public static final String GROUPE_DEFAUT_ID = "DEFAUT";

    /**
     * Reference tables copied by {@link #dupliquer}, ordered so a sequential
     * insert never breaks a foreign key. {@code creneau} and its children are
     * absent: their ids are DB-generated and need the remapping handled
     * separately below. Solver <i>results</i> ({@code poste_affectation},
     * {@code planning_resolution}) are absent too — duplicating an edition
     * means "2026 = 2025 minus the assignments".
     */
    private static final List<TableACopier> TABLES_A_COPIER = List.of(
            new TableACopier("typologie", "id, label"),
            new TableACopier("emplacement", "id, nom, latitude, longitude"),
            new TableACopier("animateur", "id, prenom, nom, date_naissance, manager"),
            new TableACopier("stand",
                    "id, nom, effectif_min, effectif_max, reserve_majeurs, premium, emplacement_id, niveau_effort"),
            new TableACopier("animateur_competence", "animateur_id, typologie, niveau"),
            new TableACopier("animateur_jour_indispo", "animateur_id, jour"),
            new TableACopier("animateur_souhait", "animateur_id, typologie"),
            new TableACopier("stand_typologie", "stand_id, typologie"),
            new TableACopier("stand_indisponibilite",
                    "stand_id, date_indisponibilite, heure_debut, heure_fin, motif"),
            new TableACopier("stand_ouverture", "stand_id, date_ouverture, heure_debut, heure_fin, motif"),
            new TableACopier("constraint_toggle", "nom, motif, modifie_par_utilisateur_id, modifie_le"),
            new TableACopier("parametres_legaux",
                    "duree_hebdomadaire_max_minutes, duree_hebdomadaire_max_mineur_minutes, "
                            + "pause_minimale_entre_vacations_minutes, repos_quotidien_minimal_minutes"),
            new TableACopier("parametres_decoupage",
                    "duree_vacation_cible_minutes, duree_vacation_min_minutes, duree_vacation_max_minutes, "
                            + "duree_chevauchement_minutes, duree_pause_repas_minutes, fenetre_repas_midi_debut, "
                            + "fenetre_repas_midi_fin, fenetre_repas_soir_debut, fenetre_repas_soir_fin, "
                            + "strategie_couverture_pendant_pause, nombre_familles_decalage, "
                            + "duree_decalage_max_minutes"),
            new TableACopier("parametres_solveur", "duree_resolution_secondes"));

    private record TableACopier(String nom, String colonnes) {
    }

    @Inject
    DataSource dataSource;

    public List<Groupe> listGroupes() {
        List<Groupe> groupes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, nom, defaut, cree_le FROM groupe ORDER BY cree_le, id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp creeLe = rs.getTimestamp("cree_le");
                groupes.add(new Groupe(rs.getString("id"), rs.getString("nom"), rs.getBoolean("defaut"),
                        creeLe != null ? creeLe.toInstant() : null));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list groups", e);
        }
        return groupes;
    }

    public boolean exists(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM groupe WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to probe group " + id, e);
        }
    }

    /**
     * Id of the group flagged {@code defaut}, or {@value #GROUPE_DEFAUT_ID} if
     * none is — a database always has one (V30 seeds it, and the service
     * refuses to delete it), so the fallback only covers a hand-edited base.
     */
    public String idGroupeParDefaut() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT id FROM groupe WHERE defaut");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("id") : GROUPE_DEFAUT_ID;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the default group", e);
        }
    }

    /** Creates the group, or renames it if it already exists — {@code defaut} is never touched here. */
    public void save(Groupe groupe) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO groupe (id, nom, defaut) VALUES (?, ?, FALSE) "
                                + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom")) {
            ps.setString(1, groupe.getId());
            ps.setString(2, groupe.getNom());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save group " + groupe.getId(), e);
        }
    }

    /**
     * Flags this group as the default and clears every other one, in a single
     * transaction (clear-then-set order, so the partial unique index on
     * {@code defaut} is never violated in between — same pattern as
     * {@code groupe_creneau.actif}).
     */
    public void definirParDefaut(String id) {
        inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE groupe SET defaut = FALSE")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE groupe SET defaut = TRUE WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }, "Failed to set group " + id + " as default");
    }

    /** Drops the group and, by {@code ON DELETE CASCADE}, its whole reference model. */
    public void delete(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM groupe WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete group " + id, e);
        }
    }

    /**
     * Copies the whole reference model of {@code sourceId} into the
     * already-created {@code cibleId}, in one transaction — the "2026 = 2025
     * minus the assignments" action. Solver results are never copied.
     *
     * <p>Every table is copied by a plain {@code INSERT … SELECT} rewriting
     * {@code groupe_id}, which works because business ids are preserved
     * verbatim: that is exactly what the composite {@code (groupe_id, id)}
     * primary keys of V32 are for. {@code creneau} is the one exception — its
     * id is a DB-generated identity, so new ids are drawn up-front into a
     * temporary mapping table and the rows referencing them are rewritten
     * through it.</p>
     */
    public void dupliquer(String sourceId, String cibleId) {
        inTransaction(connection -> {
            copierGroupesCreneaux(connection, sourceId, cibleId);
            for (TableACopier table : TABLES_A_COPIER) {
                copierTable(connection, table, sourceId, cibleId);
            }
            copierCreneaux(connection, sourceId, cibleId);
            copierContraintesAdHoc(connection, sourceId, cibleId);
        }, "Failed to duplicate group " + sourceId + " into " + cibleId);
    }

    /**
     * Timeslot groups first, since the créneaux point at them. Their
     * {@code groupe_source_id} self-reference is filled in a second pass: a
     * single multi-row insert would otherwise have to order the rows so every
     * source comes before the groups generated from it.
     */
    private void copierGroupesCreneaux(Connection connection, String sourceId, String cibleId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO groupe_creneau (groupe_id, id, nom, actif) "
                        + "SELECT ?, id, nom, actif FROM groupe_creneau WHERE groupe_id = ?")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE groupe_creneau cible SET groupe_source_id = source.groupe_source_id "
                        + "FROM groupe_creneau source "
                        + "WHERE source.groupe_id = ? AND cible.groupe_id = ? AND cible.id = source.id")) {
            ps.setString(1, sourceId);
            ps.setString(2, cibleId);
            ps.executeUpdate();
        }
    }

    private void copierTable(Connection connection, TableACopier table, String sourceId, String cibleId)
            throws SQLException {
        // Column lists come from the constant above, never from user input.
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + table.nom() + " (groupe_id, " + table.colonnes() + ") "
                        + "SELECT ?, " + table.colonnes() + " FROM " + table.nom() + " WHERE groupe_id = ?")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
    }

    /**
     * Draws one new identity value per source créneau into a temporary mapping
     * table, then inserts the copies with those ids. Pairing old and new ids
     * this way is what lets {@code creneau_stand_ouvert} and
     * {@code contrainte_ad_hoc.creneau_id} be rewritten set-wise; relying on
     * the order of an {@code INSERT … RETURNING} would not be guaranteed.
     */
    private void copierCreneaux(Connection connection, String sourceId, String cibleId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE creneau_remap ("
                    + "ancien_id BIGINT PRIMARY KEY, nouvel_id BIGINT NOT NULL) ON COMMIT DROP");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO creneau_remap (ancien_id, nouvel_id) "
                        + "SELECT id, nextval(pg_get_serial_sequence('creneau', 'id')) "
                        + "FROM creneau WHERE groupe_id = ?")) {
            ps.setString(1, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO creneau (id, groupe_id, date_creneau, heure_debut, heure_fin, groupe_creneau_id, famille) "
                        + "SELECT r.nouvel_id, ?, c.date_creneau, c.heure_debut, c.heure_fin, c.groupe_creneau_id, "
                        + "c.famille FROM creneau c JOIN creneau_remap r ON r.ancien_id = c.id WHERE c.groupe_id = ?")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO creneau_stand_ouvert (groupe_id, creneau_id, stand_id) "
                        + "SELECT ?, r.nouvel_id, cso.stand_id FROM creneau_stand_ouvert cso "
                        + "JOIN creneau_remap r ON r.ancien_id = cso.creneau_id WHERE cso.groupe_id = ?")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
    }

    /** Ad hoc constraints last: they reference both a stand and a (remapped) créneau. */
    private void copierContraintesAdHoc(Connection connection, String sourceId, String cibleId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO contrainte_ad_hoc (groupe_id, id, type, creneau_id, stand_id, raison, cree_par, cree_le) "
                        + "SELECT ?, c.id, c.type, r.nouvel_id, c.stand_id, c.raison, c.cree_par, c.cree_le "
                        + "FROM contrainte_ad_hoc c LEFT JOIN creneau_remap r ON r.ancien_id = c.creneau_id "
                        + "WHERE c.groupe_id = ?")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO contrainte_animateur (groupe_id, contrainte_id, animateur_id, position) "
                        + "SELECT ?, contrainte_id, animateur_id, position FROM contrainte_animateur "
                        + "WHERE groupe_id = ?")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
    }

    private void inTransaction(TransactionalWork work, String errorMessage) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.execute(connection);
                connection.commit();
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
        void execute(Connection connection) throws SQLException;
    }
}
