package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The hand-entered constraints and the animateurs each one targets. */
@ApplicationScoped
public class ContrainteAdHocRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public List<ContrainteAdHoc> listContraintes() {
        Map<String, ContrainteAdHoc> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    """
                    SELECT id, type, creneau_id, stand_id, raison, cree_par, cree_le, modifie_le
                    FROM contrainte_ad_hoc
                    WHERE edition_id = ?
                    ORDER BY id""");
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
                    contrainte.setModifieLe(rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
                    byId.put(contrainte.getId(), contrainte);
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    """
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

    public void saveContrainte(ContrainteAdHoc contrainte) {
        scope.write("Failed to save constraint " + contrainte.getId(), connection -> {
            upsertContrainte(connection, contrainte);
        });
    }

    public void deleteContrainte(String id) {
        scope.delete("DELETE FROM contrainte_ad_hoc WHERE edition_id = ? AND id = ?", id);
    }

    void upsertContrainte(Connection connection, ContrainteAdHoc contrainte) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO contrainte_ad_hoc (edition_id, id, type, creneau_id, stand_id, raison, cree_par, cree_le)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET type = EXCLUDED.type, creneau_id = EXCLUDED.creneau_id,
                stand_id = EXCLUDED.stand_id, raison = EXCLUDED.raison, cree_par = EXCLUDED.cree_par,
                cree_le = EXCLUDED.cree_le, modifie_le = now()
                RETURNING modifie_le""")) {
            ps.setString(2, contrainte.getId());
            ps.setString(3, contrainte.getType() != null ? contrainte.getType().name() : null);
            ps.setObject(4, contrainte.getCreneau() != null ? contrainte.getCreneau().getId() : null);
            ps.setString(5, contrainte.getStand() != null ? contrainte.getStand().getId() : null);
            ps.setString(6, contrainte.getRaison());
            ps.setString(7, contrainte.getCreeParUtilisateurId());
            Instant creeLe = contrainte.getCreeLe() != null ? contrainte.getCreeLe() : Instant.now();
            ps.setTimestamp(8, Timestamp.from(creeLe));
            contrainte.setModifieLe(WriteStamp.written(ps));
        }
        try (PreparedStatement del = scope.prepareScoped(connection,
                "DELETE FROM contrainte_animateur WHERE edition_id = ? AND contrainte_id = ?")) {
            del.setString(2, contrainte.getId());
            del.executeUpdate();
        }
        List<Animateur> cibles = contrainte.getAnimateursConcernes();
        if (cibles != null && !cibles.isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection,
                    """
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
