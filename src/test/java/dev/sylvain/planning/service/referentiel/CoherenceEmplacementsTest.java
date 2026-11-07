package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.CoherenceAnalyzer.EmplacementsManquants;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The emplacement suggestion heuristic and its line in the coherence checklist. */
class CoherenceEmplacementsTest {

    private static final Emplacement PLACE = new Emplacement("e1", "Place du marché", null, null);
    private static final Emplacement PARC = new Emplacement("e2", "Parc", null, null);

    private static Stand stand(String id, String nom, Set<String> typologies, Emplacement emplacement) {
        Stand stand = new Stand(id, nom, typologies, 1, 1, false);
        stand.setEmplacement(emplacement);
        return stand;
    }

    @Test
    void nameRootStripsTrailingNumberOrIsolatedLetter() {
        assertThat(CoherenceAnalyzer.nameRoot("Buvette 3")).isEqualTo("Buvette");
        assertThat(CoherenceAnalyzer.nameRoot("Buvette B")).isEqualTo("Buvette");
        assertThat(CoherenceAnalyzer.nameRoot("Tir à l'arc")).isEqualTo("Tir à l'arc");
        assertThat(CoherenceAnalyzer.nameRoot("Buvette")).isEqualTo("Buvette");
    }

    @Test
    void majorityOfTheSameNameRootIsSuggestedWithItsReason() {
        List<Stand> stands = new ArrayList<>(List.of(
                stand("1", "Buvette 1", Set.of(), PLACE),
                stand("2", "Buvette 2", Set.of(), PLACE),
                stand("3", "Buvette 3", Set.of(), PLACE),
                stand("4", "Buvette 4", Set.of(), PLACE),
                stand("5", "Buvette 5", Set.of(), null)));
        EmplacementsManquants missing = CoherenceAnalyzer.missingEmplacements(stands, 1);
        assertThat(missing.standIds()).containsExactly("5");
        assertThat(missing.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.emplacementId()).isEqualTo("e1");
            assertThat(s.raison()).isEqualTo("4 stand(s) « Buvette … » sur 4 sont à Place du marché");
        });
    }

    @Test
    void identicalTypologiesAreTheFallbackWhenTheNameSharesNothing() {
        List<Stand> stands = List.of(
                stand("1", "Alpha", Set.of("t1"), PARC),
                stand("2", "Bravo", Set.of("t1"), PARC),
                stand("3", "Charlie", Set.of("t2"), PLACE),
                stand("4", "Delta", Set.of("t1"), null));
        EmplacementsManquants missing = CoherenceAnalyzer.missingEmplacements(stands, 2);
        assertThat(missing.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.standId()).isEqualTo("4");
            assertThat(s.emplacementNom()).isEqualTo("Parc");
        });
    }

    @Test
    void tieIsSignalledWithoutSuggestion() {
        List<Stand> stands = List.of(
                stand("1", "Buvette 1", Set.of(), PLACE),
                stand("2", "Buvette 2", Set.of(), PARC),
                stand("3", "Buvette 3", Set.of(), null));
        EmplacementsManquants missing = CoherenceAnalyzer.missingEmplacements(stands, 2);
        assertThat(missing.standIds()).containsExactly("3");
        assertThat(missing.suggestions()).isEmpty();
    }

    @Test
    void singleNeighbourIsNotEnough() {
        List<Stand> stands = List.of(stand("1", "Buvette 1", Set.of(), PLACE), stand("2", "Buvette 2", Set.of(), null));
        EmplacementsManquants missing = CoherenceAnalyzer.missingEmplacements(stands, 1);
        assertThat(missing.standIds()).containsExactly("2");
        assertThat(missing.suggestions()).isEmpty();
    }

    @Test
    void editionWithoutEmplacementSaysNothing() {
        List<Stand> stands = List.of(stand("1", "Buvette 1", Set.of(), null), stand("2", "Buvette 2", Set.of(), null));
        EmplacementsManquants missing = CoherenceAnalyzer.missingEmplacements(stands, 0);
        assertThat(missing.standIds()).isEmpty();
        assertThat(missing.suggestions()).isEmpty();
    }

    @Test
    void writeWarnsOnlyWhenTheWriteLeftTheEmplacementEmptyAgainstASuggestion() {
        List<Stand> stands = List.of(
                stand("1", "Buvette 1", Set.of(), PLACE),
                stand("2", "Buvette 2", Set.of(), PLACE),
                stand("3", "Buvette 3", Set.of(), null));
        Stand sans = stands.get(2);
        assertThat(CoherenceAnalyzer.onStandEmplacement(null, sans, stands, 1))
                .extracting(Avertissement::type)
                .containsExactly(TypeAvertissement.STAND_SANS_EMPLACEMENT);
        // untouched by this write (bulk edit of another field): silent
        assertThat(CoherenceAnalyzer.onStandEmplacement(sans, sans, stands, 1)).isEmpty();
        // has one: silent
        assertThat(CoherenceAnalyzer.onStandEmplacement(null, stands.get(0), stands, 1))
                .isEmpty();
    }

    private static CoherenceReferentielService.Sources sources(
            Set<String> affectes, List<Stand> stands, int emplacements) {
        return new CoherenceReferentielService.Sources(
                List.of(),
                List.<Creneau>of(),
                stands,
                stands,
                List.of(),
                List.of(),
                Set::of,
                null,
                List.of(),
                List.of(),
                dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.analyze(stands, List.of()),
                null,
                CoherenceAnalyzer.missingEmplacements(stands, emplacements),
                () -> affectes);
    }

    @Test
    void standsLineCountsStandsAndSuggestions() {
        List<Stand> stands = List.of(
                stand("1", "Buvette 1", Set.of(), PLACE),
                stand("2", "Buvette 2", Set.of(), PLACE),
                stand("3", "Buvette 3", Set.of(), null),
                stand("4", "Solo", Set.of(), null));
        assertThat(CoherenceReferentielService.build(sources(Set.of(), stands, 1))
                        .anomalies())
                .filteredOn(l -> l.code().equals(CoherenceReferentielService.STANDS_SANS_EMPLACEMENT))
                .singleElement()
                .satisfies(l ->
                        assertThat(l.message()).isEqualTo("2 stand(s) sans emplacement, dont 1 avec une suggestion."));
    }
}
