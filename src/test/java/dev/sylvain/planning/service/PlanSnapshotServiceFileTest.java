package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The snapshot behaviours the "résoudre tous les groupes" queue relies on
 * (issue #167): capturing a solved plan straight from memory without touching
 * {@code poste_affectation}, and the retention rule that always spares the
 * most recent snapshot of each still-existing group. Runs in its own edition
 * so the counts never depend on what other tests captured.
 */
@QuarkusTest
class PlanSnapshotServiceFileTest {

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    private PlanningFestival planEnMemoire(String animateurId) {
        Stand stand = new Stand("STAND-FILE", "Stand file", java.util.Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(999L, 1, LocalDate.of(2026, 7, 10), LocalTime.of(10, 0), LocalTime.of(12, 0));
        Animateur animateur = new Animateur(animateurId, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("poste-file", stand, creneau);
        poste.setAnimateur(animateur);
        return new PlanningFestival(creneau.getDate(), List.of(animateur), List.of(poste));
    }

    @Test
    void laCaptureDepuisLaMemoireNEcritPasDansLePlanPersiste() {
        editionService.creer(new Edition("ED-SNAP-MEM", "Édition capture mémoire", false, null));
        try {
            editionContext.executeDans("ED-SNAP-MEM", () -> {
                referenceDataService.createGroupeCreneau(new GroupeCreneau("G-MEM", "Groupe mémoire", false));

                PlanSnapshotService.SnapshotMeta meta = snapshotService.capturerDepuisSolution(
                        planEnMemoire("A-MEM"), "G-MEM", "Groupe mémoire", "0hard/0medium/0soft", "Capture mémoire");

                assertThat(meta).isNotNull();
                assertThat(meta.groupeCreneauId()).isEqualTo("G-MEM");
                assertThat(meta.nombreAffectations()).isEqualTo(1);
                assertThat(persistenceService.countPersistedAssignments()).isZero();

                PlanSnapshotService.SnapshotDetail dernier = snapshotService.dernierSnapshotDuGroupe("G-MEM");
                assertThat(dernier).isNotNull();
                assertThat(dernier.affectations()).singleElement()
                        .satisfies(a -> assertThat(a.animateurId()).isEqualTo("A-MEM"));
            });
        } finally {
            editionService.supprimer("ED-SNAP-MEM");
        }
    }

    @Test
    void laPurgeEpargneLeDernierSnapshotDeChaqueGroupeExistant() {
        editionService.creer(new Edition("ED-SNAP-PURGE", "Édition purge", false, null));
        try {
            editionContext.executeDans("ED-SNAP-PURGE", () -> {
                referenceDataService.createGroupeCreneau(new GroupeCreneau("G-A", "Groupe A", false));
                referenceDataService.createGroupeCreneau(new GroupeCreneau("G-B", "Groupe B", false));

                snapshotService.capturerDepuisSolution(planEnMemoire("A-B"), "G-B", "Groupe B", null, "B unique");
                long idB = snapshotService.dernierSnapshotDuGroupe("G-B").meta().id();
                // Far more captures of A than the retention keeps (5 by default):
                // without the per-group rule, B's snapshot would age out.
                for (int i = 0; i < 8; i++) {
                    snapshotService.capturerDepuisSolution(planEnMemoire("A-A" + i), "G-A", "Groupe A", null, "A " + i);
                }

                assertThat(snapshotService.dernierSnapshotDuGroupe("G-B")).isNotNull();
                assertThat(snapshotService.dernierSnapshotDuGroupe("G-B").meta().id()).isEqualTo(idB);
                // A's own latest capture survives too, whatever was purged around it.
                assertThat(snapshotService.dernierSnapshotDuGroupe("G-A").meta().libelle()).isEqualTo("A 7");
            });
        } finally {
            editionService.supprimer("ED-SNAP-PURGE");
        }
    }
}
