package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintFloor;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ContributionAdHoc;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.analyse.ScoreReading.ReadingLink;
import dev.sylvain.planning.service.analyse.ScoreReading.ReadingSubject;
import dev.sylvain.planning.service.analyse.ScoreReading.ReadingTone;
import dev.sylvain.planning.service.analyse.ScoreReading.ScoreSentence;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The reading of the score is built from templates on a diagnostic: every case
 * here is a diagnostic written by hand, and what is checked is the text an
 * organiser reads.
 */
class ScoreReadingTest {

    private static final Pattern CAMEL_CASE = Pattern.compile("\\b\\p{Ll}+\\p{Lu}\\w*");
    private static final Pattern POURCENTAGE = Pattern.compile("(?<!\\d)(\\d++) %");

    private static ConstraintDiagnostic rule(String name, String score, int matches) {
        return new ConstraintDiagnostic(name, score, matches, List.of(), null, null, List.of());
    }

    private static ConstraintDiagnostic floor(String name, String score, int matches, String libelle) {
        return new ConstraintDiagnostic(
                name,
                score,
                matches,
                List.of(),
                matches,
                new ConstraintFloor(1.0, "SOUHAITS", libelle, "/animateurs"),
                List.of());
    }

    private static PlanningDiagnostic diagnostic(
            String score,
            int hard,
            int vides,
            List<ConstraintDiagnostic> contraintes,
            List<ContributionAdHoc> adHoc,
            int plancherMedium,
            int plancherSoft,
            List<PivotEcarts.Cellule> pivot) {
        return new PlanningDiagnostic(
                score, vides, contraintes, null, hard, adHoc, score, plancherMedium, plancherSoft, pivot);
    }

    private static PlanningDiagnostic clean() {
        return diagnostic(
                "0hard/0medium/0soft",
                0,
                0,
                List.of(rule("posteDoitEtrePourvu", "0hard/0medium/0soft", 0)),
                List.of(),
                0,
                0,
                List.of());
    }

    /** A plan with something to say at every level, and a floor. */
    private static PlanningDiagnostic busy() {
        return diagnostic(
                "-5hard/-5600medium/-80soft",
                -5,
                3,
                List.of(
                        rule("posteDoitEtrePourvu", "-3hard/0medium/0soft", 3),
                        rule("reposQuotidienMinimal", "-2hard/0medium/0soft", 2),
                        rule("equilibrerCharge", "0hard/-300medium/0soft", 30),
                        rule("eviterChangementEmplacementEloigne", "0hard/-200medium/0soft", 4),
                        rule("standComplexeAvecReferent", "0hard/-80medium/0soft", 8),
                        rule("limiterEmplacementsParJour", "0hard/-20medium/0soft", 2),
                        floor(
                                "souhaitsIncompatibles",
                                "0hard/-5000medium/0soft",
                                500,
                                "Aucun souhait déclaré sur les fiches animateur : la règle pénalise chaque poste pourvu."),
                        rule("favoriserMixiteDesNiveaux", "0hard/0medium/-70soft", 7),
                        rule("equilibrerCreneauxPenibles", "0hard/0medium/-10soft", 1)),
                List.of(new ContributionAdHoc(
                        "C12", "AFFECTATION_FORCEE", "Pour Sarah Martin", 2, List.of("affectationForcee"))),
                -5000,
                0,
                List.of(
                        new PivotEcarts.Cellule("posteDoitEtrePourvu", PivotEcarts.Axe.JOUR, "2026-07-11", 1),
                        new PivotEcarts.Cellule("posteDoitEtrePourvu", PivotEcarts.Axe.JOUR, "2026-07-12", 2)));
    }

    private static Optional<ScoreSentence> phrase(List<ScoreSentence> lecture, ReadingSubject sujet) {
        return lecture.stream().filter(p -> p.sujet() == sujet).findFirst();
    }

    @Test
    void aCleanPlanSaysEveryMandatoryRuleHoldsAndSaysNothingOfCoverage() {
        List<ScoreSentence> lecture = ScoreReading.read(clean());

        assertThat(lecture.getFirst().sujet()).isEqualTo(ReadingSubject.VERDICT);
        assertThat(lecture.getFirst().texte()).isEqualTo("Le planning respecte toutes les règles impératives.");
        assertThat(lecture.getFirst().niveau()).isEqualTo(ReadingTone.OK);
        assertThat(phrase(lecture, ReadingSubject.COUVERTURE)).isEmpty();
        assertThat(phrase(lecture, ReadingSubject.ORGANISATION))
                .as("a zero medium score says nothing")
                .isEmpty();
    }

    @Test
    void aNegativeHardScoreNamesHowManyRulesFailAndTheHeaviestWithItsLink() {
        ScoreSentence verdict = ScoreReading.read(busy()).getFirst();

        assertThat(verdict.sujet()).isEqualTo(ReadingSubject.VERDICT);
        assertThat(verdict.niveau()).isEqualTo(ReadingTone.BLOQUANT);
        assertThat(verdict.texte())
                .startsWith("2 règles impératives ne sont pas respectées")
                .contains("surtout « Places pourvues », 3 écarts");
        assertThat(verdict.liens())
                .containsExactly(
                        new ReadingLink("Places pourvues", "/constraints", java.util.Map.of(), "posteDoitEtrePourvu"));
        assertThat(verdict.texte()).contains(verdict.liens().getFirst().texte());
    }

    @Test
    void theCoverageSentenceNamesTheWorstDayWithALinkToTheJournee() {
        ScoreSentence couverture =
                phrase(ScoreReading.read(busy()), ReadingSubject.COUVERTURE).orElseThrow();

        assertThat(couverture.texte()).isEqualTo("3 places restent vides, dont 2 le dimanche 12/07.");
        assertThat(couverture.liens().getFirst().route()).isEqualTo("/journee");
        assertThat(couverture.liens().getFirst().parametres()).containsEntry("date", "2026-07-12");
    }

    @Test
    void theOrganisationSentenceNamesAtMostThreeRulesByWeightWithSharesOutsideTheFloor() {
        ScoreSentence organisation =
                phrase(ScoreReading.read(busy()), ReadingSubject.ORGANISATION).orElseThrow();

        assertThat(organisation.liens())
                .extracting(ReadingLink::fragment)
                .containsExactly("equilibrerCharge", "eviterChangementEmplacementEloigne", "standComplexeAvecReferent");
        // 300 + 200 + 80 + 20 = 600 outside the floor: 50 %, 33 %, 13 %.
        assertThat(organisation.texte())
                .contains("(50 % des points, 30 cas)")
                .contains("(33 %")
                .contains("(13 %");
        int somme = 0;
        Matcher pourcentage = POURCENTAGE.matcher(organisation.texte());
        while (pourcentage.find()) {
            somme += Integer.parseInt(pourcentage.group(1));
        }
        assertThat(somme).isLessThanOrEqualTo(100);
    }

    @Test
    void aFloorRuleGetsTheFloorSentenceAndStaysOutOfTheOrganisationOne() {
        List<ScoreSentence> lecture = ScoreReading.read(busy());

        ScoreSentence plancher = phrase(lecture, ReadingSubject.PLANCHER).orElseThrow();
        assertThat(plancher.texte())
                .startsWith("5 000 points d'organisation viennent de règles qu'aucune résolution ne fera bouger")
                .contains("« Souhaits non couverts » (aucun souhait déclaré sur les fiches animateur)");
        assertThat(phrase(lecture, ReadingSubject.ORGANISATION).orElseThrow().texte())
                .doesNotContain("Souhaits non couverts");
    }

    @Test
    void theComfortSentenceAppearsOnlyWhenOneRuleDominates() {
        ScoreSentence confort =
                phrase(ScoreReading.read(busy()), ReadingSubject.CONFORT).orElseThrow();
        assertThat(confort.texte()).contains("Mixité des niveaux").contains("87 %");

        PlanningDiagnostic partage = diagnostic(
                "0hard/0medium/-20soft",
                0,
                0,
                List.of(
                        rule("favoriserMixiteDesNiveaux", "0hard/0medium/-10soft", 1),
                        rule("equilibrerCreneauxPenibles", "0hard/0medium/-9soft", 1),
                        rule("preserverBufferPolyvalents", "0hard/0medium/-1soft", 1)),
                List.of(),
                0,
                0,
                List.of());
        assertThat(phrase(ScoreReading.read(partage), ReadingSubject.CONFORT))
                .as("half of the points exactly is notable, less is not")
                .isPresent();
    }

    @Test
    void theExceptionSentenceCountsWithoutEverQuotingTheirFreeText() {
        ScoreSentence ajustements =
                phrase(ScoreReading.read(busy()), ReadingSubject.AJUSTEMENTS).orElseThrow();

        assertThat(ajustements.texte())
                .isEqualTo("1 ajustement manuel est en cause : 2 écarts sur « Affectations forcées ».");
        assertThat(ajustements.texte()).doesNotContain("Sarah").doesNotContain("Martin");
        assertThat(ajustements.liens().getFirst().parametres()).containsEntry("ids", "C12");
    }

    @Test
    void theSameDiagnosticAlwaysReadsTheSame() {
        assertThat(ScoreReading.read(busy())).isEqualTo(ScoreReading.read(busy()));
        assertThat(ScoreReading.read(busy(), Optional.of(clean()), Set.of("reposQuotidienMinimal")))
                .isEqualTo(ScoreReading.read(busy(), Optional.of(clean()), Set.of("reposQuotidienMinimal")));
    }

    @Test
    void noTechnicalRuleNameEverReachesTheText() {
        List<ScoreSentence> lecture = new ArrayList<>(ScoreReading.read(busy(), Optional.of(clean()), Set.of()));
        lecture.addAll(ScoreReading.read(clean(), Optional.of(busy()), Set.of()));

        assertThat(lecture).allSatisfy(p -> {
            assertThat(CAMEL_CASE.matcher(p.texte()).find()).as(p.texte()).isFalse();
            assertThat(ConstraintCatalog.PAR_NOM.keySet())
                    .noneMatch(name -> p.texte().contains(name));
            assertThat(p.liens()).allSatisfy(lien -> assertThat(p.texte()).contains(lien.texte()));
        });
    }

    @Test
    void aProtectedRuleSwitchedOffIsRecalledByTheVerdictAndARuleShippedOffIsNot() {
        ScoreSentence verdict = ScoreReading.read(clean(), Optional.empty(), Set.of("reposQuotidienMinimal"))
                .getFirst();
        assertThat(verdict.texte()).contains("1 règle légale est désactivée pour cette édition");
        assertThat(verdict.niveau()).isEqualTo(ReadingTone.ATTENTION);

        ScoreSentence parDefaut = ScoreReading.read(
                        clean(), Optional.empty(), Set.of("mineurNecessiteEncadrementMajeur"))
                .getFirst();
        assertThat(parDefaut.texte()).isEqualTo("Le planning respecte toutes les règles impératives.");
    }

    /**
     * A hard rule switched off that is not a legal one — the seats to fill, a
     * forced assignment — is just as absent from a zero hard score: the
     * verdict must not vouch for it, nor say so in green.
     */
    @Test
    void anyHardRuleSwitchedOffKeepsTheVerdictFromVouchingForEverything() {
        ScoreSentence verdict = ScoreReading.read(
                        clean(), Optional.empty(), Set.of("posteDoitEtrePourvu", "affectationForcee"))
                .getFirst();

        assertThat(verdict.texte())
                .doesNotContain("toutes les règles impératives")
                .contains("2 règles impératives sont désactivées pour cette édition");
        assertThat(verdict.niveau()).isEqualTo(ReadingTone.ATTENTION);

        ScoreSentence both = ScoreReading.read(
                        clean(), Optional.empty(), Set.of("reposQuotidienMinimal", "posteDoitEtrePourvu"))
                .getFirst();
        assertThat(both.texte())
                .contains("1 règle légale est désactivée")
                .contains("1 autre règle impérative est désactivée");

        ScoreSentence soft = ScoreReading.read(clean(), Optional.empty(), Set.of("equilibrerCharge"))
                .getFirst();
        assertThat(soft.niveau())
                .as("a medium rule off changes nothing to the verdict")
                .isEqualTo(ReadingTone.OK);
    }

    /** A floor label reduced to punctuation leaves the reason out rather than failing the whole reading. */
    @Test
    void aFloorLabelWithNothingLeftGivesNoReason() {
        assertThat(ScoreReading.reason(".")).isEmpty();
        assertThat(ScoreReading.reason(" . ")).isEmpty();
        assertThat(ScoreReading.reason(" : la règle pénalise tout.")).isEqualTo(": la règle pénalise tout");
        assertThat(ScoreReading.reason("Aucun souhait déclaré : la règle pénalise tout."))
                .isEqualTo("aucun souhait déclaré");

        PlanningDiagnostic diagnostic = diagnostic(
                "0hard/-10medium/0soft",
                0,
                0,
                List.of(floor("souhaitsIncompatibles", "0hard/-10medium/0soft", 10, ".")),
                List.of(),
                -10,
                0,
                List.of());
        assertThat(phrase(ScoreReading.read(diagnostic), ReadingSubject.PLANCHER)
                        .orElseThrow()
                        .texte())
                .endsWith("«\u00a0Souhaits non couverts\u00a0».");
    }

    /** The exceptions sentence does not depend on the order the analysis met the rules and the ids in. */
    @Test
    void theExceptionSentenceIsTheSameWhateverTheOrderOfTheContributions() {
        ContributionAdHoc forcee =
                new ContributionAdHoc("C9", "AFFECTATION_FORCEE", null, 1, List.of("affectationForcee"));
        ContributionAdHoc indispo =
                new ContributionAdHoc("C2", "INDISPONIBILITE_FORCEE", null, 1, List.of("indisponibiliteForcee"));

        ScoreSentence one = phrase(
                        ScoreReading.read(withAdjustments(List.of(forcee, indispo))), ReadingSubject.AJUSTEMENTS)
                .orElseThrow();
        ScoreSentence other = phrase(
                        ScoreReading.read(withAdjustments(List.of(indispo, forcee))), ReadingSubject.AJUSTEMENTS)
                .orElseThrow();

        assertThat(one).isEqualTo(other);
        assertThat(one.texte()).contains("«\u00a0Affectations forcées\u00a0» et «\u00a0Indisponibilités posées");
        assertThat(one.liens().getFirst().parametres()).containsEntry("ids", "C2,C9");
    }

    private static PlanningDiagnostic withAdjustments(List<ContributionAdHoc> adHoc) {
        return diagnostic("-2hard/0medium/0soft", -2, 0, List.of(), adHoc, 0, 0, List.of());
    }

    @Test
    void theComparisonSaysWhatMovedAgainstThePreviousPlan() {
        PlanningDiagnostic avant = busy();
        PlanningDiagnostic apres = diagnostic(
                "0hard/-5400medium/-80soft",
                0,
                0,
                List.of(
                        rule("posteDoitEtrePourvu", "0hard/0medium/0soft", 0),
                        rule("equilibrerCharge", "0hard/-100medium/0soft", 10),
                        rule("eviterChangementEmplacementEloigne", "0hard/-300medium/0soft", 6)),
                List.of(),
                -5000,
                0,
                List.of());

        ScoreSentence comparaison = ScoreReading.compare(apres, avant).orElseThrow();

        assertThat(comparaison.texte())
                .isEqualTo("Par rapport au plan précédent : 3 places vides en moins, 2 règles impératives en défaut "
                        + "en moins, « Équilibre de la charge » en progrès (20 cas en moins) et "
                        + "« Changements d'emplacement éloignés » en recul (2 cas en plus).");
        assertThat(comparaison.niveau()).isEqualTo(ReadingTone.OK);
    }

    @Test
    void theReadingNeverExceedsSixSentencesEvenWithTheComparison() {
        List<ScoreSentence> lecture = ScoreReading.read(busy(), Optional.of(clean()), Set.of());

        assertThat(lecture).hasSizeLessThanOrEqualTo(ScoreReading.MAX_SENTENCES);
        assertThat(lecture.getLast().sujet()).isEqualTo(ReadingSubject.COMPARAISON);
        assertThat(lecture).extracting(ScoreSentence::sujet).doesNotContain(ReadingSubject.CONFORT);
    }

    @Test
    void noDiagnosticReadsAsNothing() {
        assertThat(ScoreReading.read(null)).isEmpty();
    }

    @Test
    void sharesRoundDownAndSaySoBelowOnePercent() {
        assertThat(ScoreReading.share(1, 300)).isEqualTo("moins de 1 %");
        assertThat(ScoreReading.share(2, 3)).isEqualTo("66 %");
        assertThat(ScoreReading.number(1234567)).isEqualTo("1 234 567");
    }
}
