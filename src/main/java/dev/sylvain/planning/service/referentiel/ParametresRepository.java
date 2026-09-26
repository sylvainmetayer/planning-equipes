package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ContactOrganisation;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The single-row parameter tables (legal, quality, solver, notifications), the
 * constraint toggles and the per-edition constraint weights.
 *
 * <p>Each single-row parameter table holds one row per edition, so every read
 * has to cope with the row not being there yet: an edition that has never been
 * configured returns the defaults rather than nothing.</p>
 */
@ApplicationScoped
public class ParametresRepository {

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    /** Where a change of weight or activation is recorded, in the same transaction as the change. */
    private final WeightHistoryRepository history;

    @Inject
    public ParametresRepository(DataSource dataSource, JdbcEditionScope scope, WeightHistoryRepository history) {
        this.history = history;
        this.dataSource = dataSource;
        this.scope = scope;
    }

    public ParametresLegaux getParametresLegaux() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT duree_hebdomadaire_max_minutes, duree_hebdomadaire_max_mineur_minutes,
                        repos_quotidien_minimal_minutes,
                        coupure_repas_minutes, coupure_repas_midi_debut,
                        coupure_repas_midi_fin, coupure_repas_soir_debut, coupure_repas_soir_fin,
                        heure_debut_soiree, duree_vacation_max_minutes,
                        duree_pause_minutes
                        FROM parametres_legaux
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                ParametresLegaux parametres = new ParametresLegaux(
                        rs.getInt("duree_hebdomadaire_max_minutes"),
                        rs.getInt("duree_hebdomadaire_max_mineur_minutes"));
                parametres.setReposQuotidienMinimalMinutes(rs.getInt("repos_quotidien_minimal_minutes"));
                parametres.setCoupureRepasMinutes(rs.getInt("coupure_repas_minutes"));
                parametres.setCoupureRepasMidiDebut(rs.getObject("coupure_repas_midi_debut", LocalTime.class));
                parametres.setCoupureRepasMidiFin(rs.getObject("coupure_repas_midi_fin", LocalTime.class));
                parametres.setCoupureRepasSoirDebut(rs.getObject("coupure_repas_soir_debut", LocalTime.class));
                parametres.setCoupureRepasSoirFin(rs.getObject("coupure_repas_soir_fin", LocalTime.class));
                parametres.setHeureDebutSoiree(rs.getObject("heure_debut_soiree", LocalTime.class));
                parametres.setDureeVacationMaxMinutes(rs.getInt("duree_vacation_max_minutes"));
                parametres.setDureePauseMinutes(rs.getInt("duree_pause_minutes"));
                return parametres;
            }
            return new ParametresLegaux();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load legal parameters", e);
        }
    }

    public void saveParametresLegaux(ParametresLegaux parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO parametres_legaux (edition_id, duree_hebdomadaire_max_minutes,
                        duree_hebdomadaire_max_mineur_minutes,
                        repos_quotidien_minimal_minutes, coupure_repas_minutes,
                        coupure_repas_midi_debut, coupure_repas_midi_fin, coupure_repas_soir_debut,
                        coupure_repas_soir_fin, heure_debut_soiree, duree_vacation_max_minutes,
                        duree_pause_minutes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET duree_hebdomadaire_max_minutes = EXCLUDED.duree_hebdomadaire_max_minutes,
                        duree_hebdomadaire_max_mineur_minutes = EXCLUDED.duree_hebdomadaire_max_mineur_minutes,
                        repos_quotidien_minimal_minutes = EXCLUDED.repos_quotidien_minimal_minutes,
                        coupure_repas_minutes = EXCLUDED.coupure_repas_minutes,
                        coupure_repas_midi_debut = EXCLUDED.coupure_repas_midi_debut,
                        coupure_repas_midi_fin = EXCLUDED.coupure_repas_midi_fin,
                        coupure_repas_soir_debut = EXCLUDED.coupure_repas_soir_debut,
                        coupure_repas_soir_fin = EXCLUDED.coupure_repas_soir_fin,
                        heure_debut_soiree = EXCLUDED.heure_debut_soiree,
                        duree_vacation_max_minutes = EXCLUDED.duree_vacation_max_minutes,
                        duree_pause_minutes = EXCLUDED.duree_pause_minutes""")) {
            ps.setInt(2, parametres.getDureeHebdomadaireMaxMinutes());
            ps.setInt(3, parametres.getDureeHebdomadaireMaxMineurMinutes());
            ps.setInt(4, parametres.getReposQuotidienMinimalMinutes());
            ps.setInt(5, parametres.getCoupureRepasMinutes());
            ps.setObject(6, parametres.getCoupureRepasMidiDebut());
            ps.setObject(7, parametres.getCoupureRepasMidiFin());
            ps.setObject(8, parametres.getCoupureRepasSoirDebut());
            ps.setObject(9, parametres.getCoupureRepasSoirFin());
            ps.setObject(10, parametres.getHeureDebutSoiree());
            ps.setInt(11, parametres.getDureeVacationMaxMinutes());
            ps.setInt(12, parametres.getDureePauseMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save legal parameters", e);
        }
    }

    /* ---------------------------- Quality parameters -------------------------- */

    /**
     * What this edition chose, or {@code defauts} when it never chose anything
     * — the deployment's {@code planning.contraintes.*} block, which the caller
     * passes because this layer reads rows and knows no configuration.
     *
     * <p>The two hours are nullable on purpose: a blank hour is how
     * {@code eviterFermeturePuisOuverture} is neutralised, and midnight is not
     * the same thing as absent.</p>
     */
    public ParametresQualite getParametresQualite(ParametresQualite defauts) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT max_emplacements_distincts_par_jour, heure_service_tardif,
                        heure_service_matinal, repos_souhaite_apres_service_tardif_minutes,
                        typologies_distinctes_max, jours_consecutifs_max,
                        vitesse_marche_km_h, facteur_detour, tolerance_trajet_minutes,
                        tolerance_arrivee_groupee_minutes
                        FROM parametres_qualite
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ParametresQualite(
                        rs.getInt("max_emplacements_distincts_par_jour"),
                        rs.getObject("heure_service_tardif", LocalTime.class),
                        rs.getObject("heure_service_matinal", LocalTime.class),
                        rs.getInt("repos_souhaite_apres_service_tardif_minutes"),
                        rs.getInt("typologies_distinctes_max"),
                        rs.getInt("jours_consecutifs_max"),
                        rs.getDouble("vitesse_marche_km_h"),
                        rs.getDouble("facteur_detour"),
                        rs.getInt("tolerance_trajet_minutes"),
                        rs.getInt("tolerance_arrivee_groupee_minutes"));
            }
            return defauts;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load quality parameters", e);
        }
    }

    public void saveParametresQualite(ParametresQualite parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO parametres_qualite (edition_id, max_emplacements_distincts_par_jour,
                        heure_service_tardif, heure_service_matinal,
                        repos_souhaite_apres_service_tardif_minutes, typologies_distinctes_max,
                        jours_consecutifs_max, vitesse_marche_km_h, facteur_detour,
                        tolerance_trajet_minutes, tolerance_arrivee_groupee_minutes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET
                        max_emplacements_distincts_par_jour = EXCLUDED.max_emplacements_distincts_par_jour,
                        heure_service_tardif = EXCLUDED.heure_service_tardif,
                        heure_service_matinal = EXCLUDED.heure_service_matinal,
                        repos_souhaite_apres_service_tardif_minutes =
                                EXCLUDED.repos_souhaite_apres_service_tardif_minutes,
                        typologies_distinctes_max = EXCLUDED.typologies_distinctes_max,
                        jours_consecutifs_max = EXCLUDED.jours_consecutifs_max,
                        vitesse_marche_km_h = EXCLUDED.vitesse_marche_km_h,
                        facteur_detour = EXCLUDED.facteur_detour,
                        tolerance_trajet_minutes = EXCLUDED.tolerance_trajet_minutes,
                        tolerance_arrivee_groupee_minutes = EXCLUDED.tolerance_arrivee_groupee_minutes""")) {
            ps.setInt(2, parametres.maxEmplacementsDistinctsParJour());
            ps.setObject(3, parametres.heureServiceTardif());
            ps.setObject(4, parametres.heureServiceMatinal());
            ps.setInt(5, parametres.reposSouhaiteApresServiceTardifMinutes());
            ps.setInt(6, parametres.typologiesDistinctesMax());
            ps.setInt(7, parametres.joursConsecutifsMax());
            ps.setBigDecimal(8, BigDecimal.valueOf(parametres.vitesseMarcheKmH()));
            ps.setBigDecimal(9, BigDecimal.valueOf(parametres.facteurDetour()));
            ps.setInt(10, parametres.toleranceTrajetMinutes());
            ps.setInt(11, parametres.toleranceArriveeGroupeeMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save quality parameters", e);
        }
    }

    /* ---------------------------- Solver parameters --------------------------- */

    public ParametresSolveur getParametresSolveur() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT duree_resolution_secondes, plateau_secondes, mail_fin_resolution
                        FROM parametres_solveur
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ParametresSolveur(
                        rs.getObject("duree_resolution_secondes", Integer.class),
                        rs.getObject("plateau_secondes", Integer.class),
                        rs.getBoolean("mail_fin_resolution"));
            }
            return new ParametresSolveur();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load solver parameters", e);
        }
    }

    public void saveParametresSolveur(ParametresSolveur parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO parametres_solveur
                            (edition_id, duree_resolution_secondes, plateau_secondes, mail_fin_resolution)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET duree_resolution_secondes = EXCLUDED.duree_resolution_secondes,
                        plateau_secondes = EXCLUDED.plateau_secondes,
                        mail_fin_resolution = EXCLUDED.mail_fin_resolution""")) {
            ps.setObject(2, parametres.dureeResolutionSecondes(), Types.INTEGER);
            ps.setObject(3, parametres.plateauSecondes(), Types.INTEGER);
            ps.setBoolean(4, parametres.mailFinResolution());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save solver parameters", e);
        }
    }

    /* ------------------------ Scheduled notifications ------------------------ */

    public ParametresNotifications getParametresNotifications() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT actives, heure_rappel_veille, delai_relance_heures, anciennete_echange_jours
                        FROM parametres_notifications
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ParametresNotifications(
                        rs.getBoolean("actives"),
                        rs.getObject("heure_rappel_veille", LocalTime.class),
                        rs.getInt("delai_relance_heures"),
                        rs.getInt("anciennete_echange_jours"));
            }
            return new ParametresNotifications();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load notification parameters", e);
        }
    }

    public void saveParametresNotifications(ParametresNotifications parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO parametres_notifications (edition_id, actives, heure_rappel_veille,
                        delai_relance_heures, anciennete_echange_jours)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET actives = EXCLUDED.actives,
                        heure_rappel_veille = EXCLUDED.heure_rappel_veille,
                        delai_relance_heures = EXCLUDED.delai_relance_heures,
                        anciennete_echange_jours = EXCLUDED.anciennete_echange_jours""")) {
            ps.setBoolean(2, parametres.actives());
            ps.setObject(3, parametres.heureRappelVeille());
            ps.setInt(4, parametres.delaiRelanceHeures());
            ps.setInt(5, parametres.ancienneteEchangeJours());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save notification parameters", e);
        }
    }

    /* ------------------------- Organisation's contact ------------------------ */

    public ContactOrganisation getContactOrganisation() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT telephone, email
                        FROM contact_organisation
                        WHERE edition_id = ?""");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ContactOrganisation(rs.getString("telephone"), rs.getString("email"));
            }
            return ContactOrganisation.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the organisation's contact", e);
        }
    }

    public void saveContactOrganisation(ContactOrganisation contact) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO contact_organisation (edition_id, telephone, email)
                        VALUES (?, ?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET telephone = EXCLUDED.telephone,
                        email = EXCLUDED.email""")) {
            ps.setString(2, contact.telephone());
            ps.setString(3, contact.email());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save the organisation's contact", e);
        }
    }

    /* ---------------------------- Constraint toggles ------------------------- */

    /**
     * The state this edition has <b>explicitly</b> chosen, per constraint name.
     * A name absent from the map is one nobody touched: it follows the
     * catalogue's default, which {@code ParametresService} is what applies —
     * this layer stores rows, it does not know the catalogue.
     */
    public Map<String, Boolean> getEtatsContraintes() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(
                        connection, "SELECT nom, actif FROM constraint_toggle WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            Map<String, Boolean> etats = new LinkedHashMap<>();
            while (rs.next()) {
                etats.put(rs.getString("nom"), rs.getBoolean("actif"));
            }
            return etats;
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
                PreparedStatement ps = scope.prepareScoped(
                        connection, "SELECT nom, poids FROM ponderation_contrainte WHERE edition_id = ?");
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
     * back to the configured default. {@code change} is handed the override
     * stored before this write — {@code null} when there was none — and what it
     * returns, when not {@code null}, is written to the weight history.
     *
     * <p>Both in one transaction, the read included and serialised with any
     * other write of this rule's weight: a history line for a write that
     * rolled back would describe a dosage that never existed, and a
     * before-value read outside the transaction could be one a concurrent
     * write had already replaced.</p>
     */
    public void setConstraintWeight(String nom, Integer poids, Function<Integer, WeightChange> change) {
        scope.write("Failed to save constraint weight", connection -> {
            lockRule(connection, "ponderation_contrainte", nom);
            Integer stored;
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "SELECT poids FROM ponderation_contrainte WHERE edition_id = ? AND nom = ?")) {
                ps.setString(2, nom);
                try (ResultSet rs = ps.executeQuery()) {
                    stored = rs.next() ? rs.getInt("poids") : null;
                }
            }
            if (poids == null) {
                try (PreparedStatement ps = scope.prepareScoped(
                        connection, "DELETE FROM ponderation_contrainte WHERE edition_id = ? AND nom = ?")) {
                    ps.setString(2, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO ponderation_contrainte (edition_id, nom, poids)
                        VALUES (?, ?, ?)
                        ON CONFLICT (edition_id, nom)
                        DO UPDATE SET poids = EXCLUDED.poids""")) {
                    ps.setString(2, nom);
                    ps.setInt(3, poids);
                    ps.executeUpdate();
                }
            }
            WeightChange line = change.apply(stored);
            if (line != null) {
                history.insert(connection, line);
            }
        });
    }

    /**
     * Records the state this edition chose for one constraint, or drops the
     * row when {@code actif} is {@code null} — the constraint then falls back
     * to the catalogue's default, same convention as
     * {@link #setConstraintWeight}. The table is a state, not an audit log
     * (see migration V39); what changed, and when, is what {@code change}
     * returns from the state stored before — read, like the weight's, inside
     * the transaction that writes.
     */
    public void setEtatContrainte(String nom, Boolean actif, Function<Boolean, WeightChange> change) {
        scope.write("Failed to save constraint toggle", connection -> {
            lockRule(connection, "constraint_toggle", nom);
            Boolean stored;
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "SELECT actif FROM constraint_toggle WHERE edition_id = ? AND nom = ?")) {
                ps.setString(2, nom);
                try (ResultSet rs = ps.executeQuery()) {
                    stored = rs.next() ? rs.getBoolean("actif") : null;
                }
            }
            if (actif == null) {
                try (PreparedStatement ps = scope.prepareScoped(
                        connection, "DELETE FROM constraint_toggle WHERE edition_id = ? AND nom = ?")) {
                    ps.setString(2, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = scope.prepareScoped(connection, """
                        INSERT INTO constraint_toggle (edition_id, nom, actif)
                        VALUES (?, ?, ?)
                        ON CONFLICT (edition_id, nom)
                        DO UPDATE SET actif = EXCLUDED.actif""")) {
                    ps.setString(2, nom);
                    ps.setBoolean(3, actif);
                    ps.executeUpdate();
                }
            }
            WeightChange line = change.apply(stored);
            if (line != null) {
                history.insert(connection, line);
            }
        });
    }

    /**
     * Serialises the writes of one rule's weight (or toggle) in this edition
     * until the transaction ends. A row lock would not do: the first override
     * of a rule has no row to lock yet, and two such writes would both read
     * « nothing stored ».
     */
    private void lockRule(Connection connection, String table, String nom) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            ps.setString(1, table + '/' + scope.editionId() + '/' + nom);
            ps.execute();
        }
    }

    /** Writes lines to the weight history alone — the dosage an edition inherited by duplication. */
    public void recordHistory(List<WeightChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        scope.write("Failed to record the weight history", connection -> {
            for (WeightChange change : changes) {
                history.insert(connection, change);
            }
        });
    }
}
