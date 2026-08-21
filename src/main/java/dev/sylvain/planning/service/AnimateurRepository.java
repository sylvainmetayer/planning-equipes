package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Animateur rows, their competences, their off-days and their wishes — plus
 * the access token their espace is reached by, which is the one thing here
 * that is deliberately unique across every edition.
 */
@ApplicationScoped
public class AnimateurRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public List<Animateur> listAnimateurs() {
        Map<String, Animateur> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    """
                    SELECT id, prenom, nom, date_naissance, manager, email, jeton_acces
                    FROM animateur
                    WHERE edition_id = ?
                    ORDER BY id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = new Animateur(
                            rs.getString("id"),
                            rs.getString("prenom"),
                            rs.getString("nom"),
                            rs.getObject("date_naissance", LocalDate.class),
                            rs.getBoolean("manager"));
                    animateur.setEmail(rs.getString("email"));
                    animateur.setJetonAcces(rs.getString("jeton_acces"));
                    byId.put(animateur.getId(), animateur);
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "SELECT animateur_id, typologie, niveau FROM animateur_competence WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getCompetences().put(
                                rs.getString("typologie"),
                                NiveauCompetence.valueOf(rs.getString("niveau")));
                    }
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "SELECT animateur_id, jour FROM animateur_jour_indispo WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getJoursIndisponibles().add(rs.getObject("jour", LocalDate.class));
                    }
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "SELECT animateur_id, typologie FROM animateur_souhait WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Animateur animateur = byId.get(rs.getString("animateur_id"));
                    if (animateur != null) {
                        animateur.getSouhaits().add(rs.getString("typologie"));
                    }
                }
            }
            // Polyvalence is carried by the referential, not by the animateur row:
            // holding the ninja typologie is what makes an animateur dispatchable
            // on any stand, so the flag is derived here once competences are known.
            String typologieNinja = null;
            try (PreparedStatement ps = scope.prepareScoped(connection, "SELECT id FROM typologie WHERE edition_id = ? AND ninja LIMIT 1");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    typologieNinja = rs.getString("id");
                }
            }
            for (Animateur animateur : byId.values()) {
                animateur.appliquerTypologieNinja(typologieNinja);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list animators", e);
        }
        List<Animateur> animateurs = new ArrayList<>(byId.values());
        animateurs.sort(Comparator.comparing(Animateur::getId, OrdreNaturel.DES_IDS));
        return animateurs;
    }

    public boolean animateurExists(String id) {
        return scope.existe("animateur", id);
    }

    public void saveAnimateur(Animateur animateur) {
        scope.ecrire("Failed to save animator " + animateur.getId(), connection -> {
            upsertAnimateur(connection, animateur);
        });
    }

    public void deleteAnimateur(String id) {
        scope.supprimer("DELETE FROM animateur WHERE edition_id = ? AND id = ?", id);
    }

    /**
     * Rotates the espace-animateur access token — the one explicit way it ever
     * changes (a lost or leaked PDF link stops working once regenerated).
     *
     * @return the new token, or {@code null} when the animateur is unknown.
     */
    public String regenererJetonAnimateur(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        UPDATE animateur
                        SET jeton_acces = gen_random_uuid()::text
                        WHERE edition_id = ? AND id = ?
                        RETURNING jeton_acces""")) {
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to regenerate token for animator " + id, e);
        }
    }

    /**
     * Whether any animateur, in any edition, carries this address. Like
     * {@link #resoudreJetonAnimateur}, deliberately not edition-scoped: the
     * caller is the startup check of the remote-user mode, which has no
     * edition to speak of and wants to know whether the collision exists
     * anywhere at all.
     */
    public boolean emailAnimateurExiste(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM animateur WHERE lower(email) = lower(?) LIMIT 1")) {
            ps.setString(1, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to look up animator e-mail", e);
        }
    }

    /**
     * Resolves an espace-animateur token to its owner. Deliberately <b>not</b>
     * edition-scoped — one of the two statements in the whole backend that are
     * not, both of them here and both listed in
     * {@code IsolationEditionStructurelleTest}: the
     * token arrives on a public URL with no {@code X-Edition-Id} to trust, and
     * is globally unique precisely so it can designate the edition by itself
     * (the caller then runs everything else inside
     * {@code EditionContext.executeDans}).
     */
    public ProprietaireJeton resoudreJetonAnimateur(String jeton) {
        if (jeton == null || jeton.isBlank()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT edition_id, id, email FROM animateur WHERE jeton_acces = ?")) {
            ps.setString(1, jeton);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new ProprietaireJeton(rs.getString("edition_id"), rs.getString("id"),
                                rs.getString("email"))
                        : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve animator token", e);
        }
    }

    void upsertAnimateur(Connection connection, Animateur animateur) throws SQLException {
        upsertAnimateur(connection, animateur, false);
    }

    /**
     * @param conserverEmailSiAbsent true on the scenario-import path: a file
     *                               that carries no email for an animateur must
     *                               not silently wipe the stored one — the
     *                               address is now the espace's second factor.
     *                               The fiche update (false) can still clear it.
     */
    void upsertAnimateur(Connection connection, Animateur animateur, boolean conserverEmailSiAbsent)
            throws SQLException {
        // jeton_acces is deliberately absent: a fresh row gets the database
        // default, an existing row keeps its token. Rotation only happens
        // through regenererJetonAnimateur.
        String miseAJourEmail = conserverEmailSiAbsent
                ? "email = COALESCE(EXCLUDED.email, animateur.email)"
                : "email = EXCLUDED.email";
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance, manager, email)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, date_naissance = EXCLUDED.date_naissance,
                manager = EXCLUDED.manager,"""
                        + miseAJourEmail)) {
            ps.setString(2, animateur.getId());
            ps.setString(3, animateur.getPrenom());
            ps.setString(4, animateur.getNom());
            ps.setObject(5, animateur.getDateNaissance());
            ps.setBoolean(6, animateur.isManager());
            ps.setString(7, animateur.getEmail());
            ps.executeUpdate();
        }
        try (PreparedStatement del = scope.prepareScoped(connection,
                "DELETE FROM animateur_competence WHERE edition_id = ? AND animateur_id = ?")) {
            del.setString(2, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getCompetences() != null && !animateur.getCompetences().isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection,
                    """
                    INSERT INTO animateur_competence (edition_id, animateur_id, typologie, niveau)
                    VALUES (?, ?, ?, ?)""")) {
                for (Map.Entry<String, NiveauCompetence> entry : animateur.getCompetences().entrySet()) {
                    ins.setString(2, animateur.getId());
                    ins.setString(3, entry.getKey());
                    ins.setString(4, entry.getValue().name());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = scope.prepareScoped(connection,
                "DELETE FROM animateur_jour_indispo WHERE edition_id = ? AND animateur_id = ?")) {
            del.setString(2, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getJoursIndisponibles() != null && !animateur.getJoursIndisponibles().isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection,
                    "INSERT INTO animateur_jour_indispo (edition_id, animateur_id, jour) VALUES (?, ?, ?)")) {
                for (LocalDate jour : animateur.getJoursIndisponibles()) {
                    ins.setString(2, animateur.getId());
                    ins.setObject(3, jour);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (PreparedStatement del = scope.prepareScoped(connection,
                "DELETE FROM animateur_souhait WHERE edition_id = ? AND animateur_id = ?")) {
            del.setString(2, animateur.getId());
            del.executeUpdate();
        }
        if (animateur.getSouhaits() != null && !animateur.getSouhaits().isEmpty()) {
            try (PreparedStatement ins = scope.prepareScoped(connection,
                    "INSERT INTO animateur_souhait (edition_id, animateur_id, typologie) VALUES (?, ?, ?)")) {
                for (String typologie : animateur.getSouhaits()) {
                    ins.setString(2, animateur.getId());
                    ins.setString(3, typologie);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
    }
}
