package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.CoherenceReferentielService.CoherenceIssue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The phone count of the coherence checklist. */
class CoherenceTelephoneTest {

    private static CoherenceReferentielService.Sources sources(
            List<Animateur> animateurs, Set<String> affectes, List<Stand> stands) {
        return new CoherenceReferentielService.Sources(
                animateurs,
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
                () -> affectes);
    }

    @Test
    void phoneLineCountsFichesAndThoseWithAnUpcomingSeat() {
        Animateur avec = new Animateur();
        avec.setId("a1");
        avec.setTelephone("0600000000");
        Animateur sansA = new Animateur();
        sansA.setId("a2");
        Animateur sansB = new Animateur();
        sansB.setId("a3");
        sansB.setTelephone(" ");
        List<CoherenceIssue> lignes = CoherenceReferentielService.build(
                        sources(List.of(avec, sansA, sansB), Set.of("a2"), List.of()))
                .anomalies();
        assertThat(lignes)
                .filteredOn(l -> l.code().equals(CoherenceReferentielService.ANIMATEURS_SANS_TELEPHONE))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.message())
                            .isEqualTo("2 fiche(s) sans téléphone, dont 1 affectée(s) à une date à venir.");
                    assertThat(l.objetId()).isNull();
                });
    }

    @Test
    void noPhoneLineWhenNobodyHasOne() {
        Animateur sansA = new Animateur();
        sansA.setId("a1");
        Animateur sansB = new Animateur();
        sansB.setId("a2");
        assertThat(CoherenceReferentielService.build(sources(List.of(sansA, sansB), Set.of(), List.of()))
                        .anomalies())
                .noneMatch(l -> l.code().equals(CoherenceReferentielService.ANIMATEURS_SANS_TELEPHONE));
    }

    @Test
    void noPhoneLineWhenEveryoneHasOne() {
        Animateur avec = new Animateur();
        avec.setId("a1");
        avec.setTelephone("0600000000");
        assertThat(CoherenceReferentielService.build(sources(List.of(avec), Set.of(), List.of()))
                        .anomalies())
                .noneMatch(l -> l.code().equals(CoherenceReferentielService.ANIMATEURS_SANS_TELEPHONE));
    }
}
