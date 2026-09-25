package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.FenetreRepas;
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
    void aRebuiltTimeslotTakesItsPlaceInTheDayNumbering() {
        String edition = editionService
                .create(new Edition(null, "Édition jours", false, null))
                .getId();
        try {
            editionContext.executeIn(edition, () -> {
                referenceDataService.createTypologie(
                        new TypologieItem(null, "STRATEGIE", "Stratégie", false, null, null, null));
                String stand = referenceDataService
                        .createStand(new Stand(null, "Stand jours", Set.of("STRATEGIE"), 1, 1, false))
                        .getId();
                Creneau premier = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 11), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                Creneau troisieme = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 13), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                String animateur = referenceDataService
                        .createAnimateur(new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false))
                        .getId();

                // The middle day is gone from the grid, and only the seat still
                // describes it — exactly what a published snapshot carries.
                long disparu = Math.max(premier.getId(), troisieme.getId()) + 1;
                PlanningEvenement assemble = persistenceService.assemblerPlanning(List.of(
                        siege("poste-1", stand, animateur, premier.getId(), null),
                        siege(
                                "poste-2",
                                stand,
                                animateur,
                                disparu,
                                new PlanningPersistenceService.VacationSnapshot(
                                        LocalDate.of(2026, 7, 12), LocalTime.of(14, 0), LocalTime.of(18, 0))),
                        siege("poste-3", stand, animateur, troisieme.getId(), null)));

                assertThat(assemble.getPostes())
                        .extracting(poste -> poste.getCreneau().getJour())
                        .containsExactly(1, 2, 3);
            });
        } finally {
            editionService.delete(edition);
        }
    }

    private static PlanningPersistenceService.Siege siege(
            String posteId,
            String standId,
            String animateurId,
            long creneauId,
            PlanningPersistenceService.VacationSnapshot vacation) {
        return new PlanningPersistenceService.Siege(posteId, standId, creneauId, animateurId, null, null, vacation);
    }

    /**
     * Regression test (issue #598, review): the assembled plan carried the
     * legal parameters but <b>not</b> the meal windows those parameters
     * declare. Every reader that takes them from the plan — the animateur's
     * PDF, their timeline, their espace — then saw a plan with no window at
     * all, so {@code PauseAnalyzer} owed no coupure repas and none was ever
     * drawn. The screens passing the windows in themselves were right all
     * along, which is exactly why nothing failed.
     */
    @Test
    void theAssembledPlanCarriesTheMealWindowsOfTheParameters() {
        String edition = editionService
                .create(new Edition(null, "Édition repas", false, null))
                .getId();
        try {
            editionContext.executeIn(edition, () -> {
                referenceDataService.createTypologie(
                        new TypologieItem(null, "STRATEGIE", "Stratégie", false, null, null, null));
                Stand stand = referenceDataService.createStand(
                        new Stand(null, "Stand repas", Set.of("STRATEGIE"), 1, 1, false));
                Creneau creneau = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 16), LocalTime.of(9, 0), LocalTime.of(20, 0)));
                Animateur animateur = referenceDataService.createAnimateur(
                        new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));

                PosteAffectation poste = new PosteAffectation("poste-repas", stand, creneau);
                poste.setAnimateur(animateur);
                persistenceService.persist(
                        new PlanningEvenement(creneau.getDate(), List.of(animateur), List.of(poste)));

                PlanningEvenement charge = persistenceService.loadPersistedPlanning();
                assertThat(charge.getParametresLegaux()).isNotEmpty();
                assertThat(charge.getFenetresRepas())
                        .as("les fenêtres repas des paramètres voyagent avec le plan")
                        .isEqualTo(
                                FenetreRepas.from(charge.getParametresLegaux().get(0)));
            });
        } finally {
            editionService.delete(edition);
        }
    }

    /**
     * Regression test: {@code loadAnimateursByStandCreneau()} queried
     * {@code poste_affectation} without an {@code edition_id} filter
     * ({@code prepareStatement} instead of {@code prepareScoped}), so the
     * verrouillages of one edition were re-seeded from every edition's rows at
     * once — spotted while analyzing issue #167, wrong since multi-édition.
     */
    @Test
    void loadingAnimateursByStandTimeslotSeesOnlyItsEdition() {
        String edition = editionService
                .create(new Edition(null, "Édition scope", false, null))
                .getId();
        String[] animateurId = new String[1];
        long[] creneauScope = new long[1];
        try {
            editionContext.executeIn(edition, () -> {
                referenceDataService.createTypologie(
                        new TypologieItem(null, "STRATEGIE", "Stratégie", false, null, null, null));
                Stand stand = referenceDataService.createStand(
                        new Stand(null, "Stand scope", Set.of("STRATEGIE"), 1, 1, false));
                Creneau creneau = referenceDataService.createCreneau(
                        new Creneau(null, 1, LocalDate.of(2026, 7, 15), LocalTime.of(10, 0), LocalTime.of(12, 0)));
                Animateur animateur = referenceDataService.createAnimateur(
                        new Animateur(null, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false));
                animateurId[0] = animateur.getId();
                creneauScope[0] = creneau.getId();

                PosteAffectation poste = new PosteAffectation("poste-scope", stand, creneau);
                poste.setAnimateur(animateur);
                persistenceService.persist(
                        new PlanningEvenement(creneau.getDate(), List.of(animateur), List.of(poste)));

                assertThat(persistenceService.loadAnimateursByStandCreneau().values())
                        .anySatisfy(animateurs -> assertThat(animateurs).contains(animateurId[0]));
            });

            // Back in the default edition: the other edition's rows must be
            // invisible. Numbered per edition, the same id may well exist here:
            // what must not show up is the other edition's stand × timeslot.
            assertThat(persistenceService.loadAnimateursByStandCreneau())
                    .allSatisfy((cle, animateurs) -> assertThat(cle).doesNotEndWith("#" + creneauScope[0]));
        } finally {
            editionService.delete(edition);
        }
    }
}
