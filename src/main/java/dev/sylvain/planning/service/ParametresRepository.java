package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The three single-row parameter tables (legal, découpage, solver), the
 * constraint toggles and the per-edition constraint weights.
 *
 * <p>Each single-row parameter table holds one row per edition, so every read
 * has to cope with the row not being there yet: an edition that has never been
 * configured returns the defaults rather than nothing.</p>
 */
@ApplicationScoped
public class ParametresRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public ParametresLegaux getParametresLegaux() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        SELECT duree_hebdomadaire_max_minutes, duree_hebdomadaire_max_mineur_minutes,
                        pause_minimale_entre_vacations_minutes, repos_quotidien_minimal_minutes
                        FROM parametres_legaux
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                ParametresLegaux parametres = new ParametresLegaux(rs.getInt("duree_hebdomadaire_max_minutes"),
                        rs.getInt("duree_hebdomadaire_max_mineur_minutes"));
                parametres.setPauseMinimaleEntreVacationsMinutes(
                        rs.getInt("pause_minimale_entre_vacations_minutes"));
                parametres.setReposQuotidienMinimalMinutes(rs.getInt("repos_quotidien_minimal_minutes"));
                return parametres;
            }
            return new ParametresLegaux();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load legal parameters", e);
        }
    }

    public void saveParametresLegaux(ParametresLegaux parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO parametres_legaux (edition_id, duree_hebdomadaire_max_minutes,
                        duree_hebdomadaire_max_mineur_minutes, pause_minimale_entre_vacations_minutes,
                        repos_quotidien_minimal_minutes)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET duree_hebdomadaire_max_minutes = EXCLUDED.duree_hebdomadaire_max_minutes,
                        duree_hebdomadaire_max_mineur_minutes = EXCLUDED.duree_hebdomadaire_max_mineur_minutes,
                        pause_minimale_entre_vacations_minutes = EXCLUDED.pause_minimale_entre_vacations_minutes,
                        repos_quotidien_minimal_minutes = EXCLUDED.repos_quotidien_minimal_minutes""")) {
            ps.setInt(2, parametres.getDureeHebdomadaireMaxMinutes());
            ps.setInt(3, parametres.getDureeHebdomadaireMaxMineurMinutes());
            ps.setInt(4, parametres.getPauseMinimaleEntreVacationsMinutes());
            ps.setInt(5, parametres.getReposQuotidienMinimalMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save legal parameters", e);
        }
    }

    /* ---------------------------- Slicing parameters ------------------------- */

    public ParametresDecoupage getParametresDecoupage() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        SELECT duree_vacation_cible_minutes, duree_vacation_min_minutes,
                        duree_vacation_max_minutes, duree_chevauchement_minutes,
                        duree_pause_repas_minutes, fenetre_repas_midi_debut, fenetre_repas_midi_fin,
                        fenetre_repas_soir_debut, fenetre_repas_soir_fin, strategie_couverture_pendant_pause,
                        nombre_familles_decalage, duree_decalage_max_minutes
                        FROM parametres_decoupage
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                ParametresDecoupage parametres = new ParametresDecoupage();
                parametres.setDureeVacationCibleMinutes(rs.getInt("duree_vacation_cible_minutes"));
                parametres.setDureeVacationMinMinutes(rs.getInt("duree_vacation_min_minutes"));
                parametres.setDureeVacationMaxMinutes(rs.getInt("duree_vacation_max_minutes"));
                parametres.setDureeChevauchementMinutes(rs.getInt("duree_chevauchement_minutes"));
                parametres.setDureePauseRepasMinutes(rs.getInt("duree_pause_repas_minutes"));
                parametres.setFenetreRepasMidiDebut(rs.getObject("fenetre_repas_midi_debut", LocalTime.class));
                parametres.setFenetreRepasMidiFin(rs.getObject("fenetre_repas_midi_fin", LocalTime.class));
                parametres.setFenetreRepasSoirDebut(rs.getObject("fenetre_repas_soir_debut", LocalTime.class));
                parametres.setFenetreRepasSoirFin(rs.getObject("fenetre_repas_soir_fin", LocalTime.class));
                parametres.setStrategieCouverturePendantPause(
                        ParametresDecoupage.PauseCoverageStrategy
                                .valueOf(rs.getString("strategie_couverture_pendant_pause")));
                parametres.setNombreFamillesDecalage(rs.getInt("nombre_familles_decalage"));
                parametres.setDureeDecalageMaxMinutes(rs.getInt("duree_decalage_max_minutes"));
                return parametres;
            }
            return new ParametresDecoupage();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load découpage parameters", e);
        }
    }

    public void saveParametresDecoupage(ParametresDecoupage parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO parametres_decoupage (edition_id, duree_vacation_cible_minutes,
                        duree_vacation_min_minutes, duree_vacation_max_minutes, duree_chevauchement_minutes,
                        duree_pause_repas_minutes, fenetre_repas_midi_debut, fenetre_repas_midi_fin,
                        fenetre_repas_soir_debut, fenetre_repas_soir_fin, strategie_couverture_pendant_pause,
                        nombre_familles_decalage, duree_decalage_max_minutes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET duree_vacation_cible_minutes = EXCLUDED.duree_vacation_cible_minutes,
                        duree_vacation_min_minutes = EXCLUDED.duree_vacation_min_minutes,
                        duree_vacation_max_minutes = EXCLUDED.duree_vacation_max_minutes,
                        duree_chevauchement_minutes = EXCLUDED.duree_chevauchement_minutes,
                        duree_pause_repas_minutes = EXCLUDED.duree_pause_repas_minutes,
                        fenetre_repas_midi_debut = EXCLUDED.fenetre_repas_midi_debut,
                        fenetre_repas_midi_fin = EXCLUDED.fenetre_repas_midi_fin,
                        fenetre_repas_soir_debut = EXCLUDED.fenetre_repas_soir_debut,
                        fenetre_repas_soir_fin = EXCLUDED.fenetre_repas_soir_fin,
                        strategie_couverture_pendant_pause = EXCLUDED.strategie_couverture_pendant_pause,
                        nombre_familles_decalage = EXCLUDED.nombre_familles_decalage,
                        duree_decalage_max_minutes = EXCLUDED.duree_decalage_max_minutes""")) {
            ps.setInt(2, parametres.getDureeVacationCibleMinutes());
            ps.setInt(3, parametres.getDureeVacationMinMinutes());
            ps.setInt(4, parametres.getDureeVacationMaxMinutes());
            ps.setInt(5, parametres.getDureeChevauchementMinutes());
            ps.setInt(6, parametres.getDureePauseRepasMinutes());
            ps.setObject(7, parametres.getFenetreRepasMidiDebut());
            ps.setObject(8, parametres.getFenetreRepasMidiFin());
            ps.setObject(9, parametres.getFenetreRepasSoirDebut());
            ps.setObject(10, parametres.getFenetreRepasSoirFin());
            ps.setString(11, parametres.getStrategieCouverturePendantPause().name());
            ps.setInt(12, parametres.getNombreFamillesDecalage());
            ps.setInt(13, parametres.getDureeDecalageMaxMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save découpage parameters", e);
        }
    }

    /* ---------------------------- Solver parameters --------------------------- */

    public ParametresSolveur getParametresSolveur() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        SELECT duree_resolution_secondes, mail_fin_resolution
                        FROM parametres_solveur
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ParametresSolveur(rs.getInt("duree_resolution_secondes"),
                        rs.getBoolean("mail_fin_resolution"));
            }
            return new ParametresSolveur();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load solver parameters", e);
        }
    }

    public void saveParametresSolveur(ParametresSolveur parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO parametres_solveur (edition_id, duree_resolution_secondes, mail_fin_resolution)
                        VALUES (?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET duree_resolution_secondes = EXCLUDED.duree_resolution_secondes,
                        mail_fin_resolution = EXCLUDED.mail_fin_resolution""")) {
            ps.setInt(2, parametres.dureeResolutionSecondes());
            ps.setBoolean(3, parametres.mailFinResolution());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save solver parameters", e);
        }
    }

    /* ---------------------------- Constraint toggles ------------------------- */

    public Set<String> getContraintesDesactivees() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "SELECT nom FROM constraint_toggle WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            Set<String> desactivees = new HashSet<>();
            while (rs.next()) {
                desactivees.add(rs.getString("nom"));
            }
            return desactivees;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load constraint toggles", e);
        }
    }

    /* ---------------------------- Constraint weights ------------------------- */

    /**
     * The weights this edition overrides, by constraint name. Absent means
     * "keep the deployment default" ({@code planning.constraint-weights.<nom>}
     * in {@code application.properties}), same convention as
     * {@code constraint_toggle}: no row, no override.
     */
    public Map<String, Integer> getConstraintWeights() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "SELECT nom, poids FROM ponderation_contrainte WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            Map<String, Integer> ponderations = new LinkedHashMap<>();
            while (rs.next()) {
                ponderations.put(rs.getString("nom"), rs.getInt("poids"));
            }
            return ponderations;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load constraint weights", e);
        }
    }

    /**
     * Overrides one constraint's weight for this edition, or drops the
     * override when {@code poids} is {@code null} — the constraint then falls
     * back to the configured default.
     */
    public void setConstraintWeight(String nom, Integer poids) {
        try (Connection connection = dataSource.getConnection()) {
            if (poids == null) {
                try (PreparedStatement ps = scope.prepareScoped(connection,
                        "DELETE FROM ponderation_contrainte WHERE edition_id = ? AND nom = ?")) {
                    ps.setString(2, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO ponderation_contrainte (edition_id, nom, poids)
                        VALUES (?, ?, ?)
                        ON CONFLICT (edition_id, nom)
                        DO UPDATE SET poids = EXCLUDED.poids""")) {
                    ps.setString(2, nom);
                    ps.setInt(3, poids);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save constraint weight", e);
        }
    }

    /**
     * A row's presence means the constraint is disabled for the next solve;
     * re-enabling simply drops it. Nothing else is recorded: this is a toggle
     * table, not an audit log (see migration V39).
     */
    public void setContrainteActive(String nom, boolean actif) {
        try (Connection connection = dataSource.getConnection()) {
            if (actif) {
                try (PreparedStatement ps = scope.prepareScoped(connection,
                        "DELETE FROM constraint_toggle WHERE edition_id = ? AND nom = ?")) {
                    ps.setString(2, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO constraint_toggle (edition_id, nom)
                        VALUES (?, ?)
                        ON CONFLICT (edition_id, nom) DO NOTHING""")) {
                    ps.setString(2, nom);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save constraint toggle", e);
        }
    }
}
