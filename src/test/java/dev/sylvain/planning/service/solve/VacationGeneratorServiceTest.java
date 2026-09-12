package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link VacationGeneratorService#generateVacations} directly (plain
 * static method, no database) against the scenarios that motivated it: a
 * short day (nothing to split), a 14h "continu" day (journée + nocturne
 * merged, relay-split into several ≤6h vacations), and the residual internal
 * legal-pause fallback that only triggers when an admin configures a max
 * vacation length above the art. L3121-16 threshold.
 */
class VacationGeneratorServiceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    /** The meal break at its defaults: one hour, 12-14 and 19-21. */
    private final ParametresLegaux legaux = new ParametresLegaux();

    private static Creneau amplitude(LocalTime debut, LocalTime fin) {
        return new Creneau(1L, 1, JOUR, debut, fin);
    }

    @Test
    void amplitudeCourteSansFenetreRepasEntiereResteEnUneSeuleVacation() {
        Creneau amplitude = amplitude(
                LocalTime.of(10, 0), LocalTime.of(11, 30)); // 1h30, sous le plafond et ne touche aucune fenêtre repas

        List<Creneau> vacations =
                VacationGeneratorService.generateVacations(List.of(amplitude), new ParametresDecoupage(), legaux);

        assertThat(vacations).hasSize(1);
        Creneau vacation = vacations.get(0);
        assertThat(vacation.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(vacation.getHeureFin()).isEqualTo(LocalTime.of(11, 30));
        assertThat(vacation.getDate()).isEqualTo(JOUR);
    }

    @Test
    void amplitudeCourteQuiEngloutitLaFenetreRepasEstQuandMemeCoupeePourLaPause() {
        // A real bug seen in the field: a 5 h opening span (10:00-15:00), well
        // under the 6 h ceiling, never triggered an internal break — the
        // animateur alone on that single shift worked the whole lunch service
        // without eating, the "break" of the handover mechanism only applying if
        // they took a second shift the same day.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(15, 0)); // 5h, sous le plafond de 6h

        List<Creneau> vacations =
                VacationGeneratorService.generateVacations(List.of(amplitude), new ParametresDecoupage(), legaux);

        assertThat(vacations).hasSize(2);
        Creneau beforePause = vacations.get(0);
        Creneau afterPause = vacations.get(1);
        assertThat(beforePause.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(beforePause.getHeureFin()).isEqualTo(LocalTime.of(12, 30));
        assertThat(afterPause.getHeureDebut()).isEqualTo(LocalTime.of(13, 30));
        assertThat(afterPause.getHeureFin()).isEqualTo(LocalTime.of(15, 0));
        // An hour of real break between the two, aligned on the default midday
        // meal window (12:00-14:00) — which it now divides into two whole
        // slots, 12-13 and 13-14.
        assertThat(java.time.Duration.between(beforePause.getHeureFin(), afterPause.getHeureDebut())
                        .toMinutes())
                .isEqualTo(60);
    }

    @Test
    void journeeContinueDeQuatorzeHeuresEstDecoupeeEnVacationsRelaisSousLePlafond() {
        // 10:00 -> 00:00: the day and night merged, as in the continuous scenario.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        // 5 shifts, not 3: the first two handovers (10:00-14:00 then
        // 13:30-19:00) and the last one (18:30-00:00) each enclosed a whole meal
        // window — midday for the first, dinner for the last — so each one is cut
        // in two by a real break. The middle handover (13:30-19:00) only grazes
        // the start of the dinner window (19:00) without enclosing it, so it
        // stays whole.
        assertThat(vacations).hasSize(5);

        // No shift goes over the configured ceiling (6 h by default).
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }

        Creneau avantDejeuner = vacations.get(0);
        Creneau apresDejeuner = vacations.get(1);
        Creneau relaisMilieu = vacations.get(2);
        Creneau avantDiner = vacations.get(3);
        Creneau apresDiner = vacations.get(4);

        // The first shift starts at opening time, the last one ends at closing
        // time (midnight): the whole opening span is covered, meal breaks
        // aside.
        assertThat(avantDejeuner.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(apresDiner.getHeureFin()).isEqualTo(LocalTime.of(0, 0));

        // A real lunch break (one hour by default) between the first two.
        assertThat(avantDejeuner.getHeureFin()).isEqualTo(LocalTime.of(12, 0));
        assertThat(apresDejeuner.getHeureDebut()).isEqualTo(LocalTime.of(13, 0));

        // A 30 min handover overlap with the middle segment: during that window
        // two PosteAffectation exist on the same stand, so the coverage is never
        // short.
        assertThat(relaisMilieu.getHeureDebut()).isBefore(apresDejeuner.getHeureFin());
        assertThat(relaisMilieu.getHeureFin()).isEqualTo(LocalTime.of(19, 0));

        // A real dinner break (one hour) on the last segment, which overlaps the
        // middle one and entirely encloses the dinner window (19:00-21:00).
        assertThat(avantDiner.getHeureDebut()).isBefore(relaisMilieu.getHeureFin());
        assertThat(avantDiner.getHeureFin()).isEqualTo(LocalTime.of(21, 0));
        assertThat(apresDiner.getHeureDebut()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void aucuneVacationNeTombeSousLeMinimumMemeQuandLeDernierRelaisApprocheLaFenetreRepas() {
        // 10:00 -> 20:00: a 10 h opening span (continuous scenario, a day with
        // no night). The evening handover is drawn towards the 19:00-21:00 meal
        // window, which — with no guard — used to leave a remainder from 18:30 to
        // 20:00, a 90 min shift well under the configured minimum (3 h).
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(20, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        // The last segment (17:00-20:00) is *not* cut for a dinner break: the
        // 19:00-21:00 window is truncated by closing time (20:00), so less than
        // half of it is left inside the opening span — forcing a cut there would
        // only leave a remainder of a few minutes just before closing, which is
        // of no use. The first segment (10:00-14:00) does enclose the lunch
        // window entirely and is cut for a real meal break, so it falls under the
        // minimum — as expected.
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }
        assertThat(vacations.get(vacations.size() - 1).getHeureFin()).isEqualTo(LocalTime.of(20, 0));
        assertThat(vacations.get(vacations.size() - 1).getDureeMinutes())
                .isGreaterThanOrEqualTo(parametres.getDureeVacationMinMinutes());
    }

    @Test
    void aucuneVacationNeDepasseLePlafondMemeSurUneAmplitudeNonDivisibleProprement() {
        // A 22 h opening span, default ceiling of 6 h: forces several handovers.
        Creneau amplitude = amplitude(LocalTime.of(8, 0), LocalTime.of(6, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        assertThat(vacations).isNotEmpty();
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }
    }

    @Test
    void plafondAuDessusDuSeuilLegalDeclencheUnePauseInterneAuMilieuDeLaFenetreRepas() {
        // An admin configuring an 8 h ceiling (> the 6 h legal threshold) on an
        // opening span that fits exactly in that ceiling: the shift generated
        // would go over the legal threshold, so an internal break is inserted,
        // aligned on the midday meal window.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(18, 0)); // 8h
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setDureeVacationMaxMinutes(8 * 60);
        parametres.setDureeVacationCibleMinutes(8 * 60);

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        assertThat(vacations).hasSize(2);
        Creneau beforePause = vacations.get(0);
        Creneau afterPause = vacations.get(1);
        assertThat(beforePause.getHeureFin()).isEqualTo(LocalTime.of(14, 0));
        assertThat(afterPause.getHeureDebut()).isEqualTo(LocalTime.of(15, 0));
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes())
                    .isLessThanOrEqualTo(VacationGeneratorService.SEUIL_PAUSE_LEGALE_MINUTES);
        }
    }

    @Test
    void strategieReleveCouvreLaPauseInterneAuLieuDeFermerLeStand() {
        // The same scenario as above (an 8 h ceiling > the legal threshold), but
        // with the RELEVE strategy: a third, short shift must cover the break
        // exactly so the stand stays open.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(18, 0)); // 8h
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setDureeVacationMaxMinutes(8 * 60);
        parametres.setDureeVacationCibleMinutes(8 * 60);
        parametres.setStrategieCouverturePendantPause(ParametresDecoupage.PauseCoverageStrategy.RELEVE);

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        assertThat(vacations).hasSize(3);
        Creneau beforePause = vacations.get(0);
        Creneau releve = vacations.get(1);
        Creneau afterPause = vacations.get(2);
        assertThat(beforePause.getHeureFin()).isEqualTo(LocalTime.of(14, 0));
        assertThat(afterPause.getHeureDebut()).isEqualTo(LocalTime.of(15, 0));
        // The relief shift fills the hole exactly: no interruption in the
        // coverage of the stand.
        assertThat(releve.getHeureDebut()).isEqualTo(beforePause.getHeureFin());
        assertThat(releve.getHeureFin()).isEqualTo(afterPause.getHeureDebut());
    }

    @Test
    void strategieEffectifReduitCouvreLaPauseEtMarqueLaVacation() {
        // The same scenario as RELEVE — the break is covered, with no
        // interruption — but the covering shift also carries the marker that will
        // run the stand at half staffing (ProblemBuilder#buildPostes).
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(18, 0)); // 8h
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setDureeVacationMaxMinutes(8 * 60);
        parametres.setDureeVacationCibleMinutes(8 * 60);
        parametres.setStrategieCouverturePendantPause(ParametresDecoupage.PauseCoverageStrategy.EFFECTIF_REDUIT);

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        assertThat(vacations).hasSize(3);
        Creneau beforePause = vacations.get(0);
        Creneau couverture = vacations.get(1);
        Creneau afterPause = vacations.get(2);
        assertThat(couverture.getHeureDebut()).isEqualTo(beforePause.getHeureFin());
        assertThat(couverture.getHeureFin()).isEqualTo(afterPause.getHeureDebut());
        // Only the covering shift is marked: the two shifts framing it run at
        // full staffing.
        assertThat(couverture.isCouverturePause()).isTrue();
        assertThat(beforePause.isCouverturePause()).isFalse();
        assertThat(afterPause.isCouverturePause()).isFalse();
    }

    @Test
    void strategieReleveNeMarqueAucuneVacationCommeEffectifReduit() {
        // RELEVE covers the break at FULL staffing: the marker must stay false,
        // otherwise moving from FERMETURE/RELEVE to EFFECTIF_REDUIT would have no
        // observable effect.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(18, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setDureeVacationMaxMinutes(8 * 60);
        parametres.setDureeVacationCibleMinutes(8 * 60);
        parametres.setStrategieCouverturePendantPause(ParametresDecoupage.PauseCoverageStrategy.RELEVE);

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        assertThat(vacations).allSatisfy(v -> assertThat(v.isCouverturePause()).isFalse());
    }

    @Test
    void unePauseQuiFinitSurLaFermetureNeGenerePasDeVacationVide() {
        // Regression: a meal window stuck to the edge of the opening span made a
        // zero-length tail segment [pauseFin, fin] be emitted. A shift whose
        // heureFin == heureDebut is read back as crossing midnight (hence 24 h)
        // by Creneau#getDureeMinutes(), and claimed full staffing over those
        // fictitious 24 h.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(21, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();
        ParametresLegaux legaux = new ParametresLegaux();
        legaux.setCoupureRepasSoirDebut(LocalTime.of(20, 0));
        legaux.setCoupureRepasSoirFin(LocalTime.of(21, 0));
        legaux.setCoupureRepasMinutes(60);
        parametres.setStrategieCouverturePendantPause(ParametresDecoupage.PauseCoverageStrategy.EFFECTIF_REDUIT);

        List<Creneau> vacations = VacationGeneratorService.generateVacations(List.of(amplitude), parametres, legaux);

        assertThat(vacations).allSatisfy(v -> {
            assertThat(v.getHeureFin())
                    .as("vacation vide %s", v.getHeureDebut())
                    .isNotEqualTo(v.getHeureDebut());
            assertThat(v.getDureeMinutes()).isPositive().isLessThanOrEqualTo(24 * 60);
        });
        // And none may come near 24 h: the opening span is only 11 h long.
        assertThat(vacations).allSatisfy(v -> assertThat(v.getDureeMinutes()).isLessThanOrEqualTo(11 * 60));
    }
}
