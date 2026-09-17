package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.WriteStamp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The typologie referential, and the single {@code ninja} flag it carries.
 *
 * <p>Marking one typologie ninja demotes the previous holder — an
 * {@code UPDATE} that must never leave its edition. It once did, and marking
 * a ninja on one edition silently cleared every other edition's; see
 * {@code IsolationEditionStructurelleTest}.</p>
 */
@ApplicationScoped
public class TypologieRepository {

    @Inject
    ConcurrentModificationGuard staleWrites;

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public List<TypologieItem> listTypologies() {
        List<TypologieItem> typologies = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT id, label, ninja, max_creneaux_par_animateur, description, modifie_le
                        FROM typologie WHERE edition_id = ? ORDER BY id""");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                typologies.add(new TypologieItem(
                        rs.getString("id"),
                        rs.getString("label"),
                        rs.getBoolean("ninja"),
                        (Integer) rs.getObject("max_creneaux_par_animateur"),
                        rs.getString("description"),
                        rs.getObject("modifie_le", OffsetDateTime.class).toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list typologies", e);
        }
        return typologies;
    }

    /** Id of the single typologie flagged ninja, empty when the referential has none. */
    public Optional<String> findTypologieNinja() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(
                        connection, "SELECT id FROM typologie WHERE edition_id = ? AND ninja LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString("id")) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the ninja typology", e);
        }
    }

    public boolean typologieExists(String id) {
        return scope.exists("typologie", id);
    }

    /** Same probe inside a caller's transaction, where a typologie written a moment ago is visible. */
    boolean typologieExists(Connection connection, String id) throws SQLException {
        return scope.exists(connection, "typologie", id);
    }

    /** Writes the item and returns it stamped with the moment the database wrote it. */
    /**
     * Writes it, refusing a creation whose id is taken and an update based on an
     * out-of-date read (issue #362): both are the write's own precondition.
     *
     * @param failIfPresent true on a creation — an existing row is then a 409,
     *                      not a silent replacement
     */
    public TypologieItem saveTypologie(TypologieItem typologie, boolean failIfPresent) {
        return scope.writeAndReturn(
                "Failed to save typology " + typologie.id(),
                connection -> saveTypologie(connection, typologie, failIfPresent));
    }

    /** The same write inside a caller's transaction. */
    TypologieItem saveTypologie(Connection connection, TypologieItem typologie, boolean failIfPresent)
            throws SQLException {
        // Only one typologie may be ninja *per edition*: demote the previous
        // holder in the same transaction, otherwise the partial unique index
        // (V31, scoped per edition by V33) rejects the insert and the user
        // sees a raw constraint violation. The demotion carries the same
        // edition predicate as the index it protects — without it, flagging a
        // ninja here would silently clear the one of every other edition.
        if (typologie.ninja()) {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    UPDATE typologie
                    SET ninja = FALSE, modifie_le = now()
                    WHERE edition_id = ? AND ninja AND id <> ?""")) {
                ps.setString(2, typologie.id());
                ps.executeUpdate();
            }
        }
        return upsertTypologie(connection, typologie, failIfPresent);
    }

    public void deleteTypologie(String id) {
        scope.delete("DELETE FROM typologie WHERE edition_id = ? AND id = ?", id);
    }

    public boolean typologieInUse(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, """
                        SELECT 1
                        WHERE EXISTS (SELECT 1 FROM stand_typologie WHERE edition_id = ?
                        AND typologie = ?) OR EXISTS (SELECT 1 FROM animateur_competence WHERE edition_id = ?
                        AND typologie = ?) OR EXISTS (SELECT 1 FROM animateur_souhait WHERE edition_id = ?
                        AND typologie = ?)""")) {
            ps.setString(2, id);
            ps.setString(3, scope.editionId());
            ps.setString(4, id);
            ps.setString(5, scope.editionId());
            ps.setString(6, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check typologie usage " + id, e);
        }
    }

    /** The write's own precondition said no: a taken id on a creation, a stale read otherwise. */
    private void refuse(boolean failIfPresent, String table, String id) {
        if (failIfPresent) {
            staleWrites.refuseDuplicate(table, id);
        }
        staleWrites.refuseStale(table, id);
    }

    private TypologieItem upsertTypologie(Connection connection, TypologieItem typologie, boolean failIfPresent)
            throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO typologie (edition_id, id, label, ninja, max_creneaux_par_animateur, description)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET label = EXCLUDED.label, ninja = EXCLUDED.ninja,
                max_creneaux_par_animateur = EXCLUDED.max_creneaux_par_animateur,
                description = EXCLUDED.description, modifie_le = now()
                WHERE CAST(? AS boolean)
                AND (CAST(? AS timestamptz) IS NULL
                     OR date_trunc('milliseconds', typologie.modifie_le)
                        = date_trunc('milliseconds', CAST(? AS timestamptz)))
                RETURNING modifie_le""")) {
            ps.setString(2, typologie.id());
            ps.setString(3, typologie.label());
            ps.setBoolean(4, typologie.ninja());
            ps.setObject(5, typologie.maxCreneauxParAnimateur(), java.sql.Types.INTEGER);
            ps.setString(6, typologie.description());
            WriteStamp.bindPrecondition(ps, 7, !failIfPresent, typologie.modifieLe());
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                refuse(failIfPresent, "typologie", typologie.id());
            }
            return typologie.stamped(ecrit);
        }
    }

    /**
     * Upsert used for the typologies {@link #importFromPlanning} derives from the
     * ids stands and animateurs reference. Unlike {@link #upsertTypologie} it
     * leaves {@code ninja} alone: an import must not silently demote the ninja
     * typologie just because the derived item carries the default {@code false}.
     */
    void upsertTypologieDerivee(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO typologie (edition_id, id, label, ninja)
                VALUES (?, ?, ?, FALSE)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET label = EXCLUDED.label, modifie_le = now()""")) {
            ps.setString(2, typologie.id());
            ps.setString(3, typologie.label());
            ps.executeUpdate();
        }
    }

    List<TypologieItem> derivedTypologies(Iterable<Stand> stands, List<Animateur> animateurs) {
        LinkedHashSet<String> vues = new LinkedHashSet<>();
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
}
