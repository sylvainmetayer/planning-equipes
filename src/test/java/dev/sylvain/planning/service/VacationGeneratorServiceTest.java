package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresDecoupage;

/**
 * Exercises {@link VacationGeneratorService#genererVacations} directly (plain
 * static method, no database) against the scenarios that motivated it: a
 * short day (nothing to split), a 14h "continu" day (journée + nocturne
 * merged, relay-split into several ≤6h vacations), and the residual internal
 * legal-pause fallback that only triggers when an admin configures a max
 * vacation length above the art. L3121-16 threshold.
 */
class VacationGeneratorServiceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 10);

    private static Creneau amplitude(LocalTime debut, LocalTime fin) {
        return new Creneau(1L, 1, JOUR, debut, fin);
    }

    @Test
    void amplitudeCourteSansFenetreRepasEntiereResteEnUneSeuleVacation() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(11, 30)); // 1h30, sous le plafond et ne touche aucune fenêtre repas

        List<Creneau> vacations = VacationGeneratorService.genererVacations(
                List.of(amplitude), new ParametresDecoupage());

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

        List<Creneau> vacations = VacationGeneratorService.genererVacations(
                List.of(amplitude), new ParametresDecoupage());

        assertThat(vacations).hasSize(2);
        Creneau avantPause = vacations.get(0);
        Creneau apresPause = vacations.get(1);
        assertThat(avantPause.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(avantPause.getHeureFin()).isEqualTo(LocalTime.of(12, 30));
        assertThat(apresPause.getHeureDebut()).isEqualTo(LocalTime.of(13, 15));
        assertThat(apresPause.getHeureFin()).isEqualTo(LocalTime.of(15, 0));
        // 45 min of real break between the two, aligned on the default midday
        // meal window (12:00-14:00).
        assertThat(java.time.Duration.between(avantPause.getHeureFin(), apresPause.getHeureDebut()).toMinutes())
                .isEqualTo(45);
    }

    @Test
    void journeeContinueDeQuatorzeHeuresEstDecoupeeEnVacationsRelaisSousLePlafond() {
        // 10:00 -> 00:00: the day and night merged, as in the continuous scenario.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

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

        // A real lunch break (45 min by default) between the first two.
        assertThat(avantDejeuner.getHeureFin()).isEqualTo(LocalTime.of(12, 0));
        assertThat(apresDejeuner.getHeureDebut()).isEqualTo(LocalTime.of(12, 45));

        // A 30 min handover overlap with the middle segment: during that window
        // two PosteAffectation exist on the same stand, so the coverage is never
        // short.
        assertThat(relaisMilieu.getHeureDebut()).isBefore(apresDejeuner.getHeureFin());
        assertThat(relaisMilieu.getHeureFin()).isEqualTo(LocalTime.of(19, 0));

        // A real dinner break (45 min) on the last segment, which overlaps the
        // middle one and entirely encloses the dinner window (19:00-21:00).
        assertThat(avantDiner.getHeureDebut()).isBefore(relaisMilieu.getHeureFin());
        assertThat(avantDiner.getHeureFin()).isEqualTo(LocalTime.of(21, 0));
        assertThat(apresDiner.getHeureDebut()).isEqualTo(LocalTime.of(21, 45));
    }

    @Test
    void aucuneVacationNeTombeSousLeMinimumMemeQuandLeDernierRelaisApprocheLaFenetreRepas() {
        // 10:00 -> 20:00: a 10 h opening span (continuous scenario, a day with
        // no night). The evening handover is drawn towards the 19:00-21:00 meal
        // window, which — with no guard — used to leave a remainder from 18:30 to
        // 20:00, a 90 min shift well under the configured minimum (3 h).
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(20, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

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

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

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

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        assertThat(vacations).hasSize(2);
        Creneau avantPause = vacations.get(0);
        Creneau apresPause = vacations.get(1);
        assertThat(avantPause.getHeureFin()).isEqualTo(LocalTime.of(14, 0));
        assertThat(apresPause.getHeureDebut()).isEqualTo(LocalTime.of(14, 45));
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
        parametres.setStrategieCouverturePendantPause(
                ParametresDecoupage.StrategieCouverturePendantPause.RELEVE);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        assertThat(vacations).hasSize(3);
        Creneau avantPause = vacations.get(0);
        Creneau releve = vacations.get(1);
        Creneau apresPause = vacations.get(2);
        assertThat(avantPause.getHeureFin()).isEqualTo(LocalTime.of(14, 0));
        assertThat(apresPause.getHeureDebut()).isEqualTo(LocalTime.of(14, 45));
        // The relief shift fills the hole exactly: no interruption in the
        // coverage of the stand.
        assertThat(releve.getHeureDebut()).isEqualTo(avantPause.getHeureFin());
        assertThat(releve.getHeureFin()).isEqualTo(apresPause.getHeureDebut());
    }

    @Test
    void strategieEffectifReduitCouvreLaPauseEtMarqueLaVacation() {
        // The same scenario as RELEVE — the break is covered, with no
        // interruption — but the covering shift also carries the marker that will
        // run the stand at half staffing (PlanningService#construirePostes).
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(18, 0)); // 8h
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setDureeVacationMaxMinutes(8 * 60);
        parametres.setDureeVacationCibleMinutes(8 * 60);
        parametres.setStrategieCouverturePendantPause(
                ParametresDecoupage.StrategieCouverturePendantPause.EFFECTIF_REDUIT);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        assertThat(vacations).hasSize(3);
        Creneau avantPause = vacations.get(0);
        Creneau couverture = vacations.get(1);
        Creneau apresPause = vacations.get(2);
        assertThat(couverture.getHeureDebut()).isEqualTo(avantPause.getHeureFin());
        assertThat(couverture.getHeureFin()).isEqualTo(apresPause.getHeureDebut());
        // Only the covering shift is marked: the two shifts framing it run at
        // full staffing.
        assertThat(couverture.isCouverturePause()).isTrue();
        assertThat(avantPause.isCouverturePause()).isFalse();
        assertThat(apresPause.isCouverturePause()).isFalse();
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
        parametres.setStrategieCouverturePendantPause(
                ParametresDecoupage.StrategieCouverturePendantPause.RELEVE);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

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
        parametres.setFenetreRepasSoirDebut(LocalTime.of(20, 0));
        parametres.setFenetreRepasSoirFin(LocalTime.of(21, 0));
        parametres.setDureePauseRepasMinutes(60);
        parametres.setStrategieCouverturePendantPause(
                ParametresDecoupage.StrategieCouverturePendantPause.EFFECTIF_REDUIT);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        assertThat(vacations).allSatisfy(v -> {
            assertThat(v.getHeureFin()).as("vacation vide %s", v.getHeureDebut()).isNotEqualTo(v.getHeureDebut());
            assertThat(v.getDureeMinutes()).isPositive().isLessThanOrEqualTo(24 * 60);
        });
        // And none may come near 24 h: the opening span is only 11 h long.
        assertThat(vacations).allSatisfy(v -> assertThat(v.getDureeMinutes()).isLessThanOrEqualTo(11 * 60));
    }

    @Test
    void sansDecalageToutesLesVacationsSontTagueesFamilleZero() {
        // Default behaviour (nombreFamillesDecalage=1): nothing changes, every
        // shift generated carries family 0 — which is what guarantees backward
        // compatibility on the PlanningService#construirePostes side (a single
        // family => no filtering per stand).
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));

        List<Creneau> vacations = VacationGeneratorService.genererVacations(
                List.of(amplitude), new ParametresDecoupage());

        assertThat(vacations).allSatisfy(v -> assertThat(v.getFamille()).isZero());
    }

    @Test
    void decalageEtaleLesCoupuresDeRelaisEntreFamillesDistinctes() {
        // Reproduces the "14:00 wall" seen in practice: on a 14 h opening span
        // (10:00->00:00, a continuous day) the first handover always aims at the
        // end of the lunch window (14:00) whatever the span, because the 5 h
        // target systematically overshoots the window. With 4 families offset by
        // ±45 min, at least one family must cut somewhere other than 14:00.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setNombreFamillesDecalage(4);
        parametres.setDureeDecalageMaxMinutes(150);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        // 4 families, each slicing the opening span independently (5 shifts per
        // family with no offset, see the "continuous day" test above): families 0
        // to 3 must all show up, and every shift generated stays within the
        // bounds of the span and under the legal ceiling.
        Set<Integer> famillesVues = new java.util.HashSet<>();
        for (Creneau vacation : vacations) {
            famillesVues.add(vacation.getFamille());
            assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }
        assertThat(famillesVues).containsExactlyInAnyOrder(0, 1, 2, 3);

        // First shift ends (the "first handover") per family: at least two
        // distinct values, the proof that the offset really moves where the cut
        // falls instead of aligning everything on 14:00.
        Set<LocalTime> premieresFins = new java.util.HashSet<>();
        for (int famille = 0; famille < 4; famille++) {
            int f = famille;
            premieresFins.add(vacations.stream()
                    .filter(v -> v.getFamille() == f)
                    .min(java.util.Comparator.comparing(Creneau::getHeureDebut))
                    .orElseThrow()
                    .getHeureFin());
        }
        assertThat(premieresFins).hasSizeGreaterThan(1);
    }

    /**
     * The property the staggering actually needs: <b>every</b> famille relays
     * at its own instant, not merely "at least two of them differ". The
     * earlier implementation shifted the target before the range clamp, and a
     * clamp has two edges — familles pushed past either edge all snapped back
     * onto that same edge, so several familles kept relaying together. That is
     * what doubled the seat count at the changeover instant on real data.
     */
    @Test
    void chaqueFamilleReleveAUnInstantDistinct() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setNombreFamillesDecalage(4);
        parametres.setDureeDecalageMaxMinutes(60);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        Set<LocalTime> premieresFins = new java.util.HashSet<>();
        for (int famille = 0; famille < 4; famille++) {
            int f = famille;
            premieresFins.add(vacations.stream()
                    .filter(v -> v.getFamille() == f)
                    .min(java.util.Comparator.comparing(Creneau::getHeureDebut))
                    .orElseThrow()
                    .getHeureFin());
        }
        assertThat(premieresFins).as("une heure de relais distincte par famille").hasSize(4);
    }

    /**
     * The regression that actually cost a solve: the <b>second</b> relay of a
     * long day, the one that falls in the evening meal window.
     *
     * <p>The first staggering attempt offset the target before the range clamp.
     * On a 10:00→00:00 amplitude the first cut lands in the lunch window and
     * does spread — which is all the earlier tests checked — but the second
     * cut's target then falls <i>below</i> the 19:00 evening window, and a
     * clamp has two edges: every famille was snapped back up to 19:00 exactly.
     * Every stand changed crew at the same instant, both crews on the clock at
     * once, and the seat count doubled at 18:30. Checking only the first relay
     * would have called that fix a success.
     */
    @Test
    void leSecondRelaisDuneJourneeLongueEstLuiAussiEtaleParFamille() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setNombreFamillesDecalage(4);
        parametres.setDureeDecalageMaxMinutes(60);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        Set<LocalTime> secondsRelais = new java.util.HashSet<>();
        for (int famille = 0; famille < 4; famille++) {
            int f = famille;
            List<LocalTime> finsTriees = vacations.stream()
                    .filter(v -> v.getFamille() == f)
                    .sorted(java.util.Comparator.comparing(Creneau::getHeureDebut))
                    .map(Creneau::getHeureFin)
                    .distinct()
                    .toList();
            assertThat(finsTriees).as("famille %d doit avoir au moins deux relais", f).hasSizeGreaterThan(1);
            secondsRelais.add(finsTriees.get(1));
        }
        assertThat(secondsRelais)
                .as("le second relais doit tomber à un instant distinct par famille, pas s'écraser sur un bord de fenêtre")
                .hasSize(4);
    }

    /**
     * The fan is spread <i>inside</i> the meal window, never outside it: the
     * handover still happens around a meal for every famille, which is the
     * whole point of snapping to the window in the first place.
     */
    @Test
    void lEventailDesFamillesResteDansLaFenetreRepas() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();
        parametres.setNombreFamillesDecalage(4);
        parametres.setDureeDecalageMaxMinutes(60);

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        for (int famille = 0; famille < 4; famille++) {
            int f = famille;
            LocalTime premierRelais = vacations.stream()
                    .filter(v -> v.getFamille() == f)
                    .min(java.util.Comparator.comparing(Creneau::getHeureDebut))
                    .orElseThrow()
                    .getHeureFin();
            assertThat(premierRelais)
                    .as("relais de la famille %d dans la fenêtre déjeuner", f)
                    .isBetween(parametres.getFenetreRepasMidiDebut(), parametres.getFenetreRepasMidiFin());
        }
    }

    /**
     * Backward compatibility is exact, not approximate: with the default
     * single famille the fan is inert and the cut lands where it always did,
     * whatever {@code dureeDecalageMaxMinutes} happens to be set to.
     */
    @Test
    void uneSeuleFamilleIgnoreCompletementLEtalement() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage sansEtalement = new ParametresDecoupage();
        ParametresDecoupage avecEtalement = new ParametresDecoupage();
        avecEtalement.setNombreFamillesDecalage(1);
        avecEtalement.setDureeDecalageMaxMinutes(240);

        List<Creneau> reference = VacationGeneratorService.genererVacations(List.of(amplitude), sansEtalement);
        List<Creneau> avec = VacationGeneratorService.genererVacations(List.of(amplitude), avecEtalement);

        assertThat(avec).extracting(Creneau::getHeureDebut, Creneau::getHeureFin)
                .containsExactlyElementsOf(reference.stream()
                        .map(v -> org.assertj.core.groups.Tuple.tuple(v.getHeureDebut(), v.getHeureFin()))
                        .toList());
    }
}
