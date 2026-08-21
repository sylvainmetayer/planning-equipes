package dev.sylvain.planning.service;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
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
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.ReferenceDataService.TypologieItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Direct JDBC persistence for the reference model (stands, timeslots, animators,
 * typologies and ad hoc constraints). Every admin CRUD operation writes straight
 * to PostgreSQL through this repository, and reads reconstruct fully-hydrated
 * domain objects (skills, off-days, stand typologies, constraint targets).
 *
 * <p>Every statement here is scoped to the current {@code edition} — the
 * edition the caller is working on, resolved by {@link EditionContext}. Being the single
 * point of passage for all reference-data SQL is what makes that scoping
 * mechanical and verifiable: a query without a {@code edition_id} predicate is
 * visible as such, right here. See {@code docs/editions.md}.</p>
 */
@ApplicationScoped
public class ReferenceDataRepository {

    @Inject
    DataSource dataSource;

    @Inject
    EditionContext editionContext;

    /** Timeslot group seeded by V10 in every {@code edition}, and the fallback for callers that name none. */

    /** Edition every statement below reads and writes. */
    private String editionId() {
        return editionContext.editionIdCourant();
    }

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
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT s.id, s.nom, s.effectif_min, s.effectif_max, s.reserve_majeurs, s.premium, s.niveau_effort, "
                            + "e.id AS emplacement_id, e.nom AS emplacement_nom, e.latitude AS emplacement_latitude, "
                            + "e.longitude AS emplacement_longitude "
                            + "FROM stand s LEFT JOIN emplacement e "
                            + "ON e.edition_id = s.edition_id AND e.id = s.emplacement_id "
                            + "WHERE s.edition_id = ? ORDER BY s.id");
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
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT stand_id, typologie FROM stand_typologie WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getTypologiesProposees().add(rs.getString("typologie"));
                    }
                }
            }
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif "
                            + "FROM stand_indisponibilite WHERE edition_id = ? ORDER BY id");
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
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT id, stand_id, date_ouverture, heure_debut, heure_fin, motif "
                            + "FROM stand_ouverture WHERE edition_id = ? ORDER BY id");
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
            chargerHoraires(connection, byId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list stands", e);
        }
        List<Stand> stands = new ArrayList<>(byId.values());
        stands.sort(Comparator.comparing(Stand::getId, NATURAL_ID_ORDER));
        return stands;
    }

    /**
     * Hydrates the recurring {@link HoraireStand} rules of every stand of
     * {@code standsById}, windows included, in two queries rather than two per
     * stand. Rules are keyed by their own id so the windows can be attached
     * without re-walking the stands.
     */
    private void chargerHoraires(Connection connection, Map<String, Stand> standsById) throws SQLException {
        Map<Long, HoraireStand> horairesParId = new LinkedHashMap<>();
        try (PreparedStatement ps = prepareScoped(connection,
                "SELECT id, stand_id, mode, type_jours, jours_semaine, date_debut, date_fin, dates, motif "
                        + "FROM stand_horaire WHERE edition_id = ? ORDER BY stand_id, id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Stand stand = standsById.get(rs.getString("stand_id"));
                if (stand == null) {
                    continue;
                }
                HoraireStand horaire = new HoraireStand();
                horaire.setId(rs.getLong("id"));
                horaire.setMode(ModeHoraire.valueOf(rs.getString("mode")));
                horaire.setJours(TypeJoursHoraire.valueOf(rs.getString("type_jours")));
                horaire.setJoursSemaine(decouperCsv(rs.getString("jours_semaine"), DayOfWeek::valueOf));
                horaire.setDateDebut(rs.getObject("date_debut", LocalDate.class));
                horaire.setDateFin(rs.getObject("date_fin", LocalDate.class));
                horaire.setDates(decouperCsv(rs.getString("dates"), LocalDate::parse));
                horaire.setMotif(rs.getString("motif"));
                stand.getHoraires().add(horaire);
                horairesParId.put(horaire.getId(), horaire);
            }
        }
        if (horairesParId.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = prepareScoped(connection,
                "SELECT horaire_id, heure_debut, heure_fin FROM stand_horaire_fenetre "
                        + "WHERE edition_id = ? ORDER BY horaire_id, position, id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                HoraireStand horaire = horairesParId.get(rs.getLong("horaire_id"));
                if (horaire != null) {
                    horaire.getFenetres().add(new FenetreHoraire(
                            rs.getObject("heure_debut", LocalTime.class),
                            rs.getObject("heure_fin", LocalTime.class)));
                }
            }
        }
    }

    /** Reads back a comma-separated leaf column (see V37 on why these two aren't normalised). */
    private static <T> Set<T> decouperCsv(String csv, Function<String, T> parse) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<T> valeurs = new LinkedHashSet<>();
        for (String morceau : csv.split(",")) {
            String valeur = morceau.trim();
            if (!valeur.isEmpty()) {
                valeurs.add(parse.apply(valeur));
            }
        }
        return valeurs;
    }

    /** Writes a set back as the comma-separated form {@link #decouperCsv} reads, or {@code null} when empty. */
    private static String joindreCsv(Collection<?> valeurs) {
        if (valeurs == null || valeurs.isEmpty()) {
            return null;
        }
        return valeurs.stream().map(String::valueOf).collect(Collectors.joining(","));
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
        delete("DELETE FROM stand WHERE edition_id = ? AND id = ?", id);
    }

    private void upsertEmplacementTx(Connection connection, Emplacement emplacement) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO emplacement (edition_id, id, nom, latitude, longitude) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (edition_id, id) DO UPDATE SET nom = EXCLUDED.nom, "
                        + "latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude")) {
            ps.setString(2, emplacement.getId());
            ps.setString(3, emplacement.getNom());
            ps.setObject(4, emplacement.getLatitude());
            ps.setObject(5, emplacement.getLongitude());
            ps.executeUpdate();
        }
    }

    private void upsertStand(Connection connection, Stand stand) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs, premium, "
                        + "emplacement_id, niveau_effort) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (edition_id, id) DO UPDATE SET nom = EXCLUDED.nom, "
                        + "effectif_min = EXCLUDED.effectif_min, "
                        + "effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs, "
                        + "premium = EXCLUDED.premium, emplacement_id = EXCLUDED.emplacement_id, "
                        + "niveau_effort = EXCLUDED.niveau_effort")) {
            ps.setString(2, stand.getId());
            ps.setString(3, stand.getNom());
            ps.setInt(4, stand.getEffectifMin());
            ps.setInt(5, stand.getEffectifMax());
            ps.setBoolean(6, stand.isReserveMajeurs());
            ps.setBoolean(7, stand.isPremium());
            ps.setString(8, stand.getEmplacement() != null ? stand.getEmplacement().getId() : null);
            ps.setString(9, stand.getNiveauEffort().name());
            ps.executeUpdate();
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM stand_typologie WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getTypologiesProposees() != null && !stand.getTypologiesProposees().isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO stand_typologie (edition_id, stand_id, typologie) VALUES (?, ?, ?)")) {
                for (String typologie : stand.getTypologiesProposees()) {
                    ins.setString(2, stand.getId());
                    ins.setString(3, typologie);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM stand_indisponibilite WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getIndisponibilites() != null && !stand.getIndisponibilites().isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut, "
                            + "heure_fin, motif) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
                    ins.setString(2, stand.getId());
                    ins.setObject(3, indispo.getDate());
                    ins.setObject(4, indispo.getHeureDebut());
                    ins.setObject(5, indispo.getHeureFin());
                    ins.setString(6, indispo.getMotif());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM stand_ouverture WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getOuvertures() != null && !stand.getOuvertures().isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO stand_ouverture (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (OuvertureStand ouverture : stand.getOuvertures()) {
                    ins.setString(2, stand.getId());
                    ins.setObject(3, ouverture.getDate());
                    ins.setObject(4, ouverture.getHeureDebut());
                    ins.setObject(5, ouverture.getHeureFin());
                    ins.setString(6, ouverture.getMotif());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        upsertHoraires(connection, stand);
    }

    /**
     * Replaces the stand's rules wholesale — the windows go with them through
     * {@code ON DELETE CASCADE}. Note this writes {@link Stand#getHoraires()}
     * and the <em>dated</em> window lists above, never
     * {@link Stand#getOuverturesEffectives()}: a stand that went through
     * {@link HoraireStandResolver} can therefore be saved without freezing its
     * expansion into a few hundred dated rows.
     */
    private void upsertHoraires(Connection connection, Stand stand) throws SQLException {
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM stand_horaire WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getHoraires() == null || stand.getHoraires().isEmpty()) {
            return;
        }
        for (HoraireStand horaire : stand.getHoraires()) {
            long horaireId;
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO stand_horaire (edition_id, stand_id, mode, type_jours, jours_semaine, date_debut, "
                            + "date_fin, dates, motif) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                ins.setString(2, stand.getId());
                ins.setString(3, horaire.getMode().name());
                ins.setString(4, horaire.getJours().name());
                ins.setString(5, joindreCsv(horaire.getJoursSemaine()));
                ins.setObject(6, horaire.getDateDebut());
                ins.setObject(7, horaire.getDateFin());
                ins.setString(8, joindreCsv(horaire.getDates()));
                ins.setString(9, horaire.getMotif());
                try (ResultSet rs = ins.executeQuery()) {
                    rs.next();
                    horaireId = rs.getLong("id");
                }
            }
            horaire.setId(horaireId);
            if (horaire.getFenetres().isEmpty()) {
                continue;
            }
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO stand_horaire_fenetre (edition_id, horaire_id, position, heure_debut, heure_fin) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                int position = 0;
                for (FenetreHoraire fenetre : horaire.getFenetres()) {
                    ins.setLong(2, horaireId);
                    ins.setInt(3, position++);
                    ins.setObject(4, fenetre.getHeureDebut());
                    ins.setObject(5, fenetre.getHeureFin());
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
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT id, nom, latitude, longitude FROM emplacement WHERE edition_id = ? ORDER BY id");
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
        try (Connection connection = dataSource.getConnection()) {
            upsertEmplacementTx(connection, emplacement);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save emplacement " + emplacement.getId(), e);
        }
    }

    public void deleteEmplacement(String id) {
        delete("DELETE FROM emplacement WHERE edition_id = ? AND id = ?", id);
    }

    /* ------------------------------ Timeslots ------------------------------ */

    private static final String SELECT_CRENEAU_SQL =
            "SELECT c.id, c.date_creneau, c.heure_debut, c.heure_fin, c.famille, c.couverture_pause "
                    + "FROM creneau c WHERE c.edition_id = ?";

    public List<Creneau> listCreneaux() {
        return listCreneaux(SELECT_CRENEAU_SQL + " ORDER BY c.id");
    }

    /**
     * Replaces every créneau of the edition — how the découpage materializes
     * its vacations in place (issue #172: the amplitudes it read are consumed,
     * the edition only ever holds one grid). The persisted plan goes with the
     * créneaux it referenced.
     */
    public void replaceCreneaux(List<Creneau> creneaux) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = prepareScoped(connection,
                        "DELETE FROM poste_affectation WHERE edition_id = ?")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = prepareScoped(connection,
                        "DELETE FROM creneau WHERE edition_id = ?")) {
                    ps.executeUpdate();
                }
                for (Creneau creneau : creneaux) {
                    creneau.setId(null);
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
            throw new IllegalStateException("Failed to replace timeslots", e);
        }
    }

    /**
     * Maps one {@code SELECT_CRENEAU_SQL} row, with {@code jour} left at 0 —
     * it is never stored and is recomputed by {@link Creneau#assignerJours}
     * after loading.
     */
    private static Creneau readCreneau(ResultSet rs) throws SQLException {
        Creneau creneau = new Creneau(
                rs.getLong("id"),
                0,
                rs.getObject("date_creneau", LocalDate.class),
                rs.getObject("heure_debut", LocalTime.class),
                rs.getObject("heure_fin", LocalTime.class));
        creneau.setFamille(rs.getInt("famille"));
        creneau.setCouverturePause(rs.getBoolean("couverture_pause"));
        return creneau;
    }

    private List<Creneau> listCreneaux(String sql) {
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = prepareScoped(connection, sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Creneau creneau = readCreneau(rs);
                    byId.put(creneau.getId(), creneau);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslots", e);
        }
        // `jour` is never stored — it's computed from the edition's earliest
        // date (see Creneau.assignerJours), so consecutive calendar days
        // always yield consecutive day numbers even across a gap day.
        Creneau.assignerJours(byId.values());
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
        deleteLong("DELETE FROM creneau WHERE edition_id = ? AND id = ?", id);
    }

    /* ------------------------------ Animateurs ----------------------------- */

    public List<Animateur> listAnimateurs() {
        Map<String, Animateur> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT id, prenom, nom, date_naissance, manager, email, jeton_acces FROM animateur "
                            + "WHERE edition_id = ? ORDER BY id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = new Animateur(
                            rs.getString("id"),
                            rs.getString("prenom"),
                            rs.getString("nom"),
                            rs.getObject("date_naissance", LocalDate.class),
                            rs.getBoolean("manager"));
                    animateur.setEmail(rs.getString("email"));
                    animateur.setJetonAcces(rs.getString("jeton_acces"));
                    byId.put(animateur.getId(), animateur);
                }
            }
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT animateur_id, typologie, niveau FROM animateur_competence WHERE edition_id = ?");
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
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT animateur_id, jour FROM animateur_jour_indispo WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getJoursIndisponibles().add(rs.getObject("jour", LocalDate.class));
                    }
                }
            }
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT animateur_id, typologie FROM animateur_souhait WHERE edition_id = ?");
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
            try (PreparedStatement ps = prepareScoped(connection, "SELECT id FROM typologie WHERE edition_id = ? AND ninja LIMIT 1");
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
        delete("DELETE FROM animateur WHERE edition_id = ? AND id = ?", id);
    }

    /**
     * Rotates the espace-animateur access token — the one explicit way it ever
     * changes (a lost or leaked PDF link stops working once regenerated).
     *
     * @return the new token, or {@code null} when the animateur is unknown.
     */
    public String regenererJetonAnimateur(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "UPDATE animateur SET jeton_acces = gen_random_uuid()::text "
                                + "WHERE edition_id = ? AND id = ? RETURNING jeton_acces")) {
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to regenerate token for animator " + id, e);
        }
    }

    /**
     * (édition, animateur) behind an espace-animateur access token.
     *
     * @param editionId  edition the token designates by itself, with no
     *                   {@code X-Edition-Id} to trust
     * @param animateurId owner of the token inside that edition
     * @param email      address on the animateur's fiche, {@code null} when none
     *                   was collected — carried here so the espace guard can
     *                   match a proxy-asserted identity without a second query
     */
    public record ProprietaireJeton(String editionId, String animateurId, String email) {
    }

    /**
     * Whether any animateur, in any edition, carries this address. Like
     * {@link #resoudreJetonAnimateur}, deliberately not edition-scoped: the
     * caller is the startup check of the remote-user mode, which has no
     * edition to speak of and wants to know whether the collision exists
     * anywhere at all.
     */
    public boolean emailAnimateurExiste(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM animateur WHERE lower(email) = lower(?) LIMIT 1")) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to look up animator e-mail", e);
        }
    }

    /**
     * Resolves an espace-animateur token to its owner. Deliberately <b>not</b>
     * edition-scoped — the single exception to this repository's rule: the
     * token arrives on a public URL with no {@code X-Edition-Id} to trust, and
     * is globally unique precisely so it can designate the edition by itself
     * (the caller then runs everything else inside
     * {@code EditionContext.executeDans}).
     */
    public ProprietaireJeton resoudreJetonAnimateur(String jeton) {
        if (jeton == null || jeton.isBlank()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT edition_id, id, email FROM animateur WHERE jeton_acces = ?")) {
            ps.setString(1, jeton);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new ProprietaireJeton(rs.getString("edition_id"), rs.getString("id"),
                                rs.getString("email"))
                        : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve animator token", e);
        }
    }

    private void upsertAnimateur(Connection connection, Animateur animateur) throws SQLException {
        upsertAnimateur(connection, animateur, false);
    }

    /**
     * @param conserverEmailSiAbsent true on the scenario-import path: a file
     *                               that carries no email for an animateur must
     *                               not silently wipe the stored one — the
     *                               address is now the espace's second factor.
     *                               The fiche update (false) can still clear it.
     */
    private void upsertAnimateur(Connection connection, Animateur animateur, boolean conserverEmailSiAbsent)
            throws SQLException {
        // jeton_acces is deliberately absent: a fresh row gets the database
        // default, an existing row keeps its token. Rotation only happens
        // through regenererJetonAnimateur.
        String miseAJourEmail = conserverEmailSiAbsent
                ? "email = COALESCE(EXCLUDED.email, animateur.email)"
                : "email = EXCLUDED.email";
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance, manager, email) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (edition_id, id) DO UPDATE SET prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, "
                        + "date_naissance = EXCLUDED.date_naissance, manager = EXCLUDED.manager, "
                        + miseAJourEmail)) {
            ps.setString(2, animateur.getId());
            ps.setString(3, animateur.getPrenom());
            ps.setString(4, animateur.getNom());
            ps.setObject(5, animateur.getDateNaissance());
            ps.setBoolean(6, animateur.isManager());
            ps.setString(7, animateur.getEmail());
            ps.executeUpdate();
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM animateur_competence WHERE edition_id = ? AND animateur_id = ?")) {
            del.setString(2, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getCompetences() != null && !animateur.getCompetences().isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO animateur_competence (edition_id, animateur_id, typologie, niveau) "
                            + "VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<String, NiveauCompetence> entry : animateur.getCompetences().entrySet()) {
                    ins.setString(2, animateur.getId());
                    ins.setString(3, entry.getKey());
                    ins.setString(4, entry.getValue().name());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM animateur_jour_indispo WHERE edition_id = ? AND animateur_id = ?")) {
            del.setString(2, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getJoursIndisponibles() != null && !animateur.getJoursIndisponibles().isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO animateur_jour_indispo (edition_id, animateur_id, jour) VALUES (?, ?, ?)")) {
                for (LocalDate jour : animateur.getJoursIndisponibles()) {
                    ins.setString(2, animateur.getId());
                    ins.setObject(3, jour);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM animateur_souhait WHERE edition_id = ? AND animateur_id = ?")) {
            del.setString(2, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getSouhaits() != null && !animateur.getSouhaits().isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO animateur_souhait (edition_id, animateur_id, typologie) VALUES (?, ?, ?)")) {
                for (String typologie : animateur.getSouhaits()) {
                    ins.setString(2, animateur.getId());
                    ins.setString(3, typologie);
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
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT id, label, ninja FROM typologie WHERE edition_id = ? ORDER BY id");
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
                PreparedStatement ps = prepareScoped(connection, "SELECT id FROM typologie WHERE edition_id = ? AND ninja LIMIT 1");
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
                // Only one typologie may be ninja *per edition*: demote the previous
                // holder in the same transaction, otherwise the partial unique index
                // (V31, scoped per edition by V33) rejects the insert and the user
                // sees a raw constraint violation. The demotion carries the same
                // edition predicate as the index it protects — without it, flagging a
                // ninja here would silently clear the one of every other edition.
                if (typologie.ninja()) {
                    try (PreparedStatement ps = prepareScoped(connection,
                            "UPDATE typologie SET ninja = FALSE "
                                    + "WHERE edition_id = ? AND ninja AND id <> ?")) {
                        ps.setString(2, typologie.id());
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
        delete("DELETE FROM typologie WHERE edition_id = ? AND id = ?", id);
    }

    public boolean typologieEnUsage(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT 1 WHERE EXISTS "
                                + "(SELECT 1 FROM stand_typologie WHERE edition_id = ? AND typologie = ?) "
                                + "OR EXISTS "
                                + "(SELECT 1 FROM animateur_competence WHERE edition_id = ? AND typologie = ?) "
                                + "OR EXISTS "
                                + "(SELECT 1 FROM animateur_souhait WHERE edition_id = ? AND typologie = ?)")) {
            ps.setString(2, id);
            ps.setString(3, editionId());
            ps.setString(4, id);
            ps.setString(5, editionId());
            ps.setString(6, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check typologie usage " + id, e);
        }
    }

    private void upsertTypologie(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO typologie (edition_id, id, label, ninja) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (edition_id, id) DO UPDATE SET label = EXCLUDED.label, "
                        + "ninja = EXCLUDED.ninja")) {
            ps.setString(2, typologie.id());
            ps.setString(3, typologie.label());
            ps.setBoolean(4, typologie.ninja());
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
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO typologie (edition_id, id, label, ninja) VALUES (?, ?, ?, FALSE) "
                        + "ON CONFLICT (edition_id, id) DO UPDATE SET label = EXCLUDED.label")) {
            ps.setString(2, typologie.id());
            ps.setString(3, typologie.label());
            ps.executeUpdate();
        }
    }

    /* --------------------------- Ad hoc constraints ------------------------ */

    public List<ContrainteAdHoc> listContraintes() {
        Map<String, ContrainteAdHoc> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT id, type, creneau_id, stand_id, raison, cree_par, cree_le FROM contrainte_ad_hoc "
                            + "WHERE edition_id = ? ORDER BY id");
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
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT contrainte_id, animateur_id FROM contrainte_animateur WHERE edition_id = ? "
                            + "ORDER BY contrainte_id, position");
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
        delete("DELETE FROM contrainte_ad_hoc WHERE edition_id = ? AND id = ?", id);
    }

    private void upsertContrainte(Connection connection, ContrainteAdHoc contrainte) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO contrainte_ad_hoc (edition_id, id, type, creneau_id, stand_id, raison, cree_par, cree_le) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (edition_id, id) DO UPDATE SET type = EXCLUDED.type, "
                        + "creneau_id = EXCLUDED.creneau_id, stand_id = EXCLUDED.stand_id, raison = EXCLUDED.raison, "
                        + "cree_par = EXCLUDED.cree_par, cree_le = EXCLUDED.cree_le")) {
            ps.setString(2, contrainte.getId());
            ps.setString(3, contrainte.getType() != null ? contrainte.getType().name() : null);
            ps.setObject(4, contrainte.getCreneau() != null ? contrainte.getCreneau().getId() : null);
            ps.setString(5, contrainte.getStand() != null ? contrainte.getStand().getId() : null);
            ps.setString(6, contrainte.getRaison());
            ps.setString(7, contrainte.getCreeParUtilisateurId());
            Instant creeLe = contrainte.getCreeLe() != null ? contrainte.getCreeLe() : Instant.now();
            ps.setTimestamp(8, Timestamp.from(creeLe));
            ps.executeUpdate();
        }
        try (PreparedStatement del = prepareScoped(connection,
                "DELETE FROM contrainte_animateur WHERE edition_id = ? AND contrainte_id = ?")) {
            del.setString(2, contrainte.getId());
            del.executeUpdate();
        }
        List<Animateur> cibles = contrainte.getAnimateursConcernes();
        if (cibles != null && !cibles.isEmpty()) {
            try (PreparedStatement ins = prepareScoped(connection,
                    "INSERT INTO contrainte_animateur (edition_id, contrainte_id, animateur_id, position) "
                            + "VALUES (?, ?, ?, ?)")) {
                int position = 0;
                for (Animateur animateur : cibles) {
                    if (animateur == null || animateur.getId() == null) {
                        continue;
                    }
                    ins.setString(2, contrainte.getId());
                    ins.setString(3, animateur.getId());
                    ins.setInt(4, position++);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    /* --------------------------- Planning locks ----------------------------- */

    private static final String SELECT_VERROUILLAGE_SQL =
            "SELECT id, type, animateur_id, stand_id, creneau_id, jour, raison, cree_le "
                    + "FROM verrouillage_planning WHERE edition_id = ?";

    /** Every lock of the current edition, most recent first — the ones a solve applies. */
    public List<VerrouillagePlanning> listVerrouillages() {
        List<VerrouillagePlanning> verrouillages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        SELECT_VERROUILLAGE_SQL + " ORDER BY cree_le DESC, id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    verrouillages.add(readVerrouillage(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list planning locks", e);
        }
        return verrouillages;
    }

    private static VerrouillagePlanning readVerrouillage(ResultSet rs) throws SQLException {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning(
                rs.getString("id"), TypeVerrouillage.valueOf(rs.getString("type")));
        verrouillage.setAnimateurId(rs.getString("animateur_id"));
        verrouillage.setStandId(rs.getString("stand_id"));
        long creneauId = rs.getLong("creneau_id");
        if (!rs.wasNull()) {
            verrouillage.setCreneauId(creneauId);
        }
        verrouillage.setJour(rs.getObject("jour", LocalDate.class));
        verrouillage.setRaison(rs.getString("raison"));
        Timestamp creeLe = rs.getTimestamp("cree_le");
        verrouillage.setCreeLe(creeLe != null ? creeLe.toInstant() : null);
        return verrouillage;
    }

    /**
     * Inserts the lock, or does nothing if that exact target is already frozen
     * (see {@code idx_verrouillage_cible_edition}) — locking twice is not an
     * error, it is already locked.
     */
    public void saveVerrouillage(VerrouillagePlanning verrouillage) {
        String sql = "INSERT INTO verrouillage_planning "
                + "(edition_id, id, type, animateur_id, stand_id, creneau_id, jour, raison, cree_le) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setString(2, verrouillage.getId());
            ps.setString(3, verrouillage.getType() != null ? verrouillage.getType().name() : null);
            ps.setString(4, verrouillage.getAnimateurId());
            ps.setString(5, verrouillage.getStandId());
            ps.setObject(6, verrouillage.getCreneauId());
            ps.setObject(7, verrouillage.getJour());
            ps.setString(8, verrouillage.getRaison());
            Instant creeLe = verrouillage.getCreeLe() != null ? verrouillage.getCreeLe() : Instant.now();
            ps.setTimestamp(9, Timestamp.from(creeLe));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save planning lock " + verrouillage.getId(), e);
        }
    }

    public void deleteVerrouillage(String id) {
        delete("DELETE FROM verrouillage_planning WHERE edition_id = ? AND id = ?", id);
    }

    /* --------------------------- Legal parameters --------------------------- */

    public ParametresLegaux getParametresLegaux() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT duree_hebdomadaire_max_minutes, duree_hebdomadaire_max_mineur_minutes, "
                                + "pause_minimale_entre_vacations_minutes, repos_quotidien_minimal_minutes "
                                + "FROM parametres_legaux WHERE edition_id = ?");
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
                PreparedStatement ps = prepareScoped(connection,
                        "INSERT INTO parametres_legaux (edition_id, duree_hebdomadaire_max_minutes, "
                                + "duree_hebdomadaire_max_mineur_minutes, pause_minimale_entre_vacations_minutes, "
                                + "repos_quotidien_minimal_minutes) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT (edition_id) DO UPDATE SET "
                                + "duree_hebdomadaire_max_minutes = EXCLUDED.duree_hebdomadaire_max_minutes, "
                                + "duree_hebdomadaire_max_mineur_minutes = "
                                + "EXCLUDED.duree_hebdomadaire_max_mineur_minutes, "
                                + "pause_minimale_entre_vacations_minutes = "
                                + "EXCLUDED.pause_minimale_entre_vacations_minutes, "
                                + "repos_quotidien_minimal_minutes = EXCLUDED.repos_quotidien_minimal_minutes")) {
            ps.setInt(2, parametres.getDureeHebdomadaireMaxMinutes());
            ps.setInt(3, parametres.getDureeHebdomadaireMaxMineurMinutes());
            ps.setInt(4, parametres.getPauseMinimaleEntreVacationsMinutes());
            ps.setInt(5, parametres.getReposQuotidienMinimalMinutes());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save legal parameters", e);
        }
    }

    /* --------------------------- Découpage parameters ------------------------ */

    public ParametresDecoupage getParametresDecoupage() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT duree_vacation_cible_minutes, duree_vacation_min_minutes, duree_vacation_max_minutes, "
                                + "duree_chevauchement_minutes, duree_pause_repas_minutes, fenetre_repas_midi_debut, "
                                + "fenetre_repas_midi_fin, fenetre_repas_soir_debut, fenetre_repas_soir_fin, "
                                + "strategie_couverture_pendant_pause, nombre_familles_decalage, "
                                + "duree_decalage_max_minutes FROM parametres_decoupage WHERE edition_id = ?");
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
                PreparedStatement ps = prepareScoped(connection,
                        "INSERT INTO parametres_decoupage (edition_id, duree_vacation_cible_minutes, "
                                + "duree_vacation_min_minutes, duree_vacation_max_minutes, duree_chevauchement_minutes, "
                                + "duree_pause_repas_minutes, fenetre_repas_midi_debut, fenetre_repas_midi_fin, "
                                + "fenetre_repas_soir_debut, fenetre_repas_soir_fin, strategie_couverture_pendant_pause, "
                                + "nombre_familles_decalage, duree_decalage_max_minutes) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (edition_id) DO UPDATE SET "
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
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT duree_resolution_secondes, mail_fin_resolution "
                                + "FROM parametres_solveur WHERE edition_id = ?");
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
                PreparedStatement ps = prepareScoped(connection,
                        "INSERT INTO parametres_solveur (edition_id, duree_resolution_secondes, "
                                + "mail_fin_resolution) VALUES (?, ?, ?) "
                                + "ON CONFLICT (edition_id) DO UPDATE SET "
                                + "duree_resolution_secondes = EXCLUDED.duree_resolution_secondes, "
                                + "mail_fin_resolution = EXCLUDED.mail_fin_resolution")) {
            ps.setInt(2, parametres.getDureeResolutionSecondes());
            ps.setBoolean(3, parametres.isMailFinResolution());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save solver parameters", e);
        }
    }

    /* ---------------------------- Constraint toggles ------------------------- */

    public java.util.Set<String> getContraintesDesactivees() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT nom FROM constraint_toggle WHERE edition_id = ?");
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
     * A row's presence means the constraint is disabled for the next solve;
     * re-enabling simply drops it. Nothing else is recorded: this is a toggle
     * table, not an audit log (see migration V39).
     */
    public void setContrainteActive(String nom, boolean actif) {
        try (Connection connection = dataSource.getConnection()) {
            if (actif) {
                try (PreparedStatement ps = prepareScoped(connection,
                        "DELETE FROM constraint_toggle WHERE edition_id = ? AND nom = ?")) {
                    ps.setString(2, nom);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = prepareScoped(connection,
                        "INSERT INTO constraint_toggle (edition_id, nom) VALUES (?, ?) "
                                + "ON CONFLICT (edition_id, nom) DO NOTHING")) {
                    ps.setString(2, nom);
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
     * Stands, animateurs and timeslots are fully replaced within the
     * edition. Each imported créneau receives a freshly
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
            connection.setAutoCommit(false);
            try {
                // verrouillage_planning goes with the assignments it freezes: the
                // reference dataset is being replaced, so the validated planning
                // those locks protected no longer exists — and planning_resolution
                // goes with it, so nothing keeps claiming "résolu le …" over an
                // empty plan. Stands and animateurs, on the other hand, are
                // DIFFED, not wiped: the file's rows are upserted (which keeps
                // an existing animateur's access token, sessions and demandes
                // alive) and only the rows absent from the file are deleted.
                for (String table : List.of("contrainte_animateur", "contrainte_ad_hoc", "verrouillage_planning",
                        "poste_affectation", "planning_resolution")) {
                    // Table names come from the literal list above, never from user input.
                    try (PreparedStatement ps = prepareScoped(connection,
                            "DELETE FROM " + table + " WHERE edition_id = ?")) {
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = prepareScoped(connection,
                        "DELETE FROM creneau WHERE edition_id = ?")) {
                    ps.executeUpdate();
                }
                Map<Long, Long> idsRemap = new LinkedHashMap<>();
                for (Creneau creneau : creneauxById.values()) {
                    Long ancienId = creneau.getId();
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
                        upsertAnimateur(connection, animateur, true);
                    }
                }
                // Rows the file does not carry are the only ones deleted — for
                // an animateur that also drops, by cascade, their demandes
                // d'échange, sessions and access code.
                supprimerAbsentsTx(connection, "stand", standsById.keySet());
                supprimerAbsentsTx(connection, "animateur", animateurs.stream()
                        .filter(animateur -> animateur != null && animateur.getId() != null)
                        .map(Animateur::getId)
                        .collect(java.util.stream.Collectors.toSet()));
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

    /**
     * What a scenario import would touch, counted for the confirmation dialog
     * shown before it runs: nothing here decides anything, it only lets the
     * operator see the blast radius — replaced referentials, erased resolved
     * planning, demandes and locks that will go with it.
     */
    public record ImpactImport(int animateurs, int stands, int postes, boolean planningResolu,
            int demandesEchange, int demandesEnAttente, int verrous) {
    }

    public ImpactImport compterImpactImport() {
        try (Connection connection = dataSource.getConnection()) {
            int animateurs = compter(connection, "animateur");
            int stands = compter(connection, "stand");
            int postes = compter(connection, "poste_affectation");
            int verrous = compter(connection, "verrouillage_planning");
            int demandes = 0;
            int enAttente = 0;
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE statut = 'PROPOSEE') AS en_attente "
                            + "FROM demande_echange WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    demandes = rs.getInt("total");
                    enAttente = rs.getInt("en_attente");
                }
            }
            boolean resolu = false;
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT 1 FROM planning_resolution WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                resolu = rs.next();
            }
            return new ImpactImport(animateurs, stands, postes, resolu, demandes, enAttente, verrous);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to measure the import impact", e);
        }
    }

    /** COUNT(*) of one edition-scoped table from the literal list of {@link #compterImpactImport}. */
    private int compter(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "SELECT COUNT(*) FROM " + table + " WHERE edition_id = ?");
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Deletes the rows of {@code table} (whitelisted by its two callers in
     * {@link #importFromPlanning}: {@code stand} or {@code animateur}) whose id
     * is not in {@code idsConserves} — the diff half of the import: what the
     * file does not name disappears, what it names was upserted in place.
     */
    private void supprimerAbsentsTx(Connection connection, String table, java.util.Set<String> idsConserves)
            throws SQLException {
        List<String> absents = new ArrayList<>();
        try (PreparedStatement ps = prepareScoped(connection,
                "SELECT id FROM " + table + " WHERE edition_id = ?");
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (!idsConserves.contains(id)) {
                    absents.add(id);
                }
            }
        }
        if (absents.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = prepareScoped(connection,
                "DELETE FROM " + table + " WHERE edition_id = ? AND id = ?")) {
            for (String id : absents) {
                ps.setString(2, id);
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ps.addBatch();
            }
            // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            ps.executeBatch();
        }
    }

    /** Inserts a new timeslot row; the generated id is set back onto {@code creneau} and returned. */
    private Long insertCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin, famille, "
                        + "couverture_pause) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
            ps.setObject(2, creneau.getDate());
            ps.setObject(3, creneau.getHeureDebut());
            ps.setObject(4, creneau.getHeureFin());
            ps.setInt(5, creneau.getFamille());
            ps.setBoolean(6, creneau.isCouverturePause());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong("id");
                creneau.setId(id);
            }
        }
        return creneau.getId();
    }

    private void updateCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        // Not prepareScoped: an UPDATE's first placeholder belongs to its SET
        // clause, so the edition predicate can't be the statement's first one.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE creneau SET date_creneau = ?, heure_debut = ?, heure_fin = ?, "
                        + "famille = ?, couverture_pause = ? WHERE edition_id = ? AND id = ?")) {
            ps.setObject(1, creneau.getDate());
            ps.setObject(2, creneau.getHeureDebut());
            ps.setObject(3, creneau.getHeureFin());
            ps.setInt(4, creneau.getFamille());
            ps.setBoolean(5, creneau.isCouverturePause());
            ps.setString(6, editionId());
            ps.setLong(7, creneau.getId());
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

    /**
     * Prepares {@code sql} with the current group already bound to its
     * <b>first</b> placeholder — so write the {@code edition_id = ?} predicate
     * (or the {@code edition_id} column of an INSERT) first, and bind the
     * remaining parameters from index 2. An UPDATE, whose first placeholder
     * necessarily belongs to its SET clause, binds the group by hand instead.
     */
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

    /**
     * Table names come from this class's own call sites, never from user input —
     * a table name cannot be bound as a parameter, so it is concatenated; the id
     * that varies travels as a bound parameter. Same reasoning for every
     * {@code nosemgrep} of this file (see {@code docs/securite.md}).
     */
    private boolean exists(String table, String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT 1 FROM " + table + " WHERE edition_id = ? AND id = ?")) {
            ps.setString(2, id);
            // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to probe " + table + " " + id, e);
        }
    }

    private void delete(String sql, String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete " + id, e);
        }
    }

    private boolean existsLong(String table, Long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT 1 FROM " + table + " WHERE edition_id = ? AND id = ?")) {
            ps.setLong(2, id);
            // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to probe " + table + " " + id, e);
        }
    }

    private void deleteLong(String sql, Long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete " + id, e);
        }
    }
}
