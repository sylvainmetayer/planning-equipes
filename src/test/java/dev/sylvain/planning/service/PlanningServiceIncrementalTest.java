package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.ProblemBuilder.StatistiquesIncremental;
import dev.sylvain.planning.service.ReplanificationDiff.ChangementAffectation;

/**
 * The reconciliation of an incremental re-solve (issue #86), exercised on
 * {@code ProblemBuilder.figerPostesIncremental} and on the diff directly: no
 * database, no Quarkus context, no solve.
 *
 * <p>What these tests pin down is the perimeter itself — which seats the solver
 * is even allowed to touch. Getting it wrong is not a cosmetic bug: too wide
 * and a validated plan silently reshuffles, too narrow and the seat a
 * last-minute absence just emptied stays empty.</p>
 */
class PlanningServiceIncrementalTest {

    private static final LocalDate J1 = LocalDate.of(2026, 7, 8);
    private static final LocalDate J2 = LocalDate.of(2026, 7, 9);

    private final Stand standA = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 2, 2, false);
    private final Stand standB = new Stand("STAND-B", "Stand B", Set.of("STRATEGIE"), 1, 1, false);
    private final Creneau matinJ1 = new Creneau(1L, 1, J1, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Creneau matinJ2 = new Creneau(2L, 2, J2, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur alice = new Animateur("A1", "Alice", "Martin", LocalDate.of(2000, 1, 1), false);
    private final Animateur bob = new Animateur("A2", "Bob", "Durand", LocalDate.of(2000, 1, 1), false);

    private final List<Animateur> animateurs = List.of(alice, bob);

    private static String key(String standId, long creneauId) {
        return PlanningPersistenceService.standCreneauKey(standId, creneauId);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau) {
        return new PosteAffectation(id, stand, creneau);
    }

    @Test
    void unSiegeEncoreValideEstFigeAvecSonTitulaire() {
        List<PosteAffectation> postes = List.of(poste("p0", standA, matinJ1), poste("p1", standA, matinJ1));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1", "A2")), ReplanificationScope.automatic(), List.of());

        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(1).isVerrouille()).isTrue();
        assertThat(stats).isEqualTo(new StatistiquesIncremental(2, 2, 0, 0, 0));
    }

    @Test
    void unSiegeInvalideParUneIndisponibiliteFraicheEstLibere() {
        // The late change the whole feature exists for: Alice declares herself
        // unavailable on J1 after the plan was solved.
        alice.setJoursIndisponibles(Set.of(J1));
        List<PosteAffectation> postes = List.of(poste("p0", standA, matinJ1), poste("p1", standA, matinJ2));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1"), key("STAND-A", 2L), List.of("A1")),
                ReplanificationScope.automatic(), List.of());

        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(0).isVerrouille()).isFalse();
        // The other day is untouched: only what the change invalidated re-opens.
        assertThat(postes.get(1).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(1).isVerrouille()).isTrue();
        assertThat(stats).isEqualTo(new StatistiquesIncremental(2, 1, 1, 0, 0));
    }

    @Test
    void unTitulaireSupprimeDuReferentielLibereSonSiege() {
        List<PosteAffectation> postes = List.of(poste("p0", standA, matinJ1));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("DISPARU")), ReplanificationScope.automatic(), List.of());

        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(stats.postesLiberes()).isEqualTo(1);
    }

    @Test
    void unSiegeNouveauOuJamaisPourvuResteLibreSansCompterCommeLibere() {
        // p1 is a seat the previous solve left empty, p2 belongs to a stand
        // added since: neither was ever staffed, so neither was "freed".
        List<PosteAffectation> postes = List.of(
                poste("p0", standA, matinJ1),
                poste("p1", standA, matinJ1),
                poste("p2", standB, matinJ1));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1")), ReplanificationScope.automatic(), List.of());

        assertThat(postes.get(1).getAnimateur()).isNull();
        assertThat(postes.get(2).getAnimateur()).isNull();
        assertThat(stats).isEqualTo(new StatistiquesIncremental(3, 1, 0, 0, 2));
    }

    @Test
    void lePerimetreManuelRouvreUnAnimateurUnJourOuUnStandEncoreValides() {
        List<PosteAffectation> byAnimateur = List.of(poste("p0", standA, matinJ1), poste("p1", standA, matinJ1));
        Map<String, List<String>> persiste = Map.of(key("STAND-A", 1L), List.of("A1", "A2"));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(byAnimateur, animateurs, persiste,
                new ReplanificationScope(Set.of("A1"), Set.of(), Set.of()), List.of());

        // Alice's seat is re-opened even though nothing invalidated it — that is
        // the operator saying "elle se désiste, refais-le".
        assertThat(byAnimateur.get(0).getAnimateur()).isNull();
        assertThat(byAnimateur.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(stats).isEqualTo(new StatistiquesIncremental(2, 1, 0, 1, 0));

        List<PosteAffectation> parJour = List.of(poste("p0", standA, matinJ1), poste("p1", standA, matinJ2));
        ProblemBuilder.figerPostesIncremental(parJour, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1"), key("STAND-A", 2L), List.of("A2")),
                new ReplanificationScope(Set.of(), Set.of(J1), Set.of()), List.of());
        assertThat(parJour.get(0).getAnimateur()).isNull();
        assertThat(parJour.get(1).getAnimateur()).isEqualTo(bob);

        List<PosteAffectation> parStand = List.of(poste("p0", standA, matinJ1), poste("p1", standB, matinJ1));
        ProblemBuilder.figerPostesIncremental(parStand, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1"), key("STAND-B", 1L), List.of("A2")),
                new ReplanificationScope(Set.of(), Set.of(), Set.of("STAND-B")), List.of());
        assertThat(parStand.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(parStand.get(1).getAnimateur()).isNull();
    }

    @Test
    void uneIndisponibiliteForceeAdHocLibereLeSiegeQuElleInterdit() {
        // The other shape a late change takes: a forced unavailability on one
        // créneau. Pinning that seat would freeze a hard violation the solver
        // is then powerless to fix.
        ContrainteAdHoc indisponibilite = new ContrainteAdHoc("AH1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        indisponibilite.setAnimateursConcernes(List.of(alice));
        indisponibilite.setCreneau(matinJ1);
        List<PosteAffectation> postes = List.of(poste("p0", standA, matinJ1), poste("p1", standA, matinJ2));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1"), key("STAND-A", 2L), List.of("A1")),
                ReplanificationScope.automatic(), List.of(indisponibilite));

        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(alice);
        assertThat(stats).isEqualTo(new StatistiquesIncremental(2, 1, 1, 0, 0));
    }

    @Test
    void unePermutationDeSiegesInterchangeablesNEstPasUnChangement() {
        // Same crew on the same stand × créneau, seats swapped: nobody's
        // planning changed, so nobody must be told it did.
        PlanningEvenement solved = planningWith(
                affecte(poste("p0", standA, matinJ1), bob),
                affecte(poste("p1", standA, matinJ1), alice));

        List<ChangementAffectation> changements = ReplanificationDiff.compute(
                Map.of(key("STAND-A", 1L), List.of("A1", "A2")), solved);

        assertThat(changements).isEmpty();
    }

    @Test
    void unRemplacementEstRapporteAvecLesDeuxEquipesEnClair() {
        PlanningEvenement solved = planningWith(
                affecte(poste("p0", standA, matinJ1), bob),
                affecte(poste("p1", standA, matinJ2), alice));

        List<ChangementAffectation> changements = ReplanificationDiff.compute(
                Map.of(key("STAND-A", 1L), List.of("A1"), key("STAND-A", 2L), List.of("A1")), solved);

        assertThat(changements).singleElement().satisfies(changement -> {
            assertThat(changement.standId()).isEqualTo("STAND-A");
            assertThat(changement.date()).isEqualTo(J1.toString());
            assertThat(changement.avant()).containsExactly("Alice Martin");
            assertThat(changement.apres()).containsExactly("Bob Durand");
        });
    }

    @Test
    void unSiegeVideApresCoupEstRapporteCommeUneEquipeVide() {
        PlanningEvenement solved = planningWith(poste("p0", standA, matinJ1));

        List<ChangementAffectation> changements = ReplanificationDiff.compute(
                Map.of(key("STAND-A", 1L), List.of("A1")), solved);

        assertThat(changements).singleElement().satisfies(changement -> {
            assertThat(changement.avant()).containsExactly("Alice Martin");
            assertThat(changement.apres()).isEmpty();
        });
    }

    private static PosteAffectation affecte(PosteAffectation poste, Animateur animateur) {
        poste.setAnimateur(animateur);
        return poste;
    }

    private PlanningEvenement planningWith(PosteAffectation... postes) {
        return new PlanningEvenement(J1, animateurs, new ArrayList<>(List.of(postes)), List.of());
    }
}
