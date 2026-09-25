package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Freshness of snapshots against the referential (issue #170).
 *
 * <p>The trap this closes: <i>Restaurer</i> put a plan back with no word about
 * its age, so a capture computed before a stand was added came back in place
 * looking like a result. The marker deciding it lives in
 * {@link ReferenceDataChangeTracker} and is read by
 * {@link PlanSnapshotService}, which is where these tests aim — the REST
 * resource and the MCP tool share that guard rather than each holding one.</p>
 */
@QuarkusTest
class PlanSnapshotFraicheurTest {

    private static final LocalDate JOUR = LocalDate.of(2030, 9, 14);
    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String editionVoisine;
    /** A stand must name at least one typologie, so the fixture shares one — by its code. */
    private static final String TYPOLOGIE = "FRA-T";

    /**
     * Label of a fixture row → the id the application drew for it (ADR 0050).
     * The labels keep the tests readable; the ids are what the services take.
     */
    private final Map<String, String> ids = new HashMap<>();

    /** Labels this class captures under, and the only ones its cleanup may delete. */
    private static final Set<String> LIBELLES = Set.of(
            "Après la dernière écriture",
            "Avant de renommer le stand",
            "Plan à remettre",
            "Plan à forcer",
            "Plan orphelin",
            "Instantané de l'édition courante");

    @Inject
    PlanSnapshotService snapshots;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    DataSource dataSource;

    /**
     * The nominal case, and the one a badge saying "à jour" has to earn: a
     * capture taken after the last write of the referential describes today's
     * data, and says so.
     */
    @Test
    void aSnapshotTakenAfterTheLastWriteIsFresh() {
        try {
            fixture("FRA-S1", "FRA-A1", 9701L, "FRA-P1");

            PlanSnapshotService.SnapshotMeta meta = snapshots.capture("Après la dernière écriture", false);

            assertThat(meta.perime()).isFalse();
            assertThat(meta.referenceModifieLe()).isNotNull().isBefore(meta.creeLe());
            assertThat(snapshots.list())
                    .filteredOn(snapshot -> snapshot.id() == meta.id())
                    .singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.perime()).isFalse());
        } finally {
            nettoyer("FRA-S1", "FRA-A1", 9701L);
        }
    }

    /**
     * A real referential write — not a hand-set timestamp — is what must move
     * the badge: the whole mechanism hangs on the services already calling
     * {@code markModified}, and a test setting the marker itself would pass
     * even if they stopped.
     */
    @Test
    void editingAStandMakesEarlierSnapshotsStale() {
        try {
            fixture("FRA-S2", "FRA-A2", 9702L, "FRA-P2");
            long id = snapshots.capture("Avant de renommer le stand", false).id();

            referenceData.writeStand(
                    ids.get("FRA-S2"), new Stand(ids.get("FRA-S2"), "Stand renommé", Set.of(TYPOLOGIE), 1, 1, false));

            assertThat(snapshots.load(id).meta().perime()).isTrue();
        } finally {
            nettoyer("FRA-S2", "FRA-A2", 9702L);
        }
    }

    /**
     * The refusal itself: nothing is written, and the answer carries the two
     * dates rather than a bare no — "modifié après la capture" is the sentence
     * the screen and the MCP tool both have to be able to say.
     */
    @Test
    void restoringAStaleSnapshotIsRefusedAndWritesNothing() {
        try {
            fixture("FRA-S3", "FRA-A3", 9703L, "FRA-P3");
            long id = snapshots.capture("Plan à remettre", false).id();
            // The plan on screen differs from the snapshot, so a restore that
            // went through would be visible in the seat count.
            persistence.persist(new PlanningEvenement(JOUR, List.of(animateur(ids.get("FRA-A3"))), List.of()));
            assertThat(persistence.countPersistedAssignments()).isZero();

            referenceData.writeStand(
                    ids.get("FRA-S3"), new Stand(ids.get("FRA-S3"), "Stand modifié", Set.of(TYPOLOGIE), 1, 2, false));

            PlanSnapshotService.RestaurationResult refus = snapshots.restaurer(id, false);

            assertThat(refus.restaure()).isFalse();
            assertThat(refus.perime()).isTrue();
            assertThat(refus.referencesManquantes()).isEmpty();
            assertThat(refus.referenceModifieLe()).isAfter(refus.creeLe());
            assertThat(persistence.countPersistedAssignments()).isZero();
        } finally {
            nettoyer("FRA-S3", "FRA-A3", 9703L);
        }
    }

    /**
     * And the override: staleness is a question, not a wall. Forcing restores
     * the plan — and leaves the snapshot stale, since putting a plan back moves
     * nothing in the referential it predates.
     */
    @Test
    void forcingRestoresDespiteStaleness() {
        try {
            fixture("FRA-S4", "FRA-A4", 9704L, "FRA-P4");
            long id = snapshots.capture("Plan à forcer", false).id();
            persistence.persist(new PlanningEvenement(JOUR, List.of(animateur(ids.get("FRA-A4"))), List.of()));

            referenceData.writeStand(
                    ids.get("FRA-S4"), new Stand(ids.get("FRA-S4"), "Stand modifié", Set.of(TYPOLOGIE), 1, 2, false));

            PlanSnapshotService.RestaurationResult forcee = snapshots.restaurer(id, true);

            assertThat(forcee.restaure()).isTrue();
            assertThat(forcee.affectations()).isEqualTo(1);
            assertThat(persistence.countPersistedAssignments()).isEqualTo(1);
            assertThat(snapshots.load(id).meta().perime()).isTrue();
        } finally {
            nettoyer("FRA-S4", "FRA-A4", 9704L);
        }
    }

    /**
     * A missing reference is not a staleness refusal, and no {@code forcer}
     * lifts it: the snapshot names a stand nobody can staff any more, so
     * putting it back would produce a plan nobody ever computed. The order of
     * the two checks is the point — a snapshot is usually stale <i>because</i>
     * ids disappeared, and naming them is the actionable message.
     */
    @Test
    void forcingDoesNotLiftTheRefusalOfMissingReferences() {
        try {
            fixture("FRA-S5", "FRA-A5", 9705L, "FRA-P5");
            long id = snapshots.capture("Plan orphelin", false).id();

            referenceData.deleteStand(ids.get("FRA-S5"));

            PlanSnapshotService.RestaurationResult refus = snapshots.restaurer(id, true);

            assertThat(refus.restaure()).isFalse();
            assertThat(refus.perime()).isFalse();
            assertThat(refus.referencesManquantes()).contains("stand:" + ids.get("FRA-S5"));
        } finally {
            nettoyer("FRA-S5", "FRA-A5", 9705L);
        }
    }

    /**
     * Non-regression on the partitioning: the marker is per edition, so
     * touching 2026 must leave 2025's snapshots alone. It held while the marker
     * was a map keyed on the edition; it has to keep holding now that it is a
     * column.
     */
    @Test
    void editingAnotherEditionMakesNothingStale() {
        try {
            fixture("FRA-S6", "FRA-A6", 9706L, "FRA-P6");
            long id =
                    snapshots.capture("Instantané de l'édition courante", false).id();
            ensureNeighbouringEdition();

            editionContext.executeIn(editionVoisine, () -> changeTracker.markModified());

            assertThat(snapshots.load(id).meta().perime()).isFalse();
            assertThat(snapshots.restaurer(id, false).restaure()).isTrue();
        } finally {
            // Nested so the neighbouring edition goes even if the referential
            // cleanup throws: left behind, it fails the *next* run instead of
            // this one, which is the worst way to report a problem.
            try {
                nettoyer("FRA-S6", "FRA-A6", 9706L);
            } finally {
                editions.delete(editionVoisine);
            }
        }
    }

    /**
     * The reason for the migration: the marker used to live in a
     * {@code ConcurrentHashMap} emptied by every restart, and an empty map
     * reads as "never modified" — which would make every stale snapshot look
     * fresh, exactly in front of the button where that lie costs the most. A
     * restart cannot be staged here, so the test asserts what makes it
     * survivable: the value the service answers with is the one in the
     * database, read here by a statement of its own.
     */
    @Test
    void theLastWriteIsStoredInTheDatabaseNotInMemory() throws Exception {
        try {
            fixture("FRA-S7", "FRA-A7", 9707L, "FRA-P7");

            Instant marque = changeTracker.lastModifiedAt();

            assertThat(marque).isNotNull();
            assertThat(storedReferenceChange(editionContext.editionIdCourant())).isEqualTo(marque);
        } finally {
            nettoyer("FRA-S7", "FRA-A7", 9707L);
        }
    }

    /** Idempotent, so a run that died before its cleanup does not fail the next one. */
    private void ensureNeighbouringEdition() {
        if (editionVoisine == null
                || editions.listEditions().stream().noneMatch(edition -> editionVoisine.equals(edition.getId()))) {
            editionVoisine = editions.create(new Edition(null, "Édition voisine (fraîcheur)", false, null))
                    .getId();
        }
    }

    /** {@code edition.reference_modifie_le} read straight from the table, bypassing every service. */
    private Instant storedReferenceChange(String editionId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT reference_modifie_le FROM edition WHERE id = ?")) {
            ps.setString(1, editionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                OffsetDateTime moment = rs.getObject("reference_modifie_le", OffsetDateTime.class);
                return moment == null ? null : moment.toInstant();
            }
        }
    }

    /**
     * One stand, one animateur, one créneau and one seat persisted — written
     * through the referential services on purpose, so the marker is set by the
     * same call chain production uses.
     */
    private void fixture(String standLabel, String animateurLabel, long creneauId, String posteId) {
        if (referenceData.listTypologies().stream().noneMatch(item -> TYPOLOGIE.equals(item.code()))) {
            referenceData.createTypologie(
                    new TypologieItem(null, TYPOLOGIE, "Typologie fraîcheur", false, null, null, null));
        }
        Stand nouveau = new Stand(null, "Stand " + standLabel, Set.of(TYPOLOGIE), 1, 1, false);
        nouveau.setCode(standLabel);
        Stand stand = referenceData.writeStand(nouveau).stand();
        ids.put(standLabel, stand.getId());
        Animateur animateur = referenceData.writeAnimateur(animateur(null)).animateur();
        ids.put(animateurLabel, animateur.getId());
        referenceData.createCreneaux(List.of(creneau(creneauId)));
        PosteAffectation poste = new PosteAffectation(posteId, stand, creneau(creneauId));
        poste.setAnimateur(animateur);
        persistence.persist(new PlanningEvenement(JOUR, List.of(animateur), List.of(poste)));
    }

    private void nettoyer(String standLabel, String animateurLabel, long creneauId) {
        persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
        // Only what this class captured: the edition is shared with every other
        // test of the run, and one of them leaves a published snapshot behind —
        // which refuses to be deleted, rightly (issue #245).
        for (PlanSnapshotService.SnapshotMeta meta : snapshots.list()) {
            if (LIBELLES.contains(meta.libelle())) {
                snapshots.delete(meta.id());
            }
        }
        if (ids.containsKey(standLabel)) {
            referenceData.deleteStand(ids.get(standLabel));
        }
        if (ids.containsKey(animateurLabel)) {
            referenceData.deleteAnimateur(ids.get(animateurLabel));
        }
        referenceData.deleteCreneaux(List.of(creneauId));
        referenceData.listTypologies().stream()
                .filter(item -> TYPOLOGIE.equals(item.code()))
                .forEach(item -> referenceData.deleteTypologie(item.id()));
    }

    private static Creneau creneau(long id) {
        return new Creneau(id, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, "Prenom", "Nom fraîcheur", LocalDate.of(1990, 1, 1), false);
    }
}
