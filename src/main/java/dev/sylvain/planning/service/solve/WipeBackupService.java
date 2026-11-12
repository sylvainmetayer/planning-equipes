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
import dev.sylvain.planning.service.referentiel.GelReferentielService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.scenario.ScenarioImportService;
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
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The safety net under the two gestures that empty an edition — the reset and
 * the import of a scenario (ADR 0084): right before either writes, the
 * edition's own scenario export and its plan (seats and locks) are stored in
 * {@code sauvegarde_avant_vidage}, and {@link #restore} puts one back in a
 * single gesture. The nightly dump could only do so by rolling every other
 * edition back with it.
 *
 * <p>A backup that cannot be written <b>stops</b> the wipe — the opposite of
 * the automatique snapshot, which never fails a solve: a solve destroys nothing
 * a snapshot alone could bring back, a wipe destroys everything. Only an
 * edition holding nothing a wipe or an import would overwrite gets none.</p>
 *
 * <p>Nothing here deletes a backup on its own: they all stay until an admin
 * deletes one ({@link #delete}) or their edition goes, by cascade. An
 * automatic purge was tried, and every one of its sparing rules let a gesture
 * evict the very backup it needed (ADR 0084).</p>
 */
@ApplicationScoped
public class WipeBackupService {

    /** What emptied the edition, as stored in {@code origine}. */
    public enum Trigger {
        RESET,
        IMPORT,
        /** The state a restore replaced: restoring this one undoes the restore. */
        RESTORE
    }

    /**
     * One backup, without its content.
     *
     * @param creeLe              when the backup was taken, to the second
     * @param restaureId          for a {@link Trigger#RESTORE}, the backup that restore put back; {@code null} once
     *                            that backup was deleted
     * @param restaurationAboutie for a {@link Trigger#RESTORE}, the outcome recorded: {@code true} when it went
     *                            through, {@code false} when it did not or its outcome could not be recorded —
     *                            this backup is then the state right before that attempt
     */
    public record WipeBackup(long id, Trigger origine, Instant creeLe, Long restaureId, Boolean restaurationAboutie) {}

    /**
     * What {@link #restore} put back, and what it could not, one sentence each,
     * naming ids only. Every seat counted was written; a lock whose target was
     * already frozen is counted apart, since nothing was written.
     *
     * @param sauvegardeAvant  the backup holding the edition as it was right before this restore — restoring it
     *                         undoes this one; {@code null} when the edition held nothing
     * @param referentielNonRestaure the stands and timeslots no seat tied to the rest that could not be created again
     * @param score            the score of the restored plan, locks included, {@code null} when it could not be
     *                         analysed
     */
    public record Restoration(
            Long sauvegardeAvant,
            int affectationsRestaurees,
            List<String> affectationsNonRestaurees,
            int verrouillagesRestaures,
            int verrouillagesDejaPresents,
            List<String> verrouillagesNonRestaures,
            List<String> referentielNonRestaure,
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

    /**
     * Besides what a reset empties, what an import overwrites and the scenario
     * carries: an edition holding only its parameters, weights, day
     * templates, game categories or locations still has something to lose.
     */
    private static final List<String> TABLES_ECRASEES = List.of(
            "parametres_legaux",
            "parametres_qualite",
            "parametres_solveur",
            "constraint_toggle",
            "ponderation_contrainte",
            "journee_type",
            // Upserted by an import (a label, a code rewritten), never by a reset.
            "typologie",
            "emplacement");

    private final JdbcEditionScope scope;

    private final PlanningService planningService;

    private final PlanSnapshotService snapshots;

    private final ReferenceDataService referenceDataService;

    private final ConstraintAnalysisStore analysisStore;

    private final ObjectMapper objectMapper;

    private final ScenarioImportService scenarioImportService;

    private final GelReferentielService gel;

    @Inject
    public WipeBackupService(
            JdbcEditionScope scope,
            PlanningService planningService,
            PlanSnapshotService snapshots,
            ReferenceDataService referenceDataService,
            ConstraintAnalysisStore analysisStore,
            ObjectMapper objectMapper,
            ScenarioImportService scenarioImportService,
            GelReferentielService gel) {
        this.scope = scope;
        this.planningService = planningService;
        this.snapshots = snapshots;
        this.referenceDataService = referenceDataService;
        this.analysisStore = analysisStore;
        this.objectMapper = objectMapper;
        this.scenarioImportService = scenarioImportService;
        this.gel = gel;
    }

    /**
     * Backs the current edition up before an import, in a transaction of its
     * own since the import spans several. Refused while a solve holds the
     * edition, like the import it precedes: the interceptor runs in the
     * edition the file targets, so a refused import writes no backup. What
     * can still refuse it later leaves this backup behind.
     */
    @RefusedWhileSolving
    public void backUpBeforeImport() {
        scope.write("Failed to back the edition up before an import", connection -> insert(connection, Trigger.IMPORT));
    }

    /** Backs the current edition up inside the caller's transaction, so the wipe and its backup land together. */
    void backUp(Connection connection, Trigger trigger) throws SQLException {
        insert(connection, trigger);
    }

    /**
     * Writes the backup, unless the edition holds nothing a wipe or an import
     * would destroy or its latest backup is already this very state — a
     * retried gesture stores it once. An edition half
     * built (an imported team, no grid yet) is backed up all the same: the
     * scenario format reads an empty section back.
     *
     * @return the backup now holding the current state — the one written, or
     *         the latest one when it already did; empty for an empty edition
     */
    private Optional<Long> insert(Connection connection, Trigger trigger) throws SQLException {
        return insert(connection, trigger, null);
    }

    /**
     * {@link #insert(Connection, Trigger)}, for a restore: the row it writes
     * names the backup restored, and starts as not gone through. Never reused
     * for anything but information — the list, the error of a failed restore.
     */
    private Optional<Long> insert(Connection connection, Trigger trigger, Long restaureId) throws SQLException {
        if (!holdsAnything(connection)) {
            return Optional.empty();
        }
        String yaml = planningService.scenarioYaml();
        String plan = json(new PlanBackup(snapshots.loadPersistedAffectations(), locks()));
        try (PreparedStatement ps = scope.prepareScoped(connection, """
 SELECT s.id FROM sauvegarde_avant_vidage s
 WHERE s.edition_id = ? AND s.contenu = ? AND s.plan = ?::jsonb
 AND s.id = (SELECT d.id FROM sauvegarde_avant_vidage d WHERE d.edition_id = s.edition_id
 ORDER BY d.cree_le DESC, d.id DESC LIMIT 1)""")) {
            ps.setString(2, yaml);
            ps.setString(3, plan);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong(1));
                }
            }
        }
        try (PreparedStatement ps = scope.prepareScoped(connection, """
 INSERT INTO sauvegarde_avant_vidage (edition_id, origine, contenu, plan, restaure_id, restauration_aboutie)
 VALUES (?, ?, ?, ?::jsonb, ?, ?)
 RETURNING id""")) {
            ps.setString(2, trigger.name());
            ps.setString(3, yaml);
            ps.setString(4, plan);
            ps.setObject(5, restaureId, java.sql.Types.BIGINT);
            ps.setObject(6, restaureId == null ? null : Boolean.FALSE, java.sql.Types.BOOLEAN);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.of(rs.getLong(1));
            }
        }
    }

    /** Whether any table a wipe empties or an import overwrites holds a row of the current edition. */
    private boolean holdsAnything(Connection connection) throws SQLException {
        for (List<String> tables : List.of(PlanningPersistenceService.TABLES_A_VIDER, TABLES_ECRASEES)) {
            for (String table : tables) {
                // Table names come from those literal lists, never from user input.
                try (PreparedStatement ps = scope.prepareScoped(
                                connection, "SELECT 1 FROM " + table + " WHERE edition_id = ? LIMIT 1");
                        ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<LockBackup> locks() {
        return referenceDataService.listVerrouillages().stream()
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
    }

    /**
     * Deletes one backup of the current edition — the admin's housekeeping,
     * and the way to erase a person from the backups that still carry them.
     */
    public void delete(long id) {
        int supprimees = scope.writeAndReturn("Failed to delete an edition's backup", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM sauvegarde_avant_vidage WHERE edition_id = ? AND id = ?")) {
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        });
        if (supprimees == 0) {
            throw new BusinessError.NotFound("Sauvegarde " + id + " introuvable dans cette édition.");
        }
    }

    /** The current edition's backups, most recent first. */
    public List<WipeBackup> list() {
        return scope.read("Failed to list the edition's backups", connection -> {
            List<WipeBackup> backups = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
 SELECT id, origine, cree_le, restaure_id, restauration_aboutie FROM sauvegarde_avant_vidage
 WHERE edition_id = ? ORDER BY cree_le DESC, id DESC""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    backups.add(new WipeBackup(
                            rs.getLong("id"),
                            Trigger.valueOf(rs.getString("origine")),
                            rs.getTimestamp("cree_le").toInstant(),
                            rs.getObject("restaure_id", Long.class),
                            rs.getObject("restauration_aboutie", Boolean.class)));
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
     * Puts a backup back, in one gesture (ADR 0084). In this order:
     *
     * <ol>
     *   <li>the refusals, before anything is written: a solve holding the
     *       edition (the interceptor), a frozen family, a backup this edition
     *       does not hold, a file the import would refuse — its stands and ad
     *       hoc exceptions included;</li>
     *   <li>the current state is backed up ({@link Trigger#RESTORE}), always
     *       — unless the edition holds nothing, or its latest backup already is
     *       this very state, which is then the one named; restoring it undoes
     *       this restore;</li>
     *   <li>the scenario is imported by the import itself, which also brings
     *       back the stands and timeslots no seat ties to the rest;</li>
     *   <li>the plan is put back on it, found by natural keys
     *       ({@link #match}); refused if a plan with seats finds none;</li>
     *   <li>the locks, through the usual lock write; the backup of step 2 is
     *       then marked as belonging to a restore that went through — for the
     *       list and the error message only: the mark decides nothing.</li>
     * </ol>
     *
     * <p><b>Not atomic</b>: the repository has no transaction spanning several
     * services (ADR 0084, after the choice of A5). A failure after step 2
     * leaves the edition half restored and says at which step, with the id of
     * the backup of step 2, the state right before this attempt, and nothing
     * else: which earlier backup was the state before a whole series of
     * attempts is the reader's choice, from the list — the table cannot tell
     * it for certain (a gesture that writes no backup leaves no trace in it).
     * Restoring that backup puts the edition back as it was right before this
     * attempt, the technical ids apart. The restored referential gets new ids too: what
     * named the old ones outside the backup (snapshots, day reviews, swap
     * requests, the journal) no longer finds them.</p>
     *
     * <p>Nothing here refuses a seat for breaking a hard rule — no seat write
     * of the application does; the score of the restored plan says whether
     * one does, and the lock warnings name the frozen seats concerned.</p>
     */
    @RefusedWhileSolving
    public Restoration restore(long id) {
        String yaml = loadContent(id);
        PlanBackup plan = readPlan(column(id, SELECT_PLAN));
        referenceDataService.checkImportable(ScenarioYamlReader.fromDto(
                        ScenarioYamlReader.parseScenarioText(yaml), referenceDataService::getParametresLegaux)
                .planning());
        gel.refuseIfFrozen();

        Long avant = scope.writeAndReturn(
                "Failed to back the edition up before a restore",
                connection -> insert(connection, Trigger.RESTORE, id).orElse(null));
        String etape = "import du scénario";
        Restoration restoration;
        try {
            List<String> referentielNonRestaure = scenarioImportService.importForRestore(yaml);
            etape = "réapplication du planning";
            ScenarioDto scenario = ScenarioYamlReader.parseScenarioText(yaml);
            restoration = reapply(id, plan, scenario, avant, referentielNonRestaure);
        } catch (RuntimeException e) {
            String message = "Restauration de la sauvegarde " + id + " interrompue à l'étape « " + etape + " » : "
                    + e.getMessage() + " " + recourse(avant);
            if (e instanceof BusinessError) {
                throw new BusinessError.Conflict(message);
            }
            throw new IllegalStateException(message, e);
        }
        // The restore is complete: failing to note it is a warning, never a failed restore.
        if (avant != null) {
            try {
                scope.write("Failed to mark a restore as gone through", connection -> {
                    try (PreparedStatement ps = scope.prepareScoped(connection, """
 UPDATE sauvegarde_avant_vidage SET restauration_aboutie = TRUE
 WHERE edition_id = ? AND id = ? AND origine = 'RESTORE' AND restaure_id = ?""")) {
                        ps.setLong(2, avant);
                        ps.setLong(3, id);
                        ps.executeUpdate();
                    }
                });
            } catch (RuntimeException e) {
                restoration
                        .avertissements()
                        .add("La restauration a abouti, mais son issue n'a pas pu être notée "
                                + "dans la liste des sauvegardes : la sauvegarde " + avant
                                + " y reste marquée « non aboutie ».");
            }
        }
        return restoration;
    }

    /** What a failed restore says to go back: the backup of the state right before it, and the list. */
    private static String recourse(Long avant) {
        String liste = " Pour un état plus ancien, choisissez dans la liste des sauvegardes de l'édition : chacune y"
                + " dit sa date, son origine et, pour une restauration, la sauvegarde visée et son issue.";
        if (avant == null) {
            return "L'édition était vide juste avant cette tentative." + liste;
        }
        return "L'état de l'édition juste avant cette tentative est dans la sauvegarde " + avant
                + " : la restaurer remet l'édition comme elle était à ce moment-là." + liste;
    }

    private Restoration reapply(
            long id, PlanBackup plan, ScenarioDto scenario, Long avant, List<String> referentielNonRestaure) {
        List<Stand> standsActuels = referenceDataService.listStands();
        List<Animateur> animateursActuels = referenceDataService.listAnimateurs();
        Map<String, String> stands = matchStands(scenario.stands(), standsActuels);
        Map<String, String> animateurs = matchAnimateurs(scenario.animateurs(), animateursActuels);
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
        if (!plan.affectations().isEmpty() && restaurees.isEmpty()) {
            throw new BusinessError.Conflict("Aucun des " + plan.affectations().size()
                    + " sièges de la sauvegarde ne correspond au référentiel restauré.");
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
        // Read once the locks are in: an animateur lock weighs in the score.
        try {
            analysisStore.refreshFromPersistedPlan();
        } catch (RuntimeException _) {
            // A missing analysis is a missing score, not a failed restore.
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        return new Restoration(
                avant,
                assises,
                nonRestaurees,
                verrous,
                dejaPresents,
                verrousNonRestaures,
                referentielNonRestaure,
                avertissements,
                analysis == null ? null : analysis.diagnostic().score());
    }

    /** Each stand of the backup → the stand of the edition it is now: by code, else by name. */
    static Map<String, String> matchStands(List<StandDto> backup, List<Stand> edition) {
        return match(
                backup,
                edition,
                StandDto::id,
                Stand::getId,
                new Key<>(StandDto::code, Stand::getCode, WipeBackupService::trimmed, null),
                new Key<>(StandDto::nom, Stand::getNom, WipeBackupService::normalise, null));
    }

    /**
     * Each animateur of the backup → the fiche of the edition they are now: by
     * e-mail, else by first and last name — never a namesake whose e-mail is
     * another one.
     */
    static Map<String, String> matchAnimateurs(List<AnimateurDto> backup, List<Animateur> edition) {
        return match(
                backup,
                edition,
                AnimateurDto::id,
                Animateur::getId,
                new Key<>(AnimateurDto::email, Animateur::getEmail, WipeBackupService::normalise, null),
                new Key<>(
                        dto -> fullName(dto.prenom(), dto.nom()),
                        animateur -> fullName(animateur.getPrenom(), animateur.getNom()),
                        WipeBackupService::normalise,
                        WipeBackupService::samePersonByEmail));
    }

    /**
     * One key a backup row and an edition row are compared on — e-mail, name,
     * code — with the normalisation the import applies to it, and an optional
     * test a candidate must pass on top of the key.
     */
    private record Key<T, R>(
            Function<T, String> backup,
            Function<R, String> edition,
            UnaryOperator<String> normalise,
            BiPredicate<T, R> compatible) {}

    /**
     * Each id of the backup → the row of the edition it designates now, under
     * a prudent and injective rule. The keys are tried in order, and a key
     * designates only when it names exactly one row of the backup and exactly
     * one compatible row of the edition; otherwise — nothing, or an ambiguity
     * such as a family's shared e-mail — the next key is tried. The last one,
     * the name, is the weakest: ambiguous on either side it designates
     * nothing, so two namesakes never inherit each other's seats, and the
     * {@code compatible} test refuses a namesake whose e-mail is another one.
     * A row two backup ids designate goes to neither. Missing from the map:
     * not found.
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
                String value = key.normalise().apply(key.backup().apply(row));
                if (value == null) {
                    continue;
                }
                long inBackup = backup.stream()
                        .filter(other ->
                                value.equals(key.normalise().apply(key.backup().apply(other))))
                        .count();
                List<R> inEdition = edition.stream()
                        .filter(candidate ->
                                value.equals(key.normalise().apply(key.edition().apply(candidate))))
                        .filter(candidate ->
                                key.compatible() == null || key.compatible().test(row, candidate))
                        .toList();
                if (inBackup == 1 && inEdition.size() == 1) {
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

    /**
     * The import's {@code samePerson}: a namesake is the same person unless
     * both carry an e-mail and the two differ.
     */
    private static boolean samePersonByEmail(AnimateurDto dto, Animateur animateur) {
        String fichier = normalise(dto.email());
        String base = normalise(animateur.getEmail());
        return fichier == null || base == null || fichier.equals(base);
    }

    /** The import's normalisation of e-mails and names ({@code ScenarioIdRemap}): trimmed, case ignored. */
    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** First and last name, each normalised as the import compares them. */
    private static String fullName(String prenom, String nom) {
        String p = normalise(prenom);
        String n = normalise(nom);
        return p == null || n == null ? null : p + "|" + n;
    }

    /** The import's normalisation of codes: trimmed, compared as written. */
    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
