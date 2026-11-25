package dev.sylvain.planning.service.rh;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The {@code animateur_contact} table, scoped to the edition of the request. */
@ApplicationScoped
public class ContactsRepository {

    @Inject
    JdbcEditionScope scope;

    /** Every row of the edition, by animateur id. */
    public Map<String, ContactsAnimateur> all() {
        return scope.read("Failed to load the contacts", connection -> {
            Map<String, ContactsAnimateur> rows = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(
                            connection,
                            "SELECT animateur_id, urgence_nom, urgence_telephone, representant_nom, "
                                    + "representant_telephone FROM animateur_contact WHERE edition_id = ? "
                                    + "ORDER BY animateur_id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(rs.getString("animateur_id"), row(rs));
                }
            }
            return rows;
        });
    }

    public Optional<ContactsAnimateur> find(String animateurId) {
        return scope.read("Failed to load the contacts of an animateur", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection,
                    "SELECT animateur_id, urgence_nom, urgence_telephone, representant_nom, representant_telephone "
                            + "FROM animateur_contact WHERE edition_id = ? AND animateur_id = ?")) {
                ps.setString(2, animateurId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Replaces the contacts of one animateur; an empty set deletes the row. */
    public void replace(String animateurId, ContactsAnimateur contacts) {
        scope.write("Failed to save the contacts of an animateur", connection -> {
            if (contacts.isEmpty()) {
                try (PreparedStatement ps = scope.prepareScoped(
                        connection, "DELETE FROM animateur_contact WHERE edition_id = ? AND animateur_id = ?")) {
                    ps.setString(2, animateurId);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO animateur_contact (edition_id, animateur_id, urgence_nom, urgence_telephone,
                        representant_nom, representant_telephone, modifie_le)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (edition_id, animateur_id) DO UPDATE SET
                        urgence_nom = EXCLUDED.urgence_nom,
                        urgence_telephone = EXCLUDED.urgence_telephone,
                        representant_nom = EXCLUDED.representant_nom,
                        representant_telephone = EXCLUDED.representant_telephone,
                        modifie_le = now()""")) {
                ps.setString(2, animateurId);
                ps.setString(3, contacts.urgenceNom());
                ps.setString(4, contacts.urgenceTelephone());
                ps.setString(5, contacts.representantNom());
                ps.setString(6, contacts.representantTelephone());
                ps.executeUpdate();
            }
        });
    }

    private static ContactsAnimateur row(ResultSet rs) throws java.sql.SQLException {
        return new ContactsAnimateur(
                rs.getString("urgence_nom"),
                rs.getString("urgence_telephone"),
                rs.getString("representant_nom"),
                rs.getString("representant_telephone"));
    }
}
