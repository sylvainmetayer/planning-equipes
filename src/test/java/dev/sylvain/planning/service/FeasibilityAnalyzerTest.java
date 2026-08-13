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
        // Le samedi : 1 animateur pour 3 places (manque 2) ; le dimanche : 2
        // animateurs pour 3 places (manque 1). Les deux créneaux doivent
        // apparaître, le plus critique en premier.

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

        // Samedi : personne, donc manque (2) >= demande (2) -> CRITIQUE, et en
        // tête de liste. Dimanche : un animateur sur deux places -> ELEVE.
        assertThat(report.causes()).extracting(CauseInfaisabilite::severite)
                .containsExactly(SeveriteInfaisabilite.CRITIQUE, SeveriteInfaisabilite.ELEVE);
        assertThat(report.causes().getFirst().date()).isEqualTo(samedi);
        assertThat(report.causes().getFirst().capacite()).isZero();
    }

    @Test
    void standSansAucunAnimateurCompetentNEstPlusUneCause() {
        // Compétence désormais medium (appréciation), pas une exigence de
        // couverture : un animateur disponible mais sans appréciation sur
        // « stand-2 » compte quand même pour la capacité, et le planning reste
        // faisable au sens de FeasibilityAnalyzer.
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
        // Absent tout le festival : aucun jour ne compte dans la capacité.
        Animateur absent = animateur("a1", "STRATEGIE");
        absent.setJoursIndisponibles(jours);

        FeasibilityReport report = analyzer.analyser(List.of(absent), List.of(stand), creneaux);

        assertThat(report.totalCauses()).isEqualTo(14);
        assertThat(report.causes()).hasSize(10);
        assertThat(report.message()).contains("14 causes bloquantes");
    }

    @Test
    void animateurWithoutMatchingCompetenceStillCountsTowardsCapacity() {
        // Compétence = appréciation medium désormais, plus une exigence de
        // couverture : un animateur disponible mais non "compétent" pour le
        // stand compte quand même dans la capacité brute.
        Stand stand = stand("stand-1", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur incompetent = animateur("a2", "ADRESSE");

        FeasibilityReport report = analyzer.analyser(List.of(incompetent), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
    }

    @Test
    void standFermeSurUnCreneauNeComptePasDansLaDemande() {
        // Le stand exige 3 places mais est fermé (indisponibilité couvrant tout
        // le créneau) : aucun poste n'est généré, donc aucune demande (cf.
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
        // effectifMin = 2 (deux places générées), effectifMax = 4 : deux
        // animateurs suffisent. Compter effectifMax annoncerait un manque de 2.
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
