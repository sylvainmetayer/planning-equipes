package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.NaturalOrder;
import dev.sylvain.planning.service.WriteStamp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Stand rows and everything hanging off one: proposed typologies, dated
 * closures and openings, and the recurring horaire rules with their windows.
 *
 * <p>A stand is read in one pass and hydrated fully — five tables, five
 * statements, one connection — because every caller wants the whole object;
 * a lazily-filled stand would just move the round-trips somewhere less
 * visible.</p>
 */
@ApplicationScoped
public class StandRepository {

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
                    SELECT s.id, s.nom, s.effectif_min, s.effectif_max, s.reserve_majeurs,
                    s.premium, s.niveau_effort, s.modifie_le, e.id AS emplacement_id, e.nom AS emplacement_nom,
                    e.latitude AS emplacement_latitude, e.longitude AS emplacement_longitude
                    FROM stand s
                    LEFT JOIN emplacement e ON e.edition_id = s.edition_id AND e.id = s.emplacement_id
                    WHERE s.edition_id = ?
                    ORDER BY s.id""");
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
                    stand.setModifieLe(
                            rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
                    String emplacementId = rs.getString("emplacement_id");
                    if (emplacementId != null) {
                        stand.setEmplacement(new Emplacement(
                                emplacementId,
                                rs.getString("emplacement_nom"),
                                (Double) rs.getObject("emplacement_latitude"),
                                (Double) rs.getObject("emplacement_longitude")));
                    }
                    byId.put(stand.getId(), stand);
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(
                            connection, "SELECT stand_id, typologie FROM stand_typologie WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getTypologiesProposees().add(rs.getString("typologie"));
                    }
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif
                    FROM stand_indisponibilite
                    WHERE edition_id = ?
                    ORDER BY id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getIndisponibilites()
                                .add(new IndisponibiliteStand(
                                        rs.getLong("id"),
                                        rs.getObject("date_indisponibilite", LocalDate.class),
                                        rs.getObject("heure_debut", LocalTime.class),
                                        rs.getObject("heure_fin", LocalTime.class),
                                        rs.getString("motif")));
                    }
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif
                    FROM stand_ouverture
                    WHERE edition_id = ?
                    ORDER BY id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stand stand = byId.get(rs.getString("stand_id"));
                    if (stand != null) {
                        stand.getOuvertures()
                                .add(new OuvertureStand(
                                        rs.getLong("id"),
                                        rs.getObject("date_ouverture", LocalDate.class),
                                        rs.getObject("heure_debut", LocalTime.class),
                                        rs.getObject("heure_fin", LocalTime.class),
                                        rs.getString("motif"),
                                        rs.getObject("effectif", Integer.class)));
                    }
                }
            }
            loadHoraires(connection, byId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list stands", e);
        }
        List<Stand> stands = new ArrayList<>(byId.values());
        stands.sort(Comparator.comparing(Stand::getId, NaturalOrder.OF_IDS));
        return stands;
    }

    /**
     * Hydrates the recurring {@link HoraireStand} rules of every stand of
     * {@code standsById}, windows included, in two queries rather than two per
     * stand. Rules are keyed by their own id so the windows can be attached
     * without re-walking the stands.
     */
    private void loadHoraires(Connection connection, Map<String, Stand> standsById) throws SQLException {
        Map<Long, HoraireStand> horairesById = new LinkedHashMap<>();
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                SELECT id, stand_id, mode, type_jours, jours_semaine, date_debut, date_fin, dates, motif
                FROM stand_horaire
                WHERE edition_id = ?
                ORDER BY stand_id, id""");
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
                horaire.setJoursSemaine(splitCsv(rs.getString("jours_semaine"), DayOfWeek::valueOf));
                horaire.setDateDebut(rs.getObject("date_debut", LocalDate.class));
                horaire.setDateFin(rs.getObject("date_fin", LocalDate.class));
                horaire.setDates(splitCsv(rs.getString("dates"), LocalDate::parse));
                horaire.setMotif(rs.getString("motif"));
                stand.getHoraires().add(horaire);
                horairesById.put(horaire.getId(), horaire);
            }
        }
        if (horairesById.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                SELECT horaire_id, heure_debut, heure_fin, effectif
                FROM stand_horaire_fenetre
                WHERE edition_id = ?
                ORDER BY horaire_id, position, id""");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                HoraireStand horaire = horairesById.get(rs.getLong("horaire_id"));
                if (horaire != null) {
                    horaire.getFenetres()
                            .add(new FenetreHoraire(
                                    rs.getObject("heure_debut", LocalTime.class),
                                    rs.getObject("heure_fin", LocalTime.class),
                                    rs.getObject("effectif", Integer.class)));
                }
            }
        }
    }

    /** Reads back a comma-separated leaf column (see V37 on why these two aren't normalised). */
    private static <T> Set<T> splitCsv(String csv, Function<String, T> parse) {
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

    /** Writes a set back as the comma-separated form {@link #splitCsv} reads, or {@code null} when empty. */
    private static String joindreCsv(Collection<?> valeurs) {
        if (valeurs == null || valeurs.isEmpty()) {
            return null;
        }
        return valeurs.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** One stand, or {@code null} — what a write needs to compare against, instead of the whole referential. */
    public Stand findStand(String id) {
        return listStands().stream()
                .filter(stand -> id.equals(stand.getId()))
                .findFirst()
                .orElse(null);
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
    /** The write's own precondition said no: a taken id on a creation, a stale read otherwise. */
    private void refuse(boolean failIfPresent, String table, String id) {
        if (failIfPresent) {
            staleWrites.refuseDuplicate(table, id);
        }
        staleWrites.refuseStale(table, id);
    }

    public void saveStand(Stand stand, boolean failIfPresent) {
        scope.write("Failed to save stand " + stand.getId(), connection -> {
            upsertStand(connection, stand, failIfPresent);
        });
    }

    /** The same write inside a caller's transaction. */
    void saveStand(Connection connection, Stand stand, boolean failIfPresent) throws SQLException {
        upsertStand(connection, stand, failIfPresent);
    }

    /** Every stand of the list, in one transaction: all written, or none — what a grid save or an import promises. */
    /** Several stands, one transaction, each with its own precondition. */
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
                INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max,
                reserve_majeurs, premium, emplacement_id, niveau_effort)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min,
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
            ps.setString(3, stand.getNom());
            ps.setInt(4, stand.getEffectifMin());
            ps.setInt(5, stand.getEffectifMax());
            ps.setBoolean(6, stand.isReserveMajeurs());
            ps.setBoolean(7, stand.isPremium());
            ps.setString(
                    8, stand.getEmplacement() != null ? stand.getEmplacement().getId() : null);
            ps.setString(9, stand.getNiveauEffort().name());
            WriteStamp.bindPrecondition(ps, 10, !failIfPresent, stand.getModifieLe());
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                refuse(failIfPresent, "stand", stand.getId());
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
        if (stand.getIndisponibilites() != null && !stand.getIndisponibilites().isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite,
                    heure_debut, heure_fin, motif)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
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
        try (PreparedStatement del =
                scope.prepareScoped(connection, "DELETE FROM stand_ouverture WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getOuvertures() != null && !stand.getOuvertures().isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_ouverture
                        (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                for (OuvertureStand ouverture : stand.getOuvertures()) {
                    ins.setString(2, stand.getId());
                    ins.setObject(3, ouverture.getDate());
                    ins.setObject(4, ouverture.getHeureDebut());
                    ins.setObject(5, ouverture.getHeureFin());
                    ins.setString(6, ouverture.getMotif());
                    ins.setObject(7, ouverture.getEffectif());
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
        try (PreparedStatement del =
                scope.prepareScoped(connection, "DELETE FROM stand_horaire WHERE edition_id = ? AND stand_id = ?")) {
            del.setString(2, stand.getId());
            del.executeUpdate();
        }
        if (stand.getHoraires() == null || stand.getHoraires().isEmpty()) {
            return;
        }
        for (HoraireStand horaire : stand.getHoraires()) {
            long horaireId;
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_horaire (edition_id, stand_id, mode, type_jours,
                    jours_semaine, date_debut, date_fin, dates, motif)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id""")) {
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
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO stand_horaire_fenetre
                        (edition_id, horaire_id, position, heure_debut, heure_fin, effectif)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                int position = 0;
                for (FenetreHoraire fenetre : horaire.getFenetres()) {
                    ins.setLong(2, horaireId);
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
