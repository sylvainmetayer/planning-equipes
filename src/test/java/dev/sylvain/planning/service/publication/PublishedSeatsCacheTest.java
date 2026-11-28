package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotDetail;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotMeta;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The published plan keeps a publication's seats in memory, and nothing else:
 * the snapshot is parsed once per publication, the referential is read on
 * every call.
 */
class PublishedSeatsCacheTest {

    private static final Instant T0 = Instant.parse("2026-07-01T10:00:00Z");

    private final Snapshots snapshots = new Snapshots();

    private final Referential referential = new Referential();

    private final PlanPublieService service = new PlanPublieService(snapshots, referential, null);

    @Test
    void parsesTheSnapshotOnceForManyReads() {
        snapshots.publish("2026", 1, "anim-1");

        for (int i = 0; i < 50; i++) {
            assertThat(holders(service.planPublie())).containsExactly("anim-1");
        }

        assertThat(snapshots.contentReads).isEqualTo(1);
    }

    @Test
    void readsTheNewSeatsAfterAPublication() {
        snapshots.publish("2026", 1, "anim-1");
        service.planPublie();

        snapshots.publish("2026", 2, "anim-2");

        assertThat(holders(service.planPublie())).containsExactly("anim-2");
        assertThat(service.publicationInForce().orElseThrow().publication().id())
                .isEqualTo(2);
        assertThat(snapshots.contentReads).isEqualTo(2);
    }

    @Test
    void fallsBackOnThePreviousPublicationWhenTheLastIsGone() {
        snapshots.publish("2026", 1, "anim-1");
        snapshots.publish("2026", 2, "anim-2");
        assertThat(holders(service.planPublie())).containsExactly("anim-2");

        snapshots.delete("2026", 2);

        assertThat(holders(service.planPublie())).containsExactly("anim-1");
    }

    @Test
    void showsNothingOnceTheEditionLostEveryPublication() {
        snapshots.publish("2026", 1, "anim-1");
        service.planPublie();

        snapshots.delete("2026", 1);

        assertThat(service.publicationInForce()).isEmpty();
        assertThat(service.planPublie().getPostes()).isEmpty();
    }

    @Test
    void doesNotTrustAReusedIdWithOtherDates() {
        snapshots.publish("2026", 7, "anim-1");
        service.planPublie();

        // A wiped and re-imported edition whose sequence handed out the same id again.
        snapshots.delete("2026", 7);
        snapshots.clock = T0.plusSeconds(3600);
        snapshots.publish("2026", 7, "anim-9");

        assertThat(holders(service.planPublie())).containsExactly("anim-9");
    }

    @Test
    void resolvesTheCachedSeatsAgainstTodaysReferentialOnEveryRead() {
        snapshots.publish("2026", 1, "anim-1");
        assertThat(standNames(service.planPublie())).containsExactly("Cirque");

        referential.standName = "Cirque (renommé)";

        assertThat(standNames(service.planPublie())).containsExactly("Cirque (renommé)");
        assertThat(referential.reads).isEqualTo(2);
        assertThat(snapshots.contentReads).isEqualTo(1);
    }

    @Test
    void keepsEachEditionToItsOwnSeats() {
        snapshots.publish("2025", 1, "anim-2025");
        snapshots.publish("2026", 2, "anim-2026");

        snapshots.current = "2025";
        assertThat(holders(service.planPublie())).containsExactly("anim-2025");
        snapshots.current = "2026";
        assertThat(holders(service.planPublie())).containsExactly("anim-2026");
        snapshots.current = "2025";
        assertThat(holders(service.planPublie())).containsExactly("anim-2025");

        assertThat(snapshots.contentReads).isEqualTo(2);
    }

    /**
     * The cache's one premise: a snapshot's content never changes once
     * written. The only update of {@code version_plan} the backend makes is
     * its score.
     */
    @Test
    void noStatementRewritesASnapshotsContent() throws IOException {
        Pattern update = Pattern.compile("UPDATE\\s+version_plan\\s+SET\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        List<String> columns = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = update.matcher(Files.readString(source));
                while (matcher.find()) {
                    columns.add(matcher.group(1));
                }
            }
        }
        assertThat(columns).isNotEmpty().allMatch("score"::equalsIgnoreCase);
    }

    private static List<String> holders(PlanningEvenement plan) {
        return plan.getPostes().stream()
                .map(poste -> poste.getAnimateur() == null
                        ? null
                        : poste.getAnimateur().getId())
                .toList();
    }

    private static List<String> standNames(PlanningEvenement plan) {
        return plan.getPostes().stream().map(poste -> poste.getStand().getNom()).toList();
    }

    /** The snapshots of every edition; the edition a call works in is {@link #current}. */
    private static final class Snapshots extends PlanSnapshotService {

        private final Map<String, List<SnapshotDetail>> byEdition = new HashMap<>();

        private String current = "2026";

        private Instant clock = T0;

        private int contentReads;

        Snapshots() {
            super(0, null, null, null, null, null, null, null);
        }

        void publish(String edition, long id, String animateurId) {
            current = edition;
            clock = clock.plusSeconds(60);
            SnapshotMeta meta = new SnapshotMeta(
                    id,
                    "publication",
                    false,
                    Origine.PUBLIEE,
                    null,
                    1,
                    clock,
                    edition,
                    edition,
                    null,
                    clock,
                    null,
                    false,
                    List.of());
            AffectationSnapshot seat =
                    new AffectationSnapshot("P1", "S1", "1", "2026-07-11", "09:00", "12:00", animateurId, null, null);
            byEdition
                    .computeIfAbsent(edition, unused -> new ArrayList<>())
                    .add(new SnapshotDetail(meta, List.of(seat)));
        }

        void delete(String edition, long id) {
            byEdition.get(edition).removeIf(detail -> detail.meta().id() == id);
        }

        private List<SnapshotDetail> ofCurrent() {
            return byEdition.getOrDefault(current, List.of());
        }

        @Override
        public SnapshotMeta lastPublication() {
            List<SnapshotDetail> all = ofCurrent();
            return all.isEmpty() ? null : all.getLast().meta();
        }

        @Override
        public SnapshotDetail load(long id) {
            contentReads++;
            return ofCurrent().stream()
                    .filter(detail -> detail.meta().id() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public SnapshotDetail loadLastPublication() {
            contentReads++;
            List<SnapshotDetail> all = ofCurrent();
            return all.isEmpty() ? null : all.getLast();
        }
    }

    /** Resolves seats against a referential the test can change, and counts the reads. */
    private static final class Referential extends PlanningPersistenceService {

        private String standName = "Cirque";

        private int reads;

        Referential() {
            super(null, null, null, null, null);
        }

        @Override
        public PlanningEvenement assemblerPlanning(List<Siege> sieges) {
            reads++;
            Stand stand = new Stand("S1", standName, Set.of(), 1, 1, false);
            List<PosteAffectation> postes = new ArrayList<>();
            for (Siege siege : sieges) {
                Creneau creneau = new Creneau(
                        siege.creneauId(), 1, LocalDate.of(2026, 7, 11), LocalTime.of(9, 0), LocalTime.of(12, 0));
                PosteAffectation poste = new PosteAffectation(siege.posteId(), stand, creneau);
                if (siege.animateurId() != null) {
                    poste.setAnimateur(new Animateur(siege.animateurId(), "P", "N", LocalDate.of(1990, 1, 1), false));
                }
                postes.add(poste);
            }
            return new PlanningEvenement(LocalDate.of(2026, 7, 11), List.of(), postes);
        }
    }
}
