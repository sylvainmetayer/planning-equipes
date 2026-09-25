package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** The hand-entered constraints and the animateurs each one targets. */
@ApplicationScoped
public class ContrainteAdHocRepository {

    private final ConcurrentModificationGuard staleWrites;

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    @Inject
    public ContrainteAdHocRepository(
            ConcurrentModificationGuard staleWrites, DataSource dataSource, JdbcEditionScope scope) {
        this.staleWrites = staleWrites;
        this.dataSource = dataSource;
        this.scope = scope;
    }

    public List<ContrainteAdHoc> listContraintes() {
        Map<String, ContrainteAdHoc> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            // The créneau id is re-resolved by joining the grid on the natural
            // key stored with the constraint (issue #577): a créneau deleted
            // and recreated unchanged has a new id, and a rule written for that
            // slot finds it again. It comes back null while the grid holds no
            // such vacation, and the rule then matches nothing — never
            // everything, which is what losing the scope used to mean.
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT a.id, a.type, c.id AS creneau_id, a.creneau_date,
                           a.creneau_heure_debut, a.creneau_heure_fin,
                           a.stand_id, a.raison, a.cree_par, a.cree_le, a.modifie_le
                    FROM contrainte_ad_hoc a
                    LEFT JOIN creneau c
                      ON c.edition_id = a.edition_id
                     AND c.date_creneau = a.creneau_date
                     AND c.heure_debut = a.creneau_heure_debut
                     AND c.heure_fin = a.creneau_heure_fin
                    WHERE a.edition_id = ?
                    ORDER BY a.id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ContrainteAdHoc contrainte =
                            new ContrainteAdHoc(rs.getString("id"), TypeContrainteAdHoc.valueOf(rs.getString("type")));
                    LocalDate creneauDate = rs.getObject("creneau_date", LocalDate.class);
                    if (creneauDate != null) {
                        Creneau creneau = new Creneau();
                        long creneauId = rs.getLong("creneau_id");
                        if (!rs.wasNull()) {
                            creneau.setId(creneauId);
                        }
                        creneau.setDate(creneauDate);
                        creneau.setHeureDebut(rs.getObject("creneau_heure_debut", LocalTime.class));
                        creneau.setHeureFin(rs.getObject("creneau_heure_fin", LocalTime.class));
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
                    contrainte.setModifieLe(
                            rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
                    byId.put(contrainte.getId(), contrainte);
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT contrainte_id, animateur_id
                    FROM contrainte_animateur
                    WHERE edition_id = ?
                    ORDER BY contrainte_id, position""");
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

    /**
     * Writes it, refusing a creation whose id is taken and an update based on an
     * out-of-date read (issue #362): both are the write's own precondition.
     *
     * @param failIfPresent true on a creation — an existing row is then a 409,
     *                      not a silent replacement
     */
    public void saveContrainte(ContrainteAdHoc contrainte, boolean failIfPresent) {
        scope.write("Failed to save constraint " + contrainte.getId(), connection -> {
            upsertContrainte(connection, contrainte, failIfPresent);
        });
    }

    public void deleteContrainte(String id) {
        scope.delete("DELETE FROM contrainte_ad_hoc WHERE edition_id = ? AND id = ?", id);
    }

    /** The write's own precondition said no: a taken id on a creation, a stale read otherwise. */
    private void refuse(boolean failIfPresent, String table, String id) {
        if (failIfPresent) {
            staleWrites.refuseDuplicate(table, id);
        }
        staleWrites.refuseStale(table, id);
    }

    void upsertContrainte(Connection connection, ContrainteAdHoc contrainte) throws SQLException {
        upsertContrainte(connection, contrainte, false);
    }

    void upsertContrainte(Connection connection, ContrainteAdHoc contrainte, boolean failIfPresent)
            throws SQLException {
        // The natural key of the scope travels with it (issue #577), filled by
        // the service from the créneau it validated.
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO contrainte_ad_hoc (edition_id, id, type, creneau_id, stand_id, raison, cree_par, cree_le,
                creneau_date, creneau_heure_debut, creneau_heure_fin)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET type = EXCLUDED.type, creneau_id = EXCLUDED.creneau_id,
                stand_id = EXCLUDED.stand_id, raison = EXCLUDED.raison, cree_par = EXCLUDED.cree_par,
                cree_le = EXCLUDED.cree_le, creneau_date = EXCLUDED.creneau_date,
                creneau_heure_debut = EXCLUDED.creneau_heure_debut,
                creneau_heure_fin = EXCLUDED.creneau_heure_fin, modifie_le = now()
                WHERE CAST(? AS boolean)
                AND (CAST(? AS timestamptz) IS NULL
                     OR date_trunc('milliseconds', contrainte_ad_hoc.modifie_le)
                        = date_trunc('milliseconds', CAST(? AS timestamptz)))
                RETURNING modifie_le""")) {
            Creneau creneau = contrainte.getCreneau();
            ps.setString(2, contrainte.getId());
            ps.setString(3, contrainte.getType() != null ? contrainte.getType().name() : null);
            ps.setObject(4, creneau != null ? creneau.getId() : null);
            ps.setString(
                    5, contrainte.getStand() != null ? contrainte.getStand().getId() : null);
            ps.setString(6, contrainte.getRaison());
            ps.setString(7, contrainte.getCreeParUtilisateurId());
            Instant creeLe = contrainte.getCreeLe() != null ? contrainte.getCreeLe() : Instant.now();
            ps.setTimestamp(8, Timestamp.from(creeLe));
            ps.setObject(9, creneau != null ? creneau.getDate() : null);
            ps.setObject(10, creneau != null ? creneau.getHeureDebut() : null);
            ps.setObject(11, creneau != null ? creneau.getHeureFin() : null);
            WriteStamp.bindPrecondition(ps, 12, !failIfPresent, contrainte.getModifieLe());
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                refuse(failIfPresent, "contrainte_ad_hoc", contrainte.getId());
            }
            contrainte.setModifieLe(ecrit);
        }
        try (PreparedStatement del = scope.prepareScoped(
                connection, "DELETE FROM contrainte_animateur WHERE edition_id = ? AND contrainte_id = ?")) {
            del.setString(2, contrainte.getId());
            del.executeUpdate();
        }
        List<Animateur> cibles = contrainte.getAnimateursConcernes();
        if (cibles != null && !cibles.isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection, """
                    INSERT INTO contrainte_animateur (edition_id, contrainte_id, animateur_id, position)
                    VALUES (?, ?, ?, ?)""")) {
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
}
