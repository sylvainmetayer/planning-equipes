package dev.sylvain.planning.service.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import dev.sylvain.planning.service.diagnostic.BlockerPlaybook.ActionType;
import dev.sylvain.planning.service.diagnostic.BlockerPlaybook.Context;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import dev.sylvain.planning.solver.ConstraintCatalog.Lever;
import dev.sylvain.planning.solver.ConstraintFloorRules;
import dev.sylvain.planning.solver.ConstraintParameters;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The playbook is only worth something if it is complete and if its buttons
 * lead somewhere: a cause type or a rule added without an action, or an action
 * pointing at a route the frontend no longer has, fails here — the mechanism
 * {@code ConstraintFloorRulesTest} holds on the floors.
 */
class BlockerPlaybookTest {

    private static final Path ROUTES = Path.of("src/main/webui/src/app/app.routes.ts");
    private static final Pattern PATH = Pattern.compile("path:\\s*'([^']*)'");

    private static final Context SHORTFALL = new Context(
            42L, LocalDate.of(2026, 7, 12), List.of("S1"), List.of("STRATEGIE"), List.of(), List.of(), false);

    @Test
    void everyCauseTypeHasAtLeastOneAction() {
        assertThat(TypeCauseInfaisabilite.values())
                .allSatisfy(type -> assertThat(BlockerPlaybook.forCause(type.name(), Context.NONE))
                        .as(type.name())
                        .isNotEmpty());
        assertThat(BlockerPlaybook.causeTypes())
                .as("no line for a cause type that does not exist")
                .isSubsetOf(Arrays.stream(TypeCauseInfaisabilite.values())
                        .map(Enum::name)
                        .toList());
    }

    @Test
    void everyRuleOfTheCatalogueHasAtLeastOneAction() {
        assertThat(ConstraintCatalog.definitions())
                .allSatisfy(definition -> assertThat(BlockerPlaybook.forRule(definition, null))
                        .as(definition.name())
                        .isNotEmpty());
    }

    @Test
    void theExplanationOfARuleIsItsRemediationWordForWord() {
        assertThat(ConstraintCatalog.definitions()).allSatisfy(definition -> {
            assertThat(BlockerPlaybook.forRule(definition, null).getFirst().explication())
                    .as(definition.name())
                    .isEqualTo(definition.remediation());
            assertThat(BlockerPlaybook.forRule(definition, "/animateurs")
                            .getFirst()
                            .explication())
                    .as(definition.name() + " as a floor")
                    .isEqualTo(definition.remediation());
        });
    }

    @Test
    void aShortfallFirstOpensTheBenchOnThatTimeslot() {
        List<ActionType> actions = BlockerPlaybook.forCause("CRENEAU_SOUS_EFFECTIF", SHORTFALL);

        assertThat(actions).hasSizeBetween(1, 4);
        ActionType first = actions.getFirst();
        assertThat(first.code()).isEqualTo(BlockerPlaybook.CODE_BENCH);
        assertThat(first.libelle()).isEqualTo("Qui peut tenir ce siège ?");
        assertThat(first.route()).isEqualTo("/journee");
        assertThat(first.parametres())
                .containsEntry("creneau", "42")
                .containsEntry("stand", "S1")
                .doesNotContainKey("onglet");
    }

    @Test
    void aWeighedRuleEndsOnLoweringItsImportanceOnItsLine() {
        List<ConstraintDefinition> weighed = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() != ConstraintCatalog.Niveau.HARD)
                .toList();

        assertThat(weighed)
                .isNotEmpty()
                .allSatisfy(definition -> assertThat(
                                BlockerPlaybook.forRule(definition, null).getLast())
                        .as(definition.name())
                        .satisfies(action -> {
                            assertThat(action.code()).isEqualTo(BlockerPlaybook.CODE_LOWER_WEIGHT);
                            assertThat(action.libelle()).isEqualTo("Baisser l'importance");
                            assertThat(action.route()).isEqualTo("/regles");
                            assertThat(action.parametres())
                                    .containsEntry("onglet", "qualite")
                                    .containsEntry("regle", definition.name());
                        }));
    }

    /**
     * The Problèmes screen renders what the catalogue says: a medium or soft
     * rule whose only gesture is its weight is a card telling the organiser to
     * stop caring, never what to fix. Every one of them names a lever that
     * changes the plan or the referential, and that lever comes first.
     */
    @Test
    void noWeighedRuleOffersItsWeightAsItsOnlyGesture() {
        assertThat(ConstraintCatalog.definitions())
                .filteredOn(definition -> definition.niveau() != ConstraintCatalog.Niveau.HARD)
                .allSatisfy(definition -> {
                    assertThat(definition.levers()).as(definition.name()).isNotEmpty();
                    List<ActionType> actions = BlockerPlaybook.forRule(definition, null, SHORTFALL);
                    assertThat(actions.getFirst().code())
                            .as(definition.name())
                            .isNotEqualTo(BlockerPlaybook.CODE_LOWER_WEIGHT);
                    assertThat(actions)
                            .as(definition.name())
                            .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_LOWER_WEIGHT))
                            .hasSize(1);
                });
    }

    /** « Régler le plafond » and « Régler les seuils » open a line that has something to set. */
    @Test
    void aCapOrThresholdLeverOnlyOnARuleThatReadsASetting() {
        assertThat(ConstraintCatalog.definitions())
                .filteredOn(definition -> definition.levers().contains(Lever.CAP)
                        || definition.levers().contains(Lever.THRESHOLD))
                .isNotEmpty()
                .allSatisfy(definition -> assertThat(ConstraintParameters.declarations())
                        .as(definition.name())
                        .containsKey(definition.name()));
    }

    /** The missing referent: the seat first, then the competence, then the stand's fiche, the weight last. */
    @Test
    void theMissingReferentOffersTheSeatTheSkillAndTheStandBeforeTheWeight() {
        ConstraintDefinition referent = ConstraintCatalog.PAR_NOM.get("standComplexeAvecReferent");

        assertThat(BlockerPlaybook.forRule(referent, null, SHORTFALL))
                .extracting(ActionType::code)
                .containsExactly(
                        BlockerPlaybook.CODE_BENCH,
                        BlockerPlaybook.CODE_ADD_SKILL,
                        BlockerPlaybook.CODE_STAND_PROFILE,
                        BlockerPlaybook.CODE_LOWER_WEIGHT);
        assertThat(BlockerPlaybook.forRule(referent, null, SHORTFALL))
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_STAND_PROFILE))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.route()).isEqualTo("/stands");
                    assertThat(action.parametres()).containsEntry("edit", "S1");
                });
    }

    /** A hard rule is held, not weighed: its rule link opens the legal tab, and nothing lowers it. */
    @Test
    void aHardRuleNeverOffersItsWeight() {
        ConstraintDefinition dur = ConstraintCatalog.PAR_NOM.get("maxJoursConsecutifsTravaillesDur");

        assertThat(BlockerPlaybook.forRule(dur, null))
                .extracting(ActionType::code)
                .doesNotContain(BlockerPlaybook.CODE_LOWER_WEIGHT);
        assertThat(BlockerPlaybook.forRule(dur, null))
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_SEE_RULE))
                .singleElement()
                .satisfies(action -> assertThat(action.parametres()).containsEntry("onglet", "legal"));
    }

    @Test
    void aFloorFirstProposesToEnterTheMissingData() {
        ConstraintDefinition souhaits = ConstraintCatalog.PAR_NOM.get("souhaitsIncompatibles");

        List<ActionType> actions = BlockerPlaybook.forRule(souhaits, "/animateurs");

        assertThat(actions.getFirst().code()).isEqualTo(BlockerPlaybook.CODE_ENTER_MISSING_DATA);
        assertThat(actions.getFirst().route()).isEqualTo("/animateurs");
        assertThat(actions).extracting(ActionType::code).contains(BlockerPlaybook.CODE_LOWER_WEIGHT);
    }

    @Test
    void anAggregateRuleOpensItsScreensBare() {
        ConstraintDefinition charge = ConstraintCatalog.PAR_NOM.get("equilibrerCharge");

        assertThat(BlockerPlaybook.forRule(charge, null))
                .allSatisfy(action -> assertThat(action.parametres()).doesNotContainKeys("creneau", "stand", "ids"));
    }

    /** Every gesture of a shortfall lands on its day, and on its stand when it names only one. */
    @Test
    void aShortfallOpensEachScreenOnItsDayAndStand() {
        List<ActionType> actions = BlockerPlaybook.forCause("CRENEAU_SOUS_EFFECTIF", SHORTFALL);

        assertThat(actions)
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_LOWER_STAFFING))
                .singleElement()
                .satisfies(action -> assertThat(action.parametres())
                        .containsEntry("vue", "saisie")
                        .containsEntry("date", "2026-07-12")
                        .containsEntry("stand", "S1"));
        assertThat(actions)
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_REVIEW_DAYS_OFF))
                .singleElement()
                .satisfies(action -> assertThat(action.parametres())
                        .containsEntry("axe", "personne")
                        .containsEntry("date", "2026-07-12"));
    }

    /** A shortfall over several stands names no stand: the grid opens on the day, not on a guess. */
    @Test
    void aShortfallOverSeveralStandsPreselectsTheDayOnly() {
        Context several = new Context(
                42L, LocalDate.of(2026, 7, 12), List.of("S1", "S2"), List.of(), List.of(), List.of(), false);

        assertThat(BlockerPlaybook.forCause("CRENEAU_SOUS_EFFECTIF", several))
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_LOWER_STAFFING))
                .singleElement()
                .satisfies(action -> assertThat(action.parametres())
                        .containsEntry("date", "2026-07-12")
                        .doesNotContainKey("stand"));
    }

    /** After a solve, a rule's actions open on the day and stand of its first breach. */
    @Test
    void aRuleInDefaultOpensItsScreensOnItsFirstBreach() {
        ConstraintDefinition chevauchement = ConstraintCatalog.PAR_NOM.get("pasDeChevauchementHoraire");
        ConstraintDefinition pourvu = ConstraintCatalog.PAR_NOM.get("posteDoitEtrePourvu");

        assertThat(BlockerPlaybook.forRule(chevauchement, null, SHORTFALL))
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_REPAIR))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.route()).isEqualTo("/journee");
                    assertThat(action.parametres())
                            .containsEntry("date", "2026-07-12")
                            .containsEntry("stand", "S1");
                });
        assertThat(BlockerPlaybook.forRule(pourvu, null, SHORTFALL))
                .extracting(ActionType::code, action -> action.parametres().get("creneau"))
                .contains(org.assertj.core.groups.Tuple.tuple(BlockerPlaybook.CODE_BENCH, "42"));
        assertThat(BlockerPlaybook.forRule(pourvu, null, SHORTFALL))
                .filteredOn(action -> action.code().equals(BlockerPlaybook.CODE_ADD_SKILL))
                .singleElement()
                .satisfies(action -> assertThat(action.parametres()).containsEntry("typologies", "STRATEGIE"));
    }

    @Test
    void aProblemOnAStartedDayOffersNoGestureThatWouldChangeIt() {
        Context frozen =
                new Context(42L, LocalDate.of(2026, 7, 12), List.of("S1"), List.of(), List.of(), List.of(), true);

        assertThat(BlockerPlaybook.forCause("CRENEAU_SOUS_EFFECTIF", frozen))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.code()).isEqualTo(BlockerPlaybook.CODE_FROZEN_DAY);
                    assertThat(action.parametres()).containsEntry("date", "2026-07-12");
                });
    }

    /** After a solve too: a rule whose every breach is on a started timeslot offers only « Voir la journée ». */
    @Test
    void aRuleBreachedOnlyInTheFrozenPastOffersNoGestureThatWouldChangeIt() {
        assertThat(ConstraintCatalog.definitions())
                .allSatisfy(definition -> assertThat(BlockerPlaybook.forRule(
                                definition,
                                "/animateurs",
                                new Context(null, null, List.of(), List.of(), List.of(), List.of(), true)))
                        .as(definition.name())
                        .singleElement()
                        .satisfies(action -> {
                            assertThat(action.code()).isEqualTo(BlockerPlaybook.CODE_FROZEN_DAY);
                            assertThat(action.explication()).contains("figé");
                        }));
    }

    @Test
    void noActionEverProposesToSwitchARuleOff() {
        assertThat(BlockerPlaybook.everyAction()).allSatisfy(action -> {
            assertThat(action.code()).doesNotContain("DESACTIV");
            assertThat(action.libelle().toLowerCase()).doesNotContain("désactiv");
            assertThat(action.parametres()).doesNotContainKey("actif");
        });
    }

    @Test
    void everyRouteOfThePlaybookExistsInTheAngularRoutes() throws IOException {
        Set<String> routes = new LinkedHashSet<>();
        Matcher path = PATH.matcher(Files.readString(ROUTES));
        while (path.find()) {
            routes.add("/" + path.group(1));
        }

        assertThat(routes).as("the scan reads the routes").contains("/regles", "/diagnostic");
        assertThat(BlockerPlaybook.everyAction())
                .isNotEmpty()
                .allSatisfy(action -> assertThat(routes).as(action.code()).contains(action.route()));
        assertThat(ConstraintCatalog.definitions())
                .allSatisfy(definition -> assertThat(BlockerPlaybook.forRule(definition, null))
                        .allSatisfy(
                                action -> assertThat(routes).as(action.code()).contains(action.route())));
        // The first action of a floor is the link of its missing data: that
        // link has to lead somewhere too.
        assertThat(ConstraintFloorRules.MissingData.values())
                .allSatisfy(missing -> assertThat(routes).as(missing.name()).contains(missing.lien()));
    }

    @Test
    void theOrderIsFixedAndReadsTheSameEveryTime() {
        assertThat(BlockerPlaybook.forCause("CRENEAU_SOUS_EFFECTIF", SHORTFALL))
                .isEqualTo(BlockerPlaybook.forCause("CRENEAU_SOUS_EFFECTIF", SHORTFALL));
        assertThat(BlockerPlaybook.forCause("INCONNU", SHORTFALL)).isEmpty();
    }
}
