package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Persists a solved {@link PlanningFestival} into PostgreSQL using plain JDBC
 * (the project only ships {@code quarkus-jdbc-postgresql}, no ORM). Reference
 * rows (stands, timeslots, animators) are up-serted first so the
 * {@code poste_affectation} foreign keys are satisfied, then the assignment
 * rows are fully rewritten to mirror the latest solution.
 *
 * <p>Everything written here belongs to the current {@code edition} (see
 * {@link EditionContext}): each edition keeps its own persisted planning and its
 * own freshness, so browsing 2025's calendars never shows 2026's solve.</p>
 */
@ApplicationScoped
public class PlanningPersistenceService {

    @Inject
    DataSource dataSource;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionContext editionContext;

    /** Edition every statement below reads and writes. */
    private String editionId() {
        return editionContext.editionIdCourant();
    }

    /**
     * Writes the whole solution to the database in a single transaction and
     * returns how many assignment rows were stored.
     */
    public int persist(PlanningFestival planning) {
        if (planning == null || planning.getPostes() == null) {
            return 0;
        }
        return inTransaction(connection -> {
            upsertReferenceData(connection, planning);
            int count = rewriteAssignments(connection, planning.getPostes());
            recordResolution(connection, planning);
            return count;
        }, "Failed to persist planning solution");
    }

    /**
     * Empties the <b>current group</b>: wipes its planning tables (stands,
     * timeslots, animators, assignments and constraints) without loading any
     * scenario, and resets its timeslot groups ({@code groupe_creneau}) back to
     * the single default one. Typologies are kept: they are seeded by the
     * Flyway migrations, not by a scenario. Used by the "Reset BDD" admin
     * action to start an edition from scratch — every other group is left
     * untouched, which is why this is a scoped {@code DELETE} rather than the
     * {@code TRUNCATE} it used to be.
     */
    public void clearDatabase() {
        inTransaction(connection -> {
            clearPlanningTables(connection);
            return 0;
        }, "Failed to clear the database");
    }

    /** Child-first order, so no {@code ON DELETE} cascade has to be relied on. */
    private static final List<String> TABLES_A_VIDER = List.of("poste_affectation", "demande_echange",
            "planning_resolution",
            "contrainte_animateur", "contrainte_ad_hoc", "verrouillage_planning", "stand_typologie",
            "animateur_competence", "animateur_jour_indispo", "animateur_souhait", "stand_indisponibilite",
            "stand_ouverture", "creneau_stand_ouvert", "stand", "creneau", "animateur", "groupe_creneau");

    private void clearPlanningTables(Connection connection) throws SQLException {
        for (String table : TABLES_A_VIDER) {
            // Table names come from the literal list above, never from user input.
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE edition_id = ?")) {
                ps.setString(1, editionId());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO groupe_creneau (edition_id, id, nom, actif) VALUES (?, 'DEFAUT', 'Défaut', TRUE)")) {
            ps.setString(1, editionId());
            ps.executeUpdate();
        }
    }

    private int inTransaction(TransactionalWork work, String errorMessage) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int rows = work.execute(connection);
                connection.commit();
                return rows;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    @FunctionalInterface
    private interface TransactionalWork {
        int execute(Connection connection) throws SQLException;
    }

    private void upsertReferenceData(Connection connection, PlanningFestival planning) throws SQLException {
        List<Stand> stands = new ArrayList<>();
        List<Creneau> creneaux = new ArrayList<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getStand() != null) {
                stands.add(poste.getStand());
            }
            if (poste.getCreneau() != null) {
                creneaux.add(poste.getCreneau());
            }
        }

        String upsertStand = "INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (edition_id, id) DO UPDATE SET "
                + "nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min, "
                + "effectif_max = EXCLUDED.effectif_max, reserve_majeurs = EXCLUDED.reserve_majeurs";
        List<Stand> distinctStands = dedupById(stands, Stand::getId);
        try (PreparedStatement ps = prepareScoped(connection, upsertStand)) {
            for (Stand stand : distinctStands) {
                ps.setString(2, stand.getId());
                ps.setString(3, stand.getNom());
                ps.setInt(4, stand.getEffectifMin());
                ps.setInt(5, stand.getEffectifMax());
                ps.setBoolean(6, stand.isReserveMajeurs());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        rewriteStandTypologies(connection, distinctStands);
        rewriteStandIndisponibilites(connection, distinctStands);
        rewriteStandOuvertures(connection, distinctStands);

        String upsertCreneau = "INSERT INTO creneau (edition_id, id, date_creneau, heure_debut, heure_fin) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET "
                + "date_creneau = EXCLUDED.date_creneau, "
                + "heure_debut = EXCLUDED.heure_debut, heure_fin = EXCLUDED.heure_fin";
        try (PreparedStatement ps = prepareScoped(connection, upsertCreneau)) {
            for (Creneau creneau : dedupById(creneaux, Creneau::getId)) {
                ps.setLong(2, creneau.getId());
                ps.setObject(3, creneau.getDate());
                ps.setObject(4, creneau.getHeureDebut());
                ps.setObject(5, creneau.getHeureFin());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String upsertAnimateur = "INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance, manager) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (edition_id, id) DO UPDATE SET "
                + "prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, "
                + "date_naissance = EXCLUDED.date_naissance, manager = EXCLUDED.manager";
        try (PreparedStatement ps = prepareScoped(connection, upsertAnimateur)) {
            List<Animateur> animateurs = planning.getAnimateurs() != null
                    ? planning.getAnimateurs()
                    : List.of();
            List<Animateur> distinctAnimateurs = dedupById(animateurs, Animateur::getId);
            for (Animateur animateur : distinctAnimateurs) {
                ps.setString(2, animateur.getId());
                ps.setString(3, animateur.getPrenom());
                ps.setString(4, animateur.getNom());
                ps.setObject(5, animateur.getDateNaissance());
                ps.setBoolean(6, animateur.isManager());
                ps.addBatch();
            }
            ps.executeBatch();
            rewriteAnimateurDetails(connection, distinctAnimateurs);
        }
    }

    private void rewriteStandTypologies(Connection connection, List<Stand> stands) throws SQLException {
        try (PreparedStatement delete = prepareScoped(connection,
                        "DELETE FROM stand_typologie WHERE edition_id = ? AND stand_id = ?");
                PreparedStatement insert = prepareScoped(connection,
                        "INSERT INTO stand_typologie (edition_id, stand_id, typologie) VALUES (?, ?, ?)")) {
            for (Stand stand : stands) {
                delete.setString(2, stand.getId());
                delete.addBatch();
                if (stand.getTypologiesProposees() != null) {
                    for (String typologie : stand.getTypologiesProposees()) {
                        insert.setString(2, stand.getId());
                        insert.setString(3, typologie);
                        insert.addBatch();
                    }
                }
            }
            delete.executeBatch();
            insert.executeBatch();
        }
    }

    private void rewriteStandIndisponibilites(Connection connection, List<Stand> stands) throws SQLException {
        try (PreparedStatement delete = prepareScoped(connection,
                        "DELETE FROM stand_indisponibilite WHERE edition_id = ? AND stand_id = ?");
                PreparedStatement insert = prepareScoped(connection,
                        "INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut, "
                                + "heure_fin, motif) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Stand stand : stands) {
                delete.setString(2, stand.getId());
                delete.addBatch();
                if (stand.getIndisponibilites() != null) {
                    for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
                        insert.setString(2, stand.getId());
                        insert.setObject(3, indispo.getDate());
                        insert.setObject(4, indispo.getHeureDebut());
                        insert.setObject(5, indispo.getHeureFin());
                        insert.setString(6, indispo.getMotif());
                        insert.addBatch();
                    }
                }
            }
            delete.executeBatch();
            insert.executeBatch();
        }
    }

    private void rewriteStandOuvertures(Connection connection, List<Stand> stands) throws SQLException {
        try (PreparedStatement delete = prepareScoped(connection,
                        "DELETE FROM stand_ouverture WHERE edition_id = ? AND stand_id = ?");
                PreparedStatement insert = prepareScoped(connection,
                        "INSERT INTO stand_ouverture (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, "
                                + "motif) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Stand stand : stands) {
                delete.setString(2, stand.getId());
                delete.addBatch();
                if (stand.getOuvertures() != null) {
                    for (OuvertureStand ouverture : stand.getOuvertures()) {
                        insert.setString(2, stand.getId());
                        insert.setObject(3, ouverture.getDate());
                        insert.setObject(4, ouverture.getHeureDebut());
                        insert.setObject(5, ouverture.getHeureFin());
                        insert.setString(6, ouverture.getMotif());
                        insert.addBatch();
                    }
                }
            }
            delete.executeBatch();
            insert.executeBatch();
        }
    }

    private void rewriteAnimateurDetails(Connection connection, List<Animateur> animateurs) throws SQLException {
        try (PreparedStatement deleteComp = prepareScoped(connection,
                        "DELETE FROM animateur_competence WHERE edition_id = ? AND animateur_id = ?");
                PreparedStatement insertComp = prepareScoped(connection,
                        "INSERT INTO animateur_competence (edition_id, animateur_id, typologie, niveau) "
                                + "VALUES (?, ?, ?, ?)");
                PreparedStatement deleteJour = prepareScoped(connection,
                        "DELETE FROM animateur_jour_indispo WHERE edition_id = ? AND animateur_id = ?");
                PreparedStatement insertJour = prepareScoped(connection,
                        "INSERT INTO animateur_jour_indispo (edition_id, animateur_id, jour) VALUES (?, ?, ?)")) {
            for (Animateur animateur : animateurs) {
                deleteComp.setString(2, animateur.getId());
                deleteComp.addBatch();
                deleteJour.setString(2, animateur.getId());
                deleteJour.addBatch();
                if (animateur.getCompetences() != null) {
                    for (Map.Entry<String, NiveauCompetence> entry : animateur.getCompetences().entrySet()) {
                        insertComp.setString(2, animateur.getId());
                        insertComp.setString(3, entry.getKey());
                        insertComp.setString(4, entry.getValue().name());
                        insertComp.addBatch();
                    }
                }
                if (animateur.getJoursIndisponibles() != null) {
                    for (LocalDate jour : animateur.getJoursIndisponibles()) {
                        insertJour.setString(2, animateur.getId());
                        insertJour.setObject(3, jour);
                        insertJour.addBatch();
                    }
                }
            }
            deleteComp.executeBatch();
            deleteJour.executeBatch();
            insertComp.executeBatch();
            insertJour.executeBatch();
        }
    }

    private int rewriteAssignments(Connection connection, List<PosteAffectation> postes) throws SQLException {
        try (PreparedStatement ps = prepareScoped(connection,
                "DELETE FROM poste_affectation WHERE edition_id = ?")) {
            ps.executeUpdate();
        }

        String insert = "INSERT INTO poste_affectation "
                + "(edition_id, id, stand_id, creneau_id, animateur_id, heure_debut_effective, heure_fin_effective) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        int count = 0;
        try (PreparedStatement ps = prepareScoped(connection, insert)) {
            for (PosteAffectation poste : postes) {
                if (poste.getStand() == null || poste.getCreneau() == null) {
                    continue;
                }
                ps.setString(2, poste.getId());
                ps.setString(3, poste.getStand().getId());
                ps.setLong(4, poste.getCreneau().getId());
                ps.setString(5, poste.getAnimateur() != null ? poste.getAnimateur().getId() : null);
                ps.setObject(6, poste.getHeureDebutEffective());
                ps.setObject(7, poste.getHeureFinEffective());
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    /**
     * Applies an accepted demande d'échange (issue #165) to the persisted
     * planning: the demandeur's seat on (créneau, stand) goes to the cible,
     * and — échange croisé — the cible's own seat on the same créneau goes to
     * the demandeur. Surgical {@code UPDATE}s in one transaction, so the rest
     * of the plan (and the seat ids) stay exactly as persisted. An animateur
     * holds at most one seat per stand × créneau (hard overlap constraint), so
     * matching on {@code animateur_id} designates a single row.
     *
     * @param standCibleId {@code null} for a simple takeover (the cible was
     *                     free on the créneau)
     */
    public void appliquerEchange(long creneauId, String standDemandeurId, String demandeurId,
            String cibleId, String standCibleId) {
        inTransaction(connection -> {
            int updated = reaffecterSiege(connection, creneauId, standDemandeurId, demandeurId, cibleId);
            if (updated == 0) {
                throw new SQLException("Aucun poste de " + demandeurId + " sur ce créneau et ce stand");
            }
            if (standCibleId != null) {
                int updatedCible = reaffecterSiege(connection, creneauId, standCibleId, cibleId, demandeurId);
                if (updatedCible == 0) {
                    throw new SQLException("Aucun poste de " + cibleId + " sur ce créneau et ce stand");
                }
            }
            return updated;
        }, "Failed to apply the échange to the persisted planning");
    }

    private int reaffecterSiege(Connection connection, long creneauId, String standId,
            String occupantActuelId, String nouvelOccupantId) throws SQLException {
        // Not prepareScoped: the SET clause claims placeholder 1, so the
        // edition_id predicate is bound explicitly here.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE poste_affectation SET animateur_id = ? "
                        + "WHERE edition_id = ? AND creneau_id = ? AND stand_id = ? AND animateur_id = ?")) {
            ps.setString(1, nouvelOccupantId);
            ps.setString(2, editionId());
            ps.setLong(3, creneauId);
            ps.setString(4, standId);
            ps.setString(5, occupantActuelId);
            return ps.executeUpdate();
        }
    }

    /**
     * Records which groupe de créneaux this solve was computed for, taken from
     * the (already-hydrated) créneau of the solved postes rather than
     * re-reading "the active group" from the database: that way the record
     * reflects the group actually solved even if it was changed while the
     * solve was running. Silently records {@code null} when the solved
     * postes carry no group (e.g. a YAML scenario solved without reference
     * data), rather than blocking persistence.
     */
    private void recordResolution(Connection connection, PlanningFestival planning) throws SQLException {
        String groupeCreneauId = planning.getPostes().stream()
                .map(PosteAffectation::getCreneau)
                .filter(Objects::nonNull)
                .map(Creneau::getGroupe)
                .filter(Objects::nonNull)
                .map(GroupeCreneau::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        String sql = "INSERT INTO planning_resolution (edition_id, groupe_creneau_id, resolu_le) VALUES (?, ?, ?) "
                + "ON CONFLICT (edition_id) DO UPDATE SET groupe_creneau_id = EXCLUDED.groupe_creneau_id, "
                + "resolu_le = EXCLUDED.resolu_le";
        try (PreparedStatement ps = prepareScoped(connection, sql)) {
            ps.setString(2, groupeCreneauId);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /**
     * The groupe de créneaux the last persisted solve was computed for, and
     * when it ran. {@code null} when nothing has been solved yet.
     */
    public PlanningResolution loadResolution() {
        String sql = "SELECT r.groupe_creneau_id, g.nom, r.resolu_le FROM planning_resolution r "
                + "LEFT JOIN groupe_creneau g ON g.edition_id = r.edition_id AND g.id = r.groupe_creneau_id "
                + "WHERE r.edition_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Timestamp resoluLe = rs.getTimestamp("resolu_le");
            return new PlanningResolution(rs.getString("groupe_creneau_id"), rs.getString("nom"),
                    resoluLe != null ? resoluLe.toInstant() : null);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load planning resolution", e);
        }
    }

    /** @param groupeCreneauId may be {@code null} if the group solved for was later deleted. */
    public record PlanningResolution(String groupeCreneauId, String groupeCreneauNom, Instant resoluLe) {
    }

    /**
     * Number of assignment rows currently stored, used to confirm persistence.
     */
    public int countPersistedAssignments() {
        String sql = "SELECT COUNT(*) FROM poste_affectation WHERE edition_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count persisted assignments", e);
        }
    }

    /**
     * The animateurs of the last persisted solve, grouped by stand × créneau
     * (see {@link #cleStandCreneau}) and ordered by poste id. Empty seats are
     * skipped: a lock never freezes a hole.
     *
     * <p>Deliberately keyed on stand × créneau rather than on the poste id:
     * {@code PlanningService.construirePostes} renumbers its seats
     * ({@code poste-0}, {@code poste-1}, …) on every build, so adding a single
     * stand shifts every subsequent id. Seats of the same stand and créneau are
     * interchangeable anyway, so re-seeding them positionally restores the same
     * plan without depending on ids surviving a reference-data change.</p>
     */
    public Map<String, List<String>> chargerAnimateursParStandCreneau() {
        Map<String, List<String>> parStandCreneau = new java.util.LinkedHashMap<>();
        String sql = "SELECT stand_id, creneau_id, animateur_id FROM poste_affectation "
                + "WHERE animateur_id IS NOT NULL ORDER BY id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                parStandCreneau
                        .computeIfAbsent(cleStandCreneau(rs.getString("stand_id"), rs.getLong("creneau_id")),
                                key -> new ArrayList<>())
                        .add(rs.getString("animateur_id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load persisted assignments", e);
        }
        return parStandCreneau;
    }

    /** Grouping key of {@link #chargerAnimateursParStandCreneau()}. */
    public static String cleStandCreneau(String standId, long creneauId) {
        return standId + "#" + creneauId;
    }

    /**
     * Rebuilds the last persisted solution from the database. Read-only view
     * used by the calendars and the exports: it never triggers a solve, so
     * simply browsing the app cannot start a solver run. Returns an empty
     * planning (no postes) when nothing has been solved yet.
     */
    public PlanningFestival loadPersistedPlanning() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        Map<String, Animateur> animateursById = indexById(animateurs, Animateur::getId);
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        List<Stand> stands = referenceDataService.listStands();
        // Every group's créneaux, not just the active one's: this view shows what
        // is persisted, so the horaires have to be resolved against the same
        // days it displays.
        HoraireStandResolver.appliquer(stands, creneaux);
        Map<String, Stand> standsById = indexById(stands, Stand::getId);
        Map<Long, Creneau> creneauxById = indexById(creneaux, Creneau::getId);

        List<PosteAffectation> postes = new ArrayList<>();
        String sql = "SELECT id, stand_id, creneau_id, animateur_id, heure_debut_effective, heure_fin_effective "
                + "FROM poste_affectation WHERE edition_id = ? ORDER BY id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Stand stand = standsById.get(rs.getString("stand_id"));
                Creneau creneau = creneauxById.get(rs.getLong("creneau_id"));
                if (stand == null || creneau == null) {
                    continue;
                }
                PosteAffectation poste = new PosteAffectation(rs.getString("id"), stand, creneau);
                String animateurId = rs.getString("animateur_id");
                if (animateurId != null) {
                    poste.setAnimateur(animateursById.get(animateurId));
                }
                poste.setHeureDebutEffective(rs.getObject("heure_debut_effective", LocalTime.class));
                poste.setHeureFinEffective(rs.getObject("heure_fin_effective", LocalTime.class));
                postes.add(poste);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load persisted planning", e);
        }

        LocalDate dateDebut = postes.stream()
                .map(poste -> poste.getCreneau().getDate())
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        return new PlanningFestival(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
    }

    /**
     * Prepares {@code sql} with the current group already bound to its
     * <b>first</b> placeholder — same convention as
     * {@code ReferenceDataRepository}: write the {@code edition_id} predicate or
     * column first, bind the rest from index 2.
     */
    private PreparedStatement prepareScoped(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            ps.setString(1, editionId());
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    private <T, K> Map<K, T> indexById(List<T> items, java.util.function.Function<T, K> idFn) {
        Map<K, T> byId = new java.util.HashMap<>();
        for (T item : items) {
            K id = idFn.apply(item);
            if (id != null) {
                byId.put(id, item);
            }
        }
        return byId;
    }

    private <T, K> List<T> dedupById(List<T> items, java.util.function.Function<T, K> idFn) {
        List<T> result = new ArrayList<>();
        java.util.Set<K> seen = new java.util.HashSet<>();
        for (T item : items) {
            K id = idFn.apply(item);
            if (id != null && seen.add(id)) {
                result.add(item);
            }
        }
        return result;
    }
}
