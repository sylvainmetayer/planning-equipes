package dev.sylvain.planning.service.solve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.domain.VerrouillageTarget;
import dev.sylvain.planning.scenario.dto.AnimateurDto;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.StandDto;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.referentiel.Avertissement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The safety net under the two gestures that empty an edition — the reset and
 * the import of a scenario (ADR 0084): right before either writes, the
 * edition's own scenario export and its plan (seats and locks) are stored in
 * {@code sauvegarde_avant_vidage}. Re-importing the scenario puts the
 * referential back, {@link #restorePlan} the plan on top of it. The nightly
 * dump could only do so by rolling every other edition back with it.
 *
 * <p>A backup that cannot be written <b>stops</b> the wipe — the opposite of
 * the automatique snapshot, which never fails a solve: a solve destroys nothing
 * a snapshot alone could bring back, a wipe destroys everything. An edition
 * with nothing to export is not an error: there is nothing to lose.</p>
 */
@ApplicationScoped
public class WipeBackupService {

    /** Same bound as the automatique snapshots: an afternoon of mistakes, not an archive. */
    static final int KEPT_PER_EDITION = 5;

    /** What emptied the edition, as stored in {@code origine}. */
    public enum Trigger {
        RESET,
        IMPORT
    }

    /** One backup, without its content. */
    public record WipeBackup(long id, Trigger origine, Instant creeLe) {}

    /**
     * What {@link #restorePlan} put back, and what it could not, one sentence
     * each, naming ids only. Every seat counted was written; a lock whose
     * target was already frozen is counted apart, since nothing was written.
     *
     * @param score the score of the restored plan, hard part included, {@code null} when it could not be analysed
     */
    public record PlanRestoration(
            int affectationsRestaurees,
            List<String> affectationsNonRestaurees,
            int verrouillagesRestaures,
            int verrouillagesDejaPresents,
            List<String> verrouillagesNonRestaures,
            List<String> avertissements,
            String score) {}

    /** The plan column: seats and locks, under the ids the edition had when the backup was taken. */
    record PlanBackup(List<AffectationSnapshot> affectations, List<LockBackup> verrouillages) {}

    /** A lock, its timeslot named by its natural key — the one the lock itself stores. */
    record LockBackup(
            TypeVerrouillage type,
            String animateurId,
            String standId,
            LocalDate creneauDate,
            LocalTime creneauHeureDebut,
            LocalTime creneauHeureFin,
            LocalDate jour,
            String raison) {}

    private final JdbcEditionScope scope;

    private final PlanningService planningService;

    private final PlanSnapshotService snapshots;

    private final ReferenceDataService referenceDataService;

    private final ConstraintAnalysisStore analysisStore;

    private final ObjectMapper objectMapper;

    @Inject
    public WipeBackupService(
            JdbcEditionScope scope,
            PlanningService planningService,
            PlanSnapshotService snapshots,
            ReferenceDataService referenceDataService,
            ConstraintAnalysisStore analysisStore,
            ObjectMapper objectMapper) {
        this.scope = scope;
        this.planningService = planningService;
        this.snapshots = snapshots;
        this.referenceDataService = referenceDataService;
        this.analysisStore = analysisStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Backs the current edition up before an import, in a transaction of its
     * own since the import spans several. Refused while a solve holds the
     * edition, like the import it precedes: the interceptor runs in the
     * edition the file targets, so a refused import writes no backup. What
     * can still refuse it later (a contradiction, a stand check) leaves this
     * backup behind but evicts nothing: the bound is only enforced once the
     * import has gone through ({@link #enforceBound()}).
     */
    @RefusedWhileSolving
    public void backUpBeforeImport() {
        scope.write("Failed to back the edition up before an import", connection -> insert(connection, Trigger.IMPORT));
    }

    /** Drops the oldest backups beyond {@link #KEPT_PER_EDITION}: called once the import has gone through. */
    public void enforceBound() {
        scope.write("Failed to purge the edition's backups", this::purge);
    }

    /** Backs the current edition up inside the caller's transaction, so the wipe and its backup land together. */
    void backUp(Connection connection, Trigger trigger) throws SQLException {
        insert(connection, trigger);
        purge(connection);
    }

    /**
     * Writes the backup, unless the edition holds nothing a wipe would destroy
     * or its latest backup is already this very state — a retried gesture
     * must not push the useful backup out. An edition half built (an imported
     * team, no grid yet) is backed up all the same: the scenario format reads
     * an empty section back.
     */
    private void insert(Connection connection, Trigger trigger) throws SQLException {
        if (!holdsAnything(connection)) {
            return;
        }
        String yaml = planningService.scenarioYaml();
        List<LockBackup> verrouillages = referenceDataService.listVerrouillages().stream()
                .map(v -> new LockBackup(
                        v.getType(),
                        v.getAnimateurId(),
                        v.getStandId(),
                        v.getCreneauDate(),
                        v.getCreneauHeureDebut(),
                        v.getCreneauHeureFin(),
                        v.getJour(),
                        v.getRaison()))
                .toList();
        String plan = json(new PlanBackup(snapshots.loadPersistedAffectations(), verrouillages));
        try (PreparedStatement ps = scope.prepareScoped(connection, """
 SELECT 1 FROM sauvegarde_avant_vidage s
 WHERE s.edition_id = ? AND s.contenu = ? AND s.plan = ?::jsonb
 AND s.id = (SELECT d.id FROM sauvegarde_avant_vidage d WHERE d.edition_id = s.edition_id
 ORDER BY d.cree_le DESC, d.id DESC LIMIT 1)""")) {
            ps.setString(2, yaml);
            ps.setString(3, plan);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement ps = scope.prepareScoped(connection, """
 INSERT INTO sauvegarde_avant_vidage (edition_id, origine, contenu, plan)
 VALUES (?, ?, ?, ?::jsonb)""")) {
            ps.setString(2, trigger.name());
            ps.setString(3, yaml);
            ps.setString(4, plan);
            ps.executeUpdate();
        }
    }

    /** Whether any table a wipe empties holds a row of the current edition. */
    private boolean holdsAnything(Connection connection) throws SQLException {
        for (String table : PlanningPersistenceService.TABLES_A_VIDER) {
            // Table names come from that literal list, never from user input.
            try (PreparedStatement ps = scope.prepareScoped(
                            connection, "SELECT 1 FROM " + table + " WHERE edition_id = ? LIMIT 1");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void purge(Connection connection) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
 DELETE FROM sauvegarde_avant_vidage
 WHERE edition_id = ?
 AND id NOT IN (SELECT id FROM sauvegarde_avant_vidage WHERE edition_id = ?
 ORDER BY cree_le DESC, id DESC LIMIT ?)""")) {
            ps.setString(2, scope.editionId());
            ps.setInt(3, KEPT_PER_EDITION);
            ps.executeUpdate();
        }
    }

    /** The current edition's backups, most recent first. */
    public List<WipeBackup> list() {
        return scope.read("Failed to list the edition's backups", connection -> {
            List<WipeBackup> backups = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
 SELECT id, origine, cree_le FROM sauvegarde_avant_vidage
 WHERE edition_id = ? ORDER BY cree_le DESC, id DESC""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    backups.add(new WipeBackup(
                            rs.getLong("id"),
                            Trigger.valueOf(rs.getString("origine")),
                            rs.getTimestamp("cree_le").toInstant()));
                }
            }
            return backups;
        });
    }

    /** The scenario a backup of the current edition holds, ready for the import. */
    public String loadContent(long id) {
        return column(id, SELECT_CONTENU);
    }

    /**
     * Puts a backup's plan back on the referential its scenario was
     * re-imported as: every seat and lock whose stand, timeslot and animateur
     * are found again, the rest listed rather than guessed.
     *
     * <p>The ids of the backup are those of the edition before the wipe, and
     * a re-import gives new ones; so each is found again by what does not
     * move — a stand by its code, else its name; an animateur by e-mail, else
     * by first and last name (the import's own rule); a timeslot by its date
     * and hours. A key matching zero rows or several matches nothing. A seat
     * whose animateur is not found comes back empty; one whose stand or
     * timeslot is not found does not come back. The plan replaces the
     * persisted one wholesale, like a snapshot restore; the locks are added
     * through the usual lock write, its checks and its warnings.</p>
     *
     * <p>Nothing here refuses a seat for breaking a hard rule — no seat write
     * of the application does; the score of the restored plan says whether
     * one does, and the lock warnings name the frozen seats concerned.</p>
     */
    @RefusedWhileSolving
    public PlanRestoration restorePlan(long id) {
        ScenarioDto scenario = ScenarioYamlReader.parseScenarioText(loadContent(id));
        PlanBackup plan = readPlan(column(id, SELECT_PLAN));
        List<Stand> standsActuels = referenceDataService.listStands();
        List<Animateur> animateursActuels = referenceDataService.listAnimateurs();
        Map<String, String> stands = match(
                scenario.stands(),
                standsActuels,
                StandDto::id,
                Stand::getId,
                new Key<>(StandDto::code, Stand::getCode),
                new Key<>(StandDto::nom, Stand::getNom));
        Map<String, String> animateurs = match(
                scenario.animateurs(),
                animateursActuels,
                AnimateurDto::id,
                Animateur::getId,
                new Key<>(AnimateurDto::email, Animateur::getEmail),
                new Key<>(
                        dto -> dto.prenom() + "|" + dto.nom(),
                        animateur -> animateur.getPrenom() + "|" + animateur.getNom()));
        List<Creneau> grille = referenceDataService.listCreneaux();

        List<AffectationSnapshot> restaurees = new ArrayList<>();
        List<String> nonRestaurees = new ArrayList<>();
        for (AffectationSnapshot seat : plan.affectations()) {
            String vacation = seat.date() + " " + seat.heureDebut() + "-" + seat.heureFin();
            Optional<String> stand = Optional.ofNullable(stands.get(seat.standId()));
            Optional<Long> creneau = creneau(
                    grille,
                    LocalDate.parse(seat.date()),
                    LocalTime.parse(seat.heureDebut()),
                    LocalTime.parse(seat.heureFin()));
            if (stand.isEmpty() || creneau.isEmpty()) {
                nonRestaurees.add(seat.posteId() + " (stand " + seat.standId() + ", " + vacation + ") : "
                        + (stand.isEmpty() ? "stand" : "créneau") + " introuvable, siège non restauré");
                continue;
            }
            String animateur = null;
            if (seat.animateurId() != null) {
                animateur = animateurs.get(seat.animateurId());
                if (animateur == null) {
                    nonRestaurees.add(seat.posteId() + " (stand " + seat.standId() + ", " + vacation + ") : animateur "
                            + seat.animateurId() + " introuvable, siège restauré vide");
                }
            }
            restaurees.add(new AffectationSnapshot(
                    seat.posteId(),
                    stand.get(),
                    String.valueOf(creneau.get()),
                    seat.date(),
                    seat.heureDebut(),
                    seat.heureFin(),
                    animateur,
                    seat.heureDebutEffective(),
                    seat.heureFinEffective(),
                    seat.suiteDe()));
        }
        // A continuation whose first half did not come back continues nothing.
        Set<String> postes =
                restaurees.stream().map(AffectationSnapshot::posteId).collect(Collectors.toSet());
        restaurees.replaceAll(seat -> seat.suiteDe() == null || postes.contains(seat.suiteDe())
                ? seat
                : new AffectationSnapshot(
                        seat.posteId(),
                        seat.standId(),
                        seat.creneauId(),
                        seat.date(),
                        seat.heureDebut(),
                        seat.heureFin(),
                        seat.animateurId(),
                        seat.heureDebutEffective(),
                        seat.heureFinEffective()));
        snapshots.rewritePlan("Failed to restore the plan of backup " + id, restaurees, null);
        int assises = (int)
                restaurees.stream().filter(seat -> seat.animateurId() != null).count();

        int verrous = 0;
        int dejaPresents = 0;
        // The lock write does nothing on a target already frozen: such a lock
        // is counted apart, never as restored.
        Set<VerrouillageTarget> poses = referenceDataService.listVerrouillages().stream()
                .map(VerrouillagePlanning::target)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(HashSet::new));
        List<String> verrousNonRestaures = new ArrayList<>();
        List<String> avertissements = new ArrayList<>();
        for (LockBackup lock : plan.verrouillages()) {
            VerrouillagePlanning verrou = new VerrouillagePlanning(null, lock.type());
            verrou.setJour(lock.jour());
            verrou.setRaison(lock.raison());
            Optional<String> introuvable = Optional.empty();
            if (lock.animateurId() != null) {
                verrou.setAnimateurId(animateurs.get(lock.animateurId()));
                introuvable =
                        verrou.getAnimateurId() == null ? Optional.of("animateur " + lock.animateurId()) : introuvable;
            }
            if (lock.standId() != null) {
                verrou.setStandId(stands.get(lock.standId()));
                introuvable = verrou.getStandId() == null ? Optional.of("stand " + lock.standId()) : introuvable;
            }
            if (lock.creneauDate() != null) {
                verrou.setCreneauId(
                        creneau(grille, lock.creneauDate(), lock.creneauHeureDebut(), lock.creneauHeureFin())
                                .orElse(null));
                introuvable = verrou.getCreneauId() == null
                        ? Optional.of("créneau " + lock.creneauDate() + " " + lock.creneauHeureDebut() + "-"
                                + lock.creneauHeureFin())
                        : introuvable;
            }
            if (introuvable.isPresent()) {
                verrousNonRestaures.add("Verrou " + lock.type() + " : " + introuvable.get() + " introuvable");
                continue;
            }
            if (verrou.target().filter(poses::contains).isPresent()) {
                dejaPresents++;
                continue;
            }
            try {
                avertissements.addAll(referenceDataService.writeVerrouillage(verrou).avertissements().stream()
                        .map(Avertissement::message)
                        .toList());
                verrous++;
                verrou.target().ifPresent(poses::add);
            } catch (BusinessError refus) {
                verrousNonRestaures.add("Verrou " + lock.type() + " : " + refus.getMessage());
            }
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        return new PlanRestoration(
                assises,
                nonRestaurees,
                verrous,
                dejaPresents,
                verrousNonRestaures,
                avertissements,
                analysis == null ? null : analysis.diagnostic().score());
    }

    /** One key a backup row and an edition row are compared on: e-mail, name, code. */
    private record Key<T, R>(Function<T, String> backup, Function<R, String> edition) {}

    /**
     * Each id of the backup → the row of the edition it designates now, under
     * a prudent and injective rule. The keys are tried in order; a key that
     * names several rows of the backup or of the edition is an ambiguity and
     * stops there — no falling back on a weaker key, since two namesakes are
     * exactly where that would hand one person's seats to the other. A key
     * matching nothing falls back on the next. And a row two backup ids
     * designate goes to neither. Missing from the map: not found.
     */
    @SafeVarargs
    private static <T, R> Map<String, String> match(
            List<T> backup,
            List<R> edition,
            Function<T, String> backupId,
            Function<R, String> editionId,
            Key<T, R>... keys) {
        Map<String, String> cibles = new HashMap<>();
        for (T row : backup == null ? List.<T>of() : backup) {
            for (Key<T, R> key : keys) {
                String value = normalise(key.backup().apply(row));
                if (value == null) {
                    continue;
                }
                long inBackup = backup.stream()
                        .filter(other -> value.equals(normalise(key.backup().apply(other))))
                        .count();
                List<R> inEdition = edition.stream()
                        .filter(candidate ->
                                value.equals(normalise(key.edition().apply(candidate))))
                        .toList();
                if (inBackup > 1 || inEdition.size() > 1) {
                    break;
                }
                if (inEdition.size() == 1) {
                    cibles.put(backupId.apply(row), editionId.apply(inEdition.getFirst()));
                    break;
                }
            }
        }
        Map<String, Long> claims =
                cibles.values().stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        cibles.values().removeIf(cible -> claims.get(cible) > 1);
        return cibles;
    }

    /** The import's own normalisation ({@code ScenarioIdRemap}): trimmed, lower case, blank as absent. */
    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<Long> creneau(List<Creneau> grille, LocalDate date, LocalTime debut, LocalTime fin) {
        return unique(
                grille,
                creneau -> date.equals(creneau.getDate())
                        && debut.equals(creneau.getHeureDebut())
                        && fin.equals(creneau.getHeureFin()),
                Creneau::getId);
    }

    /** The one row the test accepts; none when zero or several do — an ambiguity is not a match. */
    private static <T, R> Optional<R> unique(List<T> rows, Predicate<T> test, Function<T, R> id) {
        List<T> candidats = rows.stream().filter(test).toList();
        return candidats.size() == 1 ? Optional.of(id.apply(candidats.getFirst())) : Optional.empty();
    }

    private static final String SELECT_CONTENU =
            "SELECT contenu FROM sauvegarde_avant_vidage WHERE edition_id = ? AND id = ?";

    private static final String SELECT_PLAN =
            "SELECT plan FROM sauvegarde_avant_vidage WHERE edition_id = ? AND id = ?";

    /** The one column {@code sql} selects, of one backup of the current edition. */
    private String column(long id, String sql) {
        return scope.read("Failed to read an edition's backup", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new BusinessError.NotFound("Sauvegarde " + id + " introuvable dans cette édition.");
                    }
                    return rs.getString(1);
                }
            }
        });
    }

    private String json(PlanBackup plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write the plan of a backup", e);
        }
    }

    private PlanBackup readPlan(String json) {
        try {
            return objectMapper.readValue(json, PlanBackup.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read the plan of a backup", e);
        }
    }
}
