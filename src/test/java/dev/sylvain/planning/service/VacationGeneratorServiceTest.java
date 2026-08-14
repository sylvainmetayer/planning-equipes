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
        // Bug réel observé : une amplitude de 5h (10:00-15:00), largement sous le
        // plafond de 6h, ne déclenchait jamais de pause interne — l'animateur
        // seul sur cette unique vacation travaillait tout le service de midi
        // sans manger, la "pause" du mécanisme de relais ne s'appliquant que
        // s'il enchaînait une deuxième vacation le même jour.
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
        // 45 min de vraie coupure entre les deux, alignée sur la fenêtre repas
        // de midi par défaut (12h-14h).
        assertThat(java.time.Duration.between(avantPause.getHeureFin(), apresPause.getHeureDebut()).toMinutes())
                .isEqualTo(45);
    }

    @Test
    void journeeContinueDeQuatorzeHeuresEstDecoupeeEnVacationsRelaisSousLePlafond() {
        // 10:00 -> 00:00 : la journée + nocturne fusionnées du scénario continu.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        // 5 vacations, pas 3 : les deux premiers relais (10:00-14:00 puis
        // 13:30-19:00) et le dernier (18:30-00:00) englobaient chacun une
        // fenêtre repas entière — midi pour le premier, dîner pour le
        // dernier — donc chacun est coupé en deux par une vraie pause. Le
        // relais du milieu (13:30-19:00) ne fait qu'effleurer le début de la
        // fenêtre dîner (19h) sans l'englober, donc reste entier.
        assertThat(vacations).hasSize(5);

        // Aucune vacation ne dépasse le plafond configuré (6h par défaut).
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }

        Creneau avantDejeuner = vacations.get(0);
        Creneau apresDejeuner = vacations.get(1);
        Creneau relaisMilieu = vacations.get(2);
        Creneau avantDiner = vacations.get(3);
        Creneau apresDiner = vacations.get(4);

        // La première vacation démarre à l'ouverture, la dernière finit à la
        // fermeture (minuit) : toute l'amplitude est couverte, coupures repas
        // mises à part.
        assertThat(avantDejeuner.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(apresDiner.getHeureFin()).isEqualTo(LocalTime.of(0, 0));

        // Vraie pause déjeuner (45 min par défaut) entre les deux premières.
        assertThat(avantDejeuner.getHeureFin()).isEqualTo(LocalTime.of(12, 0));
        assertThat(apresDejeuner.getHeureDebut()).isEqualTo(LocalTime.of(12, 45));

        // Chevauchement de relais de 30 min avec le segment du milieu :
        // pendant cette fenêtre, deux PosteAffectation existent sur le même
        // stand, donc la couverture n'est jamais en déficit.
        assertThat(relaisMilieu.getHeureDebut()).isBefore(apresDejeuner.getHeureFin());
        assertThat(relaisMilieu.getHeureFin()).isEqualTo(LocalTime.of(19, 0));

        // Vraie pause dîner (45 min) sur le dernier segment, qui chevauche le
        // milieu et englobe entièrement la fenêtre dîner (19h-21h).
        assertThat(avantDiner.getHeureDebut()).isBefore(relaisMilieu.getHeureFin());
        assertThat(avantDiner.getHeureFin()).isEqualTo(LocalTime.of(21, 0));
        assertThat(apresDiner.getHeureDebut()).isEqualTo(LocalTime.of(21, 45));
    }

    @Test
    void aucuneVacationNeTombeSousLeMinimumMemeQuandLeDernierRelaisApprocheLaFenetreRepas() {
        // 10:00 -> 20:00 : 10h d'amplitude (scénario continu, jour sans
        // nocturne). Le relais du soir est attiré vers la fenêtre repas
        // 19h-21h, ce qui — sans garde-fou — laissait un reliquat de 18h30 à
        // 20h00, une vacation de 90 min bien sous le minimum configuré (3h).
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(20, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        // Le dernier segment (17:00-20:00) n'est *pas* coupé pour une pause
        // dîner : la fenêtre 19h-21h est tronquée par la fermeture (20h), donc
        // moins de la moitié en reste dans l'amplitude — forcer une coupure
        // là ne ferait que laisser un reliquat de quelques minutes juste avant
        // la fermeture, sans aucun intérêt. Le premier segment (10:00-14:00),
        // lui, englobe entièrement la fenêtre déjeuner et est bien coupé pour
        // une vraie pause repas, donc descend sous le minimum — attendu.
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes()).isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }
        assertThat(vacations.get(vacations.size() - 1).getHeureFin()).isEqualTo(LocalTime.of(20, 0));
        assertThat(vacations.get(vacations.size() - 1).getDureeMinutes())
                .isGreaterThanOrEqualTo(parametres.getDureeVacationMinMinutes());
    }

    @Test
    void aucuneVacationNeDepasseLePlafondMemeSurUneAmplitudeNonDivisibleProprement() {
        // 22h d'amplitude, plafond par défaut de 6h : force plusieurs relais.
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
        // Un admin qui configure un plafond de 8h (> seuil légal de 6h) sur une
        // amplitude qui rentre pile dans ce plafond : la vacation générée
        // dépasserait le seuil légal, donc une pause interne est insérée,
        // alignée sur la fenêtre repas de midi.
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
        // Même scénario que ci-dessus (plafond de 8h > seuil légal), mais avec
        // la stratégie RELEVE : une troisième vacation, courte, doit couvrir
        // exactement la pause pour que le stand reste ouvert.
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
        // La relève comble exactement le trou : aucune interruption de
        // couverture du stand.
        assertThat(releve.getHeureDebut()).isEqualTo(avantPause.getHeureFin());
        assertThat(releve.getHeureFin()).isEqualTo(apresPause.getHeureDebut());
    }
}
