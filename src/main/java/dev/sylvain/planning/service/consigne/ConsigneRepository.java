package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.service.JdbcEditionScope;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The consignes of an edition, one per date, and the presets they are made from — partitioned by edition. */
@ApplicationScoped
public class ConsigneRepository {

    @Inject
    JdbcEditionScope scope;

    /* ------------------------------ consignes ------------------------------ */

    /** Every consigne of the current edition, by date. */
    public List<ConsigneEdition> list() {
        return scope.read("Failed to list the edition's consignes", connection -> {
            Map<LocalDate, ConsigneEdition> parDate = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT date_jour, fermeture_debut, fermeture_fin, motif, prereglage_nom, cree_le, modifie_le
                    FROM consigne_edition WHERE edition_id = ? ORDER BY date_jour""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate date = rs.getObject("date_jour", LocalDate.class);
                        parDate.put(
                                date,
                                new ConsigneEdition(
                                        date,
                                        rs.getObject("fermeture_debut", LocalTime.class),
                                        rs.getObject("fermeture_fin", LocalTime.class),
                                        rs.getString("motif"),
                                        rs.getString("prereglage_nom"),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        instant(rs.getTimestamp("cree_le")),
                                        instant(rs.getTimestamp("modifie_le"))));
                    }
                }
            }
            if (parDate.isEmpty()) {
                return List.of();
            }
            Map<LocalDate, List<ConsigneEdition.Fenetre>> fenetres = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT date_jour, heure_debut, heure_fin FROM consigne_edition_fenetre
                    WHERE edition_id = ? ORDER BY date_jour, position""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fenetres.computeIfAbsent(rs.getObject("date_jour", LocalDate.class), d -> new ArrayList<>())
                                .add(new ConsigneEdition.Fenetre(
                                        rs.getObject("heure_debut", LocalTime.class),
                                        rs.getObject("heure_fin", LocalTime.class)));
                    }
                }
            }
            Map<LocalDate, List<ConsigneEdition.Ouverture>> ouvertures = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT date_jour, stand_id, heure_debut, heure_fin, effectif FROM consigne_edition_ouverture
                    WHERE edition_id = ? ORDER BY date_jour, stand_id, position""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int effectif = rs.getInt("effectif");
                        ouvertures
                                .computeIfAbsent(rs.getObject("date_jour", LocalDate.class), d -> new ArrayList<>())
                                .add(new ConsigneEdition.Ouverture(
                                        rs.getString("stand_id"),
                                        rs.getObject("heure_debut", LocalTime.class),
                                        rs.getObject("heure_fin", LocalTime.class),
                                        rs.wasNull() ? null : effectif));
                    }
                }
            }
            Map<LocalDate, List<Long>> creneaux = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT date_jour, creneau_id FROM consigne_edition_creneau
                    WHERE edition_id = ? ORDER BY date_jour, creneau_id""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        creneaux.computeIfAbsent(rs.getObject("date_jour", LocalDate.class), d -> new ArrayList<>())
                                .add(rs.getLong("creneau_id"));
                    }
                }
            }
            List<ConsigneEdition> consignes = new ArrayList<>();
            for (ConsigneEdition brute : parDate.values()) {
                LocalDate date = brute.date();
                consignes.add(new ConsigneEdition(
                        date,
                        brute.fermetureDebut(),
                        brute.fermetureFin(),
                        brute.motif(),
                        brute.prereglage(),
                        fenetres.getOrDefault(date, List.of()),
                        ouvertures.getOrDefault(date, List.of()),
                        creneaux.getOrDefault(date, List.of()),
                        brute.creeLe(),
                        brute.modifieLe()));
            }
            return consignes;
        });
    }

    /** The consigne of {@code date}, if the edition carries one. */
    public Optional<ConsigneEdition> find(LocalDate date) {
        return list().stream().filter(consigne -> consigne.date().equals(date)).findFirst();
    }

    /** Every créneau id a consigne of the edition added to the grid. */
    public Set<Long> creneauxAjoutes() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConsigneEdition consigne : list()) {
            ids.addAll(consigne.creneauxAjoutes());
        }
        return ids;
    }

    /**
     * Writes the consigne, replacing the one its date already carried — its
     * windows, its openings and its added créneaux with it. The creation stamp
     * survives a replacement; the modification stamp is now.
     */
    public void save(ConsigneEdition consigne) {
        scope.write("Failed to save the consigne of " + consigne.date(), connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO consigne_edition (edition_id, date_jour, fermeture_debut, fermeture_fin, motif,
                                                  prereglage_nom, cree_le, modifie_le)
                    VALUES (?, ?, ?, ?, ?, ?, now(), now())
                    ON CONFLICT (edition_id, date_jour) DO UPDATE SET
                        fermeture_debut = EXCLUDED.fermeture_debut,
                        fermeture_fin = EXCLUDED.fermeture_fin,
                        motif = EXCLUDED.motif,
                        prereglage_nom = EXCLUDED.prereglage_nom,
                        modifie_le = now()""")) {
                ps.setObject(2, consigne.date());
                ps.setObject(3, consigne.fermetureDebut());
                ps.setObject(4, consigne.fermetureFin());
                ps.setString(5, consigne.motif());
                ps.setString(6, consigne.prereglage());
                ps.executeUpdate();
            }
            deleteDated(
                    connection,
                    "DELETE FROM consigne_edition_fenetre WHERE edition_id = ? AND date_jour = ?",
                    consigne.date());
            deleteDated(
                    connection,
                    "DELETE FROM consigne_edition_ouverture WHERE edition_id = ? AND date_jour = ?",
                    consigne.date());
            deleteDated(
                    connection,
                    "DELETE FROM consigne_edition_creneau WHERE edition_id = ? AND date_jour = ?",
                    consigne.date());
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO consigne_edition_fenetre (edition_id, date_jour, position, heure_debut, heure_fin)
                    VALUES (?, ?, ?, ?, ?)""")) {
                int position = 0;
                for (ConsigneEdition.Fenetre fenetre : consigne.fenetres()) {
                    ps.setObject(2, consigne.date());
                    ps.setInt(3, position++);
                    ps.setObject(4, fenetre.debut());
                    ps.setObject(5, fenetre.fin());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO consigne_edition_ouverture (edition_id, date_jour, stand_id, position,
                                                            heure_debut, heure_fin, effectif)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                Map<String, Integer> positions = new LinkedHashMap<>();
                for (ConsigneEdition.Ouverture ouverture : consigne.ouvertures()) {
                    int position = positions.merge(ouverture.standId(), 1, Integer::sum) - 1;
                    ps.setObject(2, consigne.date());
                    ps.setString(3, ouverture.standId());
                    ps.setInt(4, position);
                    ps.setObject(5, ouverture.debut());
                    ps.setObject(6, ouverture.fin());
                    ps.setObject(7, ouverture.effectif());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            insertCreneaux(connection, consigne.date(), consigne.creneauxAjoutes());
        });
    }

    /** Records that the consigne of {@code date} added these créneaux to the grid. */
    public void rememberCreneaux(LocalDate date, Collection<Long> creneauIds) {
        if (creneauIds.isEmpty()) {
            return;
        }
        scope.write("Failed to record the créneaux the consigne of " + date + " added", connection -> {
            insertCreneaux(connection, date, creneauIds);
        });
    }

    private void insertCreneaux(Connection connection, LocalDate date, Collection<Long> creneauIds)
            throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO consigne_edition_creneau (edition_id, date_jour, creneau_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING""")) {
            for (Long id : creneauIds) {
                ps.setObject(2, date);
                ps.setLong(3, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void deleteDated(Connection connection, String sql, LocalDate date) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setObject(2, date);
            ps.executeUpdate();
        }
    }

    /** Removes the consigne of {@code date} and everything it carried; says whether there was one. */
    public boolean delete(LocalDate date) {
        return scope.writeAndReturn("Failed to lift the consigne of " + date, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM consigne_edition WHERE edition_id = ? AND date_jour = ?")) {
                ps.setObject(2, date);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /* ------------------------------ préréglages ------------------------------ */

    /** Every preset of the current edition, by name. */
    public List<PrereglageConsigne> listPrereglages() {
        return scope.read("Failed to list the consigne presets", connection -> {
            Map<String, PrereglageConsigne> parId = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT id, nom, fermeture_debut, fermeture_fin, motif, modifie_le
                    FROM prereglage_consigne WHERE edition_id = ? ORDER BY lower(nom), id""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        parId.put(
                                rs.getString("id"),
                                new PrereglageConsigne(
                                        rs.getString("id"),
                                        rs.getString("nom"),
                                        rs.getObject("fermeture_debut", LocalTime.class),
                                        rs.getObject("fermeture_fin", LocalTime.class),
                                        rs.getString("motif"),
                                        List.of(),
                                        instant(rs.getTimestamp("modifie_le"))));
                    }
                }
            }
            if (parId.isEmpty()) {
                return List.of();
            }
            Map<String, List<ConsigneEdition.Fenetre>> fenetres = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT prereglage_id, heure_debut, heure_fin FROM prereglage_consigne_fenetre
                    WHERE edition_id = ? ORDER BY prereglage_id, position""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fenetres.computeIfAbsent(rs.getString("prereglage_id"), id -> new ArrayList<>())
                                .add(new ConsigneEdition.Fenetre(
                                        rs.getObject("heure_debut", LocalTime.class),
                                        rs.getObject("heure_fin", LocalTime.class)));
                    }
                }
            }
            List<PrereglageConsigne> prereglages = new ArrayList<>();
            for (PrereglageConsigne brut : parId.values()) {
                prereglages.add(new PrereglageConsigne(
                        brut.id(),
                        brut.nom(),
                        brut.fermetureDebut(),
                        brut.fermetureFin(),
                        brut.motif(),
                        fenetres.getOrDefault(brut.id(), List.of()),
                        brut.modifieLe()));
            }
            return prereglages;
        });
    }

    /** Writes the preset, replacing the one of the same id. */
    public void savePrereglage(PrereglageConsigne prereglage) {
        scope.write("Failed to save the consigne preset " + prereglage.id(), connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO prereglage_consigne (edition_id, id, nom, fermeture_debut, fermeture_fin, motif, modifie_le)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (edition_id, id) DO UPDATE SET
                        nom = EXCLUDED.nom,
                        fermeture_debut = EXCLUDED.fermeture_debut,
                        fermeture_fin = EXCLUDED.fermeture_fin,
                        motif = EXCLUDED.motif,
                        modifie_le = now()""")) {
                ps.setString(2, prereglage.id());
                ps.setString(3, prereglage.nom());
                ps.setObject(4, prereglage.fermetureDebut());
                ps.setObject(5, prereglage.fermetureFin());
                ps.setString(6, prereglage.motif());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM prereglage_consigne_fenetre WHERE edition_id = ? AND prereglage_id = ?")) {
                ps.setString(2, prereglage.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO prereglage_consigne_fenetre (edition_id, prereglage_id, position, heure_debut, heure_fin)
                    VALUES (?, ?, ?, ?, ?)""")) {
                int position = 0;
                for (ConsigneEdition.Fenetre fenetre : prereglage.fenetres()) {
                    ps.setString(2, prereglage.id());
                    ps.setInt(3, position++);
                    ps.setObject(4, fenetre.debut());
                    ps.setObject(5, fenetre.fin());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    /** Removes one preset; says whether there was one. */
    public boolean deletePrereglage(String id) {
        return scope.writeAndReturn("Failed to delete the consigne preset " + id, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM prereglage_consigne WHERE edition_id = ? AND id = ?")) {
                ps.setString(2, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
