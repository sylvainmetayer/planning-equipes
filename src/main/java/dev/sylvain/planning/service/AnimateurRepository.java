package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * the two tokens their public links are reached by (the espace one and the ICS
 * subscription one), which are the only things here that are deliberately
 * unique across every edition.
 */
@ApplicationScoped
public class AnimateurRepository {

    @Inject
    ConcurrentModificationGuard staleWrites;

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public List<Animateur> listAnimateurs() {
        Map<String, Animateur> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    """
                    SELECT id, prenom, nom, date_naissance, manager, email, access_token, modifie_le
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
                    animateur.setAccessToken(rs.getString("access_token"));
                    animateur.setModifieLe(rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
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
                animateur.applyNinjaTypologie(typologieNinja);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list animators", e);
        }
        List<Animateur> animateurs = new ArrayList<>(byId.values());
        animateurs.sort(Comparator.comparing(Animateur::getId, NaturalOrder.DES_IDS));
        return animateurs;
    }

    public boolean animateurExists(String id) {
        return scope.exists("animateur", id);
    }

    /**
     * Writes it, refusing a creation whose id is taken and an update based on an
     * out-of-date read (issue #362): both are the write's own precondition.
     *
     * @param failIfPresent true on a creation — an existing row is then a 409,
     *                      not a silent replacement
     */
    public void saveAnimateur(Animateur animateur, boolean failIfPresent) {
        scope.write("Failed to save animator " + animateur.getId(), connection -> {
            upsertAnimateur(connection, animateur, false, failIfPresent);
        });
    }

    /**
     * Deletes one animateur, and vacates the seats they held in the persisted
     * plan rather than removing them.
     *
     * <p>Same defect as {@link CreneauRepository#deleteCreneau} and
     * {@link StandRepository#deleteStand} fix: {@code poste_affectation} is the
     * one table referencing {@code animateur} whose foreign key neither cascades
     * nor nulls out, so the delete simply failed as soon as a plan was persisted
     * — that is after any solve at all — and failed as a 500 rather than as a
     * refusal anyone could act on.</p>
     *
     * <p><b>The seat is vacated, not destroyed</b>, and that is the difference
     * with the stand and créneau cases. {@code animateur_id} is nullable, and a
     * row holding {@code NULL} <i>is</i> how this model already spells "place
     * non pourvue" — {@code PlanningPersistenceService.assemblerPlanning} builds
     * exactly that seat, and {@code PlanningService}'s {@code postesNonPourvus}
     * counts it. Deleting the row instead would make the seat vanish, and the
     * screens that count persisted rows (heatmap, coverage KPI) would show a
     * coverage greener than reality. A stand's seat has no such choice —
     * {@code poste_affectation.stand_id} is {@code NOT NULL} — which is why the
     * two deletes read differently.</p>
     *
     * <p>Holes therefore appear in a plan nobody asked to re-solve. That is
     * deliberate: they are real, and showing them is the point (issue #328).</p>
     *
     * <p>The rule lives here rather than in an {@code ON DELETE SET NULL} so that
     * it can be read and tested in the code, which is the decision issue #281
     * took for créneaux.</p>
     */
    public void deleteAnimateur(String id) {
        scope.write("Failed to delete animator " + id, connection -> deleteAnimateurTx(connection, id));
    }

    /** The same delete, on a caller's connection — see {@link #importAnimateurs}. */
    private void deleteAnimateurTx(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                "UPDATE poste_affectation SET animateur_id = NULL WHERE edition_id = ? AND animateur_id = ?")) {
            ps.setString(2, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = scope.prepareScoped(connection,
                "DELETE FROM animateur WHERE edition_id = ? AND id = ?")) {
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Writes a whole tabular import in <b>one</b> transaction: the fiches the
     * file accepted, then — in replacement mode only — the ones it does not
     * name.
     *
     * <p>Atomic because the report is a promise. A per-row commit would let a
     * failure on row 90 leave 89 fiches written under a report announcing 150,
     * and the operator would have no way of telling which half landed. Here a
     * failure rolls everything back and propagates, so the report is either
     * entirely true or never shown.</p>
     *
     * <p>Addresses are preserved when the file carries none
     * ({@code conserverEmailSiAbsent}), for the reason the scenario import
     * has: the address is the espace's second factor, and a roster exported
     * without an e-mail column must not lock everybody out of it.</p>
     */
    public void importAnimateurs(List<Animateur> aEcrire, List<String> aSupprimer) {
        scope.write("Failed to import animators from a tabular file", connection -> {
            for (Animateur animateur : aEcrire) {
                upsertAnimateur(connection, animateur, true);
            }
            for (String id : aSupprimer) {
                deleteAnimateurTx(connection, id);
            }
        });
    }

    /**
     * Rotates the espace-animateur access token — the one explicit way it ever
     * changes (a lost or leaked PDF link stops working once regenerated).
     *
     * @return the new token, or {@code null} when the animateur is unknown.
     */
    public String regenerateAnimateurToken(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        UPDATE animateur
                        SET access_token = gen_random_uuid()::text
                        WHERE edition_id = ? AND id = ?
                        RETURNING access_token""")) {
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to regenerate token for animator " + id, e);
        }
    }

    /**
     * Rotates the ICS subscription token — the animateur's own way of killing
     * a calendar URL that leaked. Deliberately separate from
     * {@link #regenerateAnimateurToken}: the two credentials open different
     * things, so revoking one must not cost the other.
     *
     * @return the new token, or {@code null} when the animateur is unknown.
     */
    public String regenerateAbonnementToken(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        UPDATE animateur
                        SET abonnement_token = gen_random_uuid()::text
                        WHERE edition_id = ? AND id = ?
                        RETURNING abonnement_token""")) {
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to regenerate the subscription token of animator " + id, e);
        }
    }

    /** The animateur's current ICS subscription token, {@code null} when unknown. */
    public String abonnementToken(String id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "SELECT abonnement_token FROM animateur WHERE edition_id = ? AND id = ?")) {
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the subscription token of animator " + id, e);
        }
    }

    /**
     * Whether any animateur, in any edition, carries this address. Like
     * {@link #resolveAnimateurToken}, deliberately not edition-scoped: the
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
     * {@code EditionContext.executeIn}).
     */
    public TokenOwner resolveAnimateurToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT edition_id, id, email FROM animateur WHERE access_token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new TokenOwner(rs.getString("edition_id"), rs.getString("id"),
                                rs.getString("email"))
                        : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve animator token", e);
        }
    }

    /**
     * Resolves an ICS subscription token to its owner. Not edition-scoped for
     * the very reason {@link #resolveAnimateurToken} is not: the token arrives
     * on a public URL with no {@code X-Edition-Id} to trust, and is globally
     * unique so it can designate the edition by itself.
     *
     * <p>The e-mail is not read here, and that is the point: nothing this
     * token opens ever needs it. A {@link TokenOwner} with a {@code null}
     * address cannot satisfy the proxy assertion the espace guard accepts, so
     * a subscription token can never stand in for an espace session.</p>
     */
    public TokenOwner resolveAbonnementToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT edition_id, id FROM animateur WHERE abonnement_token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new TokenOwner(rs.getString("edition_id"), rs.getString("id"), null)
                        : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve a subscription token", e);
        }
    }

    /** The write's own precondition said no: a taken id on a creation, a stale read otherwise. */
    private void refuse(boolean failIfPresent, String table, String id) {
        if (failIfPresent) {
            staleWrites.refuseDuplicate(table, id);
        }
        staleWrites.refuseStale(table, id);
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
        upsertAnimateur(connection, animateur, conserverEmailSiAbsent, false);
    }

    void upsertAnimateur(Connection connection, Animateur animateur, boolean conserverEmailSiAbsent,
            boolean failIfPresent) throws SQLException {
        // Neither token is listed: a fresh row gets the database default, an
        // existing row keeps both. Rotation only happens through
        // regenerateAnimateurToken and regenerateAbonnementToken.
        String miseAJourEmail = conserverEmailSiAbsent
                ? "email = COALESCE(EXCLUDED.email, animateur.email)"
                : "email = EXCLUDED.email";
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance, manager, email)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, date_naissance = EXCLUDED.date_naissance,
                manager = EXCLUDED.manager, modifie_le = now(), """
                        + miseAJourEmail + "\n" + """
                WHERE CAST(? AS boolean)
                AND (CAST(? AS timestamptz) IS NULL
                     OR date_trunc('milliseconds', animateur.modifie_le)
                        = date_trunc('milliseconds', CAST(? AS timestamptz)))
                RETURNING modifie_le""")) {
            ps.setString(2, animateur.getId());
            ps.setString(3, animateur.getPrenom());
            ps.setString(4, animateur.getNom());
            ps.setObject(5, animateur.getDateNaissance());
            ps.setBoolean(6, animateur.isManager());
            ps.setString(7, animateur.getEmail());
            WriteStamp.bindPrecondition(ps, 8, !failIfPresent, animateur.getModifieLe());
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                refuse(failIfPresent, "animateur", animateur.getId());
            }
            animateur.setModifieLe(ecrit);
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
