package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.domain.HorairesRecurrents.Regle;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.NaturalOrder;
import dev.sylvain.planning.service.WriteStamp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Stand rows and everything hanging off one: proposed typologies and the
 * plages (dated closures and openings — two tables, one list on the stand).
 *
 * <p>A stand is read in one pass and hydrated fully — four tables, four
 * statements, one connection — because every caller wants the whole object;
 * a lazily-filled stand would just move the round-trips somewhere less
 * visible.</p>
 */
@ApplicationScoped
public class StandRepository {

    private static final String COL_STAND_ID = "stand_id";
    private static final String COL_HEURE_DEBUT = "heure_debut";
    private static final String COL_HEURE_FIN = "heure_fin";
    private static final String COL_MOTIF = "motif";
    private static final String COL_DEPLIEE = "depliee";
    private static final String COL_CONSIGNE = "consigne";

    private final ConcurrentModificationGuard staleWrites;

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    @Inject
    public StandRepository(ConcurrentModificationGuard staleWrites, DataSource dataSource, JdbcEditionScope scope) {
        this.staleWrites = staleWrites;
        this.dataSource = dataSource;
        this.scope = scope;
    }

    public List<Stand> listStands() {
        Map<String, Stand> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT s.id, s.code, s.nom, s.effectif_min, s.effectif_max, s.reserve_majeurs,
                    s.premium, s.niveau_effort, s.modifie_le, e.id AS emplacement_id, e.code AS emplacement_code,
                    e.nom AS emplacement_nom,
                    e.latitude AS emplacement_latitude, e.longitude AS emplacement_longitude
                    FROM stand s
                    LEFT JOIN emplacement e ON e.edition_id = s.edition_id AND e.id = s.emplacement_id
                    WHERE s.edition_id = ?
                    ORDER BY s.id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = new Stand();
                    stand.setId(rs.getString("id"));
                    stand.setCode(rs.getString("code"));
                    stand.setNom(rs.getString("nom"));
                    stand.setEffectifMin(rs.getInt("effectif_min"));
                    stand.setEffectifMax(rs.getInt("effectif_max"));
                    stand.setReserveMajeurs(rs.getBoolean("reserve_majeurs"));
                    stand.setPremium(rs.getBoolean("premium"));
                    stand.setNiveauEffort(NiveauEffort.valueOf(rs.getString("niveau_effort")));
                    stand.setModifieLe(
                            rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
                    String emplacementId = rs.getString("emplacement_id");
                    if (emplacementId != null) {
                        Emplacement emplacement = new Emplacement(
                                emplacementId,
                                rs.getString("emplacement_nom"),
                                (Double) rs.getObject("emplacement_latitude"),
                                (Double) rs.getObject("emplacement_longitude"));
                        emplacement.setCode(rs.getString("emplacement_code"));
                        stand.setEmplacement(emplacement);
                    }
                    byId.put(stand.getId(), stand);
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(
                            connection, "SELECT stand_id, typologie FROM stand_typologie WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString(COL_STAND_ID));
                    if (stand != null) {
                        stand.getTypologiesProposees().add(rs.getString("typologie"));
                    }
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif, depliee, consigne
                    FROM stand_indisponibilite
                    WHERE edition_id = ?
                    ORDER BY id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString(COL_STAND_ID));
                    if (stand != null) {
                        PlageStand fermeture = PlageStand.fermeture(
                                rs.getLong("id"),
                                rs.getObject("date_indisponibilite", LocalDate.class),
                                rs.getObject(COL_HEURE_DEBUT, LocalTime.class),
                                rs.getObject(COL_HEURE_FIN, LocalTime.class),
                                rs.getString(COL_MOTIF));
                        fermeture.setDepliee(rs.getBoolean(COL_DEPLIEE));
                        fermeture.setConsigne(rs.getBoolean(COL_CONSIGNE));
                        stand.getPlages().add(fermeture);
                    }
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif, depliee, consigne
                    FROM stand_ouverture
                    WHERE edition_id = ?
                    ORDER BY id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString(COL_STAND_ID));
                    if (stand != null) {
                        PlageStand ouverture = PlageStand.ouverture(
                                rs.getLong("id"),
                                rs.getObject("date_ouverture", LocalDate.class),
                                rs.getObject(COL_HEURE_DEBUT, LocalTime.class),
                                rs.getObject(COL_HEURE_FIN, LocalTime.class),
                                rs.getString(COL_MOTIF),
                                rs.getObject("effectif", Integer.class));
                        ouverture.setDepliee(rs.getBoolean(COL_DEPLIEE));
                        ouverture.setConsigne(rs.getBoolean(COL_CONSIGNE));
                        stand.getPlages().add(ouverture);
                    }
                }
            }
            Map<String, List<Regle>> horaires = loadHoraires(connection);
            byId.forEach((id, stand) -> stand.setHoraires(horaires.getOrDefault(id, List.of())));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list stands", e);
        }
        List<Stand> stands = new ArrayList<>(byId.values());
        stands.sort(Comparator.comparing(Stand::getId, NaturalOrder.OF_IDS));
        return stands;
    }

    /** True when some stand of the edition holds a model: the cheap test before any unfolding. */
    boolean hasModels(Connection connection) throws SQLException {
        try (PreparedStatement ps =
                        scope.prepareScoped(connection, "SELECT 1 FROM stand_horaire WHERE edition_id = ? LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    /**
     * Lays every stand's model on {@code jours}, the days an edition has just gained (ADR 0104): the
     * plages the rules stand for, read as 3.x read them at every read. A stand that already carries a
     * plage on a day keeps it, so a day unfolded twice — or one dated by hand — gets no duplicate.
     */
    void unfoldModels(Connection connection, Set<LocalDate> jours) throws SQLException {
        Map<String, List<Regle>> models = loadHoraires(connection);
        if (models.isEmpty() || jours.isEmpty()) {
            return;
        }
        Map<String, List<PlageStand>> declared = new LinkedHashMap<>();
        try (PreparedStatement ps = scope.prepareScoped(
                        connection,
                        "SELECT stand_id, date_ouverture AS jour FROM stand_ouverture WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            collectDeclaredDays(rs, declared);
        }
        try (PreparedStatement ps = scope.prepareScoped(
                        connection,
                        "SELECT stand_id, date_indisponibilite AS jour FROM stand_indisponibilite WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            collectDeclaredDays(rs, declared);
        }
        models.forEach((standId, rules) -> {
            List<PlageStand> exceptions = declared.getOrDefault(standId, List.of());
            List<PlageStand> expanded = HorairesRecurrents.expand(rules, exceptions, jours);
            List<PlageStand> added = expanded.subList(exceptions.size(), expanded.size());
            try {
                insertPlages(connection, standId, added);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to unfold the schedule model of stand " + standId, e);
            }
        });
    }

    /** The days each stand already carries a plage on, as placeholders the rules leave alone. */
    private static void collectDeclaredDays(ResultSet rs, Map<String, List<PlageStand>> declared) throws SQLException {
        while (rs.next()) {
            declared.computeIfAbsent(rs.getString(COL_STAND_ID), id -> new ArrayList<>())
                    .add(PlageStand.fermeture(
                            null, rs.getObject("jour", LocalDate.class), LocalTime.MIDNIGHT, null, null));
        }
    }

    /** The model of one stand (its recurring rules), empty when it has none. */
    public List<Regle> modelRules(String standId) {
        return scope.read(
                "Failed to read the schedule model of stand " + standId,
                connection -> loadHoraires(connection).getOrDefault(standId, List.of()));
    }

    /**
     * The model of every stand of the edition, windows included, in two queries: stand id to its
     * rules in the order they were declared.
     */
    Map<String, List<Regle>> loadHoraires(Connection connection) throws SQLException {
        Map<Long, List<FenetreHoraire>> fenetres = new LinkedHashMap<>();
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                SELECT horaire_id, heure_debut, heure_fin, effectif
                FROM stand_horaire_fenetre
                WHERE edition_id = ?
                ORDER BY horaire_id, position, id""");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fenetres.computeIfAbsent(rs.getLong("horaire_id"), id -> new ArrayList<>())
                        .add(new FenetreHoraire(
                                rs.getObject(COL_HEURE_DEBUT, LocalTime.class),
                                rs.getObject(COL_HEURE_FIN, LocalTime.class),
                                rs.getObject("effectif", Integer.class)));
            }
        }
        Map<String, List<Regle>> parStand = new LinkedHashMap<>();
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                SELECT id, stand_id, mode, type_jours, jours_semaine, date_debut, date_fin, dates::text[] AS dates, motif
                FROM stand_horaire
                WHERE edition_id = ?
                ORDER BY stand_id, id""");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                parStand.computeIfAbsent(rs.getString(COL_STAND_ID), stand -> new ArrayList<>())
                        .add(new Regle(
                                ModeHoraire.valueOf(rs.getString("mode")),
                                TypeJoursHoraire.valueOf(rs.getString("type_jours")),
                                readWeekdays(rs.getString("jours_semaine")),
                                rs.getObject("date_debut", LocalDate.class),
                                rs.getObject("date_fin", LocalDate.class),
                                readDates(rs.getArray("dates")),
                                fenetres.getOrDefault(id, List.of()),
                                rs.getString(COL_MOTIF),
                                id));
            }
        }
        return parStand;
    }

    /**
     * Reads back the {@code date[]} column (V122), selected as {@code text[]}
     * so no element goes through the JVM's time zone; an empty set when it is
     * {@code NULL}.
     */
    private static Set<LocalDate> readDates(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        try {
            return Arrays.stream((Object[]) array.getArray())
                    .map(date -> LocalDate.parse((String) date))
                    .collect(Collectors.toCollection(TreeSet::new));
        } finally {
            array.free();
        }
    }

    /** Writes a rule's dates as the {@code date[]} {@link #readDates} reads, or {@code null} when empty. */
    private static Array dateArray(Connection connection, Set<LocalDate> dates) throws SQLException {
        if (dates == null || dates.isEmpty()) {
            return null;
        }
        return connection.createArrayOf(
                "date", dates.stream().map(LocalDate::toString).toArray(String[]::new));
    }

    /** Reads back the weekdays column, comma-separated names: seven at most, so V37's text form holds them. */
    private static Set<DayOfWeek> readWeekdays(String names) {
        if (names == null || names.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(names.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String joinWeekdays(Set<DayOfWeek> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            return null;
        }
        return weekdays.stream().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    /** One stand, or {@code null} — what a write needs to compare against, instead of the whole referential. */
    public Stand findStand(String id) {
        return listStands().stream()
                .filter(stand -> Objects.equals(stand.getId(), id))
                .findFirst()
                .orElse(null);
    }

    /** Id of the stand carrying {@code code} in the current edition, {@code null} when none does. */
    public String idByCode(String code) {
        return scope.read("Failed to look stand code " + code + " up", connection -> idByCode(connection, code));
    }

    /** Same lookup inside a caller's transaction. */
    String idByCode(Connection connection, String code) throws SQLException {
        if (code == null || code.isBlank()) {
            return null;
        }
        try (PreparedStatement ps =
                scope.prepareScoped(connection, "SELECT id FROM stand WHERE edition_id = ? AND code = ?")) {
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("id") : null;
            }
        }
    }

    public boolean standExists(String id) {
        return scope.exists("stand", id);
    }

    /**
     * Writes the stand, refusing a creation whose id is taken and an update
     * based on an out-of-date read (issue #362): both are the write's own
     * precondition, never a probe before it.
     *
     * @param failIfPresent true on a creation — an existing row is then a 409,
     *                      not a silent replacement
     */
    public void saveStand(Stand stand, boolean failIfPresent) {
        scope.write("Failed to save stand " + stand.getId(), connection -> {
            upsertStand(connection, stand, failIfPresent);
        });
    }

    /** The same write inside a caller's transaction. */
    void saveStand(Connection connection, Stand stand, boolean failIfPresent) throws SQLException {
        upsertStand(connection, stand, failIfPresent);
    }

    /**
     * Every stand of the list, in one transaction: all written, or none — what
     * a grid save or an import promises. Each stand keeps its own precondition.
     */
    public void saveStands(List<Stand> stands) {
        scope.write("Failed to save " + stands.size() + " stands", connection -> {
            for (Stand stand : stands) {
                upsertStand(connection, stand, false);
            }
        });
    }

    /**
     * Deletes one stand, and the seats that were opened on it.
     *
     * <p>Twin of {@link CreneauRepository#deleteCreneau}: {@code poste_affectation}
     * is the one table referencing {@code stand} whose foreign key neither
     * cascades nor nulls out, so until now the delete simply failed as soon as a
     * plan was persisted — that is after any solve at all — and failed as a 500
     * rather than as a refusal anyone could act on.</p>
     *
     * <p>Destroying the seats is the only reading available and the right one:
     * {@code poste_affectation.stand_id} is {@code NOT NULL}, so a seat cannot
     * outlive its stand, and the reader already agrees —
     * {@code PlanningPersistenceService.assemblerPlanning} drops any seat naming
     * a stand the referential no longer holds. The rest of the plan survives.</p>
     *
     * <p>Contrast {@link AnimateurRepository#deleteAnimateur}, which vacates its
     * seats instead of removing them: {@code animateur_id} is nullable, so there
     * the hole can be left visible. Here it cannot.</p>
     *
     * <p>The rule lives here rather than in an {@code ON DELETE CASCADE} so that
     * it can be read and tested in the code, which is the decision issue #281
     * took for créneaux.</p>
     */
    public void deleteStand(String id) {
        scope.write("Failed to delete stand " + id, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM poste_affectation WHERE edition_id = ? AND stand_id = ?")) {
                ps.setString(2, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    scope.prepareScoped(connection, "DELETE FROM stand WHERE edition_id = ? AND id = ?")) {
                ps.setString(2, id);
                ps.executeUpdate();
            }
        });
    }

    void upsertStand(Connection connection, Stand stand) throws SQLException {
        upsertStand(connection, stand, false);
    }

    void upsertStand(Connection connection, Stand stand, boolean failIfPresent) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO stand (edition_id, id, code, nom, effectif_min, effectif_max,
                reserve_majeurs, premium, emplacement_id, niveau_effort)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET code = EXCLUDED.code, nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min,
                effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs,
                premium = EXCLUDED.premium, emplacement_id = EXCLUDED.emplacement_id,
                niveau_effort = EXCLUDED.niveau_effort,
                modifie_le = now()
                WHERE CAST(? AS boolean)
                AND (CAST(? AS timestamptz) IS NULL
                     OR date_trunc('milliseconds', stand.modifie_le)
                        = date_trunc('milliseconds', CAST(? AS timestamptz)))
                RETURNING modifie_le""")) {
            ps.setString(2, stand.getId());
            ps.setString(3, stand.getCode());
            ps.setString(4, stand.getNom());
            ps.setInt(5, stand.getEffectifMin());
            ps.setInt(6, stand.getEffectifMax());
            ps.setBoolean(7, stand.isReserveMajeurs());
            ps.setBoolean(8, stand.isPremium());
            ps.setString(
                    9, stand.getEmplacement() != null ? stand.getEmplacement().getId() : null);
            ps.setString(10, stand.getNiveauEffort().name());
            WriteStamp.bindPrecondition(ps, 11, !failIfPresent, stand.getModifieLe());
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                staleWrites.refuse(failIfPresent, "stand", stand.getId());
            }
            stand.setModifieLe(ecrit);
        }
        try (PreparedStatement del =
                scope.prepareScoped(connection, "DELETE FROM stand_typologie WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getTypologiesProposees() != null
                && !stand.getTypologiesProposees().isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(
                    connection, "INSERT INTO stand_typologie (edition_id, stand_id, typologie) VALUES (?, ?, ?)")) {
                for (String typologie : stand.getTypologiesProposees()) {
                    ins.setString(2, stand.getId());
                    ins.setString(3, typologie);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = scope.prepareScoped(
                connection, "DELETE FROM stand_indisponibilite WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        try (PreparedStatement del =
                scope.prepareScoped(connection, "DELETE FROM stand_ouverture WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        insertPlages(connection, stand.getId(), stand.getPlages());
        if (stand.horairesEnvoyes() != null) {
            upsertHoraires(connection, stand);
        }
    }

    /** Adds plages to a stand, closures and openings each to their own table; nothing is deleted. */
    void insertPlages(Connection connection, String standId, List<PlageStand> plages) throws SQLException {
        List<PlageStand> fermetures = plages.stream()
                .filter(p -> p.getMode() == ModeHoraire.FERMETURE)
                .toList();
        if (!fermetures.isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite,
                    heure_debut, heure_fin, motif, depliee, consigne)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (PlageStand indispo : fermetures) {
                    ins.setString(2, standId);
                    ins.setObject(3, indispo.getDate());
                    ins.setObject(4, indispo.getHeureDebut());
                    ins.setObject(5, indispo.getHeureFin());
                    ins.setString(6, indispo.getMotif());
                    ins.setBoolean(7, indispo.isDepliee());
                    ins.setBoolean(8, indispo.isConsigne());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        List<PlageStand> ouvertures = plages.stream()
                .filter(p -> p.getMode() == ModeHoraire.OUVERTURE)
                .toList();
        if (!ouvertures.isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_ouverture
                        (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif, depliee,
                         consigne)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (PlageStand ouverture : ouvertures) {
                    ins.setString(2, standId);
                    ins.setObject(3, ouverture.getDate());
                    ins.setObject(4, ouverture.getHeureDebut());
                    ins.setObject(5, ouverture.getHeureFin());
                    ins.setString(6, ouverture.getMotif());
                    ins.setObject(7, ouverture.getEffectif());
                    ins.setBoolean(8, ouverture.isDepliee());
                    ins.setBoolean(9, ouverture.isConsigne());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }

    /** Replaces the stand's model wholesale — the windows go with their rule through {@code ON DELETE CASCADE}. */
    private void upsertHoraires(Connection connection, Stand stand) throws SQLException {
        try (PreparedStatement del =
                scope.prepareScoped(connection, "DELETE FROM stand_horaire WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        for (Regle regle : stand.getHoraires()) {
            long horaireId;
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_horaire (edition_id, stand_id, mode, type_jours,
                    jours_semaine, date_debut, date_fin, dates, motif)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id""")) {
                ins.setString(2, stand.getId());
                ins.setString(3, regle.mode().name());
                ins.setString(4, regle.jours().name());
                ins.setString(5, joinWeekdays(regle.joursSemaine()));
                ins.setObject(6, regle.dateDebut());
                ins.setObject(7, regle.dateFin());
                ins.setArray(8, dateArray(connection, regle.dates()));
                ins.setString(9, regle.motif());
                try (ResultSet rs = ins.executeQuery()) {
                    rs.next();
                    horaireId = rs.getLong("id");
                }
            }
            if (regle.fenetres().isEmpty()) {
                continue;
            }
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_horaire_fenetre
                        (edition_id, horaire_id, position, heure_debut, heure_fin, effectif)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                // A parameter keeps its value across addBatch() until it is set again.
                ins.setLong(2, horaireId);
                int position = 0;
                for (FenetreHoraire fenetre : regle.fenetres()) {
                    ins.setInt(3, position++);
                    ins.setObject(4, fenetre.getHeureDebut());
                    ins.setObject(5, fenetre.getHeureFin());
                    ins.setObject(6, fenetre.getEffectif());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }
}
