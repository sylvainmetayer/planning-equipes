package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.RapportConformite.Personne;
import dev.sylvain.planning.service.diagnostic.ScoreDirectorConstraintDiagnosticService;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The compliance report on plans built by hand and scored by the real legal rules. */
class RapportConformiteServiceTest {

    private static final LocalDate LUNDI = LocalDate.of(2026, 8, 17);

    private final RapportConformiteService service = new RapportConformiteService(
            null, new PlanningHoursService(), new ScoreDirectorConstraintDiagnosticService(solverFactory()));

    private final Stand stand = new Stand("S1", "Buvette", Set.of(), 1, 1, false);
    private long creneauId = 1;

    private PosteAffectation seat(Animateur who, LocalDate day, LocalTime from, LocalTime to) {
        Creneau creneau = new Creneau(creneauId++, 1, day, from, to);
        PosteAffectation poste = new PosteAffectation("P" + creneau.getId(), stand, creneau);
        poste.setAnimateur(who);
        return poste;
    }

    private RapportConformite report(Animateur who, List<PosteAffectation> postes) {
        PlanningEvenement plan = new PlanningEvenement(LUNDI, List.of(who), postes);
        return service.build("E1", "Edition test", Instant.parse("2026-08-10T10:00:00Z"), plan, Instant.now());
    }

    @Test
    void anAdultOverTheWeeklyCeilingIsMarkedAndCounted() {
        Animateur ada = new Animateur("A1", "Ada", "Test", LocalDate.of(1990, 1, 1), false);
        List<PosteAffectation> postes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            postes.add(seat(ada, LUNDI.plusDays(i), LocalTime.of(9, 0), LocalTime.of(19, 30)));
        }

        RapportConformite rapport = report(ada, postes);

        Personne ligne = rapport.personnes().get(0);
        assertThat(ligne.mineur()).isFalse();
        assertThat(ligne.plafondHebdomadaire()).isEqualTo(48.0);
        assertThat(ligne.heuresTotal()).isGreaterThan(48.0);
        assertThat(ligne.semainesEnDepassement()).containsExactly("2026-W34");
        assertThat(rapport.synthese().semainesEnDepassement()).isEqualTo(1);
        assertThat(rapport.regles()).anySatisfy(r -> {
            assertThat(r.nom()).isEqualTo("dureeHebdomadaireMax");
            assertThat(r.ecarts()).isGreaterThanOrEqualTo(1);
        });
        assertThat(ligne.ecartsDetail())
                .extracting(RapportConformite.Breach::regle)
                .contains("dureeHebdomadaireMax");
        assertThat(ligne.reposQuotidienMinHeures()).isEqualTo(13.5);
    }

    @Test
    void aMinorIsHeldToTheMinorsCeilingAndNamedForTheNight() {
        Animateur bob = new Animateur("B1", "Bob", "Test", LocalDate.of(2011, 2, 2), false);
        List<PosteAffectation> postes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            postes.add(seat(bob, LUNDI.plusDays(i), LocalTime.of(9, 0), LocalTime.of(16, 30)));
        }
        postes.add(seat(bob, LUNDI.plusDays(6), LocalTime.of(21, 0), LocalTime.of(23, 30)));

        RapportConformite rapport = report(bob, postes);

        Personne ligne = rapport.personnes().get(0);
        assertThat(ligne.mineur()).isTrue();
        assertThat(ligne.plafondHebdomadaire()).isEqualTo(35.0);
        assertThat(rapport.synthese().mineurs()).isEqualTo(1);
        assertThat(ligne.semainesEnDepassement()).isNotEmpty();
        assertThat(ligne.ecartsDetail())
                .extracting(RapportConformite.Breach::regle)
                .contains("travailDeNuitInterditPourMineur");
        assertThat(ligne.heuresNuit()).isPositive();
    }

    @Test
    void aPlanWithinTheCeilingsReportsNoBreach() {
        Animateur ada = new Animateur("A1", "Ada", "Test", LocalDate.of(1990, 1, 1), false);
        RapportConformite rapport = report(
                ada,
                List.of(
                        seat(ada, LUNDI, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        seat(ada, LUNDI.plusDays(3), LocalTime.of(9, 0), LocalTime.of(13, 0))));

        assertThat(rapport.synthese().ecarts()).isZero();
        assertThat(rapport.personnes().get(0).ecartsDetail()).isEmpty();
        assertThat(rapport.personnes().get(0).semainesEnDepassement()).isEmpty();
        assertThat(rapport.regles())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.ecarts()).isZero());
        assertThat(rapport.plafonds().ecartsAuxDefauts()).isEmpty();
    }

    @Test
    void aCeilingTheEditionChangedIsPrintedWithItsDefault() {
        Animateur ada = new Animateur("A1", "Ada", "Test", LocalDate.of(1990, 1, 1), false);
        PlanningEvenement plan = new PlanningEvenement(
                LUNDI, List.of(ada), List.of(seat(ada, LUNDI, LocalTime.of(9, 0), LocalTime.of(12, 0))));
        plan.parametresLegaux().setDureeHebdomadaireMaxMinutes(44 * 60);

        RapportConformite rapport = service.build("E1", "Edition test", Instant.now(), plan, Instant.now());

        assertThat(rapport.plafonds().hebdomadaireMajeurHeures()).isEqualTo(44.0);
        assertThat(rapport.plafonds().ecartsAuxDefauts())
                .singleElement()
                .asString()
                .contains("44.0 h au lieu de 48.0 h");
    }

    @Test
    void theShortestRestIsMeasuredAcrossMidnightAndIgnoresASingleDay() {
        Animateur ada = new Animateur("A1", "Ada", "Test", LocalDate.of(1990, 1, 1), false);
        assertThat(RapportConformiteService.shortestRestHours(
                        List.of(seat(ada, LUNDI, LocalTime.of(9, 0), LocalTime.of(12, 0)))))
                .isNull();
        // 20:00 to 02:00 ends at 26:00 of the first day; the next day starts at 10:00, that is 34:00.
        assertThat(RapportConformiteService.shortestRestHours(List.of(
                        seat(ada, LUNDI, LocalTime.of(20, 0), LocalTime.of(2, 0)),
                        seat(ada, LUNDI.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0)))))
                .isEqualTo(8.0);
    }

    @Test
    void theCsvCarriesTheSameRowsAsTheReport() {
        Animateur ada = new Animateur("A1", "Ada", "Test", LocalDate.of(1990, 1, 1), false);
        RapportConformite rapport = report(ada, List.of(seat(ada, LUNDI, LocalTime.of(9, 0), LocalTime.of(13, 0))));

        String csv = RapportConformiteCsv.write(rapport);

        assertThat(csv.lines()).hasSize(3);
        assertThat(csv).startsWith("Rapport de conformité : plan publié le ").contains("Ada Test;non;");
        assertThat(csv.lines().skip(1).findFirst().orElseThrow())
                .contains("2026-W34")
                .contains("plafond hebdomadaire");
    }

    private static SolverFactory<PlanningEvenement> solverFactory() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        return SolverFactory.create(solverConfig);
    }
}
