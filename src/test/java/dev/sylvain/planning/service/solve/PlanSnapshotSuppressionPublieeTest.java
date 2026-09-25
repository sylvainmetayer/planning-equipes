package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Deleting a snapshot that carries the « publié » state (issue #34).
 *
 * <p>Only <b>the</b> published plan is untouchable — the one the animateurs
 * were last sent and the one their espace reads (issue #245). The publications
 * before it are history: the espace no longer reads them, and an edition that
 * publishes every evening otherwise accumulates snapshots nobody can ever
 * remove.</p>
 */
@QuarkusTest
class PlanSnapshotSuppressionPublieeTest {

    private static final LocalDate JOUR = LocalDate.of(2030, 10, 5);
    /** Codes: the ids themselves are drawn by the application (ADR 0050). */
    private static final String TYPOLOGIE = "SUP-T";

    private static final String STAND = "SUP-S1";
    private static final String NOM_ANIMATEUR = "Nom SUP-A1";

    private String typologieId;
    private String standId;
    private String animateurId;
    private static final long CRENEAU = 9801L;

    /** Labels this class captures under, and the only ones its cleanup may delete. */
    private static final Set<String> LIBELLES = Set.of("Publication de la veille", "Publication du soir");

    @Inject
    PlanSnapshotService snapshots;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    DataSource dataSource;

    /**
     * The bug itself: with two publications behind it, the first one is a plan
     * nobody reads any more and deleting it takes nothing back.
     */
    @Test
    void aPublicationAnotherOneReplacedIsDeletable() {
        try {
            fixture();
            long veille = snapshots.capturePubliee("Publication de la veille").id();
            long soir = snapshots.capturePubliee("Publication du soir").id();
            assertThat(snapshots.lastPublication().id()).isEqualTo(soir);

            assertThat(snapshots.delete(veille)).isTrue();

            assertThat(snapshots.list()).noneMatch(meta -> meta.id() == veille);
            assertThat(snapshots.lastPublication().id()).isEqualTo(soir);
        } finally {
            cleanUp();
        }
    }

    /** And the guard that stays: the plan on display is not deletable. */
    @Test
    void theLastPublicationStaysUndeletable() {
        try {
            fixture();
            snapshots.capturePubliee("Publication de la veille");
            long soir = snapshots.capturePubliee("Publication du soir").id();

            assertThatThrownBy(() -> snapshots.delete(soir))
                    .isInstanceOf(BusinessError.Conflict.class)
                    .hasMessageContaining("plan publié");

            assertThat(snapshots.list()).anyMatch(meta -> meta.id() == soir);
        } finally {
            cleanUp();
        }
    }

    /** A single publication is that plan, so it is refused just the same. */
    @Test
    void aLonePublicationIsThePublishedPlan() {
        try {
            fixture();
            long seule = snapshots.capturePubliee("Publication du soir").id();

            assertThatThrownBy(() -> snapshots.delete(seule)).isInstanceOf(BusinessError.Conflict.class);
        } finally {
            cleanUp();
        }
    }

    private void fixture() {
        typologieId = referenceData.listTypologies().stream()
                .filter(item -> TYPOLOGIE.equals(item.code()))
                .map(TypologieItem::id)
                .findFirst()
                .orElseGet(() -> referenceData
                        .createTypologie(
                                new TypologieItem(null, TYPOLOGIE, "Typologie suppression", false, null, null, null))
                        .id());
        Stand nouveau = new Stand(null, "Stand " + STAND, Set.of(typologieId), 1, 1, false);
        nouveau.setCode(STAND);
        Stand stand = referenceData.writeStand(nouveau).stand();
        standId = stand.getId();
        animateurId = referenceData.writeAnimateur(animateur()).animateur().getId();
        referenceData.createCreneaux(List.of(creneau()));
        PosteAffectation poste = new PosteAffectation("SUP-P1", stand, creneau());
        poste.setAnimateur(animateur());
        persistence.persist(new PlanningEvenement(JOUR, List.of(animateur()), List.of(poste)));
    }

    /**
     * Straight SQL rather than {@link PlanSnapshotService#delete}: the last
     * publication refuses to go through the service, which is the whole point
     * of the class, and the edition is shared with every other test of the run.
     */
    private void cleanUp() {
        persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("DELETE FROM plan_snapshot WHERE libelle = ANY (?)")) {
            ps.setArray(1, connection.createArrayOf("text", LIBELLES.toArray()));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clean the snapshots of the test", e);
        }
        referenceData.deleteStand(standId);
        referenceData.deleteAnimateur(animateurId);
        referenceData.deleteCreneaux(List.of(CRENEAU));
        referenceData.deleteTypologie(typologieId);
    }

    private static Creneau creneau() {
        return new Creneau(CRENEAU, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private Animateur animateur() {
        return new Animateur(animateurId, "Prenom", NOM_ANIMATEUR, LocalDate.of(1990, 1, 1), false);
    }
}
