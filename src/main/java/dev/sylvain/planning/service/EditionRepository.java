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

import dev.sylvain.planning.domain.Edition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Direct JDBC access to the {@code edition} table — the editions the whole
 * reference model is partitioned into (see {@code docs/editions.md}).
 *
 * <p>Deliberately separate from {@link StandRepository} and its siblings: those read
 * and writes <i>inside</i> an edition and gets its scope from
 * {@link EditionContext}, this one manipulates the scopes themselves and must
 * therefore stay edition-agnostic.</p>
 */
@ApplicationScoped
public class EditionRepository {

    /** Edition seeded by V30, and the fallback for any caller designating none. */
    public static final String EDITION_DEFAUT_ID = "DEFAUT";

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
            // email travels with the copy (the canicule-edition ritual of issue
            // #172 ends with « Envoyer à tous », mute without it); jeton_acces
            // deliberately does NOT: the column default mints a fresh token per
            // edition, so an espace link keeps designating exactly one edition.
            new TableACopier("animateur", "id, prenom, nom, date_naissance, manager, email"),
            new TableACopier("stand",
                    "id, nom, effectif_min, effectif_max, reserve_majeurs, premium, emplacement_id, niveau_effort"),
            new TableACopier("animateur_competence", "animateur_id, typologie, niveau"),
            new TableACopier("animateur_jour_indispo", "animateur_id, jour"),
            new TableACopier("animateur_souhait", "animateur_id, typologie"),
            new TableACopier("stand_typologie", "stand_id, typologie"),
            new TableACopier("stand_indisponibilite",
                    "stand_id, date_indisponibilite, heure_debut, heure_fin, motif"),
            new TableACopier("stand_ouverture", "stand_id, date_ouverture, heure_debut, heure_fin, motif"),
            // The recurring opening rules (V37) predated by this list: without
            // them a duplicated edition silently fell back to « open on every
            // slot ». Their BIGSERIAL ids are kept as-is — the PKs are
            // composite (edition_id, id), the child FK follows the new
            // edition_id, and the shared sequence has already consumed those
            // values, so future inserts cannot collide.
            new TableACopier("stand_horaire",
                    "id, stand_id, mode, type_jours, jours_semaine, date_debut, date_fin, dates, motif"),
            new TableACopier("stand_horaire_fenetre", "id, horaire_id, position, heure_debut, heure_fin"),
            new TableACopier("constraint_toggle", "nom"),
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

    @Inject
    JdbcEditionScope scope;

    public List<Edition> listEditions() {
        List<Edition> editions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, nom, defaut, cree_le FROM edition ORDER BY cree_le, id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp creeLe = rs.getTimestamp("cree_le");
                editions.add(new Edition(rs.getString("id"), rs.getString("nom"), rs.getBoolean("defaut"),
                        creeLe != null ? creeLe.toInstant() : null));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list editions", e);
        }
        return editions;
    }

    public boolean exists(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM edition WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to probe edition " + id, e);
        }
    }

    /**
     * Id of the edition flagged {@code defaut}, or {@value #EDITION_DEFAUT_ID} if
     * none is — a database always has one (V30 seeds it, and the service
     * refuses to delete it), so the fallback only covers a hand-edited base.
     */
    public String idEditionParDefaut() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT id FROM edition WHERE defaut");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("id") : EDITION_DEFAUT_ID;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the default edition", e);
        }
    }

    /** Creates the edition, or renames it if it already exists — {@code defaut} is never touched here. */
    public void save(Edition edition) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        """
                        INSERT INTO edition (id, nom, defaut)
                        VALUES (?, ?, FALSE)
                        ON CONFLICT (id)
                        DO UPDATE SET nom = EXCLUDED.nom""")) {
            ps.setString(1, edition.getId());
            ps.setString(2, edition.getNom());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save edition " + edition.getId(), e);
        }
    }

    /**
     * Flags this edition as the default and clears every other one, in a single
     * transaction (clear-then-set order, so the partial unique index on
     * {@code defaut} is never violated in between — same pattern as
     * the former {@code groupe_creneau.actif}).
     */
    public void definirParDefaut(String id) {
        scope.ecrire("Failed to set edition " + id + " as default", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE edition SET defaut = FALSE")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE edition SET defaut = TRUE WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
    }

    /** Drops the edition and, by {@code ON DELETE CASCADE}, its whole reference model. */
    public void delete(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM edition WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete edition " + id, e);
        }
    }

    /**
     * Copies the whole reference model of {@code sourceId} into the
     * already-created {@code cibleId}, in one transaction — the "2026 = 2025
     * minus the assignments" action. Solver results are never copied.
     *
     * <p>Every table is copied by a plain {@code INSERT … SELECT} rewriting
     * {@code edition_id}, which works because business ids are preserved
     * verbatim: that is exactly what the composite {@code (edition_id, id)}
     * primary keys of V32 are for. {@code creneau} is the one exception — its
     * id is a DB-generated identity, so new ids are drawn up-front into a
     * temporary mapping table and the rows referencing them are rewritten
     * through it.</p>
     */
    public void dupliquer(String sourceId, String cibleId) {
        scope.ecrire("Failed to duplicate edition " + sourceId + " into " + cibleId, connection -> {
            for (TableACopier table : TABLES_A_COPIER) {
                copierTable(connection, table, sourceId, cibleId);
            }
            copierCreneaux(connection, sourceId, cibleId);
            copierContraintesAdHoc(connection, sourceId, cibleId);
        });
    }

    private void copierTable(Connection connection, TableACopier table, String sourceId, String cibleId)
            throws SQLException {
        // Column lists come from the constant above, never from user input.
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + table.nom() + " (edition_id, " + table.colonnes() + ") "
                        + "SELECT ?, " + table.colonnes() + " FROM " + table.nom() + " WHERE edition_id = ?")) {
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
                """
                INSERT INTO creneau_remap (ancien_id, nouvel_id)
                SELECT id, nextval(pg_get_serial_sequence('creneau', 'id'))
                FROM creneau
                WHERE edition_id = ?""")) {
            ps.setString(1, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO creneau (id, edition_id, date_creneau, heure_debut, heure_fin, famille, couverture_pause)
                SELECT r.nouvel_id, ?, c.date_creneau, c.heure_debut, c.heure_fin, c.famille, c.couverture_pause
                FROM creneau c
                JOIN creneau_remap r ON r.ancien_id = c.id
                WHERE c.edition_id = ?""")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO creneau_stand_ouvert (edition_id, creneau_id, stand_id)
                SELECT ?, r.nouvel_id, cso.stand_id
                FROM creneau_stand_ouvert cso
                JOIN creneau_remap r ON r.ancien_id = cso.creneau_id
                WHERE cso.edition_id = ?""")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
    }

    /** Ad hoc constraints last: they reference both a stand and a (remapped) créneau. */
    private void copierContraintesAdHoc(Connection connection, String sourceId, String cibleId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO contrainte_ad_hoc (edition_id, id, type, creneau_id, stand_id, raison, cree_par, cree_le)
                SELECT ?, c.id, c.type, r.nouvel_id, c.stand_id, c.raison, c.cree_par, c.cree_le
                FROM contrainte_ad_hoc c
                LEFT JOIN creneau_remap r ON r.ancien_id = c.creneau_id
                WHERE c.edition_id = ?""")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO contrainte_animateur (edition_id, contrainte_id, animateur_id, position)
                SELECT ?, contrainte_id, animateur_id, position
                FROM contrainte_animateur
                WHERE edition_id = ?""")) {
            ps.setString(1, cibleId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
    }

}
