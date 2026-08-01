package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;

class FeasibilityAnalyzerTest {

    private final FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer();

    @Test
    void feasibleWhenEnoughCompetentAvailableAnimateurs() {
        Stand stand = stand("stand-1", 2, TypologieJeu.STRATEGIE);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", TypologieJeu.STRATEGIE);
        Animateur a2 = animateur("a2", TypologieJeu.STRATEGIE);
        Animateur a3 = animateur("a3", TypologieJeu.STRATEGIE);

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2, a3), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.creneauLePlusCritique()).isNull();
    }

    @Test
    void infeasibleWhenNotEnoughCompetentAnimateurs() {
        Stand stand = stand("stand-1", 5, TypologieJeu.STRATEGIE);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", TypologieJeu.STRATEGIE);
        Animateur a2 = animateur("a2", TypologieJeu.STRATEGIE);

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isEqualTo(3);
        assertThat(report.message()).contains("3 animateurs");
    }

    @Test
    void reportsTheCreneauWithTheWorstShortfall() {
        Stand stand = stand("stand-1", 3, TypologieJeu.STRATEGIE);
        LocalDate samedi = LocalDate.of(2026, 8, 1);
        LocalDate dimanche = LocalDate.of(2026, 8, 2);
        Creneau creneauSamedi = creneau(1, samedi);
        Creneau creneauDimanche = creneau(2, dimanche);

        Animateur a1 = animateur("a1", TypologieJeu.STRATEGIE);
        Animateur a2 = animateur("a2", TypologieJeu.STRATEGIE);
        Animateur a3 = animateur("a3", TypologieJeu.STRATEGIE);
        a1.setJoursIndisponibles(Set.of(samedi));
        a2.setJoursIndisponibles(Set.of(samedi));

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2, a3), List.of(stand),
                List.of(creneauSamedi, creneauDimanche));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isEqualTo(2);
        assertThat(report.creneauLePlusCritique().date()).isEqualTo(samedi);
    }

    @Test
    void animateurWithoutMatchingCompetenceDoesNotCountTowardsCapacity() {
        Stand stand = stand("stand-1", 1, TypologieJeu.STRATEGIE);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur competent = animateur("a1", TypologieJeu.STRATEGIE);
        Animateur incompetent = animateur("a2", TypologieJeu.ADRESSE);

        FeasibilityReport avecUnCompetent = analyzer.analyser(List.of(competent, incompetent), List.of(stand),
                List.of(creneau));
        assertThat(avecUnCompetent.feasible()).isTrue();

        FeasibilityReport sansCompetent = analyzer.analyser(List.of(incompetent), List.of(stand), List.of(creneau));
        assertThat(sansCompetent.feasible()).isFalse();
        assertThat(sansCompetent.manqueAnimateurs()).isEqualTo(1);
    }

    @Test
    void emptyInputsAreFeasibleByDefault() {
        FeasibilityReport report = analyzer.analyser(List.of(), List.of(), List.of());

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
    }

    private static Stand stand(String id, int effectifMax, TypologieJeu typologie) {
        return new Stand(id, id, Set.of(typologie), 1, effectifMax, false);
    }

    private static Creneau creneau(long id, LocalDate date) {
        return new Creneau(id, 1, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
    }

    private static Animateur animateur(String id, TypologieJeu typologie) {
        Animateur animateur = new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
        animateur.setCompetences(java.util.Map.of(typologie, NiveauCompetence.AUTONOME));
        return animateur;
    }
}
