package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
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
        PlanningEvenement problem = planningService.buildSimpleExample();
        PlanningEvenement solved = planningService.solve(problem);

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

        PlanningEvenement planning = new PlanningEvenement(creneau.getDate(), List.of(animateur), List.of(poste));
        persistenceService.persist(planning);

        PlanningEvenement reloaded = persistenceService.loadPersistedPlanning();

        assertThat(reloaded.getPostes()).hasSize(1);
        PosteAffectation reloadedPoste = reloaded.getPostes().get(0);
        assertThat(reloadedPoste.getHeureDebutEffective()).isEqualTo(LocalTime.of(17, 0));
        assertThat(reloadedPoste.getHeureFinEffective()).isEqualTo(LocalTime.of(17, 50));
    }

    /**
     * Issue #576: a seat whose créneau was deleted stands on one rebuilt from
     * the snapshot, and that rebuilt créneau must take its place in the grid's
     * day numbering — which is computed over the whole grid, from its earliest
     * date ({@code Creneau.assignerJours}), and never stored.
     *
     * <p>Left at its default the number would be 0: the night-rest rule reads
     * {@code veille.getJour() + 1 == lendemain.getJour()}, so a 0 would pass
     * for the eve of day 1 and invent a violation, and two deleted créneaux on
     * different dates would count as one day wherever a read-out groups by
     * jour.</p>
     */
    @Test
    void unCreneauReconstruitPrendSaPlaceDansLaNumerotationDesJours() {
        editionService.create(new Edition("EDITION-JOURS", "Édition jours", false, null));
        try {
            editionContext.executeIn("EDITION-JOURS", () -> {
                referenceDataService.createTypologie(new TypologieItem("STRATEGIE", "Stratégie"));
                referenceDataService.createStand(
                        new Stand("STAND-JOURS", "Stand jours", Set.of("STRATEGIE"), 1, 1, false));
                Creneau premier = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 11), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                Creneau troisieme = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 13), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                referenceDataService.createAnimateur(
                        new Animateur("A-JOURS", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));

                // The middle day is gone from the grid, and only the seat still
                // describes it — exactly what a published snapshot carries.
                long disparu = Math.max(premier.getId(), troisieme.getId()) + 1;
                PlanningEvenement assemble = persistenceService.assemblerPlanning(List.of(
                        siege("poste-1", premier.getId(), null),
                        siege(
                                "poste-2",
                                disparu,
                                new PlanningPersistenceService.VacationSnapshot(
                                        LocalDate.of(2026, 7, 12), LocalTime.of(14, 0), LocalTime.of(18, 0))),
                        siege("poste-3", troisieme.getId(), null)));

                assertThat(assemble.getPostes())
                        .extracting(poste -> poste.getCreneau().getJour())
                        .containsExactly(1, 2, 3);
            });
        } finally {
            editionService.delete("EDITION-JOURS");
        }
    }

    private static PlanningPersistenceService.Siege siege(
            String posteId, long creneauId, PlanningPersistenceService.VacationSnapshot vacation) {
        return new PlanningPersistenceService.Siege(posteId, "STAND-JOURS", creneauId, "A-JOURS", null, null, vacation);
    }

    /**
     * Regression test: {@code loadAnimateursByStandCreneau()} queried
     * {@code poste_affectation} without an {@code edition_id} filter
     * ({@code prepareStatement} instead of {@code prepareScoped}), so the
     * verrouillages of one edition were re-seeded from every edition's rows at
     * once — spotted while analyzing issue #167, wrong since multi-édition.
     */
    @Test
    void chargerAnimateursParStandCreneauNeVoitQueSonEdition() {
        editionService.create(new Edition("EDITION-SCOPE", "Édition scope", false, null));
        try {
            editionContext.executeIn("EDITION-SCOPE", () -> {
                referenceDataService.createTypologie(new TypologieItem("STRATEGIE", "Stratégie"));
                Stand stand = referenceDataService.createStand(
                        new Stand("STAND-SCOPE", "Stand scope", Set.of("STRATEGIE"), 1, 1, false));
                Creneau creneau = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 15), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                Animateur animateur = referenceDataService.createAnimateur(
                        new Animateur("A-SCOPE", "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));

                PosteAffectation poste = new PosteAffectation("poste-scope", stand, creneau);
                poste.setAnimateur(animateur);
                persistenceService.persist(
                        new PlanningEvenement(creneau.getDate(), List.of(animateur), List.of(poste)));

                assertThat(persistenceService.loadAnimateursByStandCreneau().values())
                        .anySatisfy(animateurs -> assertThat(animateurs).contains("A-SCOPE"));
            });

            // Back in the default edition: the other edition's rows must be invisible.
            assertThat(persistenceService.loadAnimateursByStandCreneau().values())
                    .allSatisfy(animateurs -> assertThat(animateurs).doesNotContain("A-SCOPE"));
        } finally {
            editionService.delete("EDITION-SCOPE");
        }
    }
}
