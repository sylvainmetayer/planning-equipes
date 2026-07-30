package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.HeuresPlanningService.HeuresAnimateur;
import dev.sylvain.planning.service.HeuresPlanningService.HeuresRapport;

class HeuresPlanningServiceTest {

    private final HeuresPlanningService service = new HeuresPlanningService();

    @Test
    void groupsHoursByIsoWeekAndComputesTotal() {
        // 2026-08-14 (Fri) and 2026-08-15 (Sat) fall in ISO week 2026-W33;
        // 2026-08-24 (Mon) falls in the following ISO week 2026-W35.
        Stand stand = new Stand("STAND-1", "Stand", java.util.Set.of(), 1, 2, false);
        Creneau creneauJ1 = new Creneau("C1", 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau creneauJ2 = new Creneau("C2", 2, LocalDate.of(2026, 8, 15), LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau creneauJ3 = new Creneau("C3", 3, LocalDate.of(2026, 8, 24), LocalTime.of(9, 0), LocalTime.of(12, 0));

        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);

        PosteAffectation poste1 = new PosteAffectation("P1", stand, creneauJ1);
        poste1.setAnimateur(ada);
        PosteAffectation poste2 = new PosteAffectation("P2", stand, creneauJ2);
        poste2.setAnimateur(ada);
        PosteAffectation poste3 = new PosteAffectation("P3", stand, creneauJ3);
        poste3.setAnimateur(ada);
        PosteAffectation posteNonAssigne = new PosteAffectation("P4", stand, creneauJ1);

        PlanningFestival planning = new PlanningFestival(creneauJ1.getDate(), List.of(ada),
                List.of(poste1, poste2, poste3, posteNonAssigne));

        HeuresRapport rapport = service.calculer(planning);

        assertThat(rapport.semaines()).containsExactly("2026-W33", "2026-W35");
        assertThat(rapport.animateurs()).hasSize(1);
        HeuresAnimateur ligne = rapport.animateurs().get(0);
        assertThat(ligne.animateurId()).isEqualTo("A-ADA");
        assertThat(ligne.heuresParSemaine().get("2026-W33")).isCloseTo(8.0, within(0.01));
        assertThat(ligne.heuresParSemaine().get("2026-W35")).isCloseTo(3.0, within(0.01));
        assertThat(ligne.total()).isCloseTo(11.0, within(0.01));
    }

    @Test
    void csvHasOneColumnPerWeekPlusTotal() {
        Stand stand = new Stand("STAND-1", "Stand", java.util.Set.of(), 1, 1, false);
        Creneau creneau = new Creneau("C1", 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(ada);
        PlanningFestival planning = new PlanningFestival(creneau.getDate(), List.of(ada), List.of(poste));

        String csv = service.genererCsv(service.calculer(planning));

        assertThat(csv).isEqualTo("animateur;2026-W33;total\nAda Lovelace;4.00;4.00\n");
    }
}
