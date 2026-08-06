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
    void amplitudeCourteResteEnUneSeuleVacation() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(15, 0)); // 5h, sous le plafond de 6h

        List<Creneau> vacations = VacationGeneratorService.genererVacations(
                List.of(amplitude), new ParametresDecoupage());

        assertThat(vacations).hasSize(1);
        Creneau vacation = vacations.get(0);
        assertThat(vacation.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(vacation.getHeureFin()).isEqualTo(LocalTime.of(15, 0));
        assertThat(vacation.getDate()).isEqualTo(JOUR);
    }

    @Test
    void journeeContinueDeQuatorzeHeuresEstDecoupeeEnVacationsRelaisSousLePlafond() {
        // 10:00 -> 00:00 : la journée + nocturne fusionnées du scénario continu.
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        ParametresDecoupage parametres = new ParametresDecoupage();

        List<Creneau> vacations = VacationGeneratorService.genererVacations(List.of(amplitude), parametres);

        assertThat(vacations).hasSize(3);

        // Aucune vacation ne dépasse le plafond configuré (6h par défaut) : la
        // pause/pause-repas de chaque animateur est le trou entre deux
        // vacations, jamais une coupure à construire à l'intérieur de l'une
        // d'elles.
        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes())
                    .isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes())
                    .isGreaterThanOrEqualTo(parametres.getDureeVacationMinMinutes());
        }

        Creneau premiere = vacations.get(0);
        Creneau deuxieme = vacations.get(1);
        Creneau troisieme = vacations.get(2);

        // La première vacation démarre à l'ouverture, la dernière finit à la
        // fermeture (minuit) : toute l'amplitude est couverte sans trou.
        assertThat(premiere.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(troisieme.getHeureFin()).isEqualTo(LocalTime.of(0, 0));

        // Chevauchement de relais de 30 min entre vacations consécutives :
        // pendant cette fenêtre, deux PosteAffectation existent sur le même
        // stand, donc la couverture n'est jamais en déficit.
        assertThat(deuxieme.getHeureDebut()).isBefore(premiere.getHeureFin());
        assertThat(troisieme.getHeureDebut()).isBefore(deuxieme.getHeureFin());

        // Les relais tombent près des fenêtres repas par défaut (12h-14h,
        // 19h-21h) : la relève se fait juste après le déjeuner et juste avant
        // le dîner, jamais en plein service.
        assertThat(premiere.getHeureFin()).isEqualTo(LocalTime.of(14, 0));
        assertThat(deuxieme.getHeureFin()).isEqualTo(LocalTime.of(19, 0));
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

        for (Creneau vacation : vacations) {
            assertThat(vacation.getDureeMinutes())
                    .isGreaterThanOrEqualTo(parametres.getDureeVacationMinMinutes())
                    .isLessThanOrEqualTo(parametres.getDureeVacationMaxMinutes());
        }
        assertThat(vacations.get(vacations.size() - 1).getHeureFin()).isEqualTo(LocalTime.of(20, 0));
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

    @Test
    void chaqueVacationHeriteDesStandsOuvertsDeLAmplitude() {
        Creneau amplitude = amplitude(LocalTime.of(10, 0), LocalTime.of(0, 0));
        amplitude.setStandsOuvertsIds(Set.of("STAND-A"));

        List<Creneau> vacations = VacationGeneratorService.genererVacations(
                List.of(amplitude), new ParametresDecoupage());

        assertThat(vacations).isNotEmpty();
        for (Creneau vacation : vacations) {
            assertThat(vacation.getStandsOuvertsIds()).containsExactly("STAND-A");
        }
    }
}
