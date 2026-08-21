package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public List<TypologieItem> listTypologies() {
        List<TypologieItem> typologies = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
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
                PreparedStatement ps = scope.prepareScoped(connection, "SELECT id FROM typologie WHERE edition_id = ? AND ninja LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString("id")) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the ninja typology", e);
        }
    }

    public boolean typologieExists(String id) {
        return scope.existe("typologie", id);
    }

    public void saveTypologie(TypologieItem typologie) {
        scope.ecrire("Failed to save typology " + typologie.id(), connection -> {
            // Only one typologie may be ninja *per edition*: demote the previous
            // holder in the same transaction, otherwise the partial unique index
            // (V31, scoped per edition by V33) rejects the insert and the user
            // sees a raw constraint violation. The demotion carries the same
            // edition predicate as the index it protects — without it, flagging a
            // ninja here would silently clear the one of every other edition.
            if (typologie.ninja()) {
                try (PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        UPDATE typologie
                        SET ninja = FALSE
                        WHERE edition_id = ? AND ninja AND id <> ?""")) {
                    ps.setString(2, typologie.id());
                    ps.executeUpdate();
                }
            }
            upsertTypologie(connection, typologie);
        });
    }

    public void deleteTypologie(String id) {
        scope.supprimer("DELETE FROM typologie WHERE edition_id = ? AND id = ?", id);
    }

    public boolean typologieEnUsage(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
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

    private void upsertTypologie(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO typologie (edition_id, id, label, ninja)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET label = EXCLUDED.label, ninja = EXCLUDED.ninja""")) {
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
    void upsertTypologieDerivee(Connection connection, TypologieItem typologie) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO typologie (edition_id, id, label, ninja)
                VALUES (?, ?, ?, FALSE)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET label = EXCLUDED.label""")) {
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
