package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanningPersistenceServiceTest {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionService editionService;

    @Inject
    EditionContext editionContext;

    @Test
    void solvedPlanningIsPersistedToDatabase() {
        PlanningFestival problem = planningService.construireExempleSimple();
        PlanningFestival solved = planningService.resoudre(problem);

        int stored = persistenceService.persist(solved);

        assertThat(stored).isEqualTo(solved.getPostes().size());
        assertThat(persistenceService.countPersistedAssignments()).isEqualTo(stored);
        assertThat(stored).isPositive();
    }

    /**
     * Regression test for the effective-window override (issue #60: a stand
     * closed for only part of a créneau) silently vanishing across a
     * persist/reload round-trip — poste_affectation had no column for it, so
     * every downstream reader of the persisted planning (exports, calendars,
     * staffing) fell back to the créneau's full hours instead of the narrower
     * open segment actually staffed.
     */
    @Test
    void effectiveWindowSurvivesPersistAndReload() {
        Stand stand = referenceDataService.createStand(
                new Stand("STAND-EFFWIN", "Stand effectif", Set.of("STRATEGIE"), 1, 1, false));
        Creneau creneau = referenceDataService.createCreneau(
                new Creneau(null, 1, LocalDate.of(2026, 7, 14), LocalTime.of(13, 40), LocalTime.of(17, 50)));
        Animateur animateur = referenceDataService.createAnimateur(
                new Animateur("A-EFFWIN", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));

        PosteAffectation poste = new PosteAffectation("poste-effwin", stand, creneau);
        poste.setHeureDebutEffective(LocalTime.of(17, 0));
        poste.setHeureFinEffective(LocalTime.of(17, 50));
        poste.setAnimateur(animateur);

        PlanningFestival planning = new PlanningFestival(creneau.getDate(), List.of(animateur), List.of(poste));
        persistenceService.persist(planning);

        PlanningFestival reloaded = persistenceService.loadPersistedPlanning();

        assertThat(reloaded.getPostes()).hasSize(1);
        PosteAffectation reloadedPoste = reloaded.getPostes().get(0);
        assertThat(reloadedPoste.getHeureDebutEffective()).isEqualTo(LocalTime.of(17, 0));
        assertThat(reloadedPoste.getHeureFinEffective()).isEqualTo(LocalTime.of(17, 50));
    }

    /**
     * Regression test: {@code chargerAnimateursParStandCreneau()} queried
     * {@code poste_affectation} without an {@code edition_id} filter
     * ({@code prepareStatement} instead of {@code prepareScoped}), so the
     * verrouillages of one edition were re-seeded from every edition's rows at
     * once — spotted while analyzing issue #167, wrong since multi-édition.
     */
    @Test
    void chargerAnimateursParStandCreneauNeVoitQueSonEdition() {
        editionService.creer(new Edition("EDITION-SCOPE", "Édition scope", false, null));
        try {
            editionContext.executeDans("EDITION-SCOPE", () -> {
                Stand stand = referenceDataService.createStand(
                        new Stand("STAND-SCOPE", "Stand scope", Set.of(), 1, 1, false));
                Creneau creneau = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 15), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                Animateur animateur = referenceDataService.createAnimateur(
                        new Animateur("A-SCOPE", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));

                PosteAffectation poste = new PosteAffectation("poste-scope", stand, creneau);
                poste.setAnimateur(animateur);
                persistenceService.persist(
                        new PlanningFestival(creneau.getDate(), List.of(animateur), List.of(poste)));

                assertThat(persistenceService.chargerAnimateursParStandCreneau().values())
                        .anySatisfy(animateurs -> assertThat(animateurs).contains("A-SCOPE"));
            });

            // Back in the default edition: the other edition's rows must be invisible.
            assertThat(persistenceService.chargerAnimateursParStandCreneau().values())
                    .allSatisfy(animateurs -> assertThat(animateurs).doesNotContain("A-SCOPE"));
        } finally {
            editionService.supprimer("EDITION-SCOPE");
        }
    }
}
