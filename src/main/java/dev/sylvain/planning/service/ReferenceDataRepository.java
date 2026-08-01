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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypologieJeu;
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
                    "SELECT s.id, s.nom, s.effectif_min, s.effectif_max, s.reserve_majeurs, s.premium, "
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
                        stand.getTypologiesProposees().add(TypologieJeu.valueOf(rs.getString("typologie")));
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
                "INSERT INTO stand (id, nom, effectif_min, effectif_max, reserve_majeurs, premium, emplacement_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min, "
                        + "effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs, "
                        + "premium = EXCLUDED.premium, emplacement_id = EXCLUDED.emplacement_id")) {
            ps.setString(1, stand.getId());
            ps.setString(2, stand.getNom());
            ps.setInt(3, stand.getEffectifMin());
            ps.setInt(4, stand.getEffectifMax());
            ps.setBoolean(5, stand.isReserveMajeurs());
            ps.setBoolean(6, stand.isPremium());
            ps.setString(7, stand.getEmplacement() != null ? stand.getEmplacement().getId() : null);
            ps.executeUpdate();
        }
        try (PreparedStatement del = connection.prepareStatement("DELETE FROM stand_typologie WHERE stand_id = ?")) {
            del.setString(1, stand.getId());
            del.executeUpdate();
        }
        if (stand.getTypologiesProposees() != null && !stand.getTypologiesProposees().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO stand_typologie (stand_id, typologie) VALUES (?, ?)")) {
                for (TypologieJeu typologie : stand.getTypologiesProposees()) {
                    ins.setString(1, stand.getId());
                    ins.setString(2, typologie.name());
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
            "SELECT c.id, c.jour, c.date_creneau, c.heure_debut, c.heure_fin, "
                    + "g.id AS groupe_id, g.nom AS groupe_nom, g.actif AS groupe_actif "
                    + "FROM creneau c JOIN groupe_creneau g ON g.id = c.groupe_creneau_id";

    public List<Creneau> listCreneaux() {
        return listCreneaux(SELECT_CRENEAU_SQL + " ORDER BY c.id");
    }

    /** Timeslots of the currently active group only — what the solver builds its problem from. */
    public List<Creneau> listCreneauxGroupeActif() {
        return listCreneaux(SELECT_CRENEAU_SQL + " WHERE g.actif ORDER BY c.id");
    }

    private List<Creneau> listCreneaux(String sql) {
        Map<String, Creneau> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Creneau creneau = new Creneau(
                            rs.getString("id"),
                            rs.getInt("jour"),
                            rs.getObject("date_creneau", LocalDate.class),
                            rs.getObject("heure_debut", LocalTime.class),
                            rs.getObject("heure_fin", LocalTime.class));
                    creneau.setGroupe(new GroupeCreneau(
                            rs.getString("groupe_id"), rs.getString("groupe_nom"), rs.getBoolean("groupe_actif")));
                    byId.put(creneau.getId(), creneau);
                }
            }
            if (!byId.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT creneau_id, stand_id FROM creneau_stand_ouvert");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Creneau creneau = byId.get(rs.getString("creneau_id"));
                        if (creneau != null) {
                            creneau.getStandsOuvertsIds().add(rs.getString("stand_id"));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslots", e);
        }
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

    public boolean creneauExists(String id) {
        return exists("creneau", id);
    }

    public void saveCreneau(Creneau creneau) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertCreneauTx(connection, creneau);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save timeslot " + creneau.getId(), e);
        }
    }

    public void deleteCreneau(String id) {
        delete("DELETE FROM creneau WHERE id = ?", id);
    }

    /* -------------------------- Timeslot groups ----------------------------- */

    public List<GroupeCreneau> listGroupesCreneaux() {
        List<GroupeCreneau> groupes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, nom, actif FROM groupe_creneau ORDER BY nom");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groupes.add(new GroupeCreneau(rs.getString("id"), rs.getString("nom"), rs.getBoolean("actif")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslot groups", e);
        }
        return groupes;
    }

    public boolean groupeCreneauExists(String id) {
        return exists("groupe_creneau", id);
    }

    /** Upserts id/nom only — {@code actif} is never touched here, see {@link #activerGroupeCreneau(String)}. */
    public void saveGroupeCreneau(GroupeCreneau groupe) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO groupe_creneau (id, nom, actif) VALUES (?, ?, FALSE) "
                                + "ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom")) {
            ps.setString(1, groupe.getId());
            ps.setString(2, groupe.getNom());
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
                                TypologieJeu.valueOf(rs.getString("typologie")),
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
                for (Map.Entry<TypologieJeu, NiveauCompetence> entry : animateur.getCompetences().entrySet()) {
                    ins.setString(1, animateur.getId());
                    ins.setString(2, entry.getKey().name());
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
    }

    /* ------------------------------ Typologies ----------------------------- */

    public List<TypologieItem> listTypologies() {
        List<TypologieItem> typologies = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT id, label FROM typologie ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                typologies.add(new TypologieItem(rs.getString("id"), rs.getString("label")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list typologies", e);
        }
        return typologies;
    }

    public boolean typologieExists(String id) {
        return exists("typologie", id);
    }

    public void saveTypologie(TypologieItem typologie) {
        try (Connection connection = dataSource.getConnection()) {
            upsertTypologie(connection, typologie);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save typology " + typologie.id(), e);
        }
    }

    public void deleteTypologie(String id) {
        delete("DELETE FROM typologie WHERE id = ?", id);
    }

    private void upsertTypologie(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO typologie (id, label) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label")) {
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
                    String creneauId = rs.getString("creneau_id");
                    if (creneauId != null) {
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
            ps.setString(3, contrainte.getCreneau() != null ? contrainte.getCreneau().getId() : null);
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
                        "SELECT duree_hebdomadaire_max_minutes FROM parametres_legaux WHERE id = 1");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new ParametresLegaux(rs.getInt("duree_hebdomadaire_max_minutes"));
            }
            return new ParametresLegaux();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load legal parameters", e);
        }
    }

    public void saveParametresLegaux(ParametresLegaux parametres) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO parametres_legaux (id, duree_hebdomadaire_max_minutes) VALUES (1, ?) "
                                + "ON CONFLICT (id) DO UPDATE SET "
                                + "duree_hebdomadaire_max_minutes = EXCLUDED.duree_hebdomadaire_max_minutes")) {
            ps.setInt(1, parametres.getDureeHebdomadaireMaxMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save legal parameters", e);
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

    public void setContrainteActive(String nom, boolean actif) {
        try (Connection connection = dataSource.getConnection()) {
            if (actif) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM constraint_toggle WHERE nom = ?")) {
                    ps.setString(1, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO constraint_toggle (nom) VALUES (?) ON CONFLICT (nom) DO NOTHING")) {
                    ps.setString(1, nom);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save constraint toggle", e);
        }
    }

    /* ------------------------------ CSV replace ----------------------------- */

    /**
     * Replaces every animator with the imported list. Assignments are dropped
     * as well: they reference animators that may no longer exist.
     */
    public void replaceAnimateurs(List<Animateur> animateurs) {
        replaceInTransaction(List.of("poste_affectation", "animateur_competence", "animateur_jour_indispo",
                "contrainte_animateur", "animateur"), connection -> {
                    for (Animateur animateur : animateurs) {
                        upsertAnimateur(connection, animateur);
                    }
                    for (TypologieItem typologie : derivedTypologies(List.of(), animateurs)) {
                        upsertTypologie(connection, typologie);
                    }
                }, "Failed to import animateurs");
    }

    /** Replaces every stand (and the assignments pointing at them). */
    public void replaceStands(List<Stand> stands) {
        replaceInTransaction(List.of("poste_affectation", "stand_typologie", "stand"), connection -> {
            for (Stand stand : stands) {
                upsertStand(connection, stand);
            }
            for (TypologieItem typologie : derivedTypologies(stands, List.of())) {
                upsertTypologie(connection, typologie);
            }
        }, "Failed to import stands");
    }

    /** Replaces every timeslot (and the assignments pointing at them). */
    public void replaceCreneaux(List<Creneau> creneaux) {
        replaceInTransaction(List.of("poste_affectation", "creneau"), connection -> {
            for (Creneau creneau : creneaux) {
                upsertCreneauTx(connection, creneau);
            }
        }, "Failed to import creneaux");
    }

    private void replaceInTransaction(List<String> tablesToClear, ConnectionWork work, String errorMessage) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String table : tablesToClear) {
                    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table)) {
                        ps.executeUpdate();
                    }
                }
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
    private interface ConnectionWork {
        void execute(Connection connection) throws SQLException;
    }

    /* -------------------------------- Import ------------------------------- */

    /**
     * Replaces the reference dataset with the one carried by a planning (used
     * by the "Load sample" action and by generic reference-data import).
     * Stands and animateurs are global and always fully replaced; timeslots
     * are not — only the currently active {@link GroupeCreneau}'s créneaux are
     * cleared and reloaded, so a scenario can be imported into one group
     * (e.g. an alternate planning) without wiping out the créneaux other
     * groups already hold. Runs in a single transaction.
     */
    public void importFromPlanning(PlanningFestival planning) {
        if (planning == null) {
            return;
        }
        Map<String, Stand> standsById = new LinkedHashMap<>();
        Map<String, Creneau> creneauxById = new LinkedHashMap<>();
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
            // Fail fast, before touching anything: creneau.id is a single global
            // primary key, so a scenario re-using an id already claimed by another
            // group (very common — the bundled scenarios all follow the same
            // "J1-MATIN" convention) can't be inserted into the active group without
            // colliding. Silently upserting on conflict would move that créneau out
            // of its current group instead of leaving it alone, defeating the whole
            // point of importing into one group without disturbing the others.
            assertAucunConflitDeGroupe(connection, groupeActifId, creneauxById);
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("contrainte_animateur", "contrainte_ad_hoc", "poste_affectation",
                        "stand_typologie", "animateur_competence", "animateur_jour_indispo", "stand",
                        "animateur")) {
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
                for (Creneau creneau : creneauxById.values()) {
                    creneau.setGroupe(groupeActif);
                    upsertCreneauTx(connection, creneau);
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
                        upsertContrainte(connection, contrainte);
                    }
                }
                for (TypologieItem typologie : derivedTypologies(standsById.values(), animateurs)) {
                    upsertTypologie(connection, typologie);
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

    /**
     * Rejects a scenario import whose créneau ids collide with ones already
     * held by a different (non-active) group — see the caller for why this
     * can't just be resolved by upserting.
     */
    private void assertAucunConflitDeGroupe(Connection connection, String groupeActifId,
            Map<String, Creneau> creneauxById) throws SQLException {
        if (creneauxById.isEmpty()) {
            return;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < creneauxById.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        List<String> conflits = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM creneau WHERE groupe_creneau_id <> ? AND id IN (" + placeholders + ")")) {
            ps.setString(1, groupeActifId);
            int index = 2;
            for (String id : creneauxById.keySet()) {
                ps.setString(index++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    conflits.add(rs.getString("id"));
                }
            }
        }
        if (!conflits.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ce scénario utilise des identifiants de créneau déjà présents dans un autre groupe : "
                            + String.join(", ", conflits)
                            + ". Renommez-les dans le scénario, ou videz/supprimez-les de l'autre groupe d'abord.");
        }
    }

    private void upsertCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        // Callers that don't know about groups yet (CSV import) leave this null;
        // fall back to the seeded default group rather than fail the NOT NULL FK.
        String groupeId = creneau.getGroupe() != null ? creneau.getGroupe().getId() : "DEFAUT";
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO creneau (id, jour, date_creneau, heure_debut, heure_fin, groupe_creneau_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET jour = EXCLUDED.jour, date_creneau = EXCLUDED.date_creneau, "
                        + "heure_debut = EXCLUDED.heure_debut, heure_fin = EXCLUDED.heure_fin, "
                        + "groupe_creneau_id = EXCLUDED.groupe_creneau_id")) {
            ps.setString(1, creneau.getId());
            ps.setInt(2, creneau.getJour());
            ps.setObject(3, creneau.getDate());
            ps.setObject(4, creneau.getHeureDebut());
            ps.setObject(5, creneau.getHeureFin());
            ps.setString(6, groupeId);
            ps.executeUpdate();
        }
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM creneau_stand_ouvert WHERE creneau_id = ?")) {
            del.setString(1, creneau.getId());
            del.executeUpdate();
        }
        if (creneau.getStandsOuvertsIds() != null && !creneau.getStandsOuvertsIds().isEmpty()) {
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO creneau_stand_ouvert (creneau_id, stand_id) VALUES (?, ?)")) {
                for (String standId : creneau.getStandsOuvertsIds()) {
                    ins.setString(1, creneau.getId());
                    ins.setString(2, standId);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    private List<TypologieItem> derivedTypologies(Iterable<Stand> stands, List<Animateur> animateurs) {
        java.util.LinkedHashSet<TypologieJeu> vues = new java.util.LinkedHashSet<>();
        stands.forEach(stand -> {
            if (stand.getTypologiesProposees() != null) {
                vues.addAll(stand.getTypologiesProposees());
            }
        });
        animateurs.forEach(animateur -> {
            if (animateur != null && animateur.getCompetences() != null) {
                vues.addAll(animateur.getCompetences().keySet());
            }
        });
        return vues.stream().map(t -> new TypologieItem(t.name(), t.name())).toList();
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
}
