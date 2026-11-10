package dev.sylvain.planning.service.solve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.GelReferentielService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferentialFamily;
import dev.sylvain.planning.service.scenario.ScenarioImportService;
import dev.sylvain.planning.service.solve.WipeBackupService.PlanRestoration;
import dev.sylvain.planning.service.solve.WipeBackupService.Trigger;
import dev.sylvain.planning.service.solve.WipeBackupService.WipeBackup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The backup taken before an edition is emptied (ADR 0084): it is there after
 * the wipe, it brings the edition back, it is bounded, and without it there is
 * no wipe. Each test works in an edition of its own, deleted afterwards.
 */
@QuarkusTest
class WipeBackupServiceTest {

    private static final String SCENARIO = "scenario.yml";

    private static final String AUTRE_SCENARIO = "gamme-03-1j-3stands-5animateurs-competences-souhaits.yaml";

    @Inject
    SolverJobService solverJobs;

    @Inject
    GelReferentielService gel;

    @Inject
    WipeBackupService wipeBackup;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ScenarioImportService scenarioImportService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Inject
    DataSource dataSource;

    @Inject
    PlanningService planningService;

    @Test
    void resettingAnEditionSavesAScenarioWhoseImportBringsItBack() {
        inANewEdition(() -> {
            // Imported into an empty edition: nothing to save yet, and no failure for it.
            scenarioImportService.importBundled("scenario.yml");
            assertThat(wipeBackup.list()).isEmpty();
            List<String> stands = standNames();
            List<String> animateurs = animateurNames();
            int creneaux = referenceDataService.listCreneaux().size();
            assertThat(stands).isNotEmpty();

            persistenceService.clearDatabase();

            assertThat(referenceDataService.listStands()).isEmpty();
            List<WipeBackup> backups = wipeBackup.list();
            assertThat(backups).singleElement().extracting(WipeBackup::origine).isEqualTo(Trigger.RESET);

            scenarioImportService.importYaml(
                    wipeBackup.loadContent(backups.get(0).id()));

            assertThat(standNames()).isEqualTo(stands);
            assertThat(animateurNames()).isEqualTo(animateurs);
            assertThat(referenceDataService.listCreneaux()).hasSize(creneaux);
        });
    }

    /**
     * The whole way back: a plan with its locks, a reset, the scenario
     * re-imported under new ids, the plan restored on it — the same seats and
     * the same locks, compared on what does not move.
     */
    @Test
    void aResetPlanComesBackWithItsLocksAfterTheScenarioIsReimported() {
        inANewEdition(() -> {
            scenarioImportService.importBundled("scenario.yml");
            seatEveryoneAndLock();
            List<String> plan = plan();
            List<String> verrous = locks();

            persistenceService.clearDatabase();
            long backup = wipeBackup.list().get(0).id();
            scenarioImportService.importYaml(wipeBackup.loadContent(backup));
            PlanRestoration restoration = wipeBackup.restorePlan(backup);

            assertThat(restoration.affectationsNonRestaurees()).isEmpty();
            assertThat(restoration.verrouillagesNonRestaures()).isEmpty();
            assertThat(restoration.verrouillagesRestaures()).isEqualTo(verrous.size());
            assertThat(restoration.affectationsRestaurees())
                    .isEqualTo(plan.stream().filter(seat -> !seat.endsWith("|")).count());
            assertThat(plan()).isEqualTo(plan);
            assertThat(locks()).isEqualTo(verrous);
        });
    }

    /** What cannot be found again is listed, and the rest comes back regardless. */
    @Test
    void aPlanWhoseAnimateurIsGoneComesBackWithoutThem() {
        inANewEdition(() -> {
            scenarioImportService.importBundled("scenario.yml");
            Animateur parti = seatEveryoneAndLock();
            String nom = parti.getPrenom() + " " + parti.getNom();
            List<String> attendu = plan().stream()
                    .map(seat -> seat.endsWith("|" + nom) ? seat.substring(0, seat.length() - nom.length()) : seat)
                    .toList();

            persistenceService.clearDatabase();
            long backup = wipeBackup.list().get(0).id();
            scenarioImportService.importYaml(wipeBackup.loadContent(backup));
            referenceDataService.listAnimateurs().stream()
                    .filter(animateur -> (animateur.getPrenom() + " " + animateur.getNom()).equals(nom))
                    .forEach(animateur -> referenceDataService.deleteAnimateur(animateur.getId()));
            PlanRestoration restoration = wipeBackup.restorePlan(backup);

            assertThat(restoration.affectationsNonRestaurees())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne).contains("animateur " + parti.getId() + " introuvable"));
            assertThat(restoration.verrouillagesNonRestaures())
                    .containsExactly("Verrou ANIMATEUR : animateur " + parti.getId() + " introuvable");
            assertThat(plan()).isEqualTo(attendu);
        });
    }

    /** Two scenarios imported in turn: each import backs the other one up, and only the last five stay. */
    @Test
    void onlyTheMostRecentBackupsOfAnEditionAreKept() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            for (int i = 0; i <= WipeBackupService.KEPT_PER_EDITION; i++) {
                scenarioImportService.importBundled(i % 2 == 0 ? AUTRE_SCENARIO : SCENARIO);
            }
            List<WipeBackup> backups = wipeBackup.list();
            assertThat(backups).hasSize(WipeBackupService.KEPT_PER_EDITION);
            long oldest = backups.getLast().id();

            scenarioImportService.importBundled(AUTRE_SCENARIO);

            assertThat(wipeBackup.list())
                    .hasSize(WipeBackupService.KEPT_PER_EDITION)
                    .extracting(WipeBackup::id)
                    .doesNotContain(oldest)
                    .first()
                    .isNotIn(backups.stream().map(WipeBackup::id).toList());
        });
    }

    /** A retried gesture over the same state stores it once, so retries cannot push the useful backup out. */
    @Test
    void theSameStateIsBackedUpOnce() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            wipeBackup.backUpBeforeImport();
            wipeBackup.backUpBeforeImport();

            assertThat(wipeBackup.list()).hasSize(1);
        });
    }

    /**
     * An import refused after its backup — here by the database, on its first
     * stand write — evicts no earlier backup, and a retry stores nothing more.
     */
    @Test
    void anImportRefusedAfterItsBackupEvictsNothing() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            for (int i = 0; i < WipeBackupService.KEPT_PER_EDITION; i++) {
                scenarioImportService.importBundled(i % 2 == 0 ? AUTRE_SCENARIO : SCENARIO);
            }
            List<Long> avant = ids();
            try {
                sql("ALTER TABLE stand ADD CONSTRAINT test_refus CHECK (false) NOT VALID");
                assertThatThrownBy(() -> scenarioImportService.importBundled(AUTRE_SCENARIO))
                        .isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> scenarioImportService.importBundled(AUTRE_SCENARIO))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                sql("ALTER TABLE stand DROP CONSTRAINT test_refus");
            }

            assertThat(ids()).containsAll(avant).hasSize(avant.size() + 1);
        });
    }

    /** An imported team without its grid yet is still backed up, and comes back. */
    @Test
    void aHalfBuiltEditionIsBackedUpBeforeItsReset() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            referenceDataService.deleteCreneaux(referenceDataService.listCreneaux().stream()
                    .map(Creneau::getId)
                    .toList());
            List<String> animateurs = animateurNames();

            persistenceService.clearDatabase();
            assertThat(referenceDataService.listAnimateurs()).isEmpty();
            scenarioImportService.importYaml(
                    wipeBackup.loadContent(wipeBackup.list().getFirst().id()));

            assertThat(animateurNames()).isEqualTo(animateurs);
        });
    }

    /** A backup of one edition is invisible from another: not listed, not readable, not restorable. */
    @Test
    void aBackupIsReachableFromItsOwnEditionOnly() {
        String autre = editionService
                .create(new Edition(null, "Édition voisine", false, null))
                .getId();
        try {
            inANewEdition(() -> {
                scenarioImportService.importBundled(SCENARIO);
                persistenceService.clearDatabase();
                long id = wipeBackup.list().getFirst().id();
                editionContext.executeIn(autre, () -> {
                    assertThat(wipeBackup.list()).isEmpty();
                    assertThatThrownBy(() -> wipeBackup.loadContent(id)).isInstanceOf(BusinessError.NotFound.class);
                    assertThatThrownBy(() -> wipeBackup.restorePlan(id)).isInstanceOf(BusinessError.NotFound.class);
                });
                given().header("X-Edition-Id", autre)
                        .get("/api/planning/sauvegardes-avant-vidage/" + id)
                        .then()
                        .statusCode(404);
                given().header("X-Edition-Id", autre)
                        .post("/api/planning/sauvegardes-avant-vidage/" + id + "/planning")
                        .then()
                        .statusCode(404);
            });
        } finally {
            editionService.delete(autre);
        }
    }

    /**
     * Two namesakes in the backup, one of them gone after the re-import: the
     * name alone would hand their seats to the other one, so they come back
     * empty — and the other one keeps exactly theirs.
     */
    @Test
    void aNamesakeNeverInheritsTheSeatsOfTheOther() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            Animateur un = homonyme("un@exemple.org");
            Animateur deux = homonyme("deux@exemple.org");
            seatEveryoneAndLock();
            List<PosteAffectation> postes =
                    persistenceService.loadPersistedPlanning().getPostes();
            persistenceService.reaffecterPoste(postes.get(0).getId(), un.getId());
            persistenceService.reaffecterPoste(postes.get(1).getId(), deux.getId());
            long seatsOfDeux = seatsOf("deux@exemple.org");
            assertThat(seatsOfDeux).isPositive();

            persistenceService.clearDatabase();
            long backup = wipeBackup.list().getFirst().id();
            scenarioImportService.importYaml(wipeBackup.loadContent(backup));
            referenceDataService.listAnimateurs().stream()
                    .filter(animateur -> "un@exemple.org".equals(animateur.getEmail()))
                    .forEach(animateur -> referenceDataService.deleteAnimateur(animateur.getId()));
            PlanRestoration restoration = wipeBackup.restorePlan(backup);

            assertThat(restoration.affectationsNonRestaurees())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne).contains("animateur " + un.getId() + " introuvable"));
            assertThat(seatsOf("deux@exemple.org")).isEqualTo(seatsOfDeux);
            assertThat(deux.getId()).isNotEqualTo(un.getId());
        });
    }

    /** Restoring twice writes the locks once, and says so. */
    @Test
    void aSecondRestoreCountsItsLocksAsAlreadyThere() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            seatEveryoneAndLock();
            int verrous = locks().size();
            persistenceService.clearDatabase();
            long backup = wipeBackup.list().getFirst().id();
            scenarioImportService.importYaml(wipeBackup.loadContent(backup));
            assertThat(wipeBackup.restorePlan(backup).verrouillagesRestaures()).isEqualTo(verrous);

            PlanRestoration second = wipeBackup.restorePlan(backup);

            assertThat(second.verrouillagesRestaures()).isZero();
            assertThat(second.verrouillagesDejaPresents()).isEqualTo(verrous);
            assertThat(locks()).hasSize(verrous);
        });
    }

    /** A frozen edition refuses the re-import, and the refusal writes no backup. */
    @Test
    void aFrozenEditionRefusesTheReimportWithoutABackup() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            persistenceService.clearDatabase();
            scenarioImportService.importYaml(
                    wipeBackup.loadContent(wipeBackup.list().getFirst().id()));
            List<Long> avant = ids();
            gel.freeze(ReferentialFamily.STANDS);
            try {
                assertThatThrownBy(() -> scenarioImportService.importBundled(AUTRE_SCENARIO))
                        .isInstanceOf(BusinessError.class);
                assertThatThrownBy(persistenceService::clearDatabase).isInstanceOf(BusinessError.class);
            } finally {
                gel.lift(ReferentialFamily.STANDS);
            }
            assertThat(ids()).isEqualTo(avant);
        });
    }

    /**
     * While a solve holds the edition, the import and the plan restore are
     * refused before anything is written: five refused retries leave the
     * backups as they were.
     */
    @Test
    void aSolveRefusesTheImportAndTheRestoreBeforeAnyBackup() {
        inANewEdition(() -> {
            scenarioImportService.importBundled(SCENARIO);
            scenarioImportService.importBundled(AUTRE_SCENARIO);
            List<Long> avant = ids();
            String job = solverJobs
                    .submitSolve(planningService.buildFromReferenceData(), 60L)
                    .getId();
            try {
                await().pollInSameThread()
                        .atMost(Duration.ofSeconds(30))
                        .until(() -> solverJobs.findActive().isPresent());
                for (int i = 0; i < WipeBackupService.KEPT_PER_EDITION; i++) {
                    assertThatThrownBy(() -> scenarioImportService.importBundled(SCENARIO))
                            .isInstanceOf(SolverJobService.SolverBusyException.class);
                }
                assertThatThrownBy(() -> wipeBackup.restorePlan(avant.getFirst()))
                        .isInstanceOf(SolverJobService.SolverBusyException.class);
            } finally {
                solverJobs.cancel(job);
                await().pollInSameThread()
                        .atMost(Duration.ofSeconds(120))
                        .until(() -> solverJobs.findActive().isEmpty());
            }
            assertThat(ids()).isEqualTo(avant);
        });
    }

    @Test
    void aBackupThatCannotBeWrittenStopsTheWipe() {
        inANewEdition(() -> {
            scenarioImportService.importBundled("scenario.yml");
            List<String> stands = standNames();
            try {
                // Every new row refused, the rows already there left alone.
                sql("ALTER TABLE sauvegarde_avant_vidage ADD CONSTRAINT test_refus CHECK (false) NOT VALID");

                assertThatThrownBy(persistenceService::clearDatabase).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> scenarioImportService.importBundled("scenario.yml"))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                sql("ALTER TABLE sauvegarde_avant_vidage DROP CONSTRAINT test_refus");
            }

            assertThat(standNames()).isEqualTo(stands);
            assertThat(wipeBackup.list()).isEmpty();
        });
    }

    /**
     * Seats the edition's animateurs round-robin on every seat, persists it,
     * and locks one of each kind; returns the animateur the animateur lock
     * freezes, who holds seats.
     */
    private Animateur seatEveryoneAndLock() {
        PlanningEvenement problem = planningService.buildFromReferenceData();
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        List<PosteAffectation> postes = problem.getPostes();
        for (int i = 0; i < postes.size(); i++) {
            postes.get(i).setAnimateur(animateurs.get(i % animateurs.size()));
        }
        persistenceService.persist(problem);
        Animateur verrouille = animateurs.get(0);
        Stand stand = referenceDataService.listStands().get(0);
        Creneau creneau = referenceDataService.listCreneaux().get(0);
        lock(TypeVerrouillage.ANIMATEUR, v -> v.setAnimateurId(verrouille.getId()));
        lock(TypeVerrouillage.STAND, v -> v.setStandId(stand.getId()));
        lock(TypeVerrouillage.CRENEAU, v -> v.setCreneauId(creneau.getId()));
        lock(TypeVerrouillage.JOUR, v -> v.setJour(creneau.getDate()));
        return verrouille;
    }

    private void lock(TypeVerrouillage type, java.util.function.Consumer<VerrouillagePlanning> target) {
        VerrouillagePlanning verrou = new VerrouillagePlanning(null, type);
        target.accept(verrou);
        referenceDataService.createVerrouillage(verrou);
    }

    /** The persisted plan on its natural keys: stand, vacation, effective hours, animateur. */
    private List<String> plan() {
        return persistenceService.loadPersistedPlanning().getPostes().stream()
                .map(poste ->
                        poste.getStand().getNom() + "|" + poste.getCreneau().getDate() + " "
                                + poste.getCreneau().getHeureDebut() + "-"
                                + poste.getCreneau().getHeureFin() + "|"
                                + poste.getHeureDebutEffective() + "-" + poste.getHeureFinEffective() + "|"
                                + (poste.getAnimateur() == null
                                        ? ""
                                        : poste.getAnimateur().getPrenom() + " "
                                                + poste.getAnimateur().getNom()))
                .sorted()
                .toList();
    }

    /** The edition's locks on their natural keys. */
    private List<String> locks() {
        List<Stand> stands = referenceDataService.listStands();
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        return referenceDataService.listVerrouillages().stream()
                .map(v -> v.getType() + "|"
                        + animateurs.stream()
                                .filter(a -> a.getId().equals(v.getAnimateurId()))
                                .map(a -> a.getPrenom() + " " + a.getNom())
                                .findFirst()
                                .orElse("")
                        + "|"
                        + stands.stream()
                                .filter(st -> st.getId().equals(v.getStandId()))
                                .map(Stand::getNom)
                                .findFirst()
                                .orElse("")
                        + "|" + v.getCreneauDate() + " " + v.getCreneauHeureDebut() + "-" + v.getCreneauHeureFin()
                        + "|" + v.getJour())
                .sorted()
                .toList();
    }

    private List<Long> ids() {
        return wipeBackup.list().stream().map(WipeBackup::id).toList();
    }

    /** A Jean Dupont of the given address, the same name as every other one. */
    private Animateur homonyme(String email) {
        Animateur animateur = new Animateur(null, "Jean", "Dupont", LocalDate.of(1990, 1, 1), false);
        animateur.setEmail(email);
        return referenceDataService.createAnimateur(animateur);
    }

    /** How many persisted seats the fiche of that address holds. */
    private long seatsOf(String email) {
        String id = referenceDataService.listAnimateurs().stream()
                .filter(animateur -> email.equals(animateur.getEmail()))
                .map(Animateur::getId)
                .findFirst()
                .orElseThrow();
        return persistenceService.loadPersistedPlanning().getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && id.equals(poste.getAnimateur().getId()))
                .count();
    }

    private void inANewEdition(Runnable test) {
        String edition = editionService
                .create(new Edition(null, "Édition sauvegarde", false, null))
                .getId();
        try {
            editionContext.executeIn(edition, test);
        } finally {
            editionService.delete(edition);
        }
    }

    private List<String> standNames() {
        return referenceDataService.listStands().stream()
                .map(Stand::getNom)
                .sorted()
                .toList();
    }

    private List<String> animateurNames() {
        return referenceDataService.listAnimateurs().stream()
                .map(animateur -> animateur.getPrenom() + " " + animateur.getNom())
                .sorted()
                .toList();
    }

    private void sql(String statement) {
        try (Connection connection = dataSource.getConnection();
                Statement st = connection.createStatement()) {
            st.execute(statement);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
