package dev.sylvain.planning.service.solve;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;

/**
 * Persists a solved {@link PlanningEvenement} into PostgreSQL using plain JDBC
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
    JdbcEditionScope scope;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    SolverJobService solverJobs;

    /** Edition every statement below reads and writes. */
    private String editionId() {
        return scope.editionId();
    }

    /**
     * Writes the whole solution to the database in a single transaction and
     * returns how many assignment rows were stored.
     */
    public int persist(PlanningEvenement planning) {
        if (planning == null || planning.getPostes() == null) {
            return 0;
        }
        return scope.writeAndReturn("Failed to persist planning solution", connection -> {
            upsertReferenceData(connection, planning);
            int count = rewriteAssignments(connection, planning.getPostes());
            recordResolution(connection);
            return count;
        });
    }

    /**
     * Empties the <b>current edition</b>: wipes its planning tables (stands,
     * timeslots, animators, assignments and constraints) without loading any
     * scenario. Typologies are kept: they are seeded by the Flyway
     * migrations, not by a scenario. Used by the "Reset BDD" admin action to
     * start an edition from scratch — every other edition is left untouched,
     * which is why this is a scoped {@code DELETE} rather than the
     * {@code TRUNCATE} it used to be.
     */
    public void clearDatabase() {
        // Refused while a solve holds this edition's solver, and the stakes are
        // higher here than for a single delete: the landing persist would put
        // back stands, animateurs, créneaux and poste_affectation, but not
        // emplacement, stand_horaire, stand_typologie, stand_ouverture nor the
        // ad hoc constraints — leaving the edition half-restored (issue #328).
        solverJobs.refuseIfSolving();
        scope.write("Failed to clear the database", this::clearPlanningTables);
    }

    /** Child-first order, so no {@code ON DELETE} cascade has to be relied on. */
    private static final List<String> TABLES_A_VIDER = List.of("poste_affectation", "demande_echange",
            "planning_resolution",
            "contrainte_animateur", "contrainte_ad_hoc", "verrouillage_planning", "stand_typologie",
            "animateur_competence", "animateur_jour_indispo", "animateur_souhait", "stand_indisponibilite",
            "stand_ouverture", "creneau_stand_ouvert", "stand", "creneau", "animateur");

    private void clearPlanningTables(Connection connection) throws SQLException {
        for (String table : TABLES_A_VIDER) {
            // Table names come from the literal list above, never from user input.
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM " + table + " WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
        }
    }

    private void upsertReferenceData(Connection connection, PlanningEvenement planning) throws SQLException {
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

        String upsertStand = """
 INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs)
 VALUES (?, ?, ?, ?, ?, ?)
 ON CONFLICT (edition_id, id)
 DO UPDATE SET nom = EXCLUDED.nom, effectif_min = EXCLUDED.effectif_min, effectif_max = EXCLUDED.effectif_max,
 reserve_majeurs = EXCLUDED.reserve_majeurs""";
        List<Stand> distinctStands = dedupById(stands, Stand::getId);
        try (PreparedStatement ps = scope.prepareScoped(connection, upsertStand)) {
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

        String upsertCreneau = """
 INSERT INTO creneau (edition_id, id, date_creneau, heure_debut, heure_fin)
 VALUES (?, ?, ?, ?, ?)
 ON CONFLICT (id)
 DO UPDATE SET date_creneau = EXCLUDED.date_creneau, heure_debut = EXCLUDED.heure_debut, heure_fin = EXCLUDED.heure_fin""";
        try (PreparedStatement ps = scope.prepareScoped(connection, upsertCreneau)) {
            for (Creneau creneau : dedupById(creneaux, Creneau::getId)) {
                ps.setLong(2, creneau.getId());
                ps.setObject(3, creneau.getDate());
                ps.setObject(4, creneau.getHeureDebut());
                ps.setObject(5, creneau.getHeureFin());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        String upsertAnimateur = """
 INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance, manager)
 VALUES (?, ?, ?, ?, ?, ?)
 ON CONFLICT (edition_id, id)
 DO UPDATE SET prenom = EXCLUDED.prenom, nom = EXCLUDED.nom, date_naissance = EXCLUDED.date_naissance,
 manager = EXCLUDED.manager""";
        try (PreparedStatement ps = scope.prepareScoped(connection, upsertAnimateur)) {
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
        try (PreparedStatement delete = scope.prepareScoped(connection,
                        "DELETE FROM stand_typologie WHERE edition_id = ? AND stand_id = ?");
                PreparedStatement insert = scope.prepareScoped(connection,
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
        try (PreparedStatement delete = scope.prepareScoped(connection,
                        "DELETE FROM stand_indisponibilite WHERE edition_id = ? AND stand_id = ?");
                PreparedStatement insert = scope.prepareScoped(connection,
                        """
                        INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite,
                        heure_debut, heure_fin, motif)
                        VALUES (?, ?, ?, ?, ?, ?)""")) {
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
        try (PreparedStatement delete = scope.prepareScoped(connection,
                        "DELETE FROM stand_ouverture WHERE edition_id = ? AND stand_id = ?");
                PreparedStatement insert = scope.prepareScoped(connection,
                        """
                        INSERT INTO stand_ouverture (edition_id, stand_id, date_ouverture,
                        heure_debut, heure_fin, motif, effectif)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
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
                        insert.setObject(7, ouverture.getEffectif());
                        insert.addBatch();
                    }
                }
            }
            delete.executeBatch();
            insert.executeBatch();
        }
    }

    private void rewriteAnimateurDetails(Connection connection, List<Animateur> animateurs) throws SQLException {
        try (PreparedStatement deleteComp = scope.prepareScoped(connection,
                        "DELETE FROM animateur_competence WHERE edition_id = ? AND animateur_id = ?");
                PreparedStatement insertComp = scope.prepareScoped(connection,
                        """
                        INSERT INTO animateur_competence (edition_id, animateur_id, typologie, niveau)
                        VALUES (?, ?, ?, ?)""");
                PreparedStatement deleteJour = scope.prepareScoped(connection,
                        "DELETE FROM animateur_jour_indispo WHERE edition_id = ? AND animateur_id = ?");
                PreparedStatement insertJour = scope.prepareScoped(connection,
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
        try (PreparedStatement ps = scope.prepareScoped(connection,
                "DELETE FROM poste_affectation WHERE edition_id = ?")) {
            ps.executeUpdate();
        }

        String insert = """
 INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id,
 heure_debut_effective, heure_fin_effective)
 VALUES (?, ?, ?, ?, ?, ?, ?)""";
        int count = 0;
        try (PreparedStatement ps = scope.prepareScoped(connection, insert)) {
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
     * planning: the demandeur's seat on (créneau, stand) goes to the target,
     * and — échange croisé — the target's own seat on the same créneau goes to
     * the demandeur. Surgical {@code UPDATE}s in one transaction, so the rest
     * of the plan (and the seat ids) stay exactly as persisted. An animateur
     * holds at most one seat per stand × créneau (hard overlap constraint), so
     * matching on {@code animateur_id} designates a single row.
     *
     * @param standCibleId {@code null} for a simple takeover (the target was
     *                     free on the créneau)
     */
    public void applyEchange(long creneauId, String standDemandeurId, String demandeurId,
            String cibleId, String standCibleId) {
        scope.writeAndReturn("Failed to apply the échange to the persisted planning", connection -> {
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
        });
    }

    /**
     * Directed variant of {@link #applyEchange}: the two reassigned seats
     * sit on two different créneaux — the demandeur's goes to the target, the
     * target's goes to the demandeur. Same one-transaction surgical updates.
     */
    public void applyDirectedEchange(long creneauId, String standDemandeurId, String demandeurId,
            String cibleId, long creneauCibleId, String standCibleId) {
        scope.writeAndReturn("Failed to apply the échange dirigé to the persisted planning", connection -> {
            int updated = reaffecterSiege(connection, creneauId, standDemandeurId, demandeurId, cibleId);
            if (updated == 0) {
                throw new SQLException("Aucun poste de " + demandeurId + " sur ce créneau et ce stand");
            }
            int updatedCible = reaffecterSiege(connection, creneauCibleId, standCibleId, cibleId, demandeurId);
            if (updatedCible == 0) {
                throw new SQLException("Aucun poste de " + cibleId + " sur ce créneau et ce stand");
            }
            return updated;
        });
    }

    /**
     * Hands one persisted seat to another animateur (issue #71), leaving every
     * other row exactly as it was — the surgical counterpart of
     * {@link #persist}, which rewrites the whole plan.
     *
     * <p>Keyed by the seat's own id rather than by its current occupant, unlike
     * {@link #applyEchange}: a repair suggestion routinely targets an
     * <em>empty</em> seat (a poste the last solve left unstaffed is precisely
     * what needs repairing), and there is no occupant to match on there.</p>
     *
     * @param animateurId {@code null} empties the seat
     * @return true when a seat was actually reassigned, false when this edition
     *         holds no such seat
     */
    public boolean reaffecterPoste(String posteId, String animateurId) {
        return scope.writeAndReturn("Failed to reassign the poste", connection -> {
            // Not prepareScoped: the SET clause claims placeholder 1, so the
            // edition_id predicate is bound explicitly here.
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE poste_affectation SET animateur_id = ? WHERE edition_id = ? AND id = ?")) {
                ps.setString(1, animateurId);
                ps.setString(2, editionId());
                ps.setString(3, posteId);
                return ps.executeUpdate();
            }
        }) > 0;
    }

    /**
     * Several seats changing hands at once, one transaction (issue #308): a
     * movement rewrites two rows, and a swap half done would leave one person
     * in two places. {@code null} empties a seat, like {@link #reaffecterPoste}.
     */
    public void reaffecterPostes(Map<String, String> animateurParPoste) {
        scope.write("Failed to move the seats", connection -> {
            for (Map.Entry<String, String> entree : animateurParPoste.entrySet()) {
                // Not prepareScoped: the SET clause claims placeholder 1.
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE poste_affectation SET animateur_id = ? WHERE edition_id = ? AND id = ?")) {
                    ps.setString(1, entree.getValue());
                    ps.setString(2, editionId());
                    ps.setString(3, entree.getKey());
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("Aucun poste " + entree.getKey() + " dans cette édition");
                    }
                }
            }
        });
    }

    private int reaffecterSiege(Connection connection, long creneauId, String standId,
            String occupantActuelId, String nouvelOccupantId) throws SQLException {
        // Not prepareScoped: the SET clause claims placeholder 1, so the
        // edition_id predicate is bound explicitly here.
        try (PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE poste_affectation
                SET animateur_id = ?
                WHERE edition_id = ? AND creneau_id = ? AND stand_id = ? AND animateur_id = ?""")) {
            ps.setString(1, nouvelOccupantId);
            ps.setString(2, editionId());
            ps.setLong(3, creneauId);
            ps.setString(4, standId);
            ps.setString(5, occupantActuelId);
            return ps.executeUpdate();
        }
    }

    /** Stamps when this edition's plan was last solved and persisted. */
    private void recordResolution(Connection connection) throws SQLException {
        String sql = """
 INSERT INTO planning_resolution (edition_id, resolu_le)
 VALUES (?, ?)
 ON CONFLICT (edition_id)
 DO UPDATE SET resolu_le = EXCLUDED.resolu_le""";
        try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /**
     * The groupe de créneaux the last persisted solve was computed for, and
     * when it ran. {@code null} when nothing has been solved yet.
     */
    public PlanningResolution loadResolution() {
        String sql = "SELECT resolu_le FROM planning_resolution WHERE edition_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Timestamp resoluLe = rs.getTimestamp("resolu_le");
            return new PlanningResolution(resoluLe != null ? resoluLe.toInstant() : null);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load planning resolution", e);
        }
    }

    public record PlanningResolution(Instant resoluLe) {
    }

    /**
     * Number of assignment rows currently stored, used to confirm persistence.
     */
    public int countPersistedAssignments() {
        String sql = "SELECT COUNT(*) FROM poste_affectation WHERE edition_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count persisted assignments", e);
        }
    }

    /**
     * The animateurs of the last persisted solve, grouped by stand × créneau
     * (see {@link #standCreneauKey}) and ordered by poste id. Empty seats are
     * skipped: a lock never freezes a hole.
     *
     * <p>Deliberately keyed on stand × créneau rather than on the poste id:
     * {@code ProblemBuilder.buildPostes} renumbers its seats
     * ({@code poste-0}, {@code poste-1}, …) on every build, so adding a single
     * stand shifts every subsequent id. Seats of the same stand and créneau are
     * interchangeable anyway, so re-seeding them positionally restores the same
     * plan without depending on ids surviving a reference-data change.</p>
     */
    public Map<String, List<String>> loadAnimateursByStandCreneau() {
        Map<String, List<String>> parStandCreneau = new LinkedHashMap<>();
        String sql = """
 SELECT stand_id, creneau_id, animateur_id
 FROM poste_affectation
 WHERE edition_id = ? AND animateur_id IS NOT NULL
 ORDER BY id""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                parStandCreneau
                        .computeIfAbsent(standCreneauKey(rs.getString("stand_id"), rs.getLong("creneau_id")),
                                key -> new ArrayList<>())
                        .add(rs.getString("animateur_id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load persisted assignments", e);
        }
        return parStandCreneau;
    }

    /** Grouping key of {@link #loadAnimateursByStandCreneau()}. */
    public static String standCreneauKey(String standId, long creneauId) {
        return standId + "#" + creneauId;
    }

    /**
     * Rebuilds the last persisted solution from the database. Read-only view
     * used by the calendars and the exports: it never triggers a solve, so
     * simply browsing the app cannot start a solver run. Returns an empty
     * planning (no postes) when nothing has been solved yet.
     */
    public PlanningEvenement loadPersistedPlanning() {
        return assemblerPlanning(readSieges());
    }

    /**
     * One seat, referential ids only — what both the {@code poste_affectation}
     * table and a snapshot's denormalised content carry.
     */
    public record Siege(String posteId, String standId, long creneauId, String animateurId,
            LocalTime heureDebutEffective, LocalTime heureFinEffective) {
    }

    /**
     * Resolves seats against today's referential and returns a planning ready
     * to read. Shared by the persisted plan and by the published one
     * (issue #245), which lives in a snapshot rather than in
     * {@code poste_affectation} but resolves exactly the same way — a seat
     * naming a stand or a créneau that no longer exists is dropped, since
     * there is nothing left to display it against.
     */
    public PlanningEvenement assemblerPlanning(List<Siege> sieges) {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        Map<String, Animateur> animateursById = indexById(animateurs, Animateur::getId);
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        List<Stand> stands = referenceDataService.listStands();
        // Every group's créneaux, not just the active one's: this view shows what
        // is persisted, so the horaires have to be resolved against the same
        // days it displays.
        HoraireStandResolver.apply(stands, creneaux);
        Map<String, Stand> standsById = indexById(stands, Stand::getId);
        Map<Long, Creneau> creneauxById = indexById(creneaux, Creneau::getId);

        List<PosteAffectation> postes = new ArrayList<>();
        for (Siege siege : sieges) {
            Stand stand = standsById.get(siege.standId());
            Creneau creneau = creneauxById.get(siege.creneauId());
            if (stand == null || creneau == null) {
                continue;
            }
            PosteAffectation poste = new PosteAffectation(siege.posteId(), stand, creneau);
            if (siege.animateurId() != null) {
                poste.setAnimateur(animateursById.get(siege.animateurId()));
            }
            poste.setHeureDebutEffective(siege.heureDebutEffective());
            poste.setHeureFinEffective(siege.heureFinEffective());
            postes.add(poste);
        }

        LocalDate dateDebut = postes.stream()
                .map(poste -> poste.getCreneau().getDate())
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        PlanningEvenement evenement = new PlanningEvenement(dateDebut, animateurs, postes,
                referenceDataService.snapshotContraintes());
        // The plan carries the legal parameters it was made under, so every
        // read-out downstream (breaks, exports, the animateur's espace) reads
        // the organiser's declarations from the plan itself.
        evenement.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        return evenement;
    }

    private List<Siege> readSieges() {
        List<Siege> sieges = new ArrayList<>();
        String sql = """
 SELECT id, stand_id, creneau_id, animateur_id, heure_debut_effective, heure_fin_effective
 FROM poste_affectation
 WHERE edition_id = ?
 ORDER BY id""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sieges.add(new Siege(
                        rs.getString("id"),
                        rs.getString("stand_id"),
                        rs.getLong("creneau_id"),
                        rs.getString("animateur_id"),
                        rs.getObject("heure_debut_effective", LocalTime.class),
                        rs.getObject("heure_fin_effective", LocalTime.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load persisted planning", e);
        }
        return sieges;
    }

    private <T, K> Map<K, T> indexById(List<T> items, Function<T, K> idFn) {
        Map<K, T> byId = new HashMap<>();
        for (T item : items) {
            K id = idFn.apply(item);
            if (id != null) {
                byId.put(id, item);
            }
        }
        return byId;
    }

    private <T, K> List<T> dedupById(List<T> items, Function<T, K> idFn) {
        List<T> result = new ArrayList<>();
        Set<K> seen = new HashSet<>();
        for (T item : items) {
            K id = idFn.apply(item);
            if (id != null && seen.add(id)) {
                result.add(item);
            }
        }
        return result;
    }
}
