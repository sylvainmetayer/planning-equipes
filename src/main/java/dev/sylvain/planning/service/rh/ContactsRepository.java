package dev.sylvain.planning.service.rh;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.WriteStamp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The {@code animateur_contact} table, scoped to the edition of the request. */
@ApplicationScoped
public class ContactsRepository {

    private final JdbcEditionScope scope;

    @Inject
    public ContactsRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Every row of the edition, by animateur id. */
    public Map<String, ContactsAnimateur> all() {
        return scope.read("Failed to load the contacts", connection -> {
            Map<String, ContactsAnimateur> rows = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(
                            connection,
                            "SELECT animateur_id, urgence_nom, urgence_telephone, representant_nom, "
                                    + "representant_telephone, modifie_le FROM animateur_contact WHERE edition_id = ? "
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
                    "SELECT animateur_id, urgence_nom, urgence_telephone, representant_nom, representant_telephone, "
                            + "modifie_le FROM animateur_contact WHERE edition_id = ? AND animateur_id = ?")) {
                ps.setString(2, animateurId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Replaces the contacts of one animateur; an empty set deletes the row. The
     * precondition is part of the write, as on every referential row (issue
     * #362): {@code contacts.modifieLe()} must be the stored stamp, and
     * {@code null} — « I saw no row » — only creates. Two people write these, the
     * animateur and the organisation; the second is refused, never silently
     * overwrites the first.
     *
     * @return the stamp written, {@code null} when the row was deleted
     * @throws BusinessError.Stale when the row was written since {@code contacts} was read
     */
    public Instant replace(String animateurId, ContactsAnimateur contacts) {
        Instant attendu = contacts.modifieLe();
        boolean[] refuse = {false};
        Instant ecrit = scope.writeAndReturn("Failed to save the contacts of an animateur", connection -> {
            if (contacts.isEmpty()) {
                // Nothing to delete is no refusal unless a row exists that the caller never saw.
                try (PreparedStatement ps = scope.prepareScoped(connection, """
                        DELETE FROM animateur_contact WHERE edition_id = ? AND animateur_id = ?
                        AND date_trunc('milliseconds', modifie_le)
                            = date_trunc('milliseconds', CAST(? AS timestamptz))""")) {
                    ps.setString(2, animateurId);
                    ps.setTimestamp(3, attendu == null ? null : Timestamp.from(attendu));
                    refuse[0] = ps.executeUpdate() == 0 && lastWrite(connection, animateurId) != null;
                }
                return null;
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
                        modifie_le = now()
                    WHERE CAST(? AS boolean)
                    AND (CAST(? AS timestamptz) IS NULL
                         OR date_trunc('milliseconds', animateur_contact.modifie_le)
                            = date_trunc('milliseconds', CAST(? AS timestamptz)))
                    RETURNING modifie_le""")) {
                ps.setString(2, animateurId);
                ps.setString(3, contacts.urgenceNom());
                ps.setString(4, contacts.urgenceTelephone());
                ps.setString(5, contacts.representantNom());
                ps.setString(6, contacts.representantTelephone());
                WriteStamp.bindPrecondition(ps, 7, attendu != null, attendu);
                Instant stamp = WriteStamp.writtenOrRefused(ps);
                refuse[0] = stamp == null;
                return stamp;
            }
        });
        if (refuse[0]) {
            throw new BusinessError.Stale(
                    "Ces contacts ont été modifiés par quelqu'un d'autre (l'animateur ou l'organisation) depuis "
                            + "leur ouverture ici. Rechargez pour voir ce qui a changé, ou enregistrez à nouveau "
                            + "pour écraser cette modification.",
                    scope.read(
                            "Failed to read the last write of the contacts of an animateur",
                            connection -> lastWrite(connection, animateurId)));
        }
        return ecrit;
    }

    private Instant lastWrite(Connection connection, String animateurId) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(
                connection, "SELECT modifie_le FROM animateur_contact WHERE edition_id = ? AND animateur_id = ?")) {
            ps.setString(2, animateurId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? rs.getObject("modifie_le", OffsetDateTime.class).toInstant()
                        : null;
            }
        }
    }

    private static ContactsAnimateur row(ResultSet rs) throws java.sql.SQLException {
        return new ContactsAnimateur(
                rs.getString("urgence_nom"),
                rs.getString("urgence_telephone"),
                rs.getString("representant_nom"),
                rs.getString("representant_telephone"),
                rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
    }
}
