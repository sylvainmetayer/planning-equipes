package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.FeasibilityAnalyzer.SeveriteInfaisabilite;
import dev.sylvain.planning.service.FeasibilityAnalyzer.TypeCauseInfaisabilite;

class FeasibilityAnalyzerTest {

    private final FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer();

    @Test
    void feasibleWhenEnoughCompetentAvailableAnimateurs() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");
        Animateur a3 = animateur("a3", "STRATEGIE");

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2, a3), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.causes()).isEmpty();
        assertThat(report.totalCauses()).isZero();
    }

    @Test
    void infeasibleWhenNotEnoughCompetentAnimateurs() {
        Stand stand = stand("stand-1", 5, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isEqualTo(3);
        assertThat(report.message()).contains("3 animateurs");
        assertThat(report.totalCauses()).isEqualTo(1);

        CauseInfaisabilite cause = report.causes().getFirst();
        assertThat(cause.type()).isEqualTo(TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF);
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.ELEVE);
        assertThat(cause.creneauId()).isEqualTo(1L);
        assertThat(cause.date()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(cause.heureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(cause.heureFin()).isEqualTo(LocalTime.of(12, 0));
        assertThat(cause.standIds()).containsExactly("stand-1");
        assertThat(cause.demande()).isEqualTo(5);
        assertThat(cause.capacite()).isEqualTo(2);
        assertThat(cause.manque()).isEqualTo(3);
        assertThat(cause.message()).contains("2026-08-01 10:00-12:00").contains("il manque 3 animateurs");
    }

    @Test
    void reportsEveryCreneauInShortfallRankedByGravity() {
        Stand stand = stand("stand-1", 3, "STRATEGIE");
        LocalDate samedi = LocalDate.of(2026, 8, 1);
        LocalDate dimanche = LocalDate.of(2026, 8, 2);
        Creneau creneauSamedi = creneau(1, samedi);
        Creneau creneauDimanche = creneau(2, dimanche);

        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");
        a1.setJoursIndisponibles(Set.of(samedi));
        // Saturday: 1 animateur for 3 seats (2 missing); Sunday: 2 animateurs
        // for 3 seats (1 missing). Both timeslots must show up, the most
        // critical one first.

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2), List.of(stand),
                List.of(creneauDimanche, creneauSamedi));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isEqualTo(2);
        assertThat(report.totalCauses()).isEqualTo(2);
        assertThat(report.causes()).hasSize(2);
        assertThat(report.causes()).extracting(CauseInfaisabilite::date).containsExactly(samedi, dimanche);
        assertThat(report.causes()).extracting(CauseInfaisabilite::manque).containsExactly(2, 1);
    }

    @Test
    void severiteCritiqueQuandAucunePlaceNePeutEtreCouverte() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        LocalDate samedi = LocalDate.of(2026, 8, 1);
        LocalDate dimanche = LocalDate.of(2026, 8, 2);
        Creneau creneauSamedi = creneau(1, samedi);
        Creneau creneauDimanche = creneau(2, dimanche);

        Animateur a1 = animateur("a1", "STRATEGIE");
        a1.setJoursIndisponibles(Set.of(samedi));

        FeasibilityReport report = analyzer.analyser(List.of(a1), List.of(stand),
                List.of(creneauSamedi, creneauDimanche));

        // Saturday: nobody, so missing (2) >= demand (2) -> CRITIQUE, and at the
        // top of the list. Sunday: one animateur for two seats -> ELEVE.
        assertThat(report.causes()).extracting(CauseInfaisabilite::severite)
                .containsExactly(SeveriteInfaisabilite.CRITIQUE, SeveriteInfaisabilite.ELEVE);
        assertThat(report.causes().getFirst().date()).isEqualTo(samedi);
        assertThat(report.causes().getFirst().capacite()).isZero();
    }

    @Test
    void standSansAucunAnimateurCompetentNEstPlusUneCause() {
        // A competence is now medium (a liking), not a coverage requirement: an
        // available animateur with no liking for "stand-2" still counts towards
        // the capacity, and the planning stays feasible in the sense of
        // FeasibilityAnalyzer.
        Stand couvert = stand("stand-1", 1, "STRATEGIE");
        Stand orphelin = stand("stand-2", 1, "ADRESSE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");

        FeasibilityReport report = analyzer.analyser(List.of(a1, a2), List.of(couvert, orphelin), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.totalCauses()).isZero();
    }

    @Test
    void laListeDesCausesEstPlafonneeMaisTotalCausesResteExhaustif() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        List<Creneau> creneaux = new java.util.ArrayList<>();
        Set<LocalDate> jours = new java.util.HashSet<>();
        for (int jour = 1; jour <= 14; jour++) {
            LocalDate date = LocalDate.of(2026, 8, jour);
            creneaux.add(creneau(jour, date));
            jours.add(date);
        }
        // Away for the whole festival: no day counts towards the capacity.
        Animateur absent = animateur("a1", "STRATEGIE");
        absent.setJoursIndisponibles(jours);

        FeasibilityReport report = analyzer.analyser(List.of(absent), List.of(stand), creneaux);

        assertThat(report.totalCauses()).isEqualTo(14);
        assertThat(report.causes()).hasSize(10);
        assertThat(report.message()).contains("14 causes bloquantes");
    }

    @Test
    void animateurWithoutMatchingCompetenceStillCountsTowardsCapacity() {
        // A competence is a medium liking now, no longer a coverage
        // requirement: an available animateur who is not "competent" for the
        // stand still counts in the raw capacity.
        Stand stand = stand("stand-1", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur incompetent = animateur("a2", "ADRESSE");

        FeasibilityReport report = analyzer.analyser(List.of(incompetent), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
    }

    @Test
    void standFermeSurUnCreneauNeComptePasDansLaDemande() {
        // The stand requires 3 seats but is closed (an indisponibilite covering
        // the whole timeslot): no seat is generated, hence no demand (see
        // PlanningService.construirePostes).
        Stand stand = stand("stand-1", 3, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        stand.setIndisponibilites(List.of(
                new IndisponibiliteStand(null, creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin(), null)));

        FeasibilityReport report = analyzer.analyser(List.of(animateur("a1", "STRATEGIE")),
                List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.causes()).isEmpty();
    }

    @Test
    void laDemandeSuitEffectifMinPasEffectifMax() {
        // effectifMin = 2 (two seats generated), effectifMax = 4: two animateurs
        // are enough. Counting effectifMax would announce 2 missing.
        Stand stand = new Stand("stand-1", "stand-1", Set.of("STRATEGIE"), 2, 4, false);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyser(
                List.of(animateur("a1", "STRATEGIE"), animateur("a2", "STRATEGIE")),
                List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
    }

    @Test
    void emptyInputsAreFeasibleByDefault() {
        FeasibilityReport report = analyzer.analyser(List.of(), List.of(), List.of());

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.causes()).isEmpty();
        assertThat(report.totalCauses()).isZero();
    }

    /**
     * {@code effectifMin} drives the demand: it is the number of seats
     * {@code PlanningService.construirePostes} actually generates. {@code
     * effectifMax} is deliberately set higher so a regression back to counting
     * it would change the expected shortfalls.
     */
    private static Stand stand(String id, int effectifMin, String typologie) {
        return new Stand(id, id, Set.of(typologie), effectifMin, effectifMin + 2, false);
    }

    private static Creneau creneau(long id, LocalDate date) {
        return new Creneau(id, 1, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
    }

    private static Animateur animateur(String id, String typologie) {
        Animateur animateur = new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
        animateur.setCompetences(java.util.Map.of(typologie, NiveauCompetence.AUTONOME));
        return animateur;
    }
}
