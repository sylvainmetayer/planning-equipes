package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.groups.Tuple.tuple;

import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementAnimateur;
import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementVacation;
import dev.sylvain.planning.service.publication.PublicationDiffService.Identite;
import dev.sylvain.planning.service.publication.PublicationDiffService.TypeChangement;
import dev.sylvain.planning.service.publication.PublicationDiffService.Vacation;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rule the whole feature rests on (issue #245): a modification wakes up
 * exactly the people whose own schedule moved, and nobody else. Plain JUnit —
 * the diff is a pure function, it needs no container to be pinned down.
 */
class PublicationDiffServiceTest {

    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);
    private static final LocalDate DIMANCHE = LocalDate.of(2026, 7, 12);

    private final PublicationDiffService diff = new PublicationDiffService();

    private static final Map<String, Identite> IDENTITES = Map.of(
            "camille", new Identite("Camille Durand", "camille@example.org"),
            "dominique", new Identite("Dominique Petit", "dominique@example.org"),
            "sasha", new Identite("Sasha Roy", null));

    private static Vacation vacation(LocalDate date, int debut, int fin, String standId, String standNom) {
        return new Vacation(date, LocalTime.of(debut, 0), LocalTime.of(fin, 0), standId, standNom);
    }

    /* ------------------------------- Nominal ------------------------------- */

    @Test
    void unEchangeEntreDeuxPersonnesNeReveilleQueCesDeuxPersonnes() {
        Map<String, List<Vacation>> publie = Map.of(
                "camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque")),
                "dominique", List.of(vacation(SAMEDI, 14, 18, "ninja", "Ninja")),
                "sasha", List.of(vacation(DIMANCHE, 10, 12, "jeux", "Jeux")));
        Map<String, List<Vacation>> courant = Map.of(
                "camille", List.of(vacation(SAMEDI, 14, 18, "ninja", "Ninja")),
                "dominique", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque")),
                "sasha", List.of(vacation(DIMANCHE, 10, 12, "jeux", "Jeux")));

        List<ChangementAnimateur> changements = diff.comparer(publie, courant, IDENTITES, false);

        assertThat(changements).extracting(ChangementAnimateur::animateurId).containsExactly("camille", "dominique");
    }

    @Test
    void unDeplacementSeLitCommeUnRemplacement() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))),
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "ninja", "Ninja"))),
                IDENTITES,
                false);

        assertThat(changements).singleElement().satisfies(changement -> {
            assertThat(changement.nomAffiche()).isEqualTo("Camille Durand");
            assertThat(changement.premiereDiffusion()).isFalse();
            assertThat(changement.changements()).singleElement().satisfies(vacation -> {
                assertThat(vacation.type()).isEqualTo(TypeChangement.DEPLACEMENT);
                assertThat(vacation.libelle()).isEqualTo("samedi 11/07 : Ninja 14h-18h remplace Cirque 14h-18h");
                assertThat(vacation.precedente().standId()).isEqualTo("cirque");
            });
        });
    }

    @Test
    void unAjoutEtUnRetraitSeDisentChacunPourSoi() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))),
                Map.of("camille", List.of(vacation(DIMANCHE, 10, 12, "jeux", "Jeux"))),
                IDENTITES,
                false);

        assertThat(changements)
                .singleElement()
                .extracting(ChangementAnimateur::changements)
                .asInstanceOf(list(ChangementVacation.class))
                .extracting(ChangementVacation::type, ChangementVacation::libelle)
                .containsExactly(
                        tuple(TypeChangement.RETRAIT, "samedi 11/07 : Cirque 14h-18h (retiré)"),
                        tuple(TypeChangement.AJOUT, "dimanche 12/07 : Jeux 10h-12h (nouveau)"));
    }

    @Test
    void unChangementDHoraireSurLeMemeStandEstUnDeplacement() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "ninja", "Ninja"))),
                Map.of(
                        "camille",
                        List.of(new Vacation(SAMEDI, LocalTime.of(14, 30), LocalTime.of(18, 0), "ninja", "Ninja"))),
                IDENTITES,
                false);

        assertThat(changements)
                .singleElement()
                .extracting(ChangementAnimateur::changements)
                .asInstanceOf(list(ChangementVacation.class))
                .singleElement()
                .extracting(ChangementVacation::libelle)
                .isEqualTo("samedi 11/07 : Ninja 14h30-18h remplace Ninja 14h-18h");
    }

    /* -------------------------------- Limites ------------------------------ */

    @Test
    void renommerUnStandNeReveillePersonne() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))),
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Chapiteau"))),
                IDENTITES,
                false);

        assertThat(changements).isEmpty();
    }

    @Test
    void unPlanIdentiqueNeConcernePersonne() {
        Map<String, List<Vacation>> plan = Map.of(
                "camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque")),
                "dominique", List.of(vacation(DIMANCHE, 10, 12, "jeux", "Jeux")));

        assertThat(diff.comparer(plan, plan, IDENTITES, false)).isEmpty();
    }

    @Test
    void deuxVacationsDuMemeJourSurLeMemeStandNeSontPasApparieesEntreElles() {
        // Morning and evening on the same stand: removing the evening one is a
        // retrait, not a move of the morning one into the evening.
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of(
                        "camille",
                        List.of(vacation(SAMEDI, 9, 12, "ninja", "Ninja"), vacation(SAMEDI, 18, 21, "ninja", "Ninja"))),
                Map.of("camille", List.of(vacation(SAMEDI, 9, 12, "ninja", "Ninja"))),
                IDENTITES,
                false);

        assertThat(changements)
                .singleElement()
                .extracting(ChangementAnimateur::changements)
                .asInstanceOf(list(ChangementVacation.class))
                .singleElement()
                .extracting(ChangementVacation::type)
                .isEqualTo(TypeChangement.RETRAIT);
    }

    @Test
    void unePremiereDiffusionAnnonceLePlanningEntier() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of(),
                Map.of(
                        "camille",
                        List.of(
                                vacation(SAMEDI, 14, 18, "cirque", "Cirque"),
                                vacation(DIMANCHE, 10, 12, "jeux", "Jeux"))),
                IDENTITES,
                true);

        assertThat(changements).singleElement().satisfies(changement -> {
            assertThat(changement.premiereDiffusion()).isTrue();
            assertThat(changement.changements())
                    .hasSize(2)
                    .allSatisfy(vacation -> assertThat(vacation.type()).isEqualTo(TypeChangement.AJOUT));
        });
    }

    @Test
    void quelquUnDeNouveauDansUnPlanDejaPublieEstUnePremiereDiffusionPourLuiSeul() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))),
                Map.of(
                        "camille",
                        List.of(vacation(SAMEDI, 14, 18, "ninja", "Ninja")),
                        "dominique",
                        List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))),
                IDENTITES,
                false);

        assertThat(changements)
                .extracting(ChangementAnimateur::animateurId, ChangementAnimateur::premiereDiffusion)
                .containsExactly(tuple("camille", false), tuple("dominique", true));
    }

    @Test
    void quelquUnQuiPerdTousSesCreneauxEstPrevenuQuandMeme() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("camille", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))), Map.of(), IDENTITES, false);

        assertThat(changements)
                .singleElement()
                .extracting(ChangementAnimateur::changements)
                .asInstanceOf(list(ChangementVacation.class))
                .singleElement()
                .extracting(ChangementVacation::type)
                .isEqualTo(TypeChangement.RETRAIT);
    }

    @Test
    void unAnimateurDisparuDuReferentielNEstPasUnDestinataire() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of("parti", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))), Map.of(), IDENTITES, false);

        assertThat(changements).isEmpty();
    }

    @Test
    void unDestinataireSansAdresseResteDansLaListe() {
        // With no address they will receive nothing, but the admin has to see
        // them: this is the only moment they learn to reach them another way.
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of(), Map.of("sasha", List.of(vacation(SAMEDI, 14, 18, "cirque", "Cirque"))), IDENTITES, true);

        assertThat(changements)
                .singleElement()
                .extracting(ChangementAnimateur::nomAffiche, ChangementAnimateur::email)
                .containsExactly("Sasha Roy", null);
    }

    @Test
    void uneVacationSansHeureEffectiveResteAnnoncable() {
        List<ChangementAnimateur> changements = diff.comparer(
                Map.of(),
                Map.of("camille", List.of(new Vacation(SAMEDI, null, null, "cirque", "Cirque"))),
                IDENTITES,
                true);

        assertThat(changements)
                .singleElement()
                .extracting(ChangementAnimateur::changements)
                .asInstanceOf(list(ChangementVacation.class))
                .singleElement()
                .extracting(ChangementVacation::libelle)
                .isEqualTo("samedi 11/07 : Cirque ?-? (nouveau)");
    }

    /* --------------------------- Minor changes ----------------------------- */

    /**
     * « Ces trois-là ne bougent que de dix minutes, inutile de les alarmer »
     * (issue #503): the same vacation, on the same stand, sliding within the
     * quarter of an hour somebody plans their arrival on.
     */
    @Test
    void aSlideWithinTheThresholdOnTheSameStandIsMinor() {
        assertThat(PublicationDiffService.isMinor(deplacement(
                        vacation(SAMEDI, 14, 18, "cirque", "Cirque"),
                        new Vacation(SAMEDI, LocalTime.of(14, 10), LocalTime.of(18, 0), "cirque", "Cirque"))))
                .isTrue();
    }

    @Test
    void aSlideBeyondTheThresholdIsNotMinor() {
        assertThat(PublicationDiffService.isMinor(deplacement(
                        vacation(SAMEDI, 14, 18, "cirque", "Cirque"),
                        new Vacation(SAMEDI, LocalTime.of(14, 16), LocalTime.of(18, 0), "cirque", "Cirque"))))
                .isFalse();
    }

    /** The threshold applies to both ends: a shift that stretches the evening is not a détail. */
    @Test
    void aSlideMinorAtTheStartAndMajorAtTheEndIsNotMinor() {
        assertThat(PublicationDiffService.isMinor(deplacement(
                        vacation(SAMEDI, 14, 18, "cirque", "Cirque"), vacation(SAMEDI, 14, 20, "cirque", "Cirque"))))
                .isFalse();
    }

    @Test
    void changingStandIsNeverMinorHoweverSmallTheSlide() {
        assertThat(PublicationDiffService.isMinor(deplacement(
                        vacation(SAMEDI, 14, 18, "cirque", "Cirque"), vacation(SAMEDI, 14, 18, "ninja", "Ninja"))))
                .isFalse();
    }

    /**
     * A seat somebody does not know they hold, or no longer holds, is the
     * thing this whole feature exists to say — whatever its length.
     */
    @Test
    void anAdditionAndAWithdrawalAreNeverMinor() {
        Vacation cirque = vacation(SAMEDI, 14, 18, "cirque", "Cirque");
        assertThat(PublicationDiffService.isMinor(
                        new ChangementVacation(TypeChangement.AJOUT, cirque, null, "peu importe")))
                .isFalse();
        assertThat(PublicationDiffService.isMinor(
                        new ChangementVacation(TypeChangement.RETRAIT, cirque, null, "peu importe")))
                .isFalse();
    }

    @Test
    void aVacationWithoutReadableHoursIsNeverMinor() {
        assertThat(PublicationDiffService.isMinor(deplacement(
                        new Vacation(SAMEDI, null, null, "cirque", "Cirque"),
                        vacation(SAMEDI, 14, 18, "cirque", "Cirque"))))
                .isFalse();
    }

    private static ChangementVacation deplacement(Vacation avant, Vacation apres) {
        return new ChangementVacation(TypeChangement.DEPLACEMENT, apres, avant, "peu importe");
    }
}
