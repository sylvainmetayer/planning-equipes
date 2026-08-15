package dev.sylvain.planning.service;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.ReferenceDataService.TypologieItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Direct JDBC persistence for the reference model (stands, timeslots, animators,
 * typologies and ad hoc constraints). Every admin CRUD operation writes straight
 * to PostgreSQL through this repository, and reads reconstruct fully-hydrated
 * domain objects (skills, off-days, stand typologies, constraint targets).
 */
@ApplicationScoped
public class ReferenceDataRepository {

    @Inject
    DataSource dataSource;

    /**
     * Numeric-aware id order ("A2" before "A10"), unlike SQL's {@code ORDER BY id}
     * which sorts ids as plain text ("A1", "A10", "A100", "A101", ..., "A11", ...).
     * Every list read here is re-sorted with it in Java: besides being confusing
     * wherever these lists reach the UI, that scrambled order also becomes the
     * solver's animateurRange value order, and the local search (fixed random
     * seed) is highly sensitive to it — an alphabetically-scrambled animateur
     * list measurably slowed convergence on scenario-complet.yaml (~25s to
     * hard-feasible with a natural order vs. not even converging within the full
     * 180s production time budget with the raw SQL order).
     */
    private static final Comparator<String> NATURAL_ID_ORDER = ReferenceDataRepository::compareNatural;

    private static final Pattern ID_CHUNK = Pattern.compile("(\\d+)|(\\D+)");

    private static int compareNatural(String a, String b) {
        Matcher ma = ID_CHUNK.matcher(a);
        Matcher mb = ID_CHUNK.matcher(b);
        while (ma.find() && mb.find()) {
            String chunkA = ma.group();
            String chunkB = mb.group();
            int comparison = Character.isDigit(chunkA.charAt(0)) && Character.isDigit(chunkB.charAt(0))
                    ? new BigInteger(chunkA).compareTo(new BigInteger(chunkB))
                    : chunkA.compareTo(chunkB);
            if (comparison != 0) {
                return comparison;
            }
        }
        return a.length() - b.length();
    }

    /* ------------------------------- Stands -------------------------------- */

    public List<Stand> listStands() {
        Map<String, Stand> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT s.id, s.nom, s.effectif_min, s.effectif_max, s.reserve_majeurs, s.premium, s.niveau_effort, "
                            + "e.id AS emplacement_id, e.nom AS emplacement_nom, e.latitude AS emplacement_latitude, "
                            + "e.longitude AS emplacement_longitude "
                            + "FROM stand s LEFT JOIN emplacement e ON e.id = s.emplacement_id ORDER BY s.id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = new Stand();
                    stand.setId(rs.getString("id"));
                    stand.setNom(rs.getString("nom"));
                    stand.setEffectifMin(rs.getInt("effectif_min"));
                    stand.setEffectifMax(rs.getInt("effectif_max"));
                    stand.setReserveMajeurs(rs.getBoolean("reserve_majeurs"));
                    stand.setPremium(rs.getBoolean("premium"));
                    stand.setNiveauEffort(NiveauEffort.valueOf(rs.getString("niveau_effort")));
                    String emplacementId = rs.getString("emplacement_id");
                    if (emplacementId != null) {
                        stand.setEmplacement(new Emplacement(emplacementId, rs.getString("emplacement_nom"),
                                (Double) rs.getObject("emplacement_latitude"),
                                (Double) rs.getObject("emplacement_longitude")));
                    }
                    byId.put(stand.getId(), stand);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT stand_id, typologie FROM stand_typologie");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getTypologiesProposees().add(rs.getString("typologie"));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif "
                            + "FROM stand_indisponibilite ORDER BY id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getIndisponibilites().add(new IndisponibiliteStand(
                                rs.getLong("id"),
                                rs.getObject("date_indisponibilite", LocalDate.class),
                                rs.getObject("heure_debut", LocalTime.class),
                                rs.getObject("heure_fin", LocalTime.class),
                                rs.getString("motif")));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, stand_id, date_ouverture, heure_debut, heure_fin, motif "
                            + "FROM stand_ouverture ORDER BY id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getOuvertures().add(new OuvertureStand(
                                rs.getLong("id"),
                                rs.getObject("date_ouverture", LocalDate.class),
                                rs.getObject("heure_debut", LocalTime.class),
                                rs.getObject("heure_fin", LocalTime.class),
                                rs.getString("motif")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list stands", e);
        }
        List<Stand> stands = new ArrayList<>(byId.values());
        stands.sort(Comparator.comparing(Stand::getId, NATURAL_ID_ORDER));
        return stands;
    }

    public boolean standExists(String id) {
        return exists("stand", id);
    }

    public void saveStand(Stand stand) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertStand(connection, stand);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save stand " + stand.getId(), e);
        }
    }

    public void deleteStand(String id) {
        delete("DELETE FROM stand WHERE id = ?", id);
    }

    private void upsertEmplacementTx(Connection connection, Emplacement emplacement) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO emplacement (id, nom, latitude, longitude) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom, latitude = EXCLUDED.latitude, "
                        + "longitude = EXCLUDED.longitude")) {
            ps.setString(1, emplacement.getId());
            ps.setString(2, emplacement.getNom());
            ps.setObject(3, emplacement.getLatitude());
            ps.setObject(4, emplacement.getLongitude());
            ps.executeUpdate();
        }
    }

    private void upsertStand(Connection connection, Stand stand) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO stand (id, nom, effectif_min, effectif_max, reserve_majeurs, premium, emplacement_id, "
                        + "niveau_effort) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min, "
                        + "effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs, "
                        + "premium = EXCLUDED.premium, emplacement_id = EXCLUDED.emplacement_id, "
                        + "niveau_effort = EXCLUDED.niveau_effort")) {
            ps.setString(1, stand.getId());
            ps.setString(2, stand.getNom());
            ps.setInt(3, stand.getEffectifMin());
            ps.setInt(4, stand.getEffectifMax());
            ps.setBoolean(5, stand.isReserveMajeurs());
            ps.setBoolean(6, stand.isPremium());
            ps.setString(7, stand.getEmplacement() != null ? stand.getEmplacement().getId() : null);
            ps.setString(8, stand.getNiveauEffort().name());
            ps.executeUpdate();
        }
        try (PreparedStatement del = connection.prepareStatement("DELETE FROM stand_typologie WHERE stand_id = ?")) {
            del.setString(1, stand.getId());
            del.executeUpdate();
        }
        if (stand.getTypologiesProposees() != null && !stand.getTypologiesProposees().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO stand_typologie (stand_id, typologie) VALUES (?, ?)")) {
                for (String typologie : stand.getTypologiesProposees()) {
                    ins.setString(1, stand.getId());
                    ins.setString(2, typologie);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM stand_indisponibilite WHERE stand_id = ?")) {
            del.setString(1, stand.getId());
            del.executeUpdate();
        }
        if (stand.getIndisponibilites() != null && !stand.getIndisponibilites().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO stand_indisponibilite (stand_id, date_indisponibilite, heure_debut, heure_fin, motif) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
                    ins.setString(1, stand.getId());
                    ins.setObject(2, indispo.getDate());
                    ins.setObject(3, indispo.getHeureDebut());
                    ins.setObject(4, indispo.getHeureFin());
                    ins.setString(5, indispo.getMotif());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM stand_ouverture WHERE stand_id = ?")) {
            del.setString(1, stand.getId());
            del.executeUpdate();
        }
        if (stand.getOuvertures() != null && !stand.getOuvertures().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO stand_ouverture (stand_id, date_ouverture, heure_debut, heure_fin, motif) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                for (OuvertureStand ouverture : stand.getOuvertures()) {
                    ins.setString(1, stand.getId());
                    ins.setObject(2, ouverture.getDate());
                    ins.setObject(3, ouverture.getHeureDebut());
                    ins.setObject(4, ouverture.getHeureFin());
                    ins.setString(5, ouverture.getMotif());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    /* ---------------------------- Emplacements ------------------------------ */

    public List<Emplacement> listEmplacements() {
        List<Emplacement> emplacements = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, nom, latitude, longitude FROM emplacement ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                emplacements.add(new Emplacement(rs.getString("id"), rs.getString("nom"),
                        (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list emplacements", e);
        }
        return emplacements;
    }

    public boolean emplacementExists(String id) {
        return exists("emplacement", id);
    }

    public void saveEmplacement(Emplacement emplacement) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO emplacement (id, nom, latitude, longitude) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom, latitude = EXCLUDED.latitude, "
                                + "longitude = EXCLUDED.longitude")) {
            ps.setString(1, emplacement.getId());
            ps.setString(2, emplacement.getNom());
            ps.setObject(3, emplacement.getLatitude());
            ps.setObject(4, emplacement.getLongitude());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save emplacement " + emplacement.getId(), e);
        }
    }

    public void deleteEmplacement(String id) {
        delete("DELETE FROM emplacement WHERE id = ?", id);
    }

    /* ------------------------------ Timeslots ------------------------------ */

    private static final String SELECT_CRENEAU_SQL =
            "SELECT c.id, c.date_creneau, c.heure_debut, c.heure_fin, c.famille, "
                    + "g.id AS groupe_id, g.nom AS groupe_nom, g.actif AS groupe_actif "
                    + "FROM creneau c JOIN groupe_creneau g ON g.id = c.groupe_creneau_id";

    public List<Creneau> listCreneaux() {
        return listCreneaux(SELECT_CRENEAU_SQL + " ORDER BY c.id");
    }

    /** Timeslots of the currently active group only — what the solver builds its problem from. */
    public List<Creneau> listCreneauxGroupeActif() {
        return listCreneaux(SELECT_CRENEAU_SQL + " WHERE g.actif ORDER BY c.id");
    }

    /** Timeslots of one specific group, active or not — used by the découpage generator to read a source "amplitudes" group. */
    public List<Creneau> listCreneauxParGroupe(String groupeId) {
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    SELECT_CRENEAU_SQL + " WHERE g.id = ? ORDER BY c.id")) {
                ps.setString(1, groupeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Creneau creneau = new Creneau(
                                rs.getLong("id"),
                                0,
                                rs.getObject("date_creneau", LocalDate.class),
                                rs.getObject("heure_debut", LocalTime.class),
                                rs.getObject("heure_fin", LocalTime.class));
                        creneau.setFamille(rs.getInt("famille"));
                        creneau.setGroupe(new GroupeCreneau(
                                rs.getString("groupe_id"), rs.getString("groupe_nom"), rs.getBoolean("groupe_actif")));
                        byId.put(creneau.getId(), creneau);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslots for group " + groupeId, e);
        }
        List<Creneau> creneaux = new ArrayList<>(byId.values());
        Creneau.assignerJours(creneaux);
        creneaux.sort(Comparator.comparingInt(Creneau::getJour)
                .thenComparing(Creneau::getHeureDebut, Comparator.nullsLast(Comparator.naturalOrder())));
        return creneaux;
    }

    /**
     * Replaces every créneau of one specific group (source-amplitudes or
     * generated-vacations), leaving every other group untouched. Used by the
     * découpage generator to (re)materialize a target group's vacations from
     * a source group's amplitudes.
     */
    public void replaceCreneauxDuGroupe(String groupeId, List<Creneau> creneaux) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM poste_affectation WHERE creneau_id IN "
                                + "(SELECT id FROM creneau WHERE groupe_creneau_id = ?)")) {
                    ps.setString(1, groupeId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM creneau WHERE groupe_creneau_id = ?")) {
                    ps.setString(1, groupeId);
                    ps.executeUpdate();
                }
                GroupeCreneau groupe = new GroupeCreneau(groupeId, null, false);
                for (Creneau creneau : creneaux) {
                    creneau.setId(null);
                    creneau.setGroupe(groupe);
                    insertCreneauTx(connection, creneau);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to replace timeslots for group " + groupeId, e);
        }
    }

    private List<Creneau> listCreneaux(String sql) {
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Creneau creneau = new Creneau(
                            rs.getLong("id"),
                            0,
                            rs.getObject("date_creneau", LocalDate.class),
                            rs.getObject("heure_debut", LocalTime.class),
                            rs.getObject("heure_fin", LocalTime.class));
                    creneau.setFamille(rs.getInt("famille"));
                    creneau.setGroupe(new GroupeCreneau(
                            rs.getString("groupe_id"), rs.getString("groupe_nom"), rs.getBoolean("groupe_actif")));
                    byId.put(creneau.getId(), creneau);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslots", e);
        }
        // `jour` is never stored — it's computed per group from each group's
        // earliest date (see Creneau.assignerJours), so consecutive calendar
        // days always yield consecutive day numbers even across a gap day.
        Map<String, List<Creneau>> parGroupe = new LinkedHashMap<>();
        for (Creneau creneau : byId.values()) {
            parGroupe.computeIfAbsent(creneau.getGroupe().getId(), k -> new ArrayList<>()).add(creneau);
        }
        parGroupe.values().forEach(Creneau::assignerJours);
        // Chronological order (day, then start time), not `ORDER BY id`: ids like
        // J1.../J10... sort lexicographically ("J10-MATIN" before "J2-MATIN"), and
        // even within one day "APREM"/"MATIN"/"SOIREE" sort alphabetically instead
        // of morning-afternoon-evening. Besides being confusing wherever this list
        // feeds the UI, that scrambled order became the solver's animateurRange-
        // adjacent value order too, and a fixed-seed search is highly sensitive to
        // it — this scenario went from converging in ~25s to stalling short of
        // hard-feasibility within the full 180s production budget.
        List<Creneau> creneaux = new ArrayList<>(byId.values());
        creneaux.sort(Comparator.comparingInt(Creneau::getJour)
                .thenComparing(Creneau::getHeureDebut, Comparator.nullsLast(Comparator.naturalOrder())));
        return creneaux;
    }

    public boolean creneauExists(Long id) {
        return existsLong("creneau", id);
    }

    /** Inserts a new timeslot; the database generates its id, which is set back onto {@code creneau}. */
    public Creneau insertCreneau(Creneau creneau) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertCreneauTx(connection, creneau);
                connection.commit();
                return creneau;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save timeslot", e);
        }
    }

    /** Updates an existing timeslot in place; its id is left untouched. */
    public void updateCreneau(Creneau creneau) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                updateCreneauTx(connection, creneau);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save timeslot " + creneau.getId(), e);
        }
    }

    public void deleteCreneau(Long id) {
        deleteLong("DELETE FROM creneau WHERE id = ?", id);
    }

    /* -------------------------- Timeslot groups ----------------------------- */

    public List<GroupeCreneau> listGroupesCreneaux() {
        List<GroupeCreneau> groupes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, nom, actif, groupe_source_id FROM groupe_creneau ORDER BY nom");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groupes.add(new GroupeCreneau(rs.getString("id"), rs.getString("nom"), rs.getBoolean("actif"),
                        rs.getString("groupe_source_id")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslot groups", e);
        }
        return groupes;
    }

    public boolean groupeCreneauExists(String id) {
        return exists("groupe_creneau", id);
    }

    /**
     * Upserts id/nom/groupeSourceId only — {@code actif} is never touched
     * here, see {@link #activerGroupeCreneau(String)}.
     */
    public void saveGroupeCreneau(GroupeCreneau groupe) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO groupe_creneau (id, nom, actif, groupe_source_id) VALUES (?, ?, FALSE, ?) "
                                + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom, "
                                + "groupe_source_id = EXCLUDED.groupe_source_id")) {
            ps.setString(1, groupe.getId());
            ps.setString(2, groupe.getNom());
            ps.setString(3, groupe.getGroupeSourceId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save timeslot group " + groupe.getId(), e);
        }
    }

    /**
     * Activates the given group and deactivates every other one, in a single
     * transaction (deactivate-then-activate order, so the partial unique index
     * on {@code actif} is never violated in between).
     */
    public void activerGroupeCreneau(String id) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE groupe_creneau SET actif = FALSE")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE groupe_creneau SET actif = TRUE WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to activate timeslot group " + id, e);
        }
    }

    public void deleteGroupeCreneau(String id) {
        delete("DELETE FROM groupe_creneau WHERE id = ?", id);
    }

    /* ------------------------------ Animateurs ----------------------------- */

    public List<Animateur> listAnimateurs() {
        Map<String, Animateur> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, prenom, nom, date_naissance, manager FROM animateur ORDER BY id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = new Animateur(
                            rs.getString("id"),
                            rs.getString("prenom"),
                            rs.getString("nom"),
                            rs.getObject("date_naissance", LocalDate.class),
                            rs.getBoolean("manager"));
                    byId.put(animateur.getId(), animateur);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT animateur_id, typologie, niveau FROM animateur_competence");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getCompetences().put(
                                rs.getString("typologie"),
                                NiveauCompetence.valueOf(rs.getString("niveau")));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT animateur_id, jour FROM animateur_jour_indispo");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getJoursIndisponibles().add(rs.getObject("jour", LocalDate.class));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT animateur_id, typologie FROM animateur_souhait");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getSouhaits().add(rs.getString("typologie"));
                    }
                }
            }
            // Polyvalence is carried by the referential, not by the animateur row:
            // holding the ninja typologie is what makes an animateur dispatchable
            // on any stand, so the flag is derived here once competences are known.
            String typologieNinja = null;
            try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM typologie WHERE ninja LIMIT 1");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    typologieNinja = rs.getString("id");
                }
            }
            for (Animateur animateur : byId.values()) {
                animateur.appliquerTypologieNinja(typologieNinja);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list animators", e);
        }
        List<Animateur> animateurs = new ArrayList<>(byId.values());
        animateurs.sort(Comparator.comparing(Animateur::getId, NATURAL_ID_ORDER));
        return animateurs;
    }

    public boolean animateurExists(String id) {
        return exists("animateur", id);
    }

    public void saveAnimateur(Animateur animateur) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertAnimateur(connection, animateur);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save animator " + animateur.getId(), e);
        }
    }

    public void deleteAnimateur(String id) {
        delete("DELETE FROM animateur WHERE id = ?", id);
    }

    private void upsertAnimateur(Connection connection, Animateur animateur) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO animateur (id, prenom, nom, date_naissance, manager) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, "
                        + "date_naissance = EXCLUDED.date_naissance, manager = EXCLUDED.manager")) {
            ps.setString(1, animateur.getId());
            ps.setString(2, animateur.getPrenom());
            ps.setString(3, animateur.getNom());
            ps.setObject(4, animateur.getDateNaissance());
            ps.setBoolean(5, animateur.isManager());
            ps.executeUpdate();
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM animateur_competence WHERE animateur_id = ?")) {
            del.setString(1, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getCompetences() != null && !animateur.getCompetences().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO animateur_competence (animateur_id, typologie, niveau) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, NiveauCompetence> entry : animateur.getCompetences().entrySet()) {
                    ins.setString(1, animateur.getId());
                    ins.setString(2, entry.getKey());
                    ins.setString(3, entry.getValue().name());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM animateur_jour_indispo WHERE animateur_id = ?")) {
            del.setString(1, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getJoursIndisponibles() != null && !animateur.getJoursIndisponibles().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO animateur_jour_indispo (animateur_id, jour) VALUES (?, ?)")) {
                for (LocalDate jour : animateur.getJoursIndisponibles()) {
                    ins.setString(1, animateur.getId());
                    ins.setObject(2, jour);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM animateur_souhait WHERE animateur_id = ?")) {
            del.setString(1, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getSouhaits() != null && !animateur.getSouhaits().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO animateur_souhait (animateur_id, typologie) VALUES (?, ?)")) {
                for (String typologie : animateur.getSouhaits()) {
                    ins.setString(1, animateur.getId());
                    ins.setString(2, typologie);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    /* ------------------------------ Typologies ----------------------------- */

    public List<TypologieItem> listTypologies() {
        List<TypologieItem> typologies = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection
                        .prepareStatement("SELECT id, label, ninja FROM typologie ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                typologies.add(new TypologieItem(rs.getString("id"), rs.getString("label"), rs.getBoolean("ninja")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list typologies", e);
        }
        return typologies;
    }

    /** Id of the single typologie flagged ninja, empty when the referential has none. */
    public Optional<String> findTypologieNinja() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT id FROM typologie WHERE ninja LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString("id")) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the ninja typology", e);
        }
    }

    public boolean typologieExists(String id) {
        return exists("typologie", id);
    }

    public void saveTypologie(TypologieItem typologie) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Only one typologie may be ninja: demote the previous holder in the
                // same transaction, otherwise the partial unique index of V30 rejects
                // the insert and the user sees a raw constraint violation.
                if (typologie.ninja()) {
                    try (PreparedStatement ps = connection
                            .prepareStatement("UPDATE typologie SET ninja = FALSE WHERE ninja AND id <> ?")) {
                        ps.setString(1, typologie.id());
                        ps.executeUpdate();
                    }
                }
                upsertTypologie(connection, typologie);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save typology " + typologie.id(), e);
        }
    }

    public void deleteTypologie(String id) {
        delete("DELETE FROM typologie WHERE id = ?", id);
    }

    public boolean typologieEnUsage(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 WHERE EXISTS (SELECT 1 FROM stand_typologie WHERE typologie = ?) "
                                + "OR EXISTS (SELECT 1 FROM animateur_competence WHERE typologie = ?) "
                                + "OR EXISTS (SELECT 1 FROM animateur_souhait WHERE typologie = ?)")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setString(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check typologie usage " + id, e);
        }
    }

    private void upsertTypologie(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO typologie (id, label, ninja) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label, ninja = EXCLUDED.ninja")) {
            ps.setString(1, typologie.id());
            ps.setString(2, typologie.label());
            ps.setBoolean(3, typologie.ninja());
            ps.executeUpdate();
        }
    }

    /**
     * Upsert used for the typologies {@link #importFromPlanning} derives from the
     * ids stands and animateurs reference. Unlike {@link #upsertTypologie} it
     * leaves {@code ninja} alone: an import must not silently demote the ninja
     * typologie just because the derived item carries the default {@code false}.
     */
    private void upsertTypologieDerivee(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO typologie (id, label, ninja) VALUES (?, ?, FALSE) "
                        + "ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label")) {
            ps.setString(1, typologie.id());
            ps.setString(2, typologie.label());
            ps.executeUpdate();
        }
    }

    /* --------------------------- Ad hoc constraints ------------------------ */

    public List<ContrainteAdHoc> listContraintes() {
        Map<String, ContrainteAdHoc> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, type, creneau_id, stand_id, raison, cree_par, cree_le FROM contrainte_ad_hoc ORDER BY id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ContrainteAdHoc contrainte = new ContrainteAdHoc(
                            rs.getString("id"), TypeContrainteAdHoc.valueOf(rs.getString("type")));
                    long creneauId = rs.getLong("creneau_id");
                    if (!rs.wasNull()) {
                        Creneau creneau = new Creneau();
                        creneau.setId(creneauId);
                        contrainte.setCreneau(creneau);
                    }
                    String standId = rs.getString("stand_id");
                    if (standId != null) {
                        Stand stand = new Stand();
                        stand.setId(standId);
                        contrainte.setStand(stand);
                    }
                    contrainte.setRaison(rs.getString("raison"));
                    contrainte.setCreeParUtilisateurId(rs.getString("cree_par"));
                    Timestamp creeLe = rs.getTimestamp("cree_le");
                    contrainte.setCreeLe(creeLe != null ? creeLe.toInstant() : null);
                    byId.put(contrainte.getId(), contrainte);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT contrainte_id, animateur_id FROM contrainte_animateur ORDER BY contrainte_id, position");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ContrainteAdHoc contrainte = byId.get(rs.getString("contrainte_id"));
                    if (contrainte != null) {
                        Animateur animateur = new Animateur();
                        animateur.setId(rs.getString("animateur_id"));
                        contrainte.getAnimateursConcernes().add(animateur);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list ad hoc constraints", e);
        }
        return new ArrayList<>(byId.values());
    }

    public void saveContrainte(ContrainteAdHoc contrainte) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertContrainte(connection, contrainte);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save constraint " + contrainte.getId(), e);
        }
    }

    public void deleteContrainte(String id) {
        delete("DELETE FROM contrainte_ad_hoc WHERE id = ?", id);
    }

    private void upsertContrainte(Connection connection, ContrainteAdHoc contrainte) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO contrainte_ad_hoc (id, type, creneau_id, stand_id, raison, cree_par, cree_le) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, "
                        + "creneau_id = EXCLUDED.creneau_id, stand_id = EXCLUDED.stand_id, raison = EXCLUDED.raison, "
                        + "cree_par = EXCLUDED.cree_par, cree_le = EXCLUDED.cree_le")) {
            ps.setString(1, contrainte.getId());
            ps.setString(2, contrainte.getType() != null ? contrainte.getType().name() : null);
            ps.setObject(3, contrainte.getCreneau() != null ? contrainte.getCreneau().getId() : null);
            ps.setString(4, contrainte.getStand() != null ? contrainte.getStand().getId() : null);
            ps.setString(5, contrainte.getRaison());
            ps.setString(6, contrainte.getCreeParUtilisateurId());
            Instant creeLe = contrainte.getCreeLe() != null ? contrainte.getCreeLe() : Instant.now();
            ps.setTimestamp(7, Timestamp.from(creeLe));
            ps.executeUpdate();
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM contrainte_animateur WHERE contrainte_id = ?")) {
            del.setString(1, contrainte.getId());
            del.executeUpdate();
        }
        List<Animateur> cibles = contrainte.getAnimateursConcernes();
        if (cibles != null && !cibles.isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO contrainte_animateur (contrainte_id, animateur_id, position) VALUES (?, ?, ?)")) {
                int position = 0;
                for (Animateur animateur : cibles) {
                    if (animateur == null || animateur.getId() == null) {
                        continue;
                    }
                    ins.setString(1, contrainte.getId());
                    ins.setString(2, animateur.getId());
                    ins.setInt(3, position++);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    /* --------------------------- Legal parameters --------------------------- */

    public ParametresLegaux getParametresLegaux() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT duree_hebdomadaire_max_minutes, duree_hebdomadaire_max_mineur_minutes, "
                                + "pause_minimale_entre_vacations_minutes, repos_quotidien_minimal_minutes "
                                + "FROM parametres_legaux WHERE id = 1");
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
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO parametres_legaux (id, duree_hebdomadaire_max_minutes, "
                                + "duree_hebdomadaire_max_mineur_minutes, pause_minimale_entre_vacations_minutes, "
                                + "repos_quotidien_minimal_minutes) VALUES (1, ?, ?, ?, ?) "
                                + "ON CONFLICT (id) DO UPDATE SET "
                                + "duree_hebdomadaire_max_minutes = EXCLUDED.duree_hebdomadaire_max_minutes, "
                                + "duree_hebdomadaire_max_mineur_minutes = "
                                + "EXCLUDED.duree_hebdomadaire_max_mineur_minutes, "
                                + "pause_minimale_entre_vacations_minutes = "
                                + "EXCLUDED.pause_minimale_entre_vacations_minutes, "
                                + "repos_quotidien_minimal_minutes = EXCLUDED.repos_quotidien_minimal_minutes")) {
            ps.setInt(1, parametres.getDureeHebdomadaireMaxMinutes());
            ps.setInt(2, parametres.getDureeHebdomadaireMaxMineurMinutes());
            ps.setInt(3, parametres.getPauseMinimaleEntreVacationsMinutes());
            ps.setInt(4, parametres.getReposQuotidienMinimalMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save legal parameters", e);
        }
    }

    /* --------------------------- Découpage parameters ------------------------ */

    public ParametresDecoupage getParametresDecoupage() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT duree_vacation_cible_minutes, duree_vacation_min_minutes, duree_vacation_max_minutes, "
                                + "duree_chevauchement_minutes, duree_pause_repas_minutes, fenetre_repas_midi_debut, "
                                + "fenetre_repas_midi_fin, fenetre_repas_soir_debut, fenetre_repas_soir_fin, "
                                + "strategie_couverture_pendant_pause, nombre_familles_decalage, "
                                + "duree_decalage_max_minutes FROM parametres_decoupage WHERE id = 1");
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
                        ParametresDecoupage.StrategieCouverturePendantPause
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
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO parametres_decoupage (id, duree_vacation_cible_minutes, "
                                + "duree_vacation_min_minutes, duree_vacation_max_minutes, duree_chevauchement_minutes, "
                                + "duree_pause_repas_minutes, fenetre_repas_midi_debut, fenetre_repas_midi_fin, "
                                + "fenetre_repas_soir_debut, fenetre_repas_soir_fin, strategie_couverture_pendant_pause, "
                                + "nombre_familles_decalage, duree_decalage_max_minutes) "
                                + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                                + "duree_vacation_cible_minutes = EXCLUDED.duree_vacation_cible_minutes, "
                                + "duree_vacation_min_minutes = EXCLUDED.duree_vacation_min_minutes, "
                                + "duree_vacation_max_minutes = EXCLUDED.duree_vacation_max_minutes, "
                                + "duree_chevauchement_minutes = EXCLUDED.duree_chevauchement_minutes, "
                                + "duree_pause_repas_minutes = EXCLUDED.duree_pause_repas_minutes, "
                                + "fenetre_repas_midi_debut = EXCLUDED.fenetre_repas_midi_debut, "
                                + "fenetre_repas_midi_fin = EXCLUDED.fenetre_repas_midi_fin, "
                                + "fenetre_repas_soir_debut = EXCLUDED.fenetre_repas_soir_debut, "
                                + "fenetre_repas_soir_fin = EXCLUDED.fenetre_repas_soir_fin, "
                                + "strategie_couverture_pendant_pause = EXCLUDED.strategie_couverture_pendant_pause, "
                                + "nombre_familles_decalage = EXCLUDED.nombre_familles_decalage, "
                                + "duree_decalage_max_minutes = EXCLUDED.duree_decalage_max_minutes")) {
            ps.setInt(1, parametres.getDureeVacationCibleMinutes());
            ps.setInt(2, parametres.getDureeVacationMinMinutes());
            ps.setInt(3, parametres.getDureeVacationMaxMinutes());
            ps.setInt(4, parametres.getDureeChevauchementMinutes());
            ps.setInt(5, parametres.getDureePauseRepasMinutes());
            ps.setObject(6, parametres.getFenetreRepasMidiDebut());
            ps.setObject(7, parametres.getFenetreRepasMidiFin());
            ps.setObject(8, parametres.getFenetreRepasSoirDebut());
            ps.setObject(9, parametres.getFenetreRepasSoirFin());
            ps.setString(10, parametres.getStrategieCouverturePendantPause().name());
            ps.setInt(11, parametres.getNombreFamillesDecalage());
            ps.setInt(12, parametres.getDureeDecalageMaxMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save découpage parameters", e);
        }
    }

    /* ---------------------------- Solver parameters --------------------------- */

    public ParametresSolveur getParametresSolveur() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection
                        .prepareStatement("SELECT duree_resolution_secondes FROM parametres_solveur WHERE id = 1");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ParametresSolveur(rs.getInt("duree_resolution_secondes"));
            }
            return new ParametresSolveur();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load solver parameters", e);
        }
    }

    public void saveParametresSolveur(ParametresSolveur parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO parametres_solveur (id, duree_resolution_secondes) VALUES (1, ?) "
                                + "ON CONFLICT (id) DO UPDATE SET "
                                + "duree_resolution_secondes = EXCLUDED.duree_resolution_secondes")) {
            ps.setInt(1, parametres.getDureeResolutionSecondes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save solver parameters", e);
        }
    }

    /* ---------------------------- Constraint toggles ------------------------- */

    public java.util.Set<String> getContraintesDesactivees() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT nom FROM constraint_toggle");
                ResultSet rs = ps.executeQuery()) {
            java.util.Set<String> desactivees = new java.util.HashSet<>();
            while (rs.next()) {
                desactivees.add(rs.getString("nom"));
            }
            return desactivees;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load constraint toggles", e);
        }
    }

    /**
     * Disabling a constraint records <i>who</i> (as reported by the client),
     * <i>when</i> and <i>why</i> alongside the toggle — see migration V16 and
     * constat C2 of the RH compliance audit. Re-enabling simply drops the row,
     * so the trace of a past disabling does not survive its cancellation: this
     * is a toggle table, not an audit log.
     */
    public void setContrainteActive(String nom, boolean actif, String motif, String utilisateurId) {
        try (Connection connection = dataSource.getConnection()) {
            if (actif) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM constraint_toggle WHERE nom = ?")) {
                    ps.setString(1, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO constraint_toggle (nom, motif, modifie_par_utilisateur_id, modifie_le) "
                                + "VALUES (?, ?, ?, now()) ON CONFLICT (nom) DO UPDATE SET "
                                + "motif = EXCLUDED.motif, "
                                + "modifie_par_utilisateur_id = EXCLUDED.modifie_par_utilisateur_id, "
                                + "modifie_le = EXCLUDED.modifie_le")) {
                    ps.setString(1, nom);
                    ps.setString(2, motif);
                    ps.setString(3, utilisateurId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save constraint toggle", e);
        }
    }

    /* -------------------------------- Import ------------------------------- */

    /**
     * Replaces the reference dataset with the one carried by a planning (used
     * by the "Load sample" action and by generic reference-data import).
     * Stands and animateurs are global and always fully replaced; timeslots
     * are not — only the currently active {@link GroupeCreneau}'s créneaux are
     * cleared and reloaded, so a scenario can be imported into one group
     * (e.g. an alternate planning) without wiping out the créneaux other
     * groups already hold. Each imported créneau receives a freshly
     * DB-generated id (ids are a numeric identity column, so collisions with
     * another group's créneaux are structurally impossible); ad hoc
     * constraints referencing one of those créneaux by its original
     * (scenario-local) id are remapped to the new generated id accordingly.
     * Runs in a single transaction.
     */
    public void importFromPlanning(PlanningFestival planning) {
        if (planning == null) {
            return;
        }
        Map<String, Stand> standsById = new LinkedHashMap<>();
        Map<Long, Creneau> creneauxById = new LinkedHashMap<>();
        if (planning.getPostes() != null) {
            for (PosteAffectation poste : planning.getPostes()) {
                if (poste.getStand() != null) {
                    standsById.putIfAbsent(poste.getStand().getId(), poste.getStand());
                }
                if (poste.getCreneau() != null) {
                    creneauxById.putIfAbsent(poste.getCreneau().getId(), poste.getCreneau());
                }
            }
        }
        List<Animateur> animateurs = planning.getAnimateurs() != null ? planning.getAnimateurs() : List.of();
        List<ContrainteAdHoc> contraintes = planning.getContraintesAdHoc() != null
                ? planning.getContraintesAdHoc()
                : List.of();

        try (Connection connection = dataSource.getConnection()) {
            String groupeActifId = groupeActifId(connection);
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("contrainte_animateur", "contrainte_ad_hoc", "poste_affectation",
                        "stand_typologie", "stand_indisponibilite", "stand_ouverture", "animateur_competence",
                        "animateur_jour_indispo", "animateur_souhait", "stand", "animateur")) {
                    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table)) {
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM creneau WHERE groupe_creneau_id = ?")) {
                    ps.setString(1, groupeActifId);
                    ps.executeUpdate();
                }
                GroupeCreneau groupeActif = new GroupeCreneau(groupeActifId, null, false);
                Map<Long, Long> idsRemap = new LinkedHashMap<>();
                for (Creneau creneau : creneauxById.values()) {
                    Long ancienId = creneau.getId();
                    creneau.setGroupe(groupeActif);
                    Long nouvelId = insertCreneauTx(connection, creneau);
                    idsRemap.put(ancienId, nouvelId);
                }
                Map<String, Emplacement> emplacementsById = new LinkedHashMap<>();
                for (Stand stand : standsById.values()) {
                    if (stand.getEmplacement() != null) {
                        emplacementsById.putIfAbsent(stand.getEmplacement().getId(), stand.getEmplacement());
                    }
                }
                for (Emplacement emplacement : emplacementsById.values()) {
                    upsertEmplacementTx(connection, emplacement);
                }
                for (TypologieItem typologie : derivedTypologies(standsById.values(), animateurs)) {
                    upsertTypologieDerivee(connection, typologie);
                }
                for (Stand stand : standsById.values()) {
                    upsertStand(connection, stand);
                }
                for (Animateur animateur : animateurs) {
                    if (animateur != null && animateur.getId() != null) {
                        upsertAnimateur(connection, animateur);
                    }
                }
                for (ContrainteAdHoc contrainte : contraintes) {
                    if (contrainte != null && contrainte.getId() != null) {
                        if (contrainte.getCreneau() != null && contrainte.getCreneau().getId() != null) {
                            Long nouvelId = idsRemap.get(contrainte.getCreneau().getId());
                            if (nouvelId != null) {
                                contrainte.getCreneau().setId(nouvelId);
                            }
                        }
                        upsertContrainte(connection, contrainte);
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to import reference data from planning", e);
        }
    }

    private String groupeActifId(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM groupe_creneau WHERE actif");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("id") : "DEFAUT";
        }
    }

    /** Inserts a new timeslot row; the generated id is set back onto {@code creneau} and returned. */
    private Long insertCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        // Callers that don't know about groups yet (CSV import) leave this null;
        // fall back to the seeded default group rather than fail the NOT NULL FK.
        String groupeId = creneau.getGroupe() != null ? creneau.getGroupe().getId() : "DEFAUT";
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO creneau (date_creneau, heure_debut, heure_fin, groupe_creneau_id, famille) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, creneau.getDate());
            ps.setObject(2, creneau.getHeureDebut());
            ps.setObject(3, creneau.getHeureFin());
            ps.setString(4, groupeId);
            ps.setInt(5, creneau.getFamille());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong("id");
                creneau.setId(id);
            }
        }
        return creneau.getId();
    }

    private void updateCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        String groupeId = creneau.getGroupe() != null ? creneau.getGroupe().getId() : "DEFAUT";
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE creneau SET date_creneau = ?, heure_debut = ?, heure_fin = ?, groupe_creneau_id = ?, famille = ? "
                        + "WHERE id = ?")) {
            ps.setObject(1, creneau.getDate());
            ps.setObject(2, creneau.getHeureDebut());
            ps.setObject(3, creneau.getHeureFin());
            ps.setString(4, groupeId);
            ps.setInt(5, creneau.getFamille());
            ps.setLong(6, creneau.getId());
            ps.executeUpdate();
        }
    }

    private List<TypologieItem> derivedTypologies(Iterable<Stand> stands, List<Animateur> animateurs) {
        java.util.LinkedHashSet<String> vues = new java.util.LinkedHashSet<>();
        stands.forEach(stand -> {
            if (stand.getTypologiesProposees() != null) {
                vues.addAll(stand.getTypologiesProposees());
            }
        });
        animateurs.forEach(animateur -> {
            if (animateur == null) {
                return;
            }
            if (animateur.getCompetences() != null) {
                vues.addAll(animateur.getCompetences().keySet());
            }
            if (animateur.getSouhaits() != null) {
                vues.addAll(animateur.getSouhaits());
            }
        });
        return vues.stream().map(t -> new TypologieItem(t, t)).toList();
    }

    /* -------------------------------- Helpers ------------------------------ */

    private boolean exists(String table, String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to probe " + table + " " + id, e);
        }
    }

    private void delete(String sql, String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete " + id, e);
        }
    }

    private boolean existsLong(String table, Long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to probe " + table + " " + id, e);
        }
    }

    private void deleteLong(String sql, Long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete " + id, e);
        }
    }
}
