package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.CandidatFormation;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.PlanFormation;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.TypologieAFormer;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.FragiliteFindings;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.SeveriteFragilite;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.TypologieStaffing;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link FormationAnalyzer} on plain objects: the shortage it lists is the
 * staffing need's and the fragility report's, figure for figure, and its
 * candidates are the people one step from mastering the category.
 */
class FormationAnalyzerTest {

    private static final LocalDate JOUR_1 = LocalDate.of(2026, 7, 10);
    private static final LocalDate JOUR_2 = JOUR_1.plusDays(1);

    private static final List<TypologieItem> TYPOLOGIES = List.of(
            new TypologieItem("ESCAPE", "Escape game", false, null, null, null),
            new TypologieItem("QUIZ", "Quiz", false, null, null, null),
            new TypologieItem("JEUX", "Jeux", false, null, null, null),
            new TypologieItem("NINJA", "Ninja", true, null, null, null));

    /**
     * Three categories, each in shortage for its own reason. ESCAPE rests on
     * alice alone on the second day, since everybody else holding it is off;
     * QUIZ rests on gina alone every day, fred being a minor on adults-only
     * stands; JEUX has two specialists for three simultaneous seats — a
     * shortage only the staffing need sees.
     */
    private static final class Fixture {
        final Stand escape = stand("S-ESC", 1, false, "ESCAPE");
        final Stand quiz = stand("S-QUIZ", 1, true, "QUIZ");
        final Stand jeux = stand("S-JEUX", 3, false, "JEUX");
        final Creneau matin1 = new Creneau(1L, 1, JOUR_1, LocalTime.of(10, 0), LocalTime.of(12, 0));
        final Creneau matin2 = new Creneau(2L, 2, JOUR_2, LocalTime.of(10, 0), LocalTime.of(12, 0));

        final Animateur alice = animateur("alice", Map.of("ESCAPE", NiveauCompetence.REFERENT));
        final Animateur bob = animateur("bob", Map.of("ESCAPE", NiveauCompetence.AUTONOME));
        final Animateur carole = animateur("carole", Map.of("ESCAPE", NiveauCompetence.DEBUTANT));
        final Animateur dan =
                animateur("dan", Map.of("ESCAPE", NiveauCompetence.DEBUTANT, "NINJA", NiveauCompetence.AUTONOME));
        final Animateur eve = animateur("eve", Map.of());
        final Animateur fred = animateur("fred", Map.of("QUIZ", NiveauCompetence.AUTONOME));
        final Animateur gina = animateur("gina", Map.of("QUIZ", NiveauCompetence.AUTONOME));
        final Animateur hank = animateur("hank", Map.of("JEUX", NiveauCompetence.REFERENT));
        final Animateur ivy = animateur("ivy", Map.of("JEUX", NiveauCompetence.DEBUTANT));

        final List<Animateur> animateurs = List.of(alice, bob, carole, dan, eve, fred, gina, hank, ivy);
        final List<PosteAffectation> postes = new ArrayList<>();

        Fixture() {
            bob.getSouhaits().add("ESCAPE");
            eve.getSouhaits().add("ESCAPE");
            bob.getJoursIndisponibles().add(JOUR_2);
            carole.getJoursIndisponibles().add(JOUR_2);
            dan.getJoursIndisponibles().add(JOUR_2);
            dan.applyNinjaTypologie("NINJA");
            fred.setDateNaissance(LocalDate.of(2012, 1, 1));
            postes.addAll(seats(escape, matin1, alice));
            postes.addAll(seats(escape, matin2, alice));
            postes.addAll(seats(quiz, matin1, gina));
            postes.addAll(seats(quiz, matin2, gina));
            postes.addAll(seats(jeux, matin1, hank, ivy, null));
        }

        StaffingSummary staffing() {
            return new StaffingAnalyzer().analyze(postes, animateurs, TYPOLOGIES, 48 * 60, 30);
        }

        PlanningEvenement planning() {
            return new PlanningEvenement(JOUR_1, animateurs, postes);
        }

        PlanFormation plan() {
            return FormationAnalyzer.compute(
                    staffing(),
                    new FragiliteAnalyzer().findings(planning()),
                    true,
                    animateurs,
                    List.of(escape, quiz, jeux),
                    TYPOLOGIES,
                    List.of(JOUR_1, JOUR_2));
        }
    }

    @Test
    void aCategoryIsListedIffTheStaffingNeedOrTheFragilityReportFlagsIt() {
        PlanFormation plan = new Fixture().plan();

        assertThat(plan.typologies()).extracting(TypologieAFormer::typologie).containsExactly("JEUX", "QUIZ", "ESCAPE");
        assertThat(plan.planPersiste()).isTrue();
        assertThat(plan.aucuneCompetence()).isFalse();
    }

    @Test
    void theShortageFiguresAreTheOnesTheBesoinAndFragiliteTabsShow() {
        Fixture fixture = new Fixture();
        PlanFormation plan = fixture.plan();
        StaffingSummary staffing = fixture.staffing();
        RapportFragilite fragilite = new FragiliteAnalyzer().analyze(fixture.planning());

        for (TypologieAFormer ligne : plan.typologies()) {
            int manqueBesoin = staffing.parCompetence().parTypologie().stream()
                    .filter(besoin -> besoin.typologie().equals(ligne.typologie()))
                    .mapToInt(TypologieStaffing::manque)
                    .sum();
            List<CompetenceRare> raresFragilite = fragilite.competencesRares().stream()
                    .filter(rare -> rare.typologies().contains(ligne.typologie()))
                    .toList();
            assertThat(ligne.manque()).as(ligne.typologie()).isEqualTo(manqueBesoin);
            assertThat(ligne.competencesRares()).as(ligne.typologie()).isEqualTo(raresFragilite.size());
            assertThat(ligne.groupesSansSpecialiste()).as(ligne.typologie()).isEqualTo((int) raresFragilite.stream()
                    .filter(rare -> rare.specialistes() == 0)
                    .count());
        }
        assertThat(ligne(plan, "JEUX").manque()).isEqualTo(1);
        assertThat(ligne(plan, "QUIZ").competencesRares()).isEqualTo(2);
        assertThat(ligne(plan, "ESCAPE").competencesRares()).isEqualTo(1);
        assertThat(ligne(plan, "ESCAPE").postesIrremplacables()).isEqualTo(1);
        assertThat(ligne(plan, "ESCAPE").joursTension()).containsExactly(JOUR_2);
        assertThat(ligne(plan, "QUIZ").joursTension()).containsExactly(JOUR_1, JOUR_2);
    }

    @Test
    void onlyBeginnersAndAutonomousPeopleAreCandidatesNeverAReferentANinjaOrSomebodyWithoutIt() {
        PlanFormation plan = new Fixture().plan();

        // alice is REFERENT, dan a ninja, eve wished for it without holding it.
        assertThat(ligne(plan, "ESCAPE").candidats())
                .extracting(CandidatFormation::animateurId)
                .containsExactly("bob", "carole");
        assertThat(ligne(plan, "JEUX").candidats())
                .extracting(CandidatFormation::animateurId)
                .containsExactly("ivy");
    }

    @Test
    void aMinorIsLeftOutOfACategoryWhoseEveryStandIsAdultsOnly() {
        PlanFormation plan = new Fixture().plan();

        assertThat(ligne(plan, "QUIZ").candidats())
                .extracting(CandidatFormation::animateurId)
                .containsExactly("gina");
        assertThat(ligne(plan, "QUIZ").candidats().getFirst().joursTensionDisponibles())
                .isEqualTo(2);
    }

    @Test
    void candidatesAreRankedByTensionDaysThenWishThenLevel() {
        CompetenceRare jour1 = rare("S1", 1L, JOUR_1);
        CompetenceRare jour2 = rare("S1", 2L, JOUR_2);
        Animateur toujours = animateur("a-toujours", Map.of("ESCAPE", NiveauCompetence.DEBUTANT));
        Animateur souhaitAutonome = animateur("b-souhait-autonome", Map.of("ESCAPE", NiveauCompetence.AUTONOME));
        souhaitAutonome.getSouhaits().add("ESCAPE");
        souhaitAutonome.getJoursIndisponibles().add(JOUR_1);
        Animateur souhaitDebutant = animateur("c-souhait-debutant", Map.of("ESCAPE", NiveauCompetence.DEBUTANT));
        souhaitDebutant.getSouhaits().add("ESCAPE");
        souhaitDebutant.getJoursIndisponibles().add(JOUR_1);
        Animateur autonome = animateur("d-autonome", Map.of("ESCAPE", NiveauCompetence.AUTONOME));
        autonome.getJoursIndisponibles().add(JOUR_1);
        Animateur jamais = animateur("e-jamais", Map.of("ESCAPE", NiveauCompetence.AUTONOME));
        jamais.getSouhaits().add("ESCAPE");
        jamais.getJoursIndisponibles().addAll(List.of(JOUR_1, JOUR_2));

        PlanFormation plan = FormationAnalyzer.compute(
                null,
                new FragiliteFindings(List.of(), List.of(jour1, jour2), 2, 0, false, false),
                true,
                List.of(jamais, autonome, souhaitDebutant, souhaitAutonome, toujours),
                List.of(stand("S1", 1, false, "ESCAPE")),
                TYPOLOGIES,
                List.of(JOUR_1, JOUR_2));

        // Somebody free on none of the tension days is listed last, not hidden.
        assertThat(ligne(plan, "ESCAPE").candidats())
                .extracting(CandidatFormation::animateurId)
                .containsExactly("a-toujours", "b-souhait-autonome", "c-souhait-debutant", "d-autonome", "e-jamais");
        assertThat(ligne(plan, "ESCAPE").candidats().getLast().joursTensionDisponibles())
                .isZero();
    }

    @Test
    void withoutAPersistedPlanOnlyTheStaffingNeedSpeaks() {
        Fixture fixture = new Fixture();
        PlanFormation plan = FormationAnalyzer.compute(
                fixture.staffing(),
                new FragiliteAnalyzer().findings(new PlanningEvenement(JOUR_1, fixture.animateurs, List.of())),
                false,
                fixture.animateurs,
                List.of(fixture.escape, fixture.quiz, fixture.jeux),
                TYPOLOGIES,
                List.of(JOUR_1, JOUR_2));

        assertThat(plan.planPersiste()).isFalse();
        assertThat(plan.typologies()).extracting(TypologieAFormer::typologie).containsExactly("JEUX");
    }

    @Test
    void aCategoryWithoutCandidateIsStillListedAndTheCsvSaysRecruit() {
        CompetenceRare rare = rare("S1", 1L, JOUR_1);
        PlanFormation plan = FormationAnalyzer.compute(
                null,
                new FragiliteFindings(List.of(), List.of(rare), 1, 0, false, false),
                true,
                List.of(animateur("alice", Map.of("ESCAPE", NiveauCompetence.REFERENT))),
                List.of(stand("S1", 1, false, "ESCAPE")),
                TYPOLOGIES,
                List.of(JOUR_1));

        assertThat(ligne(plan, "ESCAPE").candidats()).isEmpty();
        assertThat(FormationAnalyzer.generateCsv(plan))
                .isEqualTo("typologie;manque besoin;competences rares;sans specialiste;postes irremplacables;"
                        + "jours en tension;animateur;niveau;souhait;jours en tension disponibles\n"
                        + "Escape game;0;1;0;0;1;aucun candidat : recrutement;;;\n");
    }

    @Test
    void theCsvHasOneLinePerCategoryAndCandidateInTheScreenOrder() {
        String csv = FormationAnalyzer.generateCsv(new Fixture().plan());

        assertThat(csv.lines().skip(1).toList())
                .containsExactly(
                        "Jeux;1;0;0;0;0;ivy IVY;DEBUTANT;non;0",
                        "Quiz;0;2;0;1;2;gina GINA;AUTONOME;non;2",
                        "Escape game;0;1;0;1;1;bob BOB;AUTONOME;oui;0",
                        "Escape game;0;1;0;1;1;carole CAROLE;DEBUTANT;non;0");
    }

    @Test
    void anEditionWhereNobodyHoldsAnyCompetenceSaysSo() {
        PlanFormation plan = FormationAnalyzer.compute(
                null,
                new FragiliteFindings(List.of(), List.of(), 0, 0, false, false),
                false,
                List.of(animateur("alice", Map.of())),
                List.of(),
                TYPOLOGIES,
                List.of());

        assertThat(plan.aucuneCompetence()).isTrue();
        assertThat(plan.typologies()).isEmpty();
    }

    /**
     * The tab is a reading, never a solve: none of the three classes behind it
     * reaches the solver or the what-if machinery.
     */
    @Test
    void nothingBehindTheTabSolvesOrSimulates() throws IOException {
        for (String source : List.of(
                "src/main/java/dev/sylvain/planning/service/analyse/FormationAnalyzer.java",
                "src/main/java/dev/sylvain/planning/service/analyse/FormationService.java",
                "src/main/java/dev/sylvain/planning/api/FormationResource.java")) {
            assertThat(Files.readString(Path.of(source)))
                    .as(source)
                    .doesNotContain("SolverManager")
                    .doesNotContain("SolverFactory")
                    .doesNotContain("SolutionManager")
                    .doesNotContain("PlanningWhatIf")
                    .doesNotContain("SolverJobService")
                    .doesNotContain("PlanningService ");
        }
    }

    private static TypologieAFormer ligne(PlanFormation plan, String typologie) {
        return plan.typologies().stream()
                .filter(ligne -> ligne.typologie().equals(typologie))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + typologie));
    }

    private static CompetenceRare rare(String standId, long creneauId, LocalDate date) {
        return new CompetenceRare(
                standId,
                standId,
                creneauId,
                date,
                1,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                List.of("ESCAPE"),
                1,
                null,
                null,
                0,
                true,
                SeveriteFragilite.ELEVEE);
    }

    private static Stand stand(String id, int effectif, boolean reserveMajeurs, String typologie) {
        return new Stand(id, id, Set.of(typologie), effectif, effectif, reserveMajeurs);
    }

    private static Animateur animateur(String id, Map<String, NiveauCompetence> competences) {
        Animateur animateur = new Animateur(id, id, id.toUpperCase(Locale.ROOT), LocalDate.of(1990, 1, 1), false);
        animateur.getCompetences().putAll(competences);
        return animateur;
    }

    private static List<PosteAffectation> seats(Stand stand, Creneau creneau, Animateur... titulaires) {
        List<PosteAffectation> postes = new ArrayList<>();
        for (int seat = 0; seat < titulaires.length; seat++) {
            PosteAffectation poste =
                    new PosteAffectation(stand.getId() + "-" + creneau.getId() + "-" + seat, stand, creneau);
            poste.setAnimateur(titulaires[seat]);
            postes.add(poste);
        }
        return postes;
    }
}
