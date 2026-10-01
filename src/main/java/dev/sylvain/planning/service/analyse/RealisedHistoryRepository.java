package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapCounts;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapTotal;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.PreviousEdition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The frozen measure of finished editions ({@code kpi_realise}): one row per
 * edition and game category, and one for the whole event under the empty
 * game category id.
 *
 * <p>Like {@code kpi_historique}, the table carries no foreign key and keeps
 * the names it shows: the measure outlives the edition it describes, since
 * the edition that reads it is the next one. Writing goes through the
 * edition's own scope — the nightly job enters each edition before freezing
 * it —, reading the previous measure deliberately does not: it is, by
 * definition, another edition's row, possibly of an edition deleted since.</p>
 */
@ApplicationScoped
public class RealisedHistoryRepository {

    /** The game category id of the whole event's row. */
    static final String EVENT_ROW = "";

    private static final String EXISTS = "SELECT 1 FROM kpi_realise WHERE edition_id = ? LIMIT 1";

    private static final String INSERT = """
 INSERT INTO kpi_realise (edition_id, typologie_id, edition_nom, typologie_nom, premier_jour, dernier_jour,
 jours_comptes, sieges_publies, sieges_tenus, absences, remplacements, sieges_vides, sieges_retires,
 sieges_ajoutes, minutes_publiees, minutes_realisees, minutes_perdues, fige_le)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
 ON CONFLICT (edition_id, typologie_id) DO NOTHING""";

    /**
     * The event row of the edition whose event ended last before {@code
     * before}, any edition but the reader's — deleted ones included.
     *
     * <p>Two events ending the same day — a variant duplicated from the real
     * edition, typically — are told apart by the measure that covers more
     * days, then by the one frozen first, then by id: a variant published
     * late counts fewer days (a late reference is never counted), and the
     * real edition's rows, written once, are the older ones. Deterministic
     * whatever the order the job met them in.</p>
     */
    private static final String SELECT_PREVIOUS_EVENT = """
 SELECT edition_id, edition_nom, premier_jour, dernier_jour, jours_comptes, fige_le
 FROM kpi_realise
 WHERE edition_id <> ? AND typologie_id = '' AND dernier_jour < ?
 ORDER BY dernier_jour DESC, jours_comptes DESC, fige_le ASC, edition_id
 LIMIT 1""";

    private static final String SELECT_ROWS = """
 SELECT typologie_id, typologie_nom, sieges_publies, absences, remplacements, sieges_vides, sieges_retires,
 sieges_ajoutes, minutes_publiees, minutes_realisees, minutes_perdues
 FROM kpi_realise
 WHERE edition_id = ?
 ORDER BY typologie_nom, typologie_id""";

    private final JdbcEditionScope scope;

    @Inject
    public RealisedHistoryRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Whether the current edition's measure was ever frozen. */
    public boolean exists() {
        return scope.read("Failed to read the realised measure", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, EXISTS);
                    ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        });
    }

    /**
     * Writes the current edition's measure from {@code report}, in one
     * transaction — once: a row already there is kept as it is ({@code ON
     * CONFLICT DO NOTHING}), so a second run that raced past the job's
     * {@link #exists()} check writes nothing. Rewriting a measure is an
     * operator's SQL {@code DELETE}, then the next night.
     *
     * @return how many rows were written
     */
    public int insert(
            String editionNom, LocalDate firstDay, LocalDate lastDay, RealisedVsPlanned report, Instant frozenAt) {
        int countedDays = (int) report.days().stream()
                .filter(RealisedVsPlanned.RealisedDay::counted)
                .count();
        List<GapTotal> rows = new ArrayList<>();
        rows.add(new GapTotal(EVENT_ROW, null, report.event()));
        rows.addAll(report.byTypologie());
        return scope.writeAndReturn("Failed to freeze the realised measure", connection -> {
            try (PreparedStatement insert = scope.prepareScoped(connection, INSERT)) {
                // The same on every row: bound once, kept by each addBatch.
                insert.setString(3, editionNom);
                insert.setDate(5, Date.valueOf(firstDay));
                insert.setDate(6, Date.valueOf(lastDay));
                insert.setInt(7, countedDays);
                insert.setTimestamp(18, Timestamp.from(frozenAt));
                for (GapTotal row : rows) {
                    GapCounts counts = row.counts();
                    insert.setString(2, row.key());
                    insert.setString(4, row.label());
                    insert.setInt(8, counts.publishedSeats());
                    insert.setInt(9, counts.keptSeats());
                    insert.setInt(10, counts.absences());
                    insert.setInt(11, counts.replacements());
                    insert.setInt(12, counts.emptySeats());
                    insert.setInt(13, counts.removedSeats());
                    insert.setInt(14, counts.addedSeats());
                    insert.setInt(15, counts.publishedMinutes());
                    insert.setInt(16, counts.realisedMinutes());
                    insert.setInt(17, counts.lostMinutes());
                    insert.addBatch();
                }
                int written = 0;
                for (int count : insert.executeBatch()) {
                    // A driver rewriting the batch reports no count: the row went in.
                    written += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, count);
                }
                return written;
            }
        });
    }

    /**
     * The measure the current edition reads as its predecessor's: the edition
     * whose event ended last before {@code firstDay}, the reader's first day.
     * Editions carry no dates, so the order of the events is the only order
     * that means « previous » — the creation order would name a variant
     * duplicated last week, and a name is free text.
     */
    public PreviousEdition previous(LocalDate firstDay) {
        return scope.read("Failed to read the previous realised measure", connection -> {
            PreviousEdition entete;
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_PREVIOUS_EVENT)) {
                ps.setDate(2, Date.valueOf(firstDay));
                entete = readHeader(ps);
            }
            if (entete == null) {
                return PreviousEdition.NONE;
            }
            return withRows(connection, entete);
        });
    }

    private static PreviousEdition readHeader(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Timestamp figeLe = rs.getTimestamp("fige_le");
            return new PreviousEdition(
                    true,
                    rs.getString("edition_id"),
                    rs.getString("edition_nom"),
                    rs.getObject("premier_jour", LocalDate.class),
                    rs.getObject("dernier_jour", LocalDate.class),
                    rs.getInt("jours_comptes"),
                    figeLe == null ? null : figeLe.toInstant(),
                    null,
                    List.of());
        }
    }

    /**
     * The rows of the edition {@code entete} names. Not scoped: that edition is
     * not the reader's, and may no longer exist.
     */
    private static PreviousEdition withRows(Connection connection, PreviousEdition entete) throws SQLException {
        GapCounts event = GapCounts.ZERO;
        List<GapTotal> byTypologie = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ROWS)) {
            ps.setString(1, entete.editionId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GapCounts counts = GapCounts.of(
                            rs.getInt("sieges_publies"),
                            rs.getInt("absences"),
                            rs.getInt("remplacements"),
                            rs.getInt("sieges_vides"),
                            rs.getInt("sieges_retires"),
                            rs.getInt("sieges_ajoutes"),
                            rs.getInt("minutes_publiees"),
                            rs.getInt("minutes_realisees"),
                            rs.getInt("minutes_perdues"));
                    String typologieId = rs.getString("typologie_id");
                    if (EVENT_ROW.equals(typologieId)) {
                        event = counts;
                    } else {
                        String nom = rs.getString("typologie_nom");
                        byTypologie.add(new GapTotal(typologieId, nom == null ? typologieId : nom, counts));
                    }
                }
            }
        }
        return new PreviousEdition(
                true,
                entete.editionId(),
                entete.editionNom(),
                entete.firstDay(),
                entete.lastDay(),
                entete.countedDays(),
                entete.frozenAt(),
                event,
                List.copyOf(byTypologie));
    }
}
